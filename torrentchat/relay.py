"""Relaying for nodes that can't accept incoming connections (home NAT, mobile data, firewalls).

A node behind NAT opens one outgoing connection to a relay and *attaches* (proving it holds its
key). It then advertises the address "relay:<relay address>" (e.g. relay:1.2.3.4:9000 or
relay:wss://chat.example.com); a node may attach to several hubs at once. Anyone wanting to reach it
sends {"type": "forward", "to": pubkey, "msg": ...} to the relay, which pushes the request down the
attached connection and returns the node's reply, so every request type works through a relay.

Offline recipients: IOUs, not mailboxes. If the recipient isn't attached, a hub stores no message,
not even ciphertext. For things worth delivering later (direct messages, file offers, group invites,
leave requests) it records an IOU: "sender owes recipient item <id>", with a timestamp, and replies
{"queued": true, "iou": true}. The sender keeps the sealed item in its own outbox (outbox.py). When
the recipient attaches, the hub tells it who owes it what and nudges each sender to deliver. Item ids
let the recipient recognise the same IOU held by several hubs, and its stores drop duplicates.

Attached-connection messages (JSON lines or WebSocket text messages, see wire.py):
  node  -> relay  {"type": "attach", "req": {"pubkey", "ts"}, "sig"}    then  {"ok": true}
  relay -> node   {"rid": n, "msg": {...}}
  node  -> relay  {"rid": n, "reply": {...}}
  node  -> relay  {"keepalive": ts}   every 25 s, so proxies don't close an idle connection
"""

import asyncio
import json
import time
from pathlib import Path
from typing import Awaitable, Callable

from .identity import Identity, canonical, verify
from .wire import Conn, connect

IOU_TYPES = {"msg", "group_update", "group_leave", "file_offer"}  # group messages catch up via sync
IOU_MAX_AGE = 30 * 24 * 3600
IOU_LIMIT = 200  # item ids remembered per (sender, recipient) pair
FORWARD_TIMEOUT = 10.0
ATTACH_MAX_AGE = 120  # seconds an attach signature stays valid
KEEPALIVE = 25.0  # Cloudflare and most proxies drop WebSockets idle for ~100 s

Nudge = Callable[[str, str], Awaitable[None]]  # (sender, recipient): "recipient is back, deliver"


class _Attached:
    def __init__(self, conn: Conn):
        self.conn = conn
        self.pending: dict[int, asyncio.Future] = {}
        self.next_rid = 0


class RelayHub:
    """Server side: runs inside every node, but is only useful on one that's publicly reachable."""

    def __init__(self, path: Path | None = None, log: Callable[[str], None] = lambda s: None):
        self.path = path
        self.log = log
        self.attached: dict[str, _Attached] = {}
        # recipient -> sender -> {"ts": last update, "items": [item ids]}
        self.ious: dict[str, dict[str, dict]] = {}
        if path and path.exists():
            data = json.loads(path.read_text(encoding="utf-8"))
            self.ious = data.get("ious", {}) if isinstance(data, dict) and "ious" in data else {}
        self.nudge: Nudge | None = None  # set by the node: how to reach a sender
        self.forwarded = 0  # requests relayed live (for stats / tests)
        self._tasks: set[asyncio.Task] = set()
        self._expire()

    async def handle_attach(self, msg: dict, conn: Conn) -> None:
        req, sig = msg.get("req") or {}, str(msg.get("sig"))
        pubkey = str(req.get("pubkey"))
        try:
            fresh = abs(time.time() - float(req.get("ts"))) <= ATTACH_MAX_AGE
        except (TypeError, ValueError):
            fresh = False
        if not fresh or not verify(pubkey, canonical(req), sig):
            await conn.send({"ok": False, "error": "bad attach"})
            return

        att = _Attached(conn)
        old = self.attached.get(pubkey)
        if old:
            old.conn.close()
        self.attached[pubkey] = att
        await conn.send({"ok": True})
        self.log(f"relay: {pubkey[:8]} attached")
        self._spawn(self._redeem_ious(pubkey))
        try:
            while (data := await conn.recv()) is not None:
                try:
                    fut = att.pending.pop(int(data["rid"]), None)
                except (KeyError, TypeError, ValueError):
                    continue  # keepalive or junk
                if fut and not fut.done():
                    fut.set_result(data.get("reply") if isinstance(data.get("reply"), dict) else None)
        except (OSError, ConnectionError, asyncio.IncompleteReadError, ValueError):
            pass
        finally:
            if self.attached.get(pubkey) is att:
                del self.attached[pubkey]
                self.log(f"relay: {pubkey[:8]} detached")
            for fut in att.pending.values():
                if not fut.done():
                    fut.set_result(None)

    def close(self) -> None:
        """Drop every attached connection (they reconnect elsewhere or later)."""
        for att in list(self.attached.values()):
            att.conn.close()
        for task in list(self._tasks):
            task.cancel()

    async def forward(self, to: str, msg: dict, sender: str | None = None, item: str | None = None) -> dict:
        att = self.attached.get(to)
        if att:
            reply = await self._push(att, msg)
            if reply is not None:
                self.forwarded += 1
                return reply
        if msg.get("type") in IOU_TYPES and sender and item:
            owed = self.ious.setdefault(to, {}).setdefault(sender, {"ts": 0, "items": []})
            if item not in owed["items"]:
                owed["items"] = (owed["items"] + [item])[-IOU_LIMIT:]
            owed["ts"] = time.time()
            self._save()
            return {"ok": True, "queued": True, "iou": True}
        return {"ok": False, "error": "unreachable", "unreachable": True}

    async def _push(self, att: _Attached, msg: dict) -> dict | None:
        rid = att.next_rid = att.next_rid + 1
        fut = asyncio.get_running_loop().create_future()
        att.pending[rid] = fut
        try:
            await att.conn.send({"rid": rid, "msg": msg})
            return await asyncio.wait_for(fut, FORWARD_TIMEOUT)
        except (OSError, ConnectionError, asyncio.TimeoutError):
            att.pending.pop(rid, None)
            return None

    async def _redeem_ious(self, pubkey: str) -> None:
        """Someone came back: tell them who owes them what, and nudge those senders to deliver."""
        owed = self.ious.pop(pubkey, {})
        if not owed:
            return
        self._save()
        self.log(f"relay: {pubkey[:8]} is back; nudging {len(owed)} sender(s)")
        att = self.attached.get(pubkey)
        if att:
            await self._push(att, {"type": "owed", "from": {s: o["items"] for s, o in owed.items()}})
        if self.nudge:
            await asyncio.gather(*(self.nudge(sender, pubkey) for sender in owed), return_exceptions=True)

    def owed_to(self, pubkey: str) -> dict[str, list[str]]:
        return {s: o["items"] for s, o in self.ious.get(pubkey, {}).items()}

    def _expire(self) -> None:
        cutoff = time.time() - IOU_MAX_AGE
        for to in list(self.ious):
            self.ious[to] = {s: o for s, o in self.ious[to].items() if o["ts"] >= cutoff}
            if not self.ious[to]:
                del self.ious[to]

    def _save(self) -> None:
        if self.path:
            self.path.parent.mkdir(parents=True, exist_ok=True)
            self.path.write_text(json.dumps({"ious": self.ious}), encoding="utf-8")

    def _spawn(self, coro) -> None:
        task = asyncio.create_task(coro)
        self._tasks.add(task)
        task.add_done_callback(self._tasks.discard)


class RelayClient:
    """Node side: keep an attached connection to a relay open, reconnecting when it drops."""

    def __init__(self, relay: str, ident: Identity, handler: Callable[[dict], Awaitable[dict]],
                 log: Callable[[str], None] = lambda s: None):
        self.relay = relay
        self.ident = ident
        self.handler = handler
        self.log = log
        self.connected = asyncio.Event()
        self._task: asyncio.Task | None = None
        self._conn: Conn | None = None
        self._handlers: set[asyncio.Task] = set()

    def start(self) -> None:
        self._task = asyncio.create_task(self._run())

    async def stop(self) -> None:
        if self._task:
            self._task.cancel()
        if self._conn:
            self._conn.close()
        self.connected.clear()

    async def _run(self) -> None:
        delay = 1.0
        while True:
            try:
                await self._session()
                delay = 1.0
            except asyncio.CancelledError:
                raise
            except Exception as e:  # network errors, TLS errors, handshake refusals: retry
                self.log(f"relay {self.relay}: {e or type(e).__name__}")
            self.connected.clear()
            await asyncio.sleep(delay)
            delay = min(delay * 2, 30.0)

    async def _session(self) -> None:
        conn = await connect(self.relay)
        self._conn = conn
        keepalive = None
        try:
            req = {"pubkey": self.ident.pubkey, "ts": time.time()}
            await conn.send({"type": "attach", "req": req, "sig": self.ident.sign(canonical(req))})
            ack = await asyncio.wait_for(conn.recv(), 10) or {}
            if not ack.get("ok"):
                raise ConnectionError(f"relay refused attach: {ack.get('error')}")
            self.connected.set()
            self.log(f"attached to relay {self.relay}")
            keepalive = asyncio.create_task(self._keepalive(conn))
            while (data := await conn.recv()) is not None:
                task = asyncio.create_task(self._answer(conn, data.get("rid"), data.get("msg")))
                self._handlers.add(task)
                task.add_done_callback(self._handlers.discard)
            self.log(f"relay {self.relay} closed the connection")
        finally:
            if keepalive:
                keepalive.cancel()
            conn.close()

    async def _keepalive(self, conn: Conn) -> None:
        while True:
            await asyncio.sleep(KEEPALIVE)
            try:
                await conn.send({"keepalive": time.time()})
            except (OSError, ConnectionError):
                return

    async def _answer(self, conn: Conn, rid, msg) -> None:
        reply = await self.handler(msg) if isinstance(msg, dict) else {"ok": False, "error": "bad request"}
        try:
            await conn.send({"rid": rid, "reply": reply})
        except (OSError, ConnectionError):
            pass
