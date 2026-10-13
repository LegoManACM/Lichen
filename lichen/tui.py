"""Full-screen terminal UI (Textual). Same node, same commands as the CLI, plus:

  - Console (top of the sidebar): run commands, slash optional, with their output
  - Requests: group invites, file offers and strangers' messages waiting for you
  - contacts (people you trust, plus strangers who wrote to you) and groups (incl. invites)
  - pick a conversation and just type; files show up inline with how to accept them
  - Ctrl+F (or drag a file into the window) sends a file to the open conversation
  - Tab / → completes commands, names and paths; a hint line shows what fits
  - activity pane (Ctrl+L) showing connections, punches and relay events
"""

import os
import time
from pathlib import Path

from rich.markup import escape
from textual.app import App, ComposeResult
from textual.binding import Binding
from textual.containers import Horizontal, Vertical
from textual.screen import ModalScreen
from textual.suggester import Suggester
from textual.widgets import DirectoryTree, Footer, Header, Input, Label, ListItem, ListView, RichLog, Static

from .commands import Commands, link
from .files import human_size
from .identity import fingerprint, short

MARKERS = {
    "direct": "[green]●[/]",  # punched peer-to-peer UDP path
    "open": "[cyan]●[/]",  # they accept connections directly (hubs, open ports)
    "relay": "[yellow]◐[/]",  # only reachable through their hub
}
LINK_TEXT = {"direct": "direct (peer to peer)", "open": "direct (open port)", "relay": "via relay"}
CONSOLE = ("console", "")
REQUESTS = ("requests", "")
WELCOME = """Welcome to Lichen! This is the console: run commands here, no slash needed.
  help                         list every command
  peers                        who's in your phonebook
  msg alice hi                 start a conversation (that makes alice a contact)
  group create devs alice bob  invite people to a group
Pick a contact or group on the left to chat. Ctrl+F sends a file. Tab or → completes."""


def _clock(ts: float) -> str:
    return time.strftime("%H:%M", time.localtime(ts))


def dropped_file(text: str) -> Path | None:
    """A whole input that is the path of an existing file (what a terminal pastes when you drop one)."""
    candidate = text.strip().strip('"').strip("'").strip()
    if not candidate or not os.path.isabs(os.path.expanduser(candidate)):
        return None
    path = Path(candidate).expanduser()
    return path if path.is_file() else None


class CommandSuggester(Suggester):
    """Ghost-text completion from commands.py. In the console the leading slash is optional."""

    def __init__(self, app: "ChatApp"):
        super().__init__(use_cache=False, case_sensitive=True)
        self.chat_app = app

    async def get_suggestion(self, value: str) -> str | None:
        commands = self.chat_app.commands
        if not commands or not value:
            return None
        if self.chat_app.command_mode and not value.startswith("/"):
            suggestion = commands.complete("/" + value)
            return suggestion[1:] if suggestion else None
        return commands.complete(value)


class PathSuggester(Suggester):
    def __init__(self):
        super().__init__(use_cache=False, case_sensitive=True)

    async def get_suggestion(self, value: str) -> str | None:
        rest = Commands._complete_path(value)
        return value + rest if rest else None


class CommandInput(Input):
    """Input where Tab accepts the suggestion (and moves focus as usual when there isn't one)."""

    BINDINGS = [Binding("tab", "complete", "Complete", show=False, priority=True)]

    def action_complete(self) -> None:
        if self._suggestion and self.cursor_at_end and len(self._suggestion) > len(self.value):
            self.value = self._suggestion
            self.cursor_position = len(self.value)
        else:
            self.screen.focus_next()


class FilePicker(ModalScreen[Path | None]):
    """Pick a file to send: browse the tree, or type/paste a path (Tab completes)."""

    BINDINGS = [Binding("escape", "cancel", "Cancel"), Binding("ctrl+up", "parent", "Parent folder")]
    CSS = """
    FilePicker { align: center middle; }
    #picker { width: 90%; height: 85%; border: thick $accent; background: $surface; padding: 0 1; }
    #picker-title { text-style: bold; padding: 0 0 1 0; }
    #picker-tree { height: 1fr; }
    #picker-hint { color: $text-muted; height: auto; }
    """

    def __init__(self, target: str, start: Path):
        super().__init__()
        self.target = target
        self.start = start

    def compose(self) -> ComposeResult:
        with Vertical(id="picker"):
            yield Label(f"Send a file to {escape(self.target)}", id="picker-title")
            yield CommandInput(value=str(self.start).rstrip(os.sep) + os.sep, id="picker-path", suggester=PathSuggester())
            yield DirectoryTree(self.start, id="picker-tree")
            yield Static("Enter on a file sends it · type or paste a path (Tab completes) · "
                         "Ctrl+↑ parent folder · Esc cancels", id="picker-hint")

    def on_mount(self) -> None:
        self.query_one("#picker-tree", DirectoryTree).focus()

    def _show(self, folder: Path) -> None:
        self.query_one("#picker-tree", DirectoryTree).path = folder
        self.query_one("#picker-path", Input).value = str(folder).rstrip(os.sep) + os.sep

    def on_directory_tree_file_selected(self, event: DirectoryTree.FileSelected) -> None:
        self.dismiss(Path(event.path))

    def on_input_changed(self, event: Input.Changed) -> None:
        folder = Path(event.value.strip().strip('"')).expanduser()
        tree = self.query_one("#picker-tree", DirectoryTree)
        if event.value.endswith(("/", "\\")) and folder.is_dir() and Path(tree.path) != folder:
            tree.path = folder

    def on_input_submitted(self, event: Input.Submitted) -> None:
        event.stop()
        path = Path(event.value.strip().strip('"')).expanduser()
        if path.is_file():
            self.dismiss(path)
        elif path.is_dir():
            self._show(path)
        else:
            self.notify(f"no such file: {path}", severity="warning")

    def action_parent(self) -> None:
        self._show(Path(self.query_one("#picker-tree", DirectoryTree).path).parent)

    def action_cancel(self) -> None:
        self.dismiss(None)


class ChatApp(App):
    TITLE = "Lichen"
    CSS = """
    #sidebar { width: 32; border-right: solid $primary-background-lighten-2; }
    .section { padding: 0 1; text-style: bold; color: $accent; }
    #nav { height: auto; }
    #contacts { height: 2fr; }
    #groups { height: 1fr; }
    #title { height: 1; padding: 0 1; background: $boost; text-style: bold; }
    #chat { height: 1fr; padding: 0 1; }
    #hints { height: auto; max-height: 9; padding: 0 1; color: $text-muted; display: none; }
    #hints.shown { display: block; }
    #activity { width: 40%; max-width: 60; border-left: solid $primary-background-lighten-2; padding: 0 1; display: none; }
    #activity.shown { display: block; }
    """
    BINDINGS = [
        Binding("ctrl+q", "quit", "Quit"),
        Binding("ctrl+o", "open_console", "Console"),
        Binding("ctrl+r", "open_requests", "Requests"),
        Binding("ctrl+f", "send_file", "Send file"),
        Binding("ctrl+l", "toggle_activity", "Activity"),
        Binding("f5", "sync", "Sync"),
        Binding("escape", "focus_input", "Type", show=False),
    ]

    def __init__(self, args):
        super().__init__()
        self.args = args
        self.node = None
        self.commands: Commands | None = None
        self.current: tuple[str, str] = CONSOLE  # or REQUESTS, ("dm", pubkey), ("group", group_id)
        self.unread: dict[tuple[str, str], int] = {}
        self._labels: dict[str, Label] = {}  # sidebar item id -> its label
        self._items: dict[str, ListItem] = {}
        self._console: list[str] = []  # console history (markup lines)
        self._activity: list[str] = []  # kept so the activity pane can be redrawn when shown
        self._progress: dict[str, int] = {}  # file id -> percent, while downloading
        self._last_folder = Path.home()

    @property
    def command_mode(self) -> bool:
        """Views where typing runs commands (no slash needed)."""
        return self.current in (CONSOLE, REQUESTS)

    # --- layout ----------------------------------------------------------------------------------

    def compose(self) -> ComposeResult:
        yield Header()
        with Horizontal():
            with Vertical(id="sidebar"):
                self._labels["console"] = Label("[b]⌘ Console[/]")
                self._labels["requests"] = Label("[b]✉ Requests[/]")
                yield ListView(ListItem(self._labels["console"], id="console"),
                               ListItem(self._labels["requests"], id="requests"), id="nav")
                yield Label("Contacts", classes="section")
                yield ListView(id="contacts")
                yield Label("Groups", classes="section")
                yield ListView(id="groups")
            with Vertical():
                yield Static("", id="title")
                yield RichLog(id="chat", wrap=True, markup=True, min_width=1)
                yield Static("", id="hints")
                yield CommandInput(placeholder="", id="input", suggester=CommandSuggester(self))
            yield RichLog(id="activity", wrap=True, markup=True, min_width=1)
        yield Footer()

    def on_mount(self) -> None:
        self.sub_title = f"{self.args.name} · starting"
        for line in WELCOME.splitlines():
            self.to_console(f"[dim]{escape(line)}[/]")
        self.open(CONSOLE)
        self.run_worker(self._start_node(), exclusive=True)
        self.set_interval(2.0, self.refresh_sidebar)

    async def _start_node(self) -> None:
        from .cli import make_node  # cli imports us lazily; avoid the cycle at import time

        self.node = make_node(self.args, on_message=self._dm_received, on_group_message=self._group_received,
                              on_event=self._event, log=self.activity)
        self.commands = Commands(self.node, self.say)
        if self.args.config_path:
            self.activity(f"using config {self.args.config_path}")
        await self.node.start()
        self.activity(f"reachable at {', '.join(self.node.addrs)}")
        self.to_console(f"[dim]online as {escape(self.node.name)}, reachable at {escape(', '.join(self.node.addrs))}[/]")
        waiting = len(self.commands.requests())
        if waiting:
            self.to_console(f"[b]{waiting} request(s) waiting[/]: Ctrl+R to see them")
        self.refresh_sidebar()

    # --- output ------------------------------------------------------------------------------------

    def to_console(self, line: str) -> None:
        """Append a (markup) line to the console's history, and show it if the console is open."""
        self._console.append(line)
        del self._console[:-2000]
        if self.current == CONSOLE:
            self.query_one("#chat", RichLog).write(line)

    def say(self, text: str) -> None:
        """Command output: always kept in the console, and shown inline if typed elsewhere."""
        self.to_console(f"[dim]{escape(text)}[/]")
        if self.current != CONSOLE:
            self.query_one("#chat", RichLog).write(f"[dim]{escape(text)}[/]")

    def activity(self, text: str) -> None:
        line = f"[dim]{time.strftime('%H:%M:%S')}[/] {escape(text)}"
        self._activity.append(line)
        del self._activity[:-500]
        pane = self.query_one("#activity", RichLog)
        if pane.has_class("shown"):
            pane.write(line)

    def _who(self, pubkey: str) -> str:
        if self.node and pubkey == self.node.ident.pubkey:
            return "you"
        return self.commands.name_of(pubkey) if self.commands else short(pubkey)

    def _line(self, sender: str, text: str, ts: float, note: str = "") -> str:
        me = self.node and sender == self.node.ident.pubkey
        color = "green" if me else "cyan"
        return f"[dim]{_clock(ts)}[/] [bold {color}]{escape(self._who(sender))}[/] {escape(text)}{note}"

    def _file_line(self, entry: dict) -> str:
        fid, direction = entry["file"], entry["dir"]
        if direction == "out":
            e = self.node.files.outgoing[fid]
            return (f"[dim]{_clock(e['ts'])}[/] [bold green]you[/] 📎 offered [b]{escape(e['name'])}[/] "
                    f"({human_size(e['size'])})")
        o = self.node.files.incoming[fid]
        head = (f"[dim]{_clock(o['ts'])}[/] [bold cyan]{escape(self._who(o['from']))}[/] 📎 "
                f"[b]{escape(o['name'])}[/] ({human_size(o['size'])})")
        status = o.get("status")
        if status == "pending":
            trust = "" if self.node.trust.trusted(o["from"]) else " [yellow](not a contact)[/]"
            return f"{head}{trust}  [reverse] /accept {escape(o['name'])} [/] or /decline"
        if status == "downloading":
            return f"{head}  [dim]downloading… {self._progress.get(fid, 0)}%[/]"
        if status == "done":
            return f"{head}  [green]✓ saved to {escape(o.get('saved', ''))}[/]"
        if status == "failed":
            return f"{head}  [red]failed: {escape(o.get('error') or '')}[/] (/accept {escape(o['name'])} resumes)"
        return f"{head}  [dim]{escape(status or '')}{': ' + escape(o['error']) if o.get('error') else ''}[/]"

    def _entry_line(self, entry: dict) -> str:
        return self._file_line(entry) if "file" in entry else self._line(entry["from"], entry["text"], entry["ts"])

    # --- sidebar -----------------------------------------------------------------------------------

    def refresh_sidebar(self) -> None:
        if not self.node:
            return
        node, me = self.node, self.node.ident.pubkey
        requests = self.commands.requests()
        self._labels["requests"].update(f"[b]✉ Requests[/]{' [reverse] ' + str(len(requests)) + ' [/]' if requests else ''}")

        strangers = set(self.commands.message_requests())
        people = {pk for pk, lv in node.trust.levels.items() if lv == "trusted"} | strangers
        contacts = []
        for pk in sorted(people, key=lambda k: self._who(k).lower()):
            rec = node.phonebook.get(pk)
            marker = MARKERS[link(node, rec)] if rec else "[dim]○[/]"
            name = escape(self._who(pk))
            if pk in strangers:
                warn = " [red]⚠[/]" if self.commands.impostor_warning(pk) else ""
                label = f"{marker} [i]{name}[/] [yellow]?[/]{warn}"
            else:
                label = f"{marker} {name}" + (" [green]✓[/]" if node.trust.is_verified(pk) else "")
            contacts.append((f"c-{pk}", label + self._badge(("dm", pk))))
        self._sync_list(self.query_one("#contacts", ListView), "c-", contacts)

        groups = []
        for g in sorted(node.groups.all(), key=lambda g: g.name.lower()):
            status = node.groups.status.get(g.group_id)
            if me not in g.members or status not in ("accepted", "pending"):
                continue
            label = f"# {escape(g.name)}" if status == "accepted" else f"✉ [i]{escape(g.name)}[/] [yellow](invite)[/]"
            groups.append((f"g-{g.group_id}", label + self._badge(("group", g.group_id))))
        self._sync_list(self.query_one("#groups", ListView), "g-", groups)

        status = node.relay_status() or "hosting"
        if node.outbox.items:
            status += f" · {len(node.outbox.items)} waiting to send"
        self.sub_title = f"{node.name} ({short(me)}) · {status}"
        self._render_title()

    def _badge(self, key: tuple[str, str]) -> str:
        n = self.unread.get(key, 0)
        return f" [reverse] {n} [/]" if n else ""

    def _sync_list(self, view: ListView, prefix: str, entries: list[tuple[str, str]]) -> None:
        """Update labels in place, append new items and remove gone ones, so selection survives.
        Labels are tracked here rather than queried: a just-appended item may not be mounted yet."""
        wanted = {item_id for item_id, _ in entries}
        for item_id in [i for i in self._items if i.startswith(prefix) and i not in wanted]:
            self._items.pop(item_id).remove()
            self._labels.pop(item_id, None)
        for item_id, text in entries:
            if item_id in self._items:
                self._labels[item_id].update(text)
            else:
                self._labels[item_id] = label = Label(text)
                self._items[item_id] = item = ListItem(label, id=item_id)
                view.append(item)

    def on_list_view_selected(self, event: ListView.Selected) -> None:
        item_id = event.item.id or ""
        if item_id == "console":
            self.open(CONSOLE)
        elif item_id == "requests":
            self.open(REQUESTS)
        elif item_id.startswith("c-"):
            self.open(("dm", item_id[2:]))
        elif item_id.startswith("g-"):
            self.open(("group", item_id[2:]))

    # --- conversations -----------------------------------------------------------------------------

    def _history(self, key: tuple[str, str]) -> list[dict]:
        """Messages plus file offers in a conversation, oldest first."""
        kind, ident = key
        if not self.node or kind not in ("dm", "group"):
            return []
        files = self.node.files
        if kind == "dm":
            entries = list(self.node.dms.messages(ident))
            entries += [{"file": fid, "dir": "in", "ts": o["ts"]} for fid, o in files.incoming.items()
                        if o["from"] == ident and not o.get("group")]
            entries += [{"file": fid, "dir": "out", "ts": e["ts"]} for fid, e in files.outgoing.items()
                        if e["to"] == [ident] and not e.get("group")]
        else:
            entries = list(self.node.groups.messages(ident))
            entries += [{"file": fid, "dir": "in", "ts": o["ts"]} for fid, o in files.incoming.items()
                        if o.get("group") == ident]
            entries += [{"file": fid, "dir": "out", "ts": e["ts"]} for fid, e in files.outgoing.items()
                        if e.get("group") == ident]
        return sorted(entries, key=lambda e: e["ts"])

    def open(self, key: tuple[str, str]) -> None:
        if key not in (CONSOLE, REQUESTS) and not self.node:
            return
        self.current = key
        self.unread.pop(key, None)
        chat = self.query_one("#chat", RichLog)
        chat.clear()
        if key == CONSOLE:
            for line in self._console:
                chat.write(line)
        elif key == REQUESTS:
            self._render_requests()
        elif key[0] == "group" and self.node.groups.status.get(key[1]) == "pending":
            g = self.node.groups.get(key[1])
            names = ", ".join(self._who(m) for m in g.members)
            chat.write(f"[b]{escape(self._who(g.owner))}[/] invited you to [b]#{escape(g.name)}[/] "
                       f"({escape(names)}; history {g.history}).")
            chat.write(f"Type [reverse] /accept {escape(g.name)} [/] to join, or /decline {escape(g.name)}. "
                       "Nothing from the group shows until you join.")
        else:
            history = self._history(key)
            for entry in history[-500:]:
                chat.write(self._entry_line(entry))
            if not history:
                chat.write("[dim]No messages yet. Say hi! (Ctrl+F sends a file)[/]")
            if key[0] == "dm" and self.commands and (warning := self.commands.impostor_warning(key[1])):
                chat.write(f"[bold red]{escape(warning)}[/]")
            if key[0] == "dm" and self.commands and key[1] in self.commands.message_requests():
                who = escape(self._who(key[1]))
                chat.write(f"[yellow]{who} isn't one of your contacts.[/] Reply or /accept {who} to add them; "
                           f"/block {who} to ignore them.")
        self._render_title()
        self.refresh_sidebar()
        inp = self.query_one("#input", CommandInput)
        inp.placeholder = {CONSOLE: "Type a command (help, peers, group create …)  ·  Tab completes",
                           REQUESTS: "accept ITEM / decline ITEM  ·  Tab completes"}.get(
            key, f"Message {self._convo_name(key)}  ·  Ctrl+F or drop a file to send one  ·  /commands work too")
        self._update_hints(inp.value)
        inp.focus()

    def _render_requests(self) -> None:
        chat = self.query_one("#chat", RichLog)
        items = self.commands.requests() if self.commands else []
        if not items:
            chat.write("[dim]Nothing waiting for you. Invites, file offers and messages from people who "
                       "aren't your contacts show up here.[/]")
            return
        chat.write("[b]Waiting for you[/] (type accept or decline plus the name; Tab completes):")
        for kind, ident, text in items:
            name = (self.commands._group_name(ident) if kind == "group" else
                    self.node.files.incoming[ident]["name"] if kind == "file" else self._who(ident))
            chat.write(f"  • {escape(text)}   [reverse] accept {escape(name)} [/]")

    def _convo_name(self, key: tuple[str, str]) -> str:
        kind, ident = key
        if key == CONSOLE:
            return "Console"
        if key == REQUESTS:
            return "Requests"
        if kind == "dm":
            return self._who(ident)
        g = self.node.groups.get(ident)
        return f"#{g.name}" if g else "#?"

    def _render_title(self) -> None:
        title = self.query_one("#title", Static)
        if self.current == CONSOLE:
            title.update("⌘ Console  [dim]· run commands, no slash needed · Tab completes · Ctrl+O returns here[/]")
            return
        if self.current == REQUESTS:
            title.update("✉ Requests  [dim]· invites, file offers, and messages from people who aren't contacts[/]")
            return
        if not self.node:
            return
        kind, ident = self.current
        downloading = [f"{self.node.files.incoming[f]['name']} {p}%" for f, p in self._progress.items()
                       if f in self.node.files.incoming]
        busy = f"  [dim]· downloading {escape(', '.join(downloading))}[/]" if downloading else ""
        if kind == "dm":
            rec = self.node.phonebook.get(ident)
            how = LINK_TEXT.get(link(self.node, rec), "") if rec else "unknown"
            path = self.node.udp.path(ident)
            where = f" {path[0]}:{path[1]}" if path else ""
            trust = {"trusted": "contact", "blocked": "blocked"}.get(self.node.trust.get(ident), "not a contact")
            verified = ("[green]✓ verified[/]" if self.node.trust.is_verified(ident)
                        else f"[dim]{fingerprint(ident)} · /verify to check[/]")
            title.update(f"{escape(self._convo_name(self.current))}  {verified}  [dim]· {trust} · {how}{where}[/]{busy}")
        else:
            g = self.node.groups.get(ident)
            if g:
                names = ", ".join(self._who(m) for m in g.members)
                state = " · invite" if self.node.groups.status.get(ident) == "pending" else ""
                host = "you" if g.owner == self.node.ident.pubkey else self._who(g.owner)
                title.update(f"{escape(self._convo_name(self.current))}  [dim]· {len(g.members)} members "
                             f"({escape(names)}) · host {escape(host)} · history {g.history}{state}[/]{busy}")

    # --- incoming ----------------------------------------------------------------------------------

    def _dm_received(self, rec, text: str, inner: dict) -> None:
        sender = inner["from"]
        quiet = not self.node.trust.trusted(sender)  # strangers' messages are requests: no toast
        self._incoming(("dm", sender), self._line(sender, text, inner["ts"]),
                       None if quiet else f"{self._who(sender)}: {text}")

    def _group_received(self, group, rec, text: str, inner: dict, live: bool) -> None:
        note = "" if live else " [dim](caught up)[/]"
        self._incoming(("group", inner["group"]), self._line(inner["from"], text, inner["ts"], note),
                       f"{self._who(inner['from'])} in #{group.name}: {text}")

    def _incoming(self, key, line: str, toast: str | None) -> None:
        if self.current == key:
            self.query_one("#chat", RichLog).write(line)
        else:
            self.unread[key] = self.unread.get(key, 0) + 1
            if toast:
                self.notify(toast[:120], title="New message", timeout=4)
        self.refresh_sidebar()

    def _event(self, kind: str, d: dict) -> None:
        if kind == "group_invite":
            self.notify(f"{self._who(d['inviter'])} invited you to #{d['group'].name}. Ctrl+R to answer.",
                        title="Group invite", timeout=6)
        elif kind == "file_offer":
            o = d["offer"]
            key = ("group", o["group"]) if o.get("group") else ("dm", o["from"])
            if self.current == key:
                self.query_one("#chat", RichLog).write(self._file_line({"file": d["file_id"], "dir": "in"}))
            elif d["trusted"]:
                self.unread[key] = self.unread.get(key, 0) + 1
                self.notify(f"{self._who(o['from'])} offers {o['name']} ({human_size(o['size'])})", title="File")
        elif kind == "file_progress":
            self._progress[d["file_id"]] = int(100 * d["done"] / max(d["offer"]["size"], 1))
            self._render_title()
            return
        elif kind in ("file_done", "file_failed"):
            self._progress.pop(d["file_id"], None)
            o = d["offer"]
            key = ("group", o["group"]) if o.get("group") else ("dm", o["from"])
            if self.current == key:
                self.query_one("#chat", RichLog).write(self._file_line({"file": d["file_id"], "dir": "in"}))
            if kind == "file_done":
                self.notify(f"saved {o['name']} to {d['saved']}", title="Download complete", timeout=6)
            else:
                self.notify(f"{o['name']}: {d['error']}", title="Download failed", severity="error", timeout=8)
        elif kind == "group_owner":
            self.notify(f"{self._who(d['previous'])} made you the host of #{d['group'].name}", title="Group")
        elif kind == "group_deleted":
            g = d["group"]
            self.notify(f"{self._who(g.owner)} deleted #{g.name}", title="Group", timeout=6)
            if self.current == ("group", g.group_id):
                self.open(CONSOLE)
        elif kind == "owed":
            self.to_console(f"[dim]{escape(self._who(d['sender']))} has {d['count']} thing(s) for you; "
                            f"they'll arrive when {escape(self._who(d['sender']))} is online[/]")
        if self.current == REQUESTS:
            self.open(REQUESTS)
        self.refresh_sidebar()

    # --- input -------------------------------------------------------------------------------------

    def _update_hints(self, value: str) -> None:
        hints = self.query_one("#hints", Static)
        text = ""
        if self.commands and value.strip():
            if not self.command_mode and (dropped := dropped_file(value)):
                text = (f"📎 Enter sends {dropped.name} ({human_size(dropped.stat().st_size)}) "
                        f"to {self._convo_name(self.current)}")
            else:
                if self.command_mode and not value.startswith("/"):
                    value = "/" + value
                text = self.commands.hint(value)
        hints.update(escape(text))
        hints.set_class(bool(text), "shown")

    def on_input_changed(self, event: Input.Changed) -> None:
        if event.input.id == "input":
            self._update_hints(event.value)

    async def on_input_submitted(self, event: Input.Submitted) -> None:
        if event.input.id != "input":
            return
        text = event.value.strip()
        event.input.value = ""
        self._update_hints("")
        if not text:
            return
        if not self.node or not self.commands:
            self.say("still starting up, one moment…")
            return

        if self.command_mode or text.startswith("/"):
            # Commands can take a while (downloads, offline peers): run them without freezing the UI.
            self.run_worker(self._run_command(text if text.startswith("/") else "/" + text))
            return

        kind, ident = self.current
        if kind == "group" and self.node.groups.status.get(ident) == "pending":
            self.say(f"accept the invite first: /accept {self._convo_name(self.current)[1:]}")
            return
        if dropped := dropped_file(text):
            self.run_worker(self._send_file(dropped))
            return
        chat = self.query_one("#chat", RichLog)
        if kind == "dm":
            result = await self.node.send(ident, text)
            note = {"delivered": "", "waiting": " [dim](they're offline: will send when you're both online)[/]"}.get(
                result, " [red](not delivered)[/]")
            chat.write(self._line(self.node.ident.pubkey, text, time.time(), note))
        else:
            g = self.node.groups.get(ident)
            if g:
                await self.commands.send_group(g, text)
                chat.write(self._line(self.node.ident.pubkey, text, time.time()))
        self.refresh_sidebar()

    async def _run_command(self, line: str) -> None:
        echo = f"[bold]> {escape(line)}[/]"
        self.to_console(echo)
        if self.current != CONSOLE:
            self.query_one("#chat", RichLog).write(echo)
        # A command can add things to the open conversation (/msg, /g, /send to it): show just those.
        key = self.current
        before = len(self._history(key))
        if not await self.commands.run(line):
            await self.action_quit()
            return
        if self.current == key and key[0] in ("dm", "group"):
            chat = self.query_one("#chat", RichLog)
            for entry in self._history(key)[before:]:
                chat.write(self._entry_line(entry))
        elif self.current == REQUESTS:
            self.open(REQUESTS)
        self.refresh_sidebar()

    # --- sending files -----------------------------------------------------------------------------

    def action_send_file(self) -> None:
        if self.current[0] not in ("dm", "group") or not self.node:
            self.notify("Open a conversation first, then Ctrl+F (or /send NAME PATH).", severity="warning")
            return
        if self.current[0] == "group" and not self.node.groups.joined(self.current[1]):
            self.notify("Accept the invite first.", severity="warning")
            return

        def picked(path: Path | None) -> None:
            if path:
                self._last_folder = path.parent
                self.run_worker(self._send_file(path))

        self.push_screen(FilePicker(self._convo_name(self.current), self._last_folder), picked)

    async def _send_file(self, path: Path) -> None:
        kind, ident = self.current
        try:
            fid, entry, offered = await self.node.send_file(path, [ident] if kind == "dm" else [],
                                                            group=ident if kind == "group" else None)
        except ValueError as e:
            self.notify(str(e), severity="error")
            return
        if kind == "dm" and self.node.trust.get(ident) == "unknown":
            self.node.trust.set(ident, "trusted")
        if self.current == (kind, ident):
            total = len(entry["to"])
            note = "" if offered == total else f" [dim]({offered}/{total} now; the rest when they're online)[/]"
            self.query_one("#chat", RichLog).write(self._file_line({"file": fid, "dir": "out"}) + note)
        self.refresh_sidebar()

    # --- actions -----------------------------------------------------------------------------------

    def action_open_console(self) -> None:
        self.open(CONSOLE)

    def action_open_requests(self) -> None:
        self.open(REQUESTS)

    def action_toggle_activity(self) -> None:
        pane = self.query_one("#activity", RichLog)
        pane.toggle_class("shown")
        if pane.has_class("shown"):
            # RichLog wraps at write time, so (re)write once the pane has its real width.
            self.call_after_refresh(self._redraw_activity)

    def _redraw_activity(self) -> None:
        pane = self.query_one("#activity", RichLog)
        pane.clear()
        for line in self._activity:
            pane.write(line)

    def action_focus_input(self) -> None:
        self.query_one("#input", CommandInput).focus()

    def action_sync(self) -> None:
        if self.node:
            self.run_worker(self._run_command("/sync"))

    async def action_quit(self) -> None:
        if self.node:
            await self.node.stop()
        self.exit()
