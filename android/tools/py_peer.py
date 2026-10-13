"""A real Python hub (plus, optionally, a relay-only Python user attached to it) for the Android tests and
for trying the phone against a live node. One asyncio process; control it with JSON lines on stdin and
read JSON events from stdout (logs go to stderr).

    python android/tools/py_peer.py --dir /tmp/peer --port 9100 --user alice

Commands (one JSON object per line):
  {"cmd": "whoami"}                                   -> {"event": "whoami", "hub": {...}, "user": {...}|null}
  {"cmd": "send", "from": "user"|"hub", "to": PK, "text": "..."}   -> {"event": "sent", "result": "delivered"|"waiting"|null}
  {"cmd": "stop_user"} / {"cmd": "start_user"}        simulate the user going offline / coming back
  {"cmd": "state"}                                    -> {"event": "state", "ious": ..., "user_outbox": n, "hub_outbox": n,
                                                          "phonebook": [names], "hub_attached": [pubkeys]}
  {"cmd": "dms", "who": "user"|"hub", "peer": PK}     -> {"event": "dms", "messages": [...]}
  {"cmd": "trust", "who": "user"|"hub", "pk": PK, "level": "trusted"}  -> {"event": "ok"}
  {"cmd": "inject", "who": "user"|"hub", "peer": PK, "text": "..."}   put a signed message from that node into its own history
                                                      (as if it had been sent but never arrived: chat sync must find it)
  {"cmd": "forget", "who": "user"|"hub", "peer": PK}  drop that node's history with peer (in memory only)
  {"cmd": "sync", "who": "user"|"hub", "peer": PK}    run dm_sync with peer -> {"event": "synced", "n": gained}
  {"cmd": "send_file", "from": "user"|"hub", "to": PK|[PK], "name": "x.bin", "size": N, "seed": S}
                                                      write N deterministic bytes (random.Random(S)) and offer them
                                                      -> {"event": "file_sent", "file_id", "sha256", "size", "delivered"}
  {"cmd": "offers", "who": "user"|"hub"}              -> {"event": "offers", "incoming": {file_id: offer}, "outgoing": {...}}
  {"cmd": "accept_file", "who": ..., "file_id": ID}   download an offer -> {"event": "file_downloaded", "saved": path|null, "sha256": ...}
  {"cmd": "decline_file", "who": ..., "file_id": ID}  -> {"event": "ok"}
  {"cmd": "file_limits", "who": ..., "max_mb": F, "auto_mb": F}   -> {"event": "ok"}
  {"cmd": "quit"}
Events: {"event": "ready", ...whoami}, {"event": "message", "node": "user"|"hub", "from": PK, "text": ..., "id": ...}
"""

import argparse
import asyncio
import json
import os
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))  # this checkout's lichen/, whatever the venv has installed

from lichen.identity import b64, canonical  # noqa: E402
from lichen.node import Node  # noqa: E402


def emit(**event) -> None:
    sys.stdout.write(json.dumps(event) + "\n")
    sys.stdout.flush()


def log(text: str) -> None:
    sys.stderr.write(text + "\n")
    sys.stderr.flush()


class Peer:
    def __init__(self, args):
        self.args = args
        self.dir = Path(args.dir)
        self.hub_addr = f"127.0.0.1:{args.port}"
        self.hub: Node | None = None
        self.user: Node | None = None

    def _on_message(self, who: str):
        def cb(rec, text, env):
            emit(event="message", node=who, **{"from": env["from"]}, text=text, id=env.get("id"))
        return cb

    async def start_hub(self) -> None:
        self.hub = Node.from_dir(self.dir / "hub", "hub", host="127.0.0.1", port=self.args.port, direct=True, stun=[],
                                 on_message=self._on_message("hub"), log=lambda s: log("[hub] " + s))
        await self.hub.start()

    async def start_user(self) -> None:
        if not self.args.user or self.user:
            return
        self.user = Node.from_dir(self.dir / "user", self.args.user, relay=self.hub_addr, bootstrap=[self.hub_addr],
                                  direct=False, stun=[], on_message=self._on_message("user"),
                                  log=lambda s: log("[user] " + s))
        await self.user.start()

    async def stop_user(self) -> None:
        if self.user:
            await self.user.stop()
            self.user = None

    def whoami(self) -> dict:
        return {"hub": {"pubkey": self.hub.ident.pubkey, "addr": self.hub_addr, "name": "hub"},
                "user": ({"pubkey": self.user.ident.pubkey, "name": self.user.name} if self.user else
                         ({"pubkey": self._user_pubkey(), "name": self.args.user, "offline": True} if self.args.user else None))}

    def _user_pubkey(self) -> str | None:
        from lichen.identity import Identity
        key = self.dir / "user" / "key"
        return Identity.load_or_create(key).pubkey if key.exists() else None

    def _node(self, who: str) -> Node | None:
        return self.hub if who == "hub" else self.user

    async def command(self, cmd: dict) -> bool:
        kind = cmd.get("cmd")
        if kind == "whoami":
            emit(event="whoami", **self.whoami())
        elif kind == "send":
            node = self._node(cmd.get("from", "user"))
            if node is None:
                emit(event="sent", result=None, error="that node is not running")
            else:
                emit(event="sent", result=await node.send(cmd["to"], cmd["text"]))
        elif kind == "stop_user":
            await self.stop_user()
            emit(event="ok", cmd=kind)
        elif kind == "start_user":
            await self.start_user()
            emit(event="ok", cmd=kind)
        elif kind == "state":
            u = self.user
            emit(event="state", ious=self.hub.hub.ious, hub_outbox=len(self.hub.outbox.items),
                 user_outbox=len(u.outbox.items) if u else None,
                 phonebook=[r.name for r in self.hub.phonebook.all()],
                 hub_attached={pk: list(d) for pk, d in self.hub.hub.attached.items()})
        elif kind == "dms":
            node = self._node(cmd.get("who", "user"))
            emit(event="dms", messages=node.dms.convos.get(cmd["peer"], []) if node else [])
        elif kind == "trust":
            self._node(cmd.get("who", "user")).trust.set(cmd["pk"], cmd["level"])
            emit(event="ok", cmd=kind)
        elif kind == "inject":
            node = self._node(cmd.get("who", "user"))
            inner = {"from": node.ident.pubkey, "to": cmd["peer"], "ts": time.time(), "id": b64(os.urandom(12)),
                     "text": cmd["text"]}
            inner["sig"] = node.ident.sign(canonical(inner))
            node.dms.add(cmd["peer"], inner)
            emit(event="ok", cmd=kind, id=inner["id"])
        elif kind == "forget":
            node = self._node(cmd.get("who", "user"))
            node.dms.convos.pop(cmd["peer"], None)
            node.dms._ids.pop(cmd["peer"], None)
            emit(event="ok", cmd=kind)
        elif kind == "sync":
            emit(event="synced", n=await self._node(cmd.get("who", "user")).sync_dm(cmd["peer"]))
        elif kind == "send_file":
            import random
            node = self._node(cmd.get("from", "user"))
            path = self.dir / "outfiles" / cmd["name"]
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(random.Random(cmd.get("seed", 0)).randbytes(int(cmd["size"])))
            to = [cmd["to"]] if isinstance(cmd["to"], str) else list(cmd["to"])
            file_id, entry, delivered = await node.send_file(path, to, cmd.get("group"))
            emit(event="file_sent", file_id=file_id, sha256=entry["sha256"], size=entry["size"], delivered=delivered)
        elif kind == "offers":
            node = self._node(cmd.get("who", "user"))
            emit(event="offers", incoming=node.files.incoming, outgoing=node.files.outgoing)
        elif kind == "accept_file":
            from lichen.files import sha256_file
            node = self._node(cmd.get("who", "user"))
            saved = await node.download(cmd["file_id"])
            emit(event="file_downloaded", saved=str(saved) if saved else None,
                 sha256=sha256_file(saved) if saved else None, error=node.files.incoming[cmd["file_id"]].get("error"))
        elif kind == "decline_file":
            self._node(cmd.get("who", "user")).decline_file(cmd["file_id"])
            emit(event="ok", cmd=kind)
        elif kind == "file_limits":
            node = self._node(cmd.get("who", "user"))
            node.max_file_mb = cmd.get("max_mb", node.max_file_mb)
            node.auto_download_mb = cmd.get("auto_mb", node.auto_download_mb)
            emit(event="ok", cmd=kind)
        elif kind == "quit":
            return False
        else:
            emit(event="error", error=f"unknown command {kind!r}")
        return True

    async def run(self) -> None:
        await self.start_hub()
        await self.start_user()
        emit(event="ready", **self.whoami())
        loop = asyncio.get_running_loop()
        while True:
            line = await loop.run_in_executor(None, sys.stdin.readline)
            if not line:
                break
            try:
                cmd = json.loads(line)
                if not await self.command(cmd):
                    break
            except Exception as e:  # keep serving; the test sees the error event
                emit(event="error", error=f"{type(e).__name__}: {e}")
        await self.stop_user()
        await self.hub.stop()


def main() -> None:
    p = argparse.ArgumentParser()
    p.add_argument("--dir", required=True, help="folder for the hub's and the user's data")
    p.add_argument("--port", type=int, default=9100)
    p.add_argument("--user", help="name of a relay-only Python user attached to the hub (omit for a hub alone)")
    asyncio.run(Peer(p.parse_args()).run())


if __name__ == "__main__":
    main()
