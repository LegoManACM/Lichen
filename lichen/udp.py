"""UDP transport with hole punching, so peers behind NAT can talk directly.

How a direct path gets made (the hub is just an introducer):
  1. Every relay-using node learns its public IP:port (what its NAT maps this socket to) by pinging
     its hub over UDP, whose pong reports the address it saw, or by asking a public STUN server when
     the hub has no UDP (e.g. it sits behind Cloudflare Tunnel). Repeating this keeps the mapping open.
  2. To reach someone, you send them a "punch" request through their relay carrying your public and
     local addresses; their reply carries theirs (see node.py).
  3. Both sides ping every candidate address at once. Each NAT sees outgoing traffic to the other side
     and lets the replies in. The first signed pong from the right key wins: that's the direct path.
     Each side only trusts its own round trip, never "a ping arrived from there".
  4. Paths are kept alive with pings and dropped when they stop answering; callers fall back to the
     relay whenever a direct request fails.

Requests and replies are JSON, split into datagram-sized chunks. Reliability is simple: the requester
retransmits (asking only for reply chunks it's missing) until the reply is complete; the responder
caches replies so a retransmitted request is never handled twice.
"""

import asyncio
import json
import os
import random
import socket
import struct
import time
from typing import Awaitable, Callable

from .identity import Identity, b64, canonical, verify

CHUNK = 500  # characters of JSON per datagram; stays well under typical MTUs after escaping
REQUEST_TIMEOUT = 0.5  # per attempt
REQUEST_TRIES = 4
REPLY_CACHE_TTL = 30.0
PING_MAX_AGE = 120.0
PATH_TIMEOUT = 45.0  # drop a direct path that hasn't answered a ping for this long
PUNCH_DURATION = 3.0
PUNCH_INTERVAL = 0.1
STUN_COOKIE = 0x2112A442
STUN_COOKIE_BYTES = STUN_COOKIE.to_bytes(4, "big")
DEFAULT_STUN = ["stun.l.google.com:19302", "stun.cloudflare.com:3478"]

Addr = tuple[str, int]
Handler = Callable[[dict], Awaitable[dict]]


def local_ips() -> list[str]:
    """Addresses a peer on the same machine or LAN could reach us at."""
    ips = {"127.0.0.1"}
    try:
        ips.update(socket.gethostbyname_ex(socket.gethostname())[2])
    except OSError:
        pass
    return sorted(ips)


class UdpEndpoint:
    """Runs on a plain non-blocking socket rather than asyncio's datagram transport: hole punching
    deliberately sends to addresses that may be unreachable, and on Windows the resulting send/ICMP
    errors can leave asyncio's transport unable to answer. Here every send and receive error is
    contained to that one packet."""

    def __init__(self, ident: Identity, handler: Handler, log: Callable[[str], None] = lambda s: None):
        self.ident = ident
        self.handler = handler
        self.log = log
        self.sock: socket.socket | None = None
        self._reader: asyncio.Task | None = None
        self.public: Addr | None = None  # our address as the internet sees it (hub pong or STUN)
        self.paths: dict[str, list] = {}  # pubkey -> [addr, last time it answered]
        self._next_id = random.randrange(1 << 30)
        self._pending: dict[tuple[Addr, int], dict] = {}
        self._inbound: dict[tuple[Addr, int], dict] = {}
        self._replies: dict[tuple[Addr, int], tuple[list[bytes], float]] = {}
        self._pongs: dict[str, asyncio.Future] = {}
        self._stun: dict[bytes, asyncio.Future] = {}
        self._tasks: set[asyncio.Task] = set()

    # --- setup -----------------------------------------------------------------------------------

    async def open(self, host: str, port: int) -> None:
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        if hasattr(socket, "SIO_UDP_CONNRESET"):  # Windows: don't turn ICMP "port unreachable" into recv errors
            sock.ioctl(socket.SIO_UDP_CONNRESET, False)
        sock.setblocking(False)
        sock.bind((host, port))
        self.sock = sock
        self._reader = asyncio.create_task(self._read_loop())

    async def _read_loop(self) -> None:
        loop = asyncio.get_running_loop()
        while True:
            try:
                data, addr = await loop.sock_recvfrom(self.sock, 65535)
            except asyncio.CancelledError:
                raise
            except OSError:
                if self.sock is None or self.sock.fileno() == -1:
                    return
                continue  # one bad packet / ICMP error; keep listening
            if addr and data:  # Windows can report an errored receive as (b"", None)
                self.datagram_received(data, addr)

    @property
    def port(self) -> int:
        return self.sock.getsockname()[1]

    def local_addrs(self) -> list[Addr]:
        return [(ip, self.port) for ip in local_ips()]

    def close(self) -> None:
        if self._reader:
            self._reader.cancel()
        if self.sock:
            self.sock.close()
            self.sock = None
        for t in list(self._tasks):
            t.cancel()

    def _send_raw(self, data: bytes, addr: Addr) -> None:
        if self.sock is None:
            return
        try:
            self.sock.sendto(data, addr)
        except OSError:
            pass  # unreachable candidate, full buffer, ...: UDP is best-effort, retries cover it

    def _send(self, packet: dict, addr: Addr) -> None:
        self._send_raw(json.dumps(packet, separators=(",", ":")).encode(), addr)

    # --- receiving -------------------------------------------------------------------------------

    def datagram_received(self, data: bytes, addr) -> None:
        addr = (addr[0], addr[1])
        if len(data) >= 20 and data[4:8] == STUN_COOKIE_BYTES:
            self._on_stun(data)
            return
        try:
            p = json.loads(data)
            kind = p["t"]
        except (ValueError, KeyError, TypeError):
            return
        if kind == "ping":
            self._on_ping(p, addr)
        elif kind == "pong":
            self._on_pong(p, addr)
        elif kind == "req":
            self._on_req(p, addr)
        elif kind == "rep":
            self._on_rep(p, addr)

    def _on_ping(self, p: dict, addr: Addr) -> None:
        body = {k: p.get(k) for k in ("t", "from", "ts", "nonce", "local")}
        try:
            fresh = abs(time.time() - float(p["ts"])) <= PING_MAX_AGE
            ok = fresh and verify(str(p["from"]), canonical(body), str(p["sig"]))
        except (KeyError, TypeError, ValueError, IndexError):
            return
        if not ok:
            return
        # Just answer. An arriving ping is not a path: it only proves traffic flows toward us. Paths
        # are only made from a round trip (our ping, their signed pong), which each side does itself.
        nonce = str(p.get("nonce"))
        self._send({"t": "pong", "nonce": nonce, "you": list(addr), "from": self.ident.pubkey,
                    "sig": self.ident.sign(nonce.encode())}, addr)

    def _on_pong(self, p: dict, addr: Addr) -> None:
        fut = self._pongs.get(str(p.get("nonce")))
        if fut and not fut.done():
            fut.set_result({**p, "_addr": addr})

    def _on_req(self, p: dict, addr: Addr) -> None:
        try:
            rid, i, n, chunk = int(p["id"]), int(p["i"]), int(p["n"]), str(p["d"])
        except (KeyError, TypeError, ValueError):
            return
        key = (addr, rid)
        cached = self._replies.get(key)
        if cached:  # a retransmit: resend what they're missing, never handle twice
            missing = p.get("miss")
            for j, packet in enumerate(cached[0]):
                if missing is None or j in missing:
                    self._send_raw(packet, addr)
            return
        entry = self._inbound.setdefault(key, {"parts": {}, "n": n, "t": time.time(), "busy": False})
        entry["parts"][i] = chunk
        if len(entry["parts"]) == entry["n"] and not entry["busy"]:
            entry["busy"] = True
            self._spawn(self._answer(key, "".join(entry["parts"][j] for j in range(entry["n"]))))

    async def _answer(self, key: tuple[Addr, int], data: str) -> None:
        addr, rid = key
        try:
            msg = json.loads(data)
            if not isinstance(msg, dict):
                raise ValueError
            # Tell the handler who's asking (used by the hub for introductions); never trust a client's copy.
            msg["_udp_src"] = list(addr)
            reply = await self.handler(msg)
        except ValueError:
            reply = {"ok": False, "error": "bad request"}
        text = json.dumps(reply)
        chunks = [text[i:i + CHUNK] for i in range(0, len(text), CHUNK)] or [""]
        packets = [json.dumps({"t": "rep", "id": rid, "i": j, "n": len(chunks), "d": c}).encode()
                   for j, c in enumerate(chunks)]
        self._replies[key] = (packets, time.time())
        self._inbound.pop(key, None)
        for packet in packets:
            self._send_raw(packet, addr)
        self._gc()

    def _on_rep(self, p: dict, addr: Addr) -> None:
        try:
            pending = self._pending.get((addr, int(p["id"])))
            i, n, chunk = int(p["i"]), int(p["n"]), str(p["d"])
        except (KeyError, TypeError, ValueError):
            return
        if not pending:
            return
        pending["n"] = n
        pending["parts"][i] = chunk
        if len(pending["parts"]) == n and not pending["fut"].done():
            pending["fut"].set_result("".join(pending["parts"][j] for j in range(n)))

    def _gc(self) -> None:
        now = time.time()
        for store in (self._replies, self._inbound):
            for key in [k for k, v in store.items() if now - (v[1] if isinstance(v, tuple) else v["t"]) > REPLY_CACHE_TTL]:
                del store[key]

    # --- sending ---------------------------------------------------------------------------------

    async def request(self, addr: Addr, msg: dict, tries: int = REQUEST_TRIES) -> dict | None:
        msg = {k: v for k, v in msg.items() if k != "_udp_src"}
        self._next_id += 1
        rid = self._next_id
        text = json.dumps(msg)
        chunks = [text[i:i + CHUNK] for i in range(0, len(text), CHUNK)] or [""]
        fut = asyncio.get_running_loop().create_future()
        pending = {"parts": {}, "n": None, "fut": fut}
        self._pending[(addr, rid)] = pending
        try:
            for attempt in range(tries):
                missing = None
                if pending["n"] is not None:
                    missing = [j for j in range(pending["n"]) if j not in pending["parts"]]
                for i, c in enumerate(chunks):
                    packet = {"t": "req", "id": rid, "i": i, "n": len(chunks), "d": c}
                    if missing is not None:
                        packet["miss"] = missing
                    self._send(packet, addr)
                try:
                    reply = json.loads(await asyncio.wait_for(asyncio.shield(fut), REQUEST_TIMEOUT))
                    return reply if isinstance(reply, dict) else None
                except asyncio.TimeoutError:
                    continue
                except ValueError:
                    return None
            return None
        finally:
            self._pending.pop((addr, rid), None)

    # --- STUN (RFC 5389 binding request: "what address do my packets come from?") ----------------

    async def stun(self, server: Addr, timeout: float = 2.0) -> Addr | None:
        txid = os.urandom(12)
        fut = asyncio.get_running_loop().create_future()
        self._stun[txid] = fut
        packet = struct.pack("!HHI", 0x0001, 0, STUN_COOKIE) + txid
        try:
            for _ in range(3):
                self._send_raw(packet, server)
                try:
                    return await asyncio.wait_for(asyncio.shield(fut), timeout / 3)
                except asyncio.TimeoutError:
                    continue
            return None
        finally:
            self._stun.pop(txid, None)

    def _on_stun(self, data: bytes) -> None:
        msg_type, length = struct.unpack("!HH", data[:4])
        fut = self._stun.get(data[8:20])
        if msg_type != 0x0101 or not fut or fut.done():
            return
        pos, end = 20, min(len(data), 20 + length)
        while pos + 4 <= end:
            attr, alen = struct.unpack("!HH", data[pos:pos + 4])
            value = data[pos + 4:pos + 4 + alen]
            pos += 4 + alen + (-alen % 4)
            if attr in (0x0020, 0x0001) and len(value) >= 8 and value[1] == 0x01:  # (XOR-)MAPPED-ADDRESS, IPv4
                port = struct.unpack("!H", value[2:4])[0]
                ip = value[4:8]
                if attr == 0x0020:
                    port ^= STUN_COOKIE >> 16
                    ip = bytes(a ^ b for a, b in zip(ip, STUN_COOKIE_BYTES))
                fut.set_result((socket.inet_ntoa(ip), port))
                return

    def _ping_packet(self, nonce: str) -> dict:
        body = {"t": "ping", "from": self.ident.pubkey, "ts": time.time(), "nonce": nonce,
                "local": [list(a) for a in self.local_addrs()]}
        return {**body, "sig": self.ident.sign(canonical(body))}

    async def ping(self, addr: Addr, timeout: float = 2.0) -> dict | None:
        """Signed ping; returns the pong (with who answered and how they saw us) or None."""
        nonce = b64(os.urandom(9))
        fut = asyncio.get_running_loop().create_future()
        self._pongs[nonce] = fut
        try:
            packet = self._ping_packet(nonce)
            for _ in range(3):
                self._send(packet, addr)
                try:
                    return await asyncio.wait_for(asyncio.shield(fut), timeout / 3)
                except asyncio.TimeoutError:
                    continue
            return None
        finally:
            self._pongs.pop(nonce, None)

    async def punch(self, pubkey: str, candidates: list[Addr]) -> Addr | None:
        """Ping every candidate repeatedly until one answers as pubkey. Returns the working address."""
        nonce = b64(os.urandom(9))
        fut = asyncio.get_running_loop().create_future()
        self._pongs[nonce] = fut
        deadline = time.time() + PUNCH_DURATION
        try:
            while time.time() < deadline:
                packet = self._ping_packet(nonce)
                for addr in candidates:
                    self._send(packet, addr)
                try:
                    pong = await asyncio.wait_for(asyncio.shield(fut), PUNCH_INTERVAL)
                except asyncio.TimeoutError:
                    continue
                if pong.get("from") == pubkey and verify(pubkey, nonce.encode(), str(pong.get("sig"))):
                    self.paths[pubkey] = [pong["_addr"], time.time()]
                    return pong["_addr"]
                fut = asyncio.get_running_loop().create_future()  # wrong responder; keep listening
                self._pongs[nonce] = fut
            return None
        finally:
            self._pongs.pop(nonce, None)

    async def keepalive(self, pubkey: str) -> bool:
        """Ping an existing direct path; drop it if it's gone quiet."""
        path = self.paths.get(pubkey)
        if not path:
            return False
        pong = await self.ping(path[0])
        if pong and pong.get("from") == pubkey:
            path[1] = time.time()
            return True
        if time.time() - path[1] > PATH_TIMEOUT:
            self.paths.pop(pubkey, None)
        return False

    def path(self, pubkey: str) -> Addr | None:
        p = self.paths.get(pubkey)
        return p[0] if p and time.time() - p[1] < PATH_TIMEOUT else None

    def drop_path(self, pubkey: str) -> None:
        self.paths.pop(pubkey, None)

    def _spawn(self, coro) -> None:
        task = asyncio.create_task(coro)
        self._tasks.add(task)
        task.add_done_callback(self._tasks.discard)
