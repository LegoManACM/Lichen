"""Slash commands, shared by the plain CLI and the terminal UI so they can't drift apart."""

import os
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Callable

from . import thread
from .files import human_size
from .groups import GroupRecord
from .identity import fingerprint, safety_code, short
from .node import RELAY_PREFIX, Node
from .records import Record


@dataclass(frozen=True)
class Spec:
    name: str
    usage: str
    help: str
    # Argument kinds, for completion: peer, contact, group, mode, groupsub, pending, target, path, text.
    # A trailing "*" means the argument repeats.
    args: tuple[str, ...] = ()


# One table drives /help, tab completion and the TUI's hint line.
COMMANDS = [
    Spec("/msg", "NAME TEXT", "send a direct message (name or key prefix); makes them a contact", ("peer", "text")),
    Spec("/g", "GROUP TEXT", "send to a group", ("group", "text")),
    Spec("/send", "NAME|#GROUP PATH", "offer someone (or a group) a file; they choose whether to accept",
         ("target", "path*")),
    Spec("/open", "NAME|#GROUP", "show a conversation, numbered; /reply /react /edit /delete act on it",
         ("target",)),
    Spec("/reply", "N TEXT", "reply to message N of the open conversation", ("text", "text")),
    Spec("/react", "N EMOJI", "react to message N (+1 heart laugh wow sad fire party...); again to undo",
         ("text", "emoji")),
    Spec("/edit", "N|last TEXT", "change one of your messages", ("text", "text")),
    Spec("/delete", "N|last", "delete one of your messages, for everyone", ("text",)),
    Spec("/requests", "", "invites, file offers and messages waiting for you"),
    Spec("/accept", "ITEM", "accept a group invite, a file offer, or a stranger's messages", ("pending",)),
    Spec("/decline", "ITEM", "decline a group invite or a file offer", ("pending",)),
    Spec("/files", "", "files you've offered and been offered"),
    Spec("/group", "create|add|remove|leave|history ...", "manage groups (see below)", ("groupsub",)),
    Spec("/groups", "", "list your groups"),
    Spec("/history", "GROUP [N]", "last N messages in a group (default 20)", ("group", "text")),
    Spec("/verify", "NAME [yes]", "compare a safety code with someone to make sure you have their real key",
         ("peer", "text")),
    Spec("/unverify", "NAME", "remove the verified mark", ("peer",)),
    Spec("/nick", "NAME NICKNAME", "your own name for someone (blank to clear); only you see it", ("peer", "text")),
    Spec("/trust", "NAME", "make someone a contact", ("peer",)),
    Spec("/untrust", "NAME", "stop treating someone as a contact", ("contact",)),
    Spec("/block", "NAME", "ignore everything from someone", ("peer",)),
    Spec("/unblock", "NAME", "stop ignoring someone", ("blocked",)),
    Spec("/peers", "", "everyone in your phonebook, with trust and how you'd reach them"),
    Spec("/join", "ADDRESS", "fetch a node's / hub's phonebook (HOST:PORT or ws(s)://...)", ("text",)),
    Spec("/whoami", "", "your key, addresses and relay status"),
    Spec("/sync", "", "pull missed group messages from other members"),
    Spec("/move", "PORT [HOST]", "change your address and tell everyone", ("text", "text")),
    Spec("/help", "", "show this list"),
    Spec("/quit", "", "exit"),
]
GROUP_SUBS = {
    "create": Spec("create", "NAME [--shared] PEER...",
                   "make a group you own and invite people. History is private by default (people "
                   "added later only see messages from when they joined); --shared lets them catch up",
                   ("text", "peer*")),
    "add": Spec("add", "GROUP PEER", "invite someone (owner only)", ("group", "peer")),
    "remove": Spec("remove", "GROUP PEER", "remove a member (owner only)", ("group", "peer")),
    "leave": Spec("leave", "GROUP [NEW-OWNER]", "leave a group (the owner's node removes you); if you're "
                  "the host, name a member to hand it to", ("group", "member")),
    "transfer": Spec("transfer", "GROUP NEW-OWNER", "make another member the host (you stay a member)",
                     ("group", "member")),
    "delete": Spec("delete", "GROUP", "delete a group you host, for everyone", ("group",)),
    "history": Spec("history", "GROUP private|shared",
                    "change the history setting (owner only); applies to messages sent from now on",
                    ("group", "mode")),
}
_BY_NAME = {c.name: c for c in COMMANDS}


def _help_text() -> str:
    def row(left: str, text: str) -> str:
        # Long usages get their description on the next line so the columns stay aligned.
        return f"  {left:<28} {text}" if len(left) <= 28 else f"  {left}\n  {'':<28} {text}"

    lines = ["commands (in the terminal UI, Tab completes):"]
    lines += [row((c.name + " " + c.usage).strip(), c.help) for c in COMMANDS if c.name != "/group"]
    lines += ["", "groups:"]
    lines += [row(f"/group {s.name} {s.usage}", s.help) for s in GROUP_SUBS.values()]
    return "\n".join(lines)


HELP = _help_text()

SEND_RESULT = {"delivered": "delivered",
               "waiting": "they're offline; it'll be delivered when you're both online (it's in your outbox)",
               None: "could not reach them"}


def link(node: Node, rec: Record) -> str:
    """How we'd reach rec right now: "you", "direct" (punched UDP), "open" (they accept connections),
    or "relay"."""
    if rec.pubkey == node.ident.pubkey:
        return "you"
    if node.udp.path(rec.pubkey):
        return "direct"
    if any(not a.startswith(RELAY_PREFIX) for a in rec.addrs):
        return "open"
    return "relay"


class Commands:
    def __init__(self, node: Node, out: Callable[[str], None]):
        self.node = node
        self.out = out
        # The conversation /reply /react /edit /delete act on: ("dm", pubkey) or ("group", group_id).
        # The TUI sets it to whatever is open; in the CLI, /open sets it.
        self.context: tuple[str, str] | None = None

    # --- conversations: replies, reactions, edits ---------------------------------------------------

    def view(self, context: tuple[str, str] | None = None) -> list[thread.Item]:
        kind, ident = context or self.context or ("", "")
        if kind == "dm":
            return thread.build(self.node.dms.messages(ident))
        if kind == "group":
            return thread.build(self.node.groups.messages(ident))
        return []

    def format_item(self, item: thread.Item, items: list[thread.Item] | None = None) -> str:
        """Plain-text rendering (the CLI's; the TUI styles its own)."""
        who = "you" if item.sender == self.node.ident.pubkey else self.name_of(item.sender)
        text = "(deleted)" if item.deleted else item.text + (" (edited)" if item.edited else "")
        quote = ""
        if item.reply_to:
            original = next((i for i in items or [] if i.id == item.reply_to), None)
            quote = (f" [re #{original.number} {self.name_of(original.sender)}: {original.text[:40]!r}]"
                     if original else " [re: a message you don't have]")
        reacts = "  " + " ".join(f"{e}{len(s) if len(s) > 1 else ''}" for e, s in item.reactions.items()) \
            if item.reactions else ""
        return f"{item.number:>4}  {time.strftime('%H:%M', time.localtime(item.ts))} {who}:{quote} {text}{reacts}"

    def describe_action(self, m: dict, context: tuple[str, str]) -> str | None:
        """One line for an incoming reaction / edit / delete, or None for a normal message."""
        kind = m.get("kind")
        if kind not in thread.ACTIONS:
            return None
        who = self.name_of(m["from"])
        target = next((i for i in self.view(context) if i.id == m.get("target")), None)
        about = f" #{target.number} {target.text[:40]!r}" if target and not target.deleted else ""
        if kind == "react":
            return f"{who} reacted {thread.emoji(str(m.get('text', '')))} to{about or ' a message'}"
        if kind == "edit":
            return f"{who} edited{about or ' a message'}"
        return f"{who} deleted a message"

    async def _post(self, text: str, extra: dict) -> None:
        kind, ident = self.context
        if kind == "dm":
            result = await self.node.send(ident, text, extra)
            if result != "delivered":
                self.out("  " + SEND_RESULT[result])
        elif g := self.node.groups.get(ident):
            await self.send_group(g, text, extra)

    def _target(self, ref: str, mine: bool = False) -> thread.Item | None:
        if not self.context:
            self.out("  open a conversation first (/open NAME or /open #group)")
            return None
        items = self.view()
        if ref.lower() == "last":
            pool = [i for i in items if not i.deleted and (i.sender == self.node.ident.pubkey) == mine] if mine \
                else [i for i in items if not i.deleted and i.sender != self.node.ident.pubkey]
            item = pool[-1] if pool else None
        else:
            item = thread.by_number(items, int(ref)) if ref.isdigit() else None
        if not item:
            self.out(f"  no message {ref!r} here; the numbers are on the left of each message")
        elif mine and item.sender != self.node.ident.pubkey:
            self.out("  you can only edit or delete your own messages")
            return None
        return item

    def name_of(self, pubkey: str) -> str:
        """What to call someone: your nickname for them if you set one, else their own name, with a
        key prefix ("alice#3fK9xQ") whenever another key uses the same name. One word, no spaces."""
        node = self.node
        if nick := node.trust.nickname(pubkey):
            return nick
        rec = node.phonebook.get(pubkey)
        if not rec:
            return short(pubkey)
        base = rec.name.replace(" ", "_") or short(pubkey)
        if pubkey != node.ident.pubkey and self.name_clashes(pubkey):
            return f"{base}#{pubkey[:6]}"
        return base

    def name_clashes(self, pubkey: str) -> list[str]:
        """Other keys whose name (or your nickname for them) is the same as this key's own name."""
        rec = self.node.phonebook.get(pubkey)
        if not rec:
            return []
        name = rec.name.lower()
        same_name = [r.pubkey for r in self.node.phonebook.all() if r.pubkey != pubkey and r.name.lower() == name]
        same_nick = [k for k, n in self.node.trust.nicknames.items() if k != pubkey and n.lower() == name]
        return same_name + same_nick

    def impostor_warning(self, pubkey: str) -> str:
        """Non-empty if this key's name matches one of your contacts' (a likely impersonation)."""
        node = self.node
        if node.trust.trusted(pubkey) or node.trust.nickname(pubkey):
            return ""
        lookalikes = [k for k in self.name_clashes(pubkey) if node.trust.trusted(k) or node.trust.nickname(k)]
        if not lookalikes:
            return ""
        rec = node.phonebook.get(pubkey)
        return (f"⚠ {rec.name!r} is also the name of your contact {self.name_of(lookalikes[0])}, but this is a "
                f"different key ({fingerprint(pubkey)}). It may not be who you think.")

    def peer(self, query: str) -> Record | None:
        """Resolve a person by your nickname, their name, name#keyprefix, or a key prefix."""
        node, q = self.node, query.strip()
        name, _, prefix = q.partition("#")
        candidates = [r for r in node.phonebook.all() if r.pubkey != node.ident.pubkey]
        nick_hits = [r for r in candidates if (node.trust.nickname(r.pubkey) or "").lower() == q.lower()]
        matches = nick_hits or [r for r in candidates
                                if (r.name.replace(" ", "_").lower() == name.lower() and r.pubkey.startswith(prefix))
                                or (not prefix and r.pubkey.startswith(q))
                                or self.name_of(r.pubkey).lower() == q.lower()]
        matches = list({r.pubkey: r for r in matches}.values())
        if len(matches) != 1:
            if matches:
                options = ", ".join(self.name_of(r.pubkey) for r in matches)
                self.out(f"  several people match {query!r}: {options}")
            else:
                self.out(f"  no one matches {query!r}; see /peers")
            return None
        return matches[0]

    def member(self, g: GroupRecord, query: str) -> Record | None:
        """A member of g by name or key prefix (resolved among the group's members only)."""
        q = query.lower()
        matches = [m for m in g.members if m != self.node.ident.pubkey
                   and (self.name_of(m).lower() == q or m.lower().startswith(q))]
        if len(matches) != 1:
            self.out(f"  {'no member' if not matches else 'several members'} matching {query!r} in #{g.name}")
            return None
        return self.node.phonebook.get(matches[0]) or Record(matches[0], self.name_of(matches[0]), [], 0)

    def group(self, query: str) -> GroupRecord | None:
        matches = self.node.groups.find(query)
        if len(matches) != 1:
            self.out(f"  {'no' if not matches else 'ambiguous'} group {query!r}; use an id prefix from /groups")
            return None
        return matches[0]

    async def send_group(self, g: GroupRecord, text: str, extra: dict | None = None) -> None:
        try:
            delivered, total = await self.node.send_group(g.group_id, text, extra)
        except ValueError as e:
            self.out(f"  {e}")
            return
        if delivered < total:
            self.out(f"  sent to {delivered}/{total}; {total - delivered} unreachable will catch up later")

    # --- requests ----------------------------------------------------------------------------------

    def message_requests(self) -> list[str]:
        """Strangers who have messaged you (not contacts, not blocked)."""
        return [pk for pk, log in self.node.dms.convos.items()
                if self.node.trust.get(pk) == "unknown" and any(m["from"] == pk for m in log)]

    def pending_files(self) -> list[tuple[str, dict]]:
        return [(fid, o) for fid, o in self.node.files.incoming.items() if o.get("status") in ("pending", "failed")]

    def requests(self) -> list[tuple[str, str, str]]:
        """Everything waiting for you, as (kind, id, description)."""
        node, items = self.node, []
        for g in node.pending_groups():
            names = ", ".join(self.name_of(m) for m in g.members if m != node.ident.pubkey)
            items.append(("group", g.group_id, f"group invite: #{g.name} from {self.name_of(g.owner)} ({names})"))
        for fid, o in self.pending_files():
            who = self.name_of(o["from"]) + ("" if node.trust.trusted(o["from"]) else " (not a contact)")
            where = f" in #{self._group_name(o['group'])}" if o.get("group") else ""
            state = f" [failed: {o.get('error')}]" if o["status"] == "failed" else ""
            items.append(("file", fid, f"file: {o['name']} ({human_size(o['size'])}) from {who}{where}{state}"))
        for pk in self.message_requests():
            last = [m for m in node.dms.messages(pk) if m["from"] == pk][-1]["text"]
            items.append(("contact", pk, f"messages from {self.name_of(pk)} (not a contact): {last[:60]!r}"))
        return items

    def _group_name(self, group_id: str | None) -> str:
        g = self.node.groups.get(group_id) if group_id else None
        return g.name if g else "?"

    def _resolve_pending(self, query: str) -> tuple[str, str] | None:
        """Find the request someone means by a group name, file name / id prefix, or person's name."""
        q = query.strip().strip('"').lstrip("#").lower()
        found = [(kind, ident) for kind, ident, _ in self.requests()
                 if (kind == "group" and self._group_name(ident).lower() == q)
                 or (kind == "file" and (self.node.files.incoming[ident]["name"].lower() == q
                                         or ident.lower().startswith(q)))
                 or (kind == "contact" and self.name_of(ident).lower() == q)]
        if len(found) == 1:
            return found[0]
        self.out(f"  {'nothing pending called' if not found else 'several things match'} {query!r}; see /requests")
        return None

    # --- completion --------------------------------------------------------------------------------

    def _candidates(self, kind: str, tokens: list[str] | None = None) -> list[str]:
        kind = kind.rstrip("*")
        node = self.node
        if kind == "member":  # other members of the group named earlier on the line (/group sub GROUP ...)
            g = next((g for g in node.groups.all() if tokens and len(tokens) > 2 and g.name == tokens[2]), None)
            return sorted({self.name_of(m) for m in g.members if m != node.ident.pubkey}, key=str.lower) if g else []
        if kind == "peer":
            names = {self.name_of(r.pubkey) for r in node.phonebook.all() if r.pubkey != node.ident.pubkey}
            return sorted(names, key=str.lower)
        if kind in ("contact", "blocked"):
            level = "trusted" if kind == "contact" else "blocked"
            return sorted({self.name_of(pk) for pk, lv in node.trust.levels.items() if lv == level}, key=str.lower)
        if kind == "group":
            return sorted({g.name for g in node.groups.all()}, key=str.lower)
        if kind == "target":
            contacts = sorted({self.name_of(pk) for pk, lv in node.trust.levels.items() if lv == "trusted"}, key=str.lower)
            groups = sorted({"#" + g.name for g in node.groups.all() if node.groups.joined(g.group_id)}, key=str.lower)
            return contacts + groups
        if kind == "pending":
            out = []
            for kind_, ident, _ in self.requests():
                if kind_ == "group":
                    out.append(self._group_name(ident))
                elif kind_ == "file":
                    out.append(node.files.incoming[ident]["name"])
                else:
                    out.append(self.name_of(ident))
            return out
        if kind == "mode":
            return ["private", "shared"]
        if kind == "emoji":
            return list(thread.EMOJI)
        if kind == "groupsub":
            return list(GROUP_SUBS)
        return []

    @staticmethod
    def _complete_path(partial: str) -> str | None:
        """Complete a filesystem path: the rest of the first matching entry ("/" after folders)."""
        expanded = os.path.expanduser(partial.strip('"')) if partial else ""
        folder, prefix = os.path.split(expanded)
        try:
            entries = sorted(os.scandir(folder or "."), key=lambda e: (not e.is_dir(), e.name.lower()))
        except OSError:
            return None
        for entry in entries:
            if entry.name.lower().startswith(prefix.lower()) and entry.name != prefix and not entry.name.startswith("."):
                return entry.name[len(prefix):] + ("/" if entry.is_dir() else "")
        return None

    def _arg_kind(self, tokens: list[str]) -> str | None:
        """Kind of the argument being typed (the last token)."""
        spec = _BY_NAME.get(tokens[0].lower())
        if not spec:
            return None
        index = len(tokens) - 2
        if tokens[0].lower() == "/group" and index > 0:
            sub = GROUP_SUBS.get(tokens[1].lower())
            if not sub:
                return None
            spec, index = sub, index - 1
        if index < len(spec.args):
            return spec.args[index]
        return spec.args[-1] if spec.args and spec.args[-1].endswith("*") else None

    def complete(self, value: str) -> str | None:
        """Full-line suggestion for a partly typed command, or None. Keeps what was typed as-is."""
        if not value.startswith("/"):
            return None
        tokens = value.split(" ")
        partial = tokens[-1]
        if len(tokens) == 1:
            exact = _BY_NAME.get(partial.lower())
            if exact:
                return value + " " if exact.args else None
            match = next((c for c in COMMANDS if c.name.startswith(partial.lower())), None)
            return value + match.name[len(partial):] if match else None
        kind = self._arg_kind(tokens)
        if not kind or kind == "text":
            return None
        if kind == "path*":  # paths can contain spaces: complete everything after the target
            rest = self._complete_path(" ".join(tokens[2:]))
            return value + rest if rest else None
        options = self._candidates(kind, tokens) + (["--shared"] if kind == "peer*" and "--shared" not in tokens else [])
        used = {t.lower() for t in tokens[1:-1]}
        for option in options:
            if option.lower().startswith(partial.lower()) and option.lower() not in used:
                if option.lower() == partial.lower():
                    continue
                return value + option[len(partial):] + " "
        return None

    def hint(self, value: str) -> str:
        """One or a few lines describing what can be typed here."""
        if not value.startswith("/"):
            return ""
        tokens = value.split(" ")
        if len(tokens) == 1:
            matches = [c for c in COMMANDS if c.name.startswith(tokens[0].lower())]
            if not matches:
                return f"no command {tokens[0]!r}; try /help"
            return "\n".join(f"{(c.name + ' ' + c.usage).strip():<26} {c.help}" for c in matches[:7])
        spec = _BY_NAME.get(tokens[0].lower())
        if not spec:
            return f"no command {tokens[0]!r}; try /help"
        if spec.name == "/group" and len(tokens) > 2 and tokens[1].lower() in GROUP_SUBS:
            sub = GROUP_SUBS[tokens[1].lower()]
            usage = f"/group {sub.name} {sub.usage}  {sub.help}"
        elif spec.name == "/group":
            usage = "\n".join(f"/group {s.name} {s.usage}" for s in GROUP_SUBS.values())
        else:
            usage = f"{spec.name} {spec.usage}  {spec.help}"
        kind = self._arg_kind(tokens)
        if kind == "path*":
            return usage + "\nTab completes file and folder names"
        options = self._candidates(kind, tokens) if kind and kind != "groupsub" else []
        if options:
            label = {"peer": "people", "contact": "contacts", "blocked": "blocked", "group": "groups",
                     "mode": "options", "target": "contacts / groups", "pending": "waiting", "emoji": "emoji",
                     "member": "members"}[kind.rstrip("*")]
            shown = ", ".join(options[:12]) + (" …" if len(options) > 12 else "")
            usage += f"\n{label}: {shown}"
        return usage

    async def run(self, line: str) -> bool:
        """Execute one command line. Returns False when the user asked to quit."""
        node, out = self.node, self.out
        cmd, _, rest = line.strip().partition(" ")
        rest = rest.strip()
        if cmd == "/quit":
            return False
        elif cmd == "/help":
            out(HELP)
        elif cmd == "/whoami":
            status = node.relay_status()
            out(f"{node.name} at {', '.join(node.addrs)}{f' ({status})' if status else ''}")
            out(f"  your key: {node.ident.pubkey}")
            out(f"  fingerprint: {fingerprint(node.ident.pubkey)}  (people can compare this to check it's really you)")
            out(f"  this device: {node.device}  (lichen --export FILE to set up another device as you)")
            if node.outbox.items:
                out(f"  {len(node.outbox.items)} item(s) waiting for their recipients to come online")
            if node.udp.public:
                out(f"  public UDP address (as the internet sees it): {node.udp.public[0]}:{node.udp.public[1]}")
        elif cmd == "/peers":
            labels = {"you": "(you)", "relay": "[via relay]", "open": ""}
            for r in sorted(node.phonebook.all(), key=lambda r: self.name_of(r.pubkey).lower()):
                kind = link(node, r)
                path = node.udp.path(r.pubkey)
                how = f"[direct {path[0]}:{path[1]}]" if kind == "direct" else labels[kind]
                trust = "" if kind == "you" else {"trusted": "contact", "blocked": "BLOCKED"}.get(node.trust.get(r.pubkey), "")
                mark = "✓" if node.trust.is_verified(r.pubkey) else ("⚠" if self.impostor_warning(r.pubkey) else " ")
                own = f" (calls themselves {r.name})" if node.trust.nickname(r.pubkey) else ""
                out(f"  {mark} {self.name_of(r.pubkey):<16} {fingerprint(r.pubkey)}  {trust:<8} {how}{own}")
        elif cmd == "/join" and rest:
            await node.join(rest)
        elif cmd == "/move" and rest:
            parts = rest.split()
            await node.move(int(parts[0]), parts[1] if len(parts) > 1 else None)
        elif cmd == "/msg" and " " in rest:
            target, text = rest.split(" ", 1)
            if rec := self.peer(target):
                out("  " + SEND_RESULT[await node.send(rec.pubkey, text)])
        elif cmd == "/group":
            parts = rest.split()
            sub, args = (parts[0], parts[1:]) if parts else ("", [])
            try:
                if sub == "create" and args:
                    history = "shared" if "--shared" in args else "private"
                    recs = [self.peer(q) for q in args[1:] if q != "--shared"]
                    if None not in recs:
                        g = await node.create_group(args[0], [r.pubkey for r in recs], history)
                        out(f"  created {g.name} ({g.group_id[:8]}, history {g.history}) "
                            f"with {', '.join(map(self.name_of, g.members))}")
                elif sub == "history" and len(args) == 2:
                    if g := self.group(args[0]):
                        g = await node.set_history(g.group_id, args[1])
                        out(f"  {g.name}: history is now {g.history} (for messages from now on)")
                elif sub in ("add", "remove") and len(args) == 2:
                    if (g := self.group(args[0])) and (r := self.peer(args[1])):
                        fn = node.add_member if sub == "add" else node.remove_member
                        g = await fn(g.group_id, r.pubkey)
                        out(f"  {g.name}: {', '.join(map(self.name_of, g.members))}"
                            + (" (they'll need to accept)" if sub == "add" else ""))
                elif sub == "leave" and len(args) == 1:
                    if g := self.group(args[0]):
                        await node.leave_group(g.group_id)
                        out(f"  left #{g.name}")
                elif sub in ("leave", "transfer") and len(args) == 2:
                    if (g := self.group(args[0])) and (r := self.member(g, args[1])):
                        result = await node.transfer_group(g.group_id, r.pubkey, leave=sub == "leave")
                        done = "left it" if sub == "leave" else "you're still a member"
                        out(f"  handed #{g.name} to {r.name}; {done}" if result == "delivered" else
                            f"  {r.name} is offline: they'll take over #{g.name} when you're both online"
                            if result == "waiting" else f"  couldn't reach {r.name}")
                elif sub == "delete" and len(args) == 1:
                    if g := self.group(args[0]):
                        await node.delete_group(g.group_id)
                        out(f"  deleted #{g.name} for everyone")
                else:
                    out(HELP)
            except ValueError as e:
                out(f"  {e}")
        elif cmd == "/groups":
            for g in node.groups.all():
                status = "" if node.ident.pubkey in g.members else " (removed)"
                owner = " (owner)" if g.owner == node.ident.pubkey else ""
                out(f"  {g.name:<12} {g.group_id[:8]}  history {g.history:<7}  "
                    f"{', '.join(map(self.name_of, g.members))}{owner}{status}")
        elif cmd == "/g" and " " in rest:
            target, text = rest.split(" ", 1)
            if g := self.group(target):
                await self.send_group(g, text)
        elif cmd == "/history" and rest:
            parts = rest.split()
            if g := self.group(parts[0].lstrip("#")):
                n = int(parts[1]) if len(parts) > 1 and parts[1].isdigit() else 20
                items = self.view(("group", g.group_id))
                for item in items[-n:]:
                    out(self.format_item(item, items))
        elif cmd == "/sync":
            out(f"  {await node.sync_all()} new group message(s), {await node.sync_dms()} new direct message(s)")
            sent = await node.flush_outbox()
            if sent:
                out(f"  delivered {sent} item(s) that were waiting for people to come online")
            waiting = len(node.outbox.items)
            if waiting:
                out(f"  {waiting} item(s) still waiting for their recipients to come online")
        elif cmd == "/open" and rest:
            if rest.startswith("#"):
                if g := self.group(rest[1:]):
                    self.context = ("group", g.group_id)
            elif r := self.peer(rest):
                self.context = ("dm", r.pubkey)
            if self.context:
                items = self.view()
                for item in items[-20:]:
                    out(self.format_item(item, items))
                if not items:
                    out("  no messages yet")
        elif cmd == "/reply" and " " in rest:
            ref, text = rest.split(" ", 1)
            if item := self._target(ref):
                await self._post(text, {"reply_to": item.id})
        elif cmd == "/react" and " " in rest:
            ref, symbol = rest.split(" ", 1)
            if item := self._target(ref):
                await self._post(thread.emoji(symbol), {"kind": "react", "target": item.id})
        elif cmd == "/edit" and " " in rest:
            ref, text = rest.split(" ", 1)
            if item := self._target(ref, mine=True):
                await self._post(text, {"kind": "edit", "target": item.id})
        elif cmd == "/delete" and rest:
            if item := self._target(rest.split()[0], mine=True):
                await self._post("", {"kind": "delete", "target": item.id})
        elif cmd == "/requests":
            items = self.requests()
            if not items:
                out("  nothing waiting for you")
            for _, _, text in items:
                out(f"  {text}")
            if items:
                out("  /accept ITEM or /decline ITEM (Tab completes)")
        elif cmd in ("/accept", "/decline") and rest:
            if found := self._resolve_pending(rest):
                await self._answer_request(cmd == "/accept", *found)
        elif cmd == "/verify" and rest:
            target, _, answer = rest.partition(" ")
            if r := self.peer(target):
                who = self.name_of(r.pubkey)
                if answer.strip().lower() in ("yes", "y", "confirm"):
                    node.trust.set_verified(r.pubkey, time.time())
                    if node.trust.get(r.pubkey) == "unknown":
                        node.trust.set(r.pubkey, "trusted")
                    out(f"  ✓ {who} is verified")
                else:
                    out(f"  safety code with {who}:  {safety_code(node.ident.pubkey, r.pubkey)}")
                    out(f"  Compare it with {who} in person, on a call, or in another app you trust.")
                    out(f"  They run /verify {node.name} and should see exactly the same digits.")
                    out(f"  If it matches: /verify {who} yes    If not, you are not talking to the real {r.name}.")
        elif cmd == "/unverify" and rest:
            if r := self.peer(rest):
                node.trust.set_verified(r.pubkey, None)
                out(f"  {self.name_of(r.pubkey)} is no longer marked verified")
        elif cmd == "/nick" and rest:
            target, _, nickname = rest.partition(" ")
            if r := self.peer(target):
                try:
                    node.trust.set_nickname(r.pubkey, nickname.strip() or None)
                except ValueError as e:
                    out(f"  {e}")
                else:
                    out(f"  you now see them as {self.name_of(r.pubkey)}")
        elif cmd in ("/trust", "/untrust", "/block", "/unblock") and rest:
            if r := self.peer(rest):
                level = {"/trust": "trusted", "/untrust": "unknown", "/block": "blocked", "/unblock": "unknown"}[cmd]
                node.trust.set(r.pubkey, level)
                out(f"  {r.name} is now {'a contact' if level == 'trusted' else level}")
        elif cmd == "/send" and " " in rest:
            target, path = rest.split(" ", 1)
            await self.send_file(target, path)
        elif cmd == "/files":
            if not node.files.incoming and not node.files.outgoing:
                out("  no files yet. /send NAME PATH offers one")
            for fid, o in sorted(node.files.incoming.items(), key=lambda kv: kv[1]["ts"]):
                extra = o.get("saved") or o.get("error") or ""
                out(f"  in   {o['status']:<11} {o['name']} ({human_size(o['size'])}) from "
                    f"{self.name_of(o['from'])}  {extra}")
            for fid, e in sorted(node.files.outgoing.items(), key=lambda kv: kv[1]["ts"]):
                to = f"#{self._group_name(e['group'])}" if e.get("group") else ", ".join(map(self.name_of, e["to"]))
                out(f"  out  offered     {e['name']} ({human_size(e['size'])}) to {to}")
        else:
            out(HELP)
        return True

    async def _answer_request(self, accept: bool, kind: str, ident: str) -> None:
        node, out = self.node, self.out
        try:
            if kind == "group":
                name = self._group_name(ident)
                if accept:
                    new = await node.accept_group(ident)
                    out(f"  joined #{name}" + (f"; caught up on {new} message(s)" if new else ""))
                else:
                    await node.leave_group(ident, status="declined")
                    out(f"  declined #{name}")
            elif kind == "file":
                offer = node.files.incoming[ident]
                if accept:
                    out(f"  downloading {offer['name']} ({human_size(offer['size'])})…")
                    saved = await node.download(ident)
                    out(f"  saved to {saved}" if saved else f"  failed: {offer.get('error')}")
                else:
                    node.decline_file(ident)
                    out(f"  declined {offer['name']}")
            else:
                if accept:
                    node.trust.set(ident, "trusted")
                    out(f"  {self.name_of(ident)} is now a contact")
                else:
                    out(f"  to ignore {self.name_of(ident)}, /block them")
        except ValueError as e:
            out(f"  {e}")

    async def send_file(self, target: str, path: str) -> None:
        node, out = self.node, self.out
        path = path.strip().strip('"')
        try:
            if target.startswith("#"):
                if not (g := self.group(target[1:])):
                    return
                _, entry, offered = await node.send_file(Path(path).expanduser(), [], group=g.group_id)
                out(f"  offered {entry['name']} ({human_size(entry['size'])}) to #{g.name} "
                    f"({offered}/{len(entry['to'])} reached now; the rest when they're online)")
            elif r := self.peer(target):
                if node.trust.get(r.pubkey) == "unknown":
                    node.trust.set(r.pubkey, "trusted")
                _, entry, offered = await node.send_file(Path(path).expanduser(), [r.pubkey])
                out(f"  offered {entry['name']} ({human_size(entry['size'])}) to {r.name}"
                    + ("" if offered else "; they'll get the offer when they're online"))
        except ValueError as e:
            out(f"  {e}")
