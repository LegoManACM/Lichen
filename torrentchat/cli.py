"""Interactive chat client.

Hub with an open port:        python -m torrentchat --name hub --host <public ip> --bind 0.0.0.0
Hub behind Cloudflare Tunnel: python -m torrentchat --name hub --url wss://chat.example.com
Everyone else:                python -m torrentchat --name alice --relay <hub address>
Or put the options in torrentchat.yaml and just run: python -m torrentchat   (see config.py, --init)
"""

import argparse
import asyncio
import sys
import time
from pathlib import Path

from . import config
from .commands import Commands
from .files import DEFAULT_MAX_MB, human_size
from .identity import short
from .node import Node


def parse_args(argv=None) -> argparse.Namespace:
    p = argparse.ArgumentParser(prog="torrentchat", description="Decentralized encrypted chat",
                                epilog="Options can also come from a YAML/JSON config file; flags override it.")
    p.add_argument("--config", "-c", type=Path, default=None,
                   help=f"config file (default: {' / '.join(config.DEFAULT_FILES)} in the current folder, if present)")
    p.add_argument("--init", nargs="?", const="torrentchat.yaml", default=None, metavar="FILE",
                   help="write a commented starter config (default torrentchat.yaml) and exit")
    for key, (kind, help_) in config.OPTIONS.items():
        flag = "--" + key.replace("_", "-")
        if kind is list:
            p.add_argument(flag, action="append", default=None, help=help_)
        else:
            p.add_argument(flag, type=kind, default=None, help=help_)
    args = p.parse_args(argv)

    if args.init:
        try:
            config.write_template(Path(args.init), args.name or "yourname",
                                  (args.relay or ["chat.example.com:9000"])[0])
        except config.ConfigError as e:
            p.exit(1, f"{e}\n")
        p.exit(0, f"wrote {args.init}; edit it, then run: python -m torrentchat"
                  f"{'' if args.init in config.DEFAULT_FILES else ' --config ' + args.init}\n")

    path = args.config or config.find_default()
    settings: dict = {}
    if path:
        try:
            settings = config.load(path)
        except (OSError, ValueError) as e:  # ConfigError, bad JSON/YAML, missing file
            p.exit(1, f"error: {e}\n")
    for key in config.OPTIONS:  # command line wins over the file
        if getattr(args, key) is not None:
            settings[key] = getattr(args, key)
    if not settings.get("name"):
        p.exit(1, "error: a name is required (--name NAME, or `name:` in a config file; try --init)\n")

    cfg = argparse.Namespace(**{key: settings.get(key) for key in config.OPTIONS})
    cfg.config_path = path
    cfg.port = cfg.port or 9000
    cfg.bootstrap = list(cfg.bootstrap or [])
    cfg.relay = list(cfg.relay or [])
    cfg.bootstrap += [r for r in cfg.relay if r not in cfg.bootstrap]
    return cfg


def make_node(args, **callbacks) -> Node:
    """Build a Node from parsed options (shared by the CLI and the terminal UI)."""
    return Node.from_dir(
        Path(args.data) if args.data else Path("data") / args.name,
        args.name,
        host=args.host or "127.0.0.1",
        port=args.port,
        bind=args.bind,
        bootstrap=args.bootstrap,
        relay=args.relay,
        # Behind a relay with no host/url: don't advertise a private address nobody can reach.
        direct=bool(args.host or args.url) or not args.relay,
        url=args.url,
        stun=args.stun,
        downloads=Path(args.downloads).expanduser() if getattr(args, "downloads", None) else None,
        max_file_mb=DEFAULT_MAX_MB if getattr(args, "max_file_mb", None) is None else args.max_file_mb,
        auto_download_mb=getattr(args, "auto_download_mb", None) or 0,
        **callbacks,
    )


async def run(args) -> None:
    def on_message(rec, text, env):
        who = rec.name if rec else short(env["from"])
        print(f"\n<{who}> {text}", flush=True)

    def on_group_message(group, rec, text, env, live):
        who = rec.name if rec else short(env["from"])
        when = "" if live else time.strftime(" (missed, %H:%M)", time.localtime(env["ts"]))
        print(f"\n[{group.name}] <{who}> {text}{when}", flush=True)

    def on_event(kind, d):
        if kind != "file_offer":
            return  # invites, progress and results are already logged by the node
        o = d["offer"]
        rec = node.phonebook.get(o["from"])
        who = (rec.name if rec else short(o["from"])) + ("" if d["trusted"] else " (not a contact)")
        if o["status"] == "refused":
            print(f"\n* {who} offered {o['name']} ({human_size(o['size'])}): refused, {o['error']}", flush=True)
        else:
            print(f"\n* {who} offers {o['name']} ({human_size(o['size'])}): /accept {o['name']} or /decline",
                  flush=True)

    if args.config_path:
        print(f"using config {args.config_path}")
    node = make_node(args, on_message=on_message, on_group_message=on_group_message, on_event=on_event,
                     log=lambda s: print(f"\n* {s}", flush=True))
    await node.start()
    print(f"{args.name} ({short(node.ident.pubkey)}) reachable at {', '.join(node.addrs)}. /help for commands.")

    commands = Commands(node, print)
    loop = asyncio.get_running_loop()
    try:
        while True:
            line = (await loop.run_in_executor(None, input, "> ")).strip()
            if line and not await commands.run(line):
                break
    except (EOFError, KeyboardInterrupt):
        pass
    finally:
        await node.stop()


def main(argv=None) -> None:
    args = parse_args(argv)
    ui = (args.ui or "auto").lower()
    if ui not in ("auto", "tui", "cli"):
        raise SystemExit(f"error: ui must be auto, tui or cli (got {ui!r})")
    if ui == "auto":
        ui = "tui" if sys.stdin.isatty() and sys.stdout.isatty() else "cli"
    if ui == "tui":
        try:
            from .tui import ChatApp
        except ImportError:
            raise SystemExit("the terminal UI needs Textual: pip install textual  (or use --ui cli)")
        ChatApp(args).run()
        return
    # Piped output on Windows uses a legacy code page; don't crash on names/messages it can't encode.
    for stream in (sys.stdout, sys.stderr):
        if hasattr(stream, "reconfigure"):
            stream.reconfigure(errors="replace")
    try:
        asyncio.run(run(args))
    except KeyboardInterrupt:
        pass
