"""A Lichen node. Every node is also a phonebook and a relay; a "phonebook server" is just an
always-on node at a public address (HOST:PORT, or a ws(s):// URL behind Cloudflare Tunnel etc.).

Protocol (one JSON request per connection, over TCP or WebSocket; see wire.py):
  hello    {record}           -> {record, records}  introduce yourself, receive their whole phonebook
  announce {record, from}     -> {ok}               gossip: "this key moved"; forwarded if it was news
  lookup   {pubkey}           -> {record | null}    "where does this key live now?"
  msg      {to, sealed}       -> {ok}               a direct message, encrypted to the recipient
  forward  {to, msg}          -> reply of `to`      relay msg to a node attached to us (relay.py)
  attach   (long-lived)                             a node behind NAT attaching to us (relay.py)

  punch    {peer, addrs}      -> {addrs}            "let's connect directly: ping these, give me yours"
                                                    (sent through the relay, so the hub needs no UDP)
Any request can also travel over a punched UDP path (udp.py) instead of TCP or the relay.

Groups (see groups.py):
  group_msg    {envelope, group}  -> {ok}               encrypted group message, sent to each member
  group_update {group}            -> {ok}               owner published a new member list / key epoch
  group_sync   {req, sig}         -> {group, messages}  "I have these ids, send what I'm missing"
  group_leave  {req, sig}         -> {ok}               member -> owner: "remove me" (leave / decline)
  dm_sync      {req, sig}         -> {sealed}           "I have these message ids, send what I'm missing"
Being added to a group is an *invite*: nothing shows until you accept it.

Files (see files.py):
  file_offer   {to, sealed}       -> {ok}               "want this file?" (name, size, sha256, key)
  file_get     {file_id, offset}  -> {n, ct}            one encrypted chunk, pulled by the recipient

Offline recipients: hubs keep IOUs, not messages (relay.py). Undelivered items stay sealed in our
outbox (outbox.py) and go out when the recipient is back:
  nudge        {peer}             -> {ok}               hub -> sender: "peer is back, deliver"
  owed         {from: {sender: [item ids]}}             hub -> recipient: who has something for you
Every direct message has an id, so retries, IOUs held by several hubs and chat syncs never duplicate.

Your own devices (see devices.py, and "syncing your own devices" below):
  device_sync  {to, sealed}       -> {sealed}           compare notes with another device of yours: a probe
                                                        (digest only), then the full state if they differ
  vault_put    {req, sig, blob}   -> {ok}               leave an encrypted snapshot of your state on a hub
  vault_get    {req, sig}         -> {snaps}            collect what your other devices left there (vault.py)

Trust (see trust.py): blocked keys are ignored entirely; offers and invites from anyone wait for you
to accept them; small files from trusted contacts can optionally download by themselves.
"""

import asyncio
import hashlib
import json
import os
import socket
import time
import zlib
from pathlib import Path
from typing import Callable, NamedTuple

from .crypto import EncKey, open_sealed, seal
from .devices import device_id, is_linked, mark_linked
from .dms import DmStore, legacy_id
from .files import DEFAULT_MAX_MB, FileStore, human_size
from .groups import HISTORY_MODES, GroupRecord, GroupStore, transfer_body
from .identity import Identity, b64, canonical, short, unb64, verify
from .phonebook import Phonebook
from .outbox import Outbox
from .records import Record
from .relay import RelayClient, RelayHub
from .trust import TrustStore
from .udp import DEFAULT_STUN, Addr, UdpEndpoint
from .vault import DEFAULT_MB as VAULT_DEFAULT_MB, Vault
from .wire import is_url, request, serve, split_addr

OnMessage = Callable[[Record | None, str, dict], None]
# (group, author record or None, text, decrypted message, live); live=False for catch-up
OnGroupMessage = Callable[[GroupRecord, Record | None, str, dict, bool], None]
# (kind, details): group_invite, file_offer, file_progress, file_done, file_failed
OnEvent = Callable[[str, dict], None]

SYNC_MAX_AGE = 300  # seconds a signed sync request stays valid
LEAVE_MAX_AGE = 7 * 24 * 3600  # a leave request may wait in our outbox while the owner is away
FILE_RETRIES = 4  # attempts per chunk before a download is marked failed (it can be resumed)
OUTBOX_EVERY = 4  # keepalive ticks between outbox retries (4 x 15 s = once a minute)
PRESENCE_EVERY = 2  # keepalive ticks between "still here" signals to contacts (2 x 15 s)
ONLINE_WINDOW = 75.0  # seen this recently = online
TYPING_EVERY = 3.0  # at most one typing signal per contact per this many seconds
TYPING_SHOWN = 6.0  # how long "typing…" stays up after a signal
RELAY_PREFIX = "relay:"
KEEPALIVE = 15.0  # seconds between public-address checks and pings to direct peers (keeps NAT mappings open)
PUNCH_RETRY = 60.0  # after a failed punch, use the relay for this long before trying again
SYNC_BUDGET = 2_300_000  # bytes of compressed state per device-sync message (the wire allows 4 MiB lines)
SYNC_TIMEOUT = 30.0  # a device sync can carry a lot of chat history
STATE_MAX = 256 * 1024 * 1024  # most a snapshot may inflate to
VAULT_DELAY = 5.0  # after a change, wait this long (batching) before leaving a snapshot on our hubs
VAULT_MIN_GAP = 15.0  # at most one snapshot upload per hub this often
KEEP_STEPS = (None, 2000, 500, 200, 100, 50)  # messages kept per chat when the whole history is too big


def _clean(msg: dict) -> dict:
    """_udp_src is set by the UDP layer from the packet's real source; never accept it from a peer."""
    return {k: v for k, v in msg.items() if k != "_udp_src"}


class Packed(NamedTuple):
    z: bytes  # compressed state
    digest: str  # hash of the state that matters for deciding two devices are in step (not the phonebook)


class Node:
    def __init__(
        self,
        name: str,
        ident: Identity,
        enc: EncKey,
        phonebook: Phonebook,
        groups: GroupStore,
        host: str = "127.0.0.1",
        port: int = 9000,
        bind: str | None = None,
        bootstrap: list[str] | None = None,
        relay: str | list[str] | None = None,
        direct: bool = True,
        url: str | None = None,
        stun: list[str] | None = None,
        udp_bind: str | None = None,
        hub: RelayHub | None = None,
        vault: Vault | None = None,
        data_dir: Path | None = None,
        dms: DmStore | None = None,
        outbox: Outbox | None = None,
        device: str = "default",
        trust: TrustStore | None = None,
        files: FileStore | None = None,
        max_file_mb: float = DEFAULT_MAX_MB,
        auto_download_mb: float = 0,
        on_message: OnMessage | None = None,
        on_group_message: OnGroupMessage | None = None,
        on_event: OnEvent | None = None,
        log: Callable[[str], None] = lambda s: None,
    ):
        self.name = name
        self.ident = ident
        self.enc = enc
        self.phonebook = phonebook
        self.groups = groups
        self.host = host
        self.port = port
        self.bind = bind or host
        self.bootstrap = list(bootstrap or [])
        # Hubs to attach to (receive through) if we can't accept connections ourselves; several = redundancy.
        self.relays = [relay] if isinstance(relay, str) else list(relay or [])
        self.direct = direct  # advertise our own address as reachable
        self.url = url  # public address to advertise instead of HOST:PORT (e.g. wss://chat.example.com)
        self.stun = DEFAULT_STUN if stun is None else list(stun)  # [] disables STUN
        self.hub = hub or RelayHub(log=log)
        self.dms = dms or DmStore()
        self.outbox = outbox or Outbox()
        self.device = device  # this device's id; several devices can share one identity (devices.py)
        self.device_addrs: list[str] = []  # addresses our other devices advertise, adopted into our record
        self.hub.nudge = self._nudge
        self.vault = vault or Vault(None, VAULT_DEFAULT_MB if direct else 0)
        self.data_dir = data_dir
        self.linked = bool(data_dir and is_linked(data_dir))  # this identity is on more than one device
        self._merging = False  # a device-sync merge is in progress: its changes aren't news to our other devices
        self._vault_seen = self._load_sync_state()  # device -> newest snapshot of theirs we've merged
        self._vault_pushed: dict[str, str] = {}  # hub -> digest of what we last left there
        self._vault_at: dict[str, float] = {}
        self._vault_pending = False
        self._vault_dirty = False  # something changed since the last snapshot we left
        self._rev = 0
        self.trust = trust or TrustStore()
        self.files = files or FileStore(None)
        self.max_file_mb = max_file_mb  # refuse offers bigger than this
        self.auto_download_mb = auto_download_mb  # auto-accept files up to this size from trusted contacts
        self.on_message = on_message
        self.on_group_message = on_group_message
        self.on_event = on_event
        self.log = log
        self._downloads: set[str] = set()  # file ids currently downloading
        self.server: asyncio.Server | None = None
        self.relay_clients: list[RelayClient] = []
        # Direct nodes use UDP on their TCP port (so a hub forwards one port number for both);
        # relay-only nodes take any free port on every interface.
        self.udp = UdpEndpoint(ident, self.handle, log)
        self.udp_bind = udp_bind or (self.bind if direct else "0.0.0.0")
        self.me: Record | None = None
        self._tasks: set[asyncio.Task] = set()
        self._keepalive_task: asyncio.Task | None = None
        self._punching: set[str] = set()
        self._punch_failed: dict[str, float] = {}
        self._flushing: set[str] = set()  # peers whose outbox is being delivered right now
        self._owed_seen: dict[str, set[str]] = {}  # IOU item ids already reported, per sender
        self.last_seen: dict[str, float] = {}  # pubkey -> last time we heard from / reached them
        self.typing: dict[str, float] = {}  # pubkey -> show "typing…" until this time
        self._typing_sent: dict[str, float] = {}
        self._read_sent: dict[str, float] = {}
        self._devsync_pending = False
        self._device_round_busy = False
        self.groups.on_change = self.trust.on_change = self._state_changed
        self.dms.on_change = self.outbox.on_change = self.files.on_change = self._state_changed

    @classmethod
    def from_dir(cls, data_dir: Path, name: str, downloads: Path | None = None, passphrase: str | None = None,
                 **kw) -> "Node":
        ident = Identity.load_or_create(data_dir / "key", passphrase)
        enc = EncKey.load_or_create(data_dir / "enckey", passphrase)
        hub = RelayHub(data_dir / "mailbox.json", log=kw.get("log", lambda s: None))
        vault_mb = kw.pop("vault_mb", None)  # a node that accepts connections holds snapshots unless told not to
        if vault_mb is None:
            vault_mb = VAULT_DEFAULT_MB if kw.get("direct", True) else 0
        return cls(name, ident, enc, Phonebook(data_dir / "phonebook.json"),
                   GroupStore(ident, enc, data_dir / "groups.json"), hub=hub,
                   vault=Vault(data_dir / "vault.json", vault_mb), data_dir=data_dir,
                   dms=DmStore(data_dir / "dms.json"), outbox=Outbox(data_dir / "outbox.json"),
                   device=device_id(data_dir),
                   trust=TrustStore(data_dir / "trust.json"),
                   files=FileStore(data_dir, downloads), **kw)

    def _emit(self, kind: str, **details) -> None:
        if self.on_event:
            self.on_event(kind, details)

    @property
    def addr(self) -> str:
        return self.url or f"{self.host}:{self.port}"

    @property
    def addrs(self) -> list[str]:
        own = ([self.addr] if self.direct else []) + [RELAY_PREFIX + r for r in self.relays]
        return own + [a for a in self.device_addrs if a not in own]

    @property
    def relay(self) -> str | None:
        return self.relays[0] if self.relays else None

    @property
    def relay_client(self) -> RelayClient | None:
        return self.relay_clients[0] if self.relay_clients else None

    def relay_status(self) -> str:
        """e.g. "2/2 hubs connected" ("" if we don't use relays)."""
        if not self.relay_clients:
            return ""
        up = sum(c.connected.is_set() for c in self.relay_clients)
        return f"{up}/{len(self.relay_clients)} hub{'s' if len(self.relay_clients) > 1 else ''} connected"

    # --- lifecycle -------------------------------------------------------------------------------

    async def start(self) -> None:
        await self._listen()
        await self.udp.open(self.udp_bind, self.port if self.direct else 0)
        self._refresh_record()
        for relay in self.relays:
            client = RelayClient(relay, self.ident, lambda m: self.handle(_clean(m)), log=self.log, device=self.device)
            client.start()
            self.relay_clients.append(client)

        async def attached(client: RelayClient) -> None:
            try:
                await asyncio.wait_for(client.connected.wait(), 5)
            except asyncio.TimeoutError:
                self.log(f"relay {client.relay} not reachable yet; will keep retrying")

        await asyncio.gather(*(attached(c) for c in self.relay_clients))
        await self._discover_public()
        self._keepalive_task = asyncio.create_task(self._keepalive_loop())
        for addr in self.bootstrap:
            await self.join(addr)
        # Our address may differ from last run; tell everyone we already know about.
        await self.announce()
        # Catch up: first with our own other devices (live, then what they left on our hubs), then group
        # messages, gaps in chats, and anything we owe people who are online now.
        await self.sync_devices()
        await self.sync_vault()
        await self.sync_all()
        await self.sync_dms()
        await self.flush_outbox()

    async def stop(self) -> None:
        if self._vault_dirty and self.linked:  # don't take unsent news to the grave: leave it for our other devices
            await self._vault_push(self._sync_hubs(), force=True)
        if self._keepalive_task:
            self._keepalive_task.cancel()
        self.udp.close()
        for client in self.relay_clients:
            await client.stop()
        if self.server:
            self.server.close()
            self.hub.close()  # attached connections are long-lived; wait_closed would wait on them
            await self.server.wait_closed()
            self.server = None
        for t in list(self._tasks):
            t.cancel()

    async def _listen(self) -> None:
        # Relay-only nodes don't listen: nobody can reach them directly, everything arrives via the relay.
        if self.direct:
            self.server = await serve(lambda m: self.handle(_clean(m)), self.bind, self.port,
                                      streams={"attach": self.hub.handle_attach})

    async def move(self, port: int, host: str | None = None) -> None:
        """Simulate an address change: rebind, re-sign our record, gossip it."""
        if self.server:
            self.server.close()
            await self.server.wait_closed()
        self.port = port
        if host:
            self.host = self.bind = host
        await self._listen()
        self._refresh_record()
        self.log(f"moved to {', '.join(self.addrs)}")
        await self.announce()

    def _refresh_record(self) -> None:
        prev = self.me.seq if self.me else 0
        old = self.phonebook.get(self.ident.pubkey)
        if old:
            prev = max(prev, old.seq)
        self.me = Record.create(self.ident, self.name, self.addrs, self.enc.pub, prev)
        self.phonebook.merge(self.me)

    # --- transport -------------------------------------------------------------------------------

    async def _req(self, addr: str, pubkey: str | None, msg: dict) -> dict | None:
        """One request to one peer. Order: punched UDP path if we have one, then the address itself
        (relay addresses go through the relay, and kick off a punch attempt for next time)."""
        if pubkey and (path := self.udp.path(pubkey)):
            reply = await self.udp.request(path, msg)
            if reply is not None:
                return reply
            self.udp.drop_path(pubkey)
            self.log(f"direct path to {self._name(pubkey)} lost; falling back")
        if addr.startswith(RELAY_PREFIX):
            if not pubkey:
                return None
            hub = addr[len(RELAY_PREFIX):]
            # "from"/"item" let the hub record an IOU if they're away (only for msg["iou"] items).
            reply = await request(hub, {"type": "forward", "to": pubkey, "msg": msg,
                                        "from": self.ident.pubkey, "item": msg.get("iou")})
            # Delivered live via a relay: try for a direct path next time (unless we *are* that relay).
            if reply is not None and not reply.get("unreachable") and not reply.get("queued") and hub != self.addr:
                self._spawn(self._try_direct(pubkey, hub))
            return None if reply is None or reply.get("unreachable") else reply
        return await request(addr, msg)

    # --- hole punching ---------------------------------------------------------------------------

    def _name(self, pubkey: str) -> str:
        rec = self.phonebook.get(pubkey)
        return rec.name if rec else short(pubkey)

    async def _resolve(self, hostport: str) -> Addr | None:
        """UDP replies come from an IP, so resolve names up front to match them."""
        try:
            host, port = split_addr(hostport)
            infos = await asyncio.get_running_loop().getaddrinfo(host, port, family=socket.AF_INET, type=socket.SOCK_DGRAM)
            return infos[0][4][0], port
        except (OSError, ValueError, IndexError):
            return None

    async def _discover_public(self) -> None:
        """Find our public UDP address (what NAT turns our socket into). Ask our hub over UDP if it's a
        plain HOST:PORT hub; otherwise, or if that fails, ask public STUN servers."""
        public = None
        for relay in [r for r in self.relays if not is_url(r)][:1]:
            if (hub := await self._resolve(relay)) and (pong := await self.udp.ping(hub)):
                if isinstance(pong.get("you"), list):
                    public = (str(pong["you"][0]), int(pong["you"][1]))
        for server in self.stun if public is None else []:
            if (addr := await self._resolve(server)) and (public := await self.udp.stun(addr)):
                break
        if public and public != self.udp.public:
            self.udp.public = public
            self.log(f"public UDP address {public[0]}:{public[1]}")

    def _candidates(self) -> list[list]:
        """Addresses a peer could reach our UDP socket at: public first, then local networks."""
        addrs = ([self.udp.public] if self.udp.public else []) + self.udp.local_addrs()
        return [list(a) for a in dict.fromkeys(addrs)]

    async def _keepalive_loop(self) -> None:
        tick = 0
        while True:
            await asyncio.sleep(KEEPALIVE)
            tick += 1
            if self.relays:
                await self._discover_public()
            await asyncio.gather(*(self.udp.keepalive(pk) for pk in list(self.udp.paths)))
            if tick % OUTBOX_EVERY == 0 and self.outbox.items:
                await self.flush_outbox()
            if tick % OUTBOX_EVERY == 0 and (self.relays or self.device_addrs) and not self._device_round_busy:
                self._spawn(self._device_round())  # can take a while; don't hold up the keepalive
            if tick % PRESENCE_EVERY == 0:
                await self._presence_round()

    async def _device_round(self) -> None:
        self._device_round_busy = True
        try:
            await self.sync_devices()
            await self.sync_vault()
        finally:
            self._device_round_busy = False

    async def _try_direct(self, pubkey: str, hub: str) -> None:
        """Send pubkey a punch request through their relay (it carries our addresses, the reply carries
        theirs), then both sides ping each other. Future requests use the path if it works."""
        if (self.udp.path(pubkey) or pubkey in self._punching
                or time.time() - self._punch_failed.get(pubkey, 0) < PUNCH_RETRY):
            return
        self._punching.add(pubkey)
        try:
            punch = {"type": "punch", "peer": self.ident.pubkey, "addrs": self._candidates()}
            reply = await request(hub, {"type": "forward", "to": pubkey, "msg": punch})
            addr = None
            if reply and reply.get("ok") and reply.get("addrs"):
                try:
                    theirs = [(str(a[0]), int(a[1])) for a in reply["addrs"][:9]]
                except (TypeError, ValueError, IndexError):
                    theirs = []
                addr = await self.udp.punch(pubkey, theirs) if theirs else None
            if addr:
                self.log(f"direct connection to {self._name(pubkey)} at {addr[0]}:{addr[1]}")
            else:
                self._punch_failed[pubkey] = time.time()
                self.log(f"couldn't connect directly to {self._name(pubkey)}; using the relay")
        finally:
            self._punching.discard(pubkey)

    async def _answer_punch(self, peer: str, addrs: list[Addr]) -> None:
        if peer in self._punching or self.udp.path(peer):
            return
        self._punching.add(peer)
        try:
            addr = await self.udp.punch(peer, addrs)
            if addr:
                self.log(f"direct connection to {self._name(peer)} at {addr[0]}:{addr[1]}")
        finally:
            self._punching.discard(peer)

    def _peer_targets(self, skip: set[str]) -> list[tuple[str, str | None]]:
        """(address, pubkey) for everyone we know except skip, plus bootstrap nodes."""
        targets = {(r.addrs[0], r.pubkey) for r in self.phonebook.all() if r.addrs and r.pubkey not in skip}
        known = {a for a, _ in targets}
        targets |= {(b, None) for b in self.bootstrap if b not in known}
        return [(a, pk) for a, pk in targets if a != self.addr]

    async def _request_peer(self, pubkey: str, msg: dict) -> dict | None:
        """Send msg to whoever holds pubkey, looking up a fresher address if the known one is dead."""
        rec = self.phonebook.get(pubkey)
        reply = await self._try_addrs(rec, msg) if rec else None
        if reply is not None:
            return reply
        # Unreachable at the last known address: maybe they moved while we weren't listening.
        old_seq = rec.seq if rec else -1
        rec = await self.refresh(pubkey)
        if rec and rec.seq > old_seq:
            self.log(f"{rec.name} moved, now at {', '.join(rec.addrs)}")
            return await self._try_addrs(rec, msg)
        return None

    async def _try_addrs(self, rec: Record, msg: dict) -> dict | None:
        """Try each address in turn. An IOU reply means "they're away": keep going, so every hub they
        use holds an IOU and whichever one they reconnect to nudges us."""
        iou = None
        for addr in rec.addrs:
            reply = await self._req(addr, rec.pubkey, msg)
            if reply is None:
                continue
            if reply.get("iou"):
                iou = iou or reply
                continue
            return reply
        return iou

    # --- phonebook -------------------------------------------------------------------------------

    async def join(self, addr: str) -> bool:
        """Say hello to one address and absorb its whole phonebook."""
        reply = await request(addr, {"type": "hello", "record": self.me.to_dict()})
        if not reply or not reply.get("ok"):
            self.log(f"could not reach {addr}")
            return False
        learned = sum(self.phonebook.merge(Record.from_dict(d)) for d in [reply.get("record"), *reply.get("records", [])] if d)
        self.log(f"joined via {addr}, learned {learned} record(s)")
        return True

    async def announce(self) -> None:
        await self._gossip(self.me, exclude=set())

    async def _gossip(self, rec: Record, exclude: set[str]) -> None:
        """Flood rec to every peer we know (plus bootstrap nodes). Dedup happens on receipt via seq."""
        msg = {"type": "announce", "record": rec.to_dict(), "from": self.ident.pubkey}
        # The record's owner is included on purpose: their *other* devices need to hear it (the one that
        # announced just ignores its own copy, since it isn't newer).
        targets = self._peer_targets(exclude | {self.ident.pubkey})
        await asyncio.gather(*(self._req(a, pk, msg) for a, pk in targets))

    async def refresh(self, pubkey: str) -> Record | None:
        """Ask everyone we know (and the bootstrap phonebooks) for a fresher record of pubkey."""
        msg = {"type": "lookup", "pubkey": pubkey}
        targets = self._peer_targets({self.ident.pubkey, pubkey})
        for reply in await asyncio.gather(*(self._req(a, pk, msg) for a, pk in targets)):
            if reply and reply.get("record"):
                self.phonebook.merge(Record.from_dict(reply["record"]))
        return self.phonebook.get(pubkey)

    async def _enc_key(self, pubkey: str) -> str:
        rec = self.phonebook.get(pubkey)
        if not rec or not rec.enc:
            rec = await self.refresh(pubkey)
        if not rec or not rec.enc:
            raise ValueError(f"no encryption key for {short(pubkey)} yet (they need to come online once)")
        return rec.enc

    # --- direct messages -------------------------------------------------------------------------

    async def _deliver(self, pubkey: str, msg: dict, item_id: str) -> str | None:
        """Deliver something that's worth delivering later. Returns "delivered", "waiting" (they're
        offline: it's in our outbox, and their hubs hold an IOU), or None (they refused it)."""
        msg = {**msg, "iou": item_id}
        reply = await self._request_peer(pubkey, msg)
        if reply is not None and reply.get("ok") and not reply.get("iou"):
            return "delivered"
        if reply is None or reply.get("iou"):
            self.outbox.add(pubkey, msg, item_id)
            return "waiting"
        return None

    async def _send_sealed(self, kind: str, pubkey: str, fields: dict) -> tuple[str | None, dict]:
        """Sign fields (plus a fresh message id), seal them to pubkey and deliver as `kind`.
        Returns (delivered | waiting | None, inner)."""
        inner = {"from": self.ident.pubkey, "to": pubkey, "ts": time.time(), "id": b64(os.urandom(12)), **fields}
        inner["sig"] = self.ident.sign(canonical(inner))
        try:
            enc = await self._enc_key(pubkey)
        except ValueError as e:
            self.log(str(e))
            return None, inner
        sealed = {"type": kind, "to": pubkey, "sealed": seal(enc, canonical(inner))}
        return await self._deliver(pubkey, sealed, f"{kind}:{inner['id']}"), inner

    def _open_sealed(self, msg: dict) -> dict | None:
        """Decrypt and verify a sealed request addressed to us; None if it isn't valid."""
        plain = open_sealed(self.enc, msg.get("sealed") or {})
        try:
            inner = json.loads(plain) if plain else {}
            body = {k: v for k, v in inner.items() if k != "sig"}
            if inner["to"] == self.ident.pubkey and verify(inner["from"], canonical(body), inner["sig"]):
                return inner
        except (ValueError, KeyError, TypeError, AttributeError):
            pass
        return None

    async def send(self, pubkey: str, text: str, extra: dict | None = None) -> str | None:
        """Returns "delivered", "waiting" (in our outbox until they're back), or None.
        Messaging someone makes them a trusted contact (unless you've blocked them). extra carries
        replies / reactions / edits (see thread.py)."""
        if self.trust.get(pubkey) == "unknown" and not (extra or {}).get("kind"):
            self.trust.set(pubkey, "trusted")
        result, inner = await self._send_sealed("msg", pubkey, {"text": text, **(extra or {})})
        if result:
            self.dms.add(pubkey, inner)
        if result == "delivered":
            self.dms.mark_delivered(pubkey, inner["id"])
            self._seen(pubkey)
        return result

    def message_status(self, pubkey: str, message: dict) -> str | None:
        """For a DM we sent: "waiting" (in our outbox), "delivered", "read", or None."""
        if f"msg:{message['id']}" in self.outbox.items:
            return "waiting"
        return self.dms.receipt(pubkey, message)

    # --- presence, typing, read receipts ---------------------------------------------------------
    # Ephemeral signals, sealed like messages but never stored, synced or queued, and only exchanged
    # with contacts: strangers learn nothing about when you're around or what you've read.

    def _seen(self, pubkey: str) -> None:
        was_online = self.online(pubkey)
        self.last_seen[pubkey] = time.time()
        if not was_online and self.trust.trusted(pubkey):
            self._emit("presence", pubkey=pubkey, online=True)

    def online(self, pubkey: str) -> bool:
        return time.time() - self.last_seen.get(pubkey, 0) < ONLINE_WINDOW

    async def _quick(self, pubkey: str, msg: dict) -> dict | None:
        """One cheap attempt: the direct path or their first addresses. No lookups, no outbox."""
        rec = self.phonebook.get(pubkey)
        for addr in (rec.addrs[:2] if rec else []):
            reply = await self._req(addr, pubkey, msg)
            if reply is not None and not reply.get("unreachable") and not reply.get("iou"):
                return reply
        return None

    async def _signal(self, pubkey: str, fields: dict) -> bool:
        if not self.trust.trusted(pubkey):
            return False
        rec = self.phonebook.get(pubkey)
        if not rec or not rec.enc:
            return False
        inner = {"from": self.ident.pubkey, "to": pubkey, "ts": time.time(), **fields}
        inner["sig"] = self.ident.sign(canonical(inner))
        reply = await self._quick(pubkey, {"type": "signal", "to": pubkey, "sealed": seal(rec.enc, canonical(inner))})
        if reply and reply.get("ok"):
            self._seen(pubkey)
            return True
        return False

    async def _presence_round(self) -> None:
        contacts = [pk for pk, lv in self.trust.levels.items() if lv == "trusted" and self.phonebook.get(pk)]
        was = {pk: self.online(pk) for pk in contacts}
        await asyncio.gather(*(self._signal(pk, {"signal": "here"}) for pk in contacts))
        for pk in contacts:
            if was[pk] and not self.online(pk):
                self._emit("presence", pubkey=pk, online=False)

    def send_typing(self, pubkey: str) -> None:
        if time.time() - self._typing_sent.get(pubkey, 0) >= TYPING_EVERY:
            self._typing_sent[pubkey] = time.time()
            self._spawn(self._signal(pubkey, {"signal": "typing"}))

    def send_read(self, pubkey: str) -> None:
        """Tell pubkey we've read everything they sent so far (call when their chat is on screen)."""
        theirs = [m["ts"] for m in self.dms.messages(pubkey) if m["from"] == pubkey]
        if theirs and max(theirs) > self._read_sent.get(pubkey, 0):
            self._read_sent[pubkey] = max(theirs)
            self._spawn(self._signal(pubkey, {"signal": "read", "upto": max(theirs)}))

    # --- catching up -----------------------------------------------------------------------------

    async def flush_outbox(self, peer: str | None = None) -> int:
        """Try to deliver what we owe people. Returns how many items got through."""
        sent = 0
        for pk in [peer] if peer else sorted(self.outbox.peers()):
            if pk in self._flushing:
                continue
            self._flushing.add(pk)
            try:
                for item_id, item in sorted(self.outbox.for_peer(pk), key=lambda kv: kv[1]["ts"]):
                    reply = await self._request_peer(pk, item["msg"])
                    if reply is None or reply.get("iou"):
                        break  # still away; their hubs hold IOUs again
                    self.outbox.remove(item_id)  # delivered (or refused: don't retry forever)
                    sent += bool(reply.get("ok"))
                    if reply.get("ok") and item_id.startswith("msg:"):
                        self.dms.mark_delivered(pk, item_id[4:])
                        self._emit("receipt", pubkey=pk)
            finally:
                self._flushing.discard(pk)
        if sent:
            self.log(f"delivered {sent} item(s) that were waiting")
        return sent

    async def sync_dm(self, peer: str) -> int:
        """Swap missing messages with peer: each side sends what the other lacks (by id). Returns how
        many we gained. Messages are verified against their author's signature."""
        req = {"from": self.ident.pubkey, "to": peer, "ts": time.time(), "have": self.dms.ids(peer)}
        reply = await self._request_peer(peer, {"type": "dm_sync", "req": req, "sig": self.ident.sign(canonical(req))})
        plain = open_sealed(self.enc, reply.get("sealed") or {}) if reply and reply.get("ok") else None
        if not plain:
            return 0
        new = 0
        for m in json.loads(plain).get("messages", []):
            try:
                body = {k: v for k, v in m.items() if k != "sig"}
                pair_ok = {m["from"], m["to"]} == {self.ident.pubkey, peer}
                if not pair_ok or not verify(m["from"], canonical(body), m["sig"]):
                    continue
            except (KeyError, TypeError, AttributeError):
                continue
            if self.dms.add(peer, m):
                new += 1
                if m["from"] == peer and self.on_message:
                    self.on_message(self.phonebook.get(peer), str(m["text"]), m)
        return new

    async def sync_dms(self) -> int:
        """Fill gaps in every chat, and pull chats from contacts we have no history with yet (a new
        device starts with your contacts but none of the conversation)."""
        contacts = {pk for pk, lv in self.trust.levels.items() if lv == "trusted"}
        peers = [pk for pk in set(self.dms.convos) | contacts if not self.trust.blocked(pk) and pk != self.ident.pubkey]
        return sum(await asyncio.gather(*(self.sync_dm(pk) for pk in peers)))

    # --- syncing your own devices ------------------------------------------------------------------
    # Your devices share a key but each keeps its own state, and each can be off when another is on.
    # So they keep each other up to date two ways, both with the same snapshot of everything that's
    # yours (trust, nicknames, groups with their keys and messages, chats, receipts, what's waiting in
    # the outbox, file offers, phonebook), signed by your key and sealed to your own:
    #   * live: devices that are on at the same time compare digests (a probe) and, if they differ,
    #     swap snapshots, through your hubs or directly. At startup, when another device appears, soon
    #     after a change, and every minute.
    #   * through the vault: whenever your state changes, a snapshot is left on your hubs (vault.py).
    #     A device that was off picks up everything its siblings left there when it starts.
    # Merging only ever adds: newest change wins per contact / group status, messages and outbox items
    # are unioned, so devices converge whatever order they hear things in. History too big for one
    # message is cut to the newest messages; the rest still comes from chat and group sync.

    def _state(self, keep: int | None = None) -> dict:
        me = self.ident.pubkey
        return {"trust": self.trust.device_state(), "groups": self.groups.device_state(keep),
                "dms": self.dms.device_state(keep), "outbox": self.outbox.device_state(),
                "files": self.files.device_state(),
                "phonebook": [r.to_dict() for r in sorted(self.phonebook.all(), key=lambda r: r.pubkey)
                              if r.pubkey != me]}

    def device_state(self) -> dict:
        return self._state()

    def _pack(self) -> Packed:
        """Our state, compressed to fit one message (older messages are left out if it must be)."""
        for keep in KEEP_STEPS:
            state = self._state(keep)
            z = zlib.compress(canonical(state), 6)
            if len(z) <= SYNC_BUDGET or keep == KEEP_STEPS[-1]:
                break
        core = {k: v for k, v in state.items() if k != "phonebook"}  # gossip keeps phonebooks close enough
        return Packed(z, hashlib.sha256(canonical(core)).hexdigest())

    def _seal_snapshot(self, packed: Packed, full: bool = True, rev: int = 0) -> dict:
        """A signed envelope sealed to our own key: our device id and digest, plus (if full) the state."""
        z = packed.z if full else b""
        head = {"from": self.ident.pubkey, "to": self.ident.pubkey, "device": self.device, "rev": rev,
                "ts": time.time(), "digest": packed.digest, "zhash": hashlib.sha256(z).hexdigest()}
        head["sig"] = self.ident.sign(canonical(head))
        return seal(self.enc.pub, json.dumps(head).encode() + b"\n" + z)

    def _open_snapshot(self, box) -> tuple[dict, dict | None] | None:
        """(header, state) of a snapshot sealed to us and signed by us; state is None for a probe."""
        plain = open_sealed(self.enc, box) if isinstance(box, dict) else None
        if not plain:
            return None
        try:
            line, _, z = plain.partition(b"\n")
            head = json.loads(line)
            sig = head.pop("sig")
            me = self.ident.pubkey
            if (head["from"] != me or head["to"] != me or not verify(me, canonical(head), sig)
                    or hashlib.sha256(z).hexdigest() != head["zhash"]):
                return None
            if not z:
                return head, None
            inflater = zlib.decompressobj()
            state = json.loads(inflater.decompress(z, STATE_MAX))
            return (head, state) if isinstance(state, dict) and not inflater.unconsumed_tail else None
        except (ValueError, KeyError, TypeError, AttributeError, zlib.error):
            return None

    def _link(self) -> None:
        """We've met another device of ours: from now on keep a snapshot on our hubs."""
        if not self.linked:
            self.linked = True
            if self.data_dir:
                mark_linked(self.data_dir)
            self.log("another device of yours is around; your devices will now keep each other in step")
            self._vault_soon()

    def _merge_device_state(self, state: dict, device) -> None:
        me = self.ident.pubkey
        self._merging = True
        try:
            groups = self.groups.merge_device_state(state.get("groups") or {})
            people = self.trust.merge_device_state(state.get("trust") or {})
            messages = self.dms.merge_device_state(state.get("dms") or {}, me)
            queued = self.outbox.merge_device_state(state.get("outbox") or {})
            offers = self.files.merge_device_state(state.get("files") or {})
            records = self.phonebook.merge_all(Record.from_dict(d) for d in state.get("phonebook") or []
                                               if isinstance(d, dict) and d.get("pubkey") != me)
        finally:
            self._merging = False
        if groups or people or messages or queued or offers:
            names = ", ".join("#" + self.groups.get(g).name for g in groups if self.groups.get(g))
            parts = [f"{len(groups)} group(s)" + (f" ({names})" if names else ""), f"{messages} message(s)",
                     f"{people} contact setting(s)"]
            if queued:
                parts.append(f"{queued} unsent item(s)")
            if offers:
                parts.append(f"{offers} file offer(s)")
            self.log(f"synced from your device {device}: {', '.join(parts)}")
            self._emit("devices_synced", groups=groups, people=people, messages=messages)
        if records and not (groups or people or messages or queued or offers):
            self.log(f"synced from your device {device}: {records} contact record(s)")

    def _sync_hubs(self) -> list[str]:
        """The hubs our devices can meet at: every relay we or a sibling of ours attach to (not ourselves)."""
        return [a[len(RELAY_PREFIX):] for a in self.addrs if a.startswith(RELAY_PREFIX) and a[len(RELAY_PREFIX):] != self.addr]

    def _sibling_addrs(self) -> list[str]:
        return [a for a in self.device_addrs if not a.startswith(RELAY_PREFIX) and a != self.addr]

    async def _ask_devices(self, target: str, msg: dict, device: str | None = None) -> list[dict]:
        """Send msg to our other devices through hub `target` (all of them, or just `device`), or straight
        to `target` if it's a sibling's own address. Returns every reply."""
        if target in self._sibling_addrs():
            reply = await request(target, msg, SYNC_TIMEOUT)
            return [reply] if reply else []
        fwd = {"type": "forward", "to": self.ident.pubkey, "msg": msg, "except_device": self.device, "fanout": True}
        if device:
            fwd["only_device"] = device
        reply = await request(target, fwd, SYNC_TIMEOUT)
        if not reply:
            return []
        return [r for r in reply["replies"] if isinstance(r, dict)] if isinstance(reply.get("replies"), list) else [reply]

    async def sync_devices(self) -> int:
        """Compare notes with our other devices that are on right now, through each of our hubs (or
        directly): a cheap probe first, and a full swap of state with whoever differs from us. Returns
        how many devices answered."""
        packed = self._pack()
        probe = {"type": "device_sync", "to": self.ident.pubkey, "sealed": self._seal_snapshot(packed, full=False)}
        targets = [*self._sync_hubs(), *self._sibling_addrs()]
        answers = await asyncio.gather(*(self._ask_devices(t, probe) for t in targets))
        seen: set[str] = set()
        differ: list[tuple[str, str]] = []  # (where to reach them, device id)
        for target, replies in zip(targets, answers):
            for reply in replies:
                got = self._open_snapshot(reply.get("sealed")) if reply.get("ok") else None
                if not got or got[0]["device"] == self.device or got[0]["device"] in seen:
                    continue
                seen.add(got[0]["device"])
                self._link()
                if got[0]["digest"] != packed.digest:
                    differ.append((target, got[0]["device"]))
        if differ:
            full = {"type": "device_sync", "to": self.ident.pubkey, "sealed": self._seal_snapshot(packed)}
            for replies in await asyncio.gather(*(self._ask_devices(t, full, d) for t, d in differ)):
                for reply in replies:
                    got = self._open_snapshot(reply.get("sealed")) if reply.get("ok") else None
                    if got and got[1] is not None and got[0]["device"] != self.device:
                        self._merge_device_state(got[1], got[0]["device"])
        return len(seen)

    def _state_changed(self) -> None:
        """Something worth telling our other devices changed: sync shortly (batched), and leave a
        snapshot on our hubs for the ones that are off."""
        if self._merging:
            return  # that's news from a sibling, who has told the others itself
        self._vault_soon()
        if self._devsync_pending or not (self.relays or self.device_addrs):
            return
        self._devsync_pending = True

        async def later():
            await asyncio.sleep(1.0)
            self._devsync_pending = False
            await self.sync_devices()

        try:
            self._spawn(later())
        except RuntimeError:  # no event loop yet (changes made before start)
            self._devsync_pending = False

    # --- the vault: syncing with devices that are off ----------------------------------------------

    def _sync_state_path(self) -> Path | None:
        return self.data_dir / "devsync.json" if self.data_dir else None

    def _load_sync_state(self) -> dict[str, int]:
        path = self._sync_state_path()
        try:
            return {str(d): int(r) for d, r in json.loads(path.read_text(encoding="utf-8")).get("seen", {}).items()} \
                if path and path.exists() else {}
        except (OSError, ValueError, AttributeError, TypeError):
            return {}

    def _save_sync_state(self) -> None:
        if path := self._sync_state_path():
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(json.dumps({"seen": self._vault_seen}), encoding="utf-8")

    def _signed(self, req: dict) -> dict:
        return {"req": req, "sig": self.ident.sign(canonical(req))}

    async def sync_vault(self) -> int:
        """Collect the snapshots our other devices left on our hubs, then leave ours. Returns how many
        new snapshots we merged. Only identities that live on several devices use this."""
        hubs = self._sync_hubs()
        if not self.linked or not hubs:
            return 0
        got = await self._vault_pull(hubs)
        await self._vault_push(hubs)
        return got

    async def _vault_pull(self, hubs: list[str]) -> int:
        req = {"pubkey": self.ident.pubkey, "device": self.device, "ts": time.time(), "have": dict(self._vault_seen)}
        msg = {"type": "vault_get", **self._signed(req)}
        merged = 0
        for reply in await asyncio.gather(*(request(h, msg, SYNC_TIMEOUT) for h in hubs)):
            for snap in (reply.get("snaps") or []) if reply and reply.get("ok") else []:
                try:
                    device, rev = str(snap["device"]), int(snap["rev"])
                    if device == self.device or rev <= self._vault_seen.get(device, 0):
                        continue
                    got = self._open_snapshot(json.loads(snap["blob"]))
                except (KeyError, TypeError, ValueError):
                    continue
                if not got or got[1] is None or got[0]["device"] != device:
                    continue  # not from us (a hub made it up, or it's damaged): ignore it
                self._link()
                self._merge_device_state(got[1], device)
                self._vault_seen[device] = rev
                merged += 1
        if merged:
            self._save_sync_state()
        return merged

    async def _vault_push(self, hubs: list[str], force: bool = False) -> None:
        packed = self._pack()
        self._vault_dirty = False  # (a change made while we upload sets it again)
        todo = [h for h in hubs if self._vault_pushed.get(h) != packed.digest
                and (force or time.time() - self._vault_at.get(h, 0) >= VAULT_MIN_GAP)]
        if not todo:
            return
        self._rev = max(time.time_ns() // 1000, self._rev + 1)
        blob = json.dumps(self._seal_snapshot(packed, rev=self._rev))
        req = {"pubkey": self.ident.pubkey, "device": self.device, "rev": self._rev, "ts": time.time(),
               "hash": hashlib.sha256(blob.encode()).hexdigest()}
        msg = {"type": "vault_put", **self._signed(req), "blob": blob}
        for hub in todo:
            self._vault_at[hub] = time.time()
        for hub, reply in zip(todo, await asyncio.gather(*(request(h, msg, SYNC_TIMEOUT) for h in todo))):
            if reply and reply.get("ok"):
                self._vault_pushed[hub] = packed.digest
            elif reply:
                self.log(f"hub {hub} won't keep a snapshot for your other devices: {reply.get('error')}")

    def _vault_soon(self) -> None:
        """Leave a fresh snapshot on our hubs shortly (batched), if we have other devices to leave it for."""
        if not self.linked or not self._sync_hubs():
            return
        self._vault_dirty = True
        if self._vault_pending:
            return
        self._vault_pending = True

        async def later():
            await asyncio.sleep(VAULT_DELAY)
            self._vault_pending = False
            await self._vault_push(self._sync_hubs())

        try:
            self._spawn(later())
        except RuntimeError:
            self._vault_pending = False

    def _adopt_device_addrs(self, rec: Record) -> None:
        """Another device of ours published our record: keep its addresses in ours too, so people can
        reach whichever device is up. Re-announce only if that added something (no ping-pong)."""
        extra = [a for a in rec.addrs if a not in self.addrs]
        if extra:
            self.device_addrs += extra
            self.log(f"another device of yours is at {', '.join(extra)}")
        missing = [a for a in self.addrs if a not in rec.addrs]  # their record dropped some of ours
        if extra or missing:
            self._refresh_record()
            self._spawn(self.announce())
        self._spawn(self.sync_devices())  # another device of ours is around: compare notes

    async def _peer_online(self, pubkey: str) -> None:
        """They're reachable again: deliver what we owe them and fill gaps in our chat."""
        if pubkey in self.outbox.peers():
            await self.flush_outbox(pubkey)
        if pubkey in self.dms.convos and not self.trust.blocked(pubkey):
            await self.sync_dm(pubkey)

    async def _nudge(self, sender: str, recipient: str) -> None:
        """Hub side: recipient is back; tell sender (who holds the actual items) to deliver."""
        if sender == self.ident.pubkey:
            self._spawn(self._peer_online(recipient))
        else:
            await self._request_peer(sender, {"type": "nudge", "peer": recipient})

    # --- groups ----------------------------------------------------------------------------------

    def _owned(self, group_id: str) -> GroupRecord:
        group = self.groups.get(group_id)
        if not group or group.owner != self.ident.pubkey:
            raise ValueError("only the group owner can change the group")
        return group

    async def _publish(self, name: str, members: list[str], prev: GroupRecord | None, settings: dict,
                       notify: list[str] | None = None) -> GroupRecord:
        others = sorted(set(members) - {self.ident.pubkey})
        enc_keys = {m: await self._enc_key(m) for m in others}  # raises before anything changes
        group = self.groups.build_record(name, others, enc_keys, prev, settings)
        self.groups.merge(group)
        msg = {"type": "group_update", "group": group.to_dict()}
        targets = [pk for pk in (notify or group.members) if pk != self.ident.pubkey]
        item = f"group_update:{group.group_id}:{group.seq}"
        await asyncio.gather(*(self._deliver(pk, msg, item) for pk in targets))
        return group

    async def create_group(self, name: str, members: list[str], history: str = "private") -> GroupRecord:
        if history not in HISTORY_MODES:
            raise ValueError(f"history must be one of {', '.join(HISTORY_MODES)}")
        others = sorted(set(members) - {self.ident.pubkey})
        enc_keys = {m: await self._enc_key(m) for m in others}  # fail before anything is created
        group = self.groups.build_record(name, others, enc_keys, None, {"history": history})
        self.groups.set_status(group.group_id, "accepted")  # it's ours
        self.groups.merge(group)
        msg = {"type": "group_update", "group": group.to_dict()}
        item = f"group_update:{group.group_id}:{group.seq}"
        await asyncio.gather(*(self._deliver(pk, msg, item) for pk in others))
        return group

    async def accept_group(self, group_id: str) -> int:
        """Accept an invite: join, then catch up on whatever history we're allowed to read."""
        group = self.groups.get(group_id)
        if not group or self.ident.pubkey not in group.members:
            raise ValueError("no invite for that group")
        self.groups.set_status(group_id, "accepted")
        return await self.sync_group(group_id)

    async def leave_group(self, group_id: str, status: str = "left") -> None:
        """Leave (or decline an invite to) a group: stop taking part and ask the owner to remove us."""
        group = self.groups.get(group_id)
        if not group:
            raise ValueError("unknown group")
        if group.owner == self.ident.pubkey:
            others = [m for m in group.members if m != self.ident.pubkey]
            if not others:
                raise ValueError(f"you're the host of #{group.name} and its only member: "
                                 f"/group delete {group.name} to get rid of it")
            raise ValueError(f"you're the host of #{group.name}. Choose a successor to own it: "
                             f"/group leave {group.name} NAME (or /group transfer {group.name} NAME to stay), "
                             f"or delete it for everyone: /group delete {group.name}")
        self.groups.set_status(group_id, status)
        req = {"group": group_id, "from": self.ident.pubkey, "ts": time.time()}
        leave = {"type": "group_leave", "req": req, "sig": self.ident.sign(canonical(req))}
        await self._deliver(group.owner, leave, f"group_leave:{group_id}:{req['ts']}")

    async def transfer_group(self, group_id: str, new_owner: str, leave: bool = False) -> str | None:
        """Hand a group we own to another member. We sign the hand-over and send it, with the group's
        key history, sealed to them; their node then republishes the group as its owner (dropping us
        if we're leaving). Returns "delivered" / "waiting" (they're offline: it's in our outbox)."""
        group = self._owned(group_id)
        if new_owner == self.ident.pubkey or new_owner not in group.members:
            raise ValueError("the new owner has to be another member of the group")
        transfer = {"to": new_owner, "sig": self.ident.sign(transfer_body(group_id, new_owner, len(group.transfers)))}
        epochs = {str(e): {**meta, "key": b64(self.groups.keys[group_id][e])}
                  for e, meta in self.groups.epochs.get(group_id, {}).items() if e in self.groups.keys.get(group_id, {})}
        handoff = {"group": group.to_dict(), "transfer": transfer, "epochs": epochs, "leave": leave}
        result, _ = await self._send_sealed("group_handoff", new_owner, {"handoff": handoff})
        if result and leave:
            self.groups.set_status(group_id, "left")
        return result

    async def delete_group(self, group_id: str) -> None:
        """Delete a group for everyone: a final, signed "deleted" update. Members' nodes forget its
        messages and keys (honest clients, anyway: nobody can force a copy to be forgotten)."""
        old = self._owned(group_id)
        await self._publish(old.name, old.members, old, {**old.settings, "deleted": True})
        self.groups.purge(group_id)

    async def _take_over(self, inner: dict) -> None:
        """We were handed a group: check the hand-over, adopt its key history, republish as owner."""
        h = inner.get("handoff") or {}
        group = GroupRecord.from_dict(h.get("group") or {})
        transfer = h.get("transfer") or {}
        if (not group or group.owner != inner["from"] or self.ident.pubkey not in group.members
                or transfer.get("to") != self.ident.pubkey
                or not verify(group.owner, transfer_body(group.group_id, self.ident.pubkey, len(group.transfers)),
                              str(transfer.get("sig")))):
            self.log("ignored an invalid group hand-over")
            return
        gid = group.group_id
        self.groups.merge(group)  # no-op if we already have a newer copy
        group = self.groups.get(gid)
        if group.owner != inner["from"] or len(group.transfers) != len(h["group"].get("transfers") or []):
            self.log(f"ignored a stale hand-over of #{group.name}")  # ownership moved on since it was sent
            return
        for e, meta in (h.get("epochs") or {}).items():
            try:
                self.groups.keys.setdefault(gid, {})[int(e)] = unb64(meta["key"])
                self.groups.epochs.setdefault(gid, {})[int(e)] = {"mode": meta["mode"], "start": float(meta["start"])}
            except (KeyError, TypeError, ValueError):
                continue
        leaving = group.owner if h.get("leave") else None
        enc_keys = {}
        for m in group.members:
            if m in (self.ident.pubkey, leaving):
                continue
            try:
                enc_keys[m] = await self._enc_key(m)
            except ValueError:
                self.log(f"no key for {self._name(m)} yet; they'll need re-adding to #{group.name}")
        new = self.groups.build_record(group.name, list(enc_keys), enc_keys, group, group.settings,
                                       transfer={"to": self.ident.pubkey, "sig": transfer["sig"]})
        self.groups.set_status(gid, "accepted")
        self.groups.merge(new)
        self.log(f"{self._name(inner['from'])} made you the owner of #{group.name}")
        self._emit("group_owner", group=new, previous=inner["from"])
        msg = {"type": "group_update", "group": new.to_dict()}
        item = f"group_update:{gid}:{new.seq}"
        await asyncio.gather(*(self._deliver(pk, msg, item) for pk in set(group.members) - {self.ident.pubkey}))

    def pending_groups(self) -> list[GroupRecord]:
        return [g for g in self.groups.all()
                if self.groups.status.get(g.group_id) == "pending" and self.ident.pubkey in g.members]

    async def add_member(self, group_id: str, pubkey: str) -> GroupRecord:
        old = self._owned(group_id)
        return await self._publish(old.name, [*old.members, pubkey], old, old.settings)

    async def remove_member(self, group_id: str, pubkey: str) -> GroupRecord:
        old = self._owned(group_id)
        # Tell the removed member too, so they know they're out. They don't get the new key.
        return await self._publish(old.name, [m for m in old.members if m != pubkey], old, old.settings,
                                   notify=old.members)

    async def set_history(self, group_id: str, history: str) -> GroupRecord:
        """Starts a new key epoch; messages already sent keep the setting they were sent under."""
        if history not in HISTORY_MODES:
            raise ValueError(f"history must be one of {', '.join(HISTORY_MODES)}")
        old = self._owned(group_id)
        return await self._publish(old.name, old.members, old, {**old.settings, "history": history})

    async def send_group(self, group_id: str, text: str, extra: dict | None = None) -> tuple[int, int]:
        """Deliver to every member we can reach right now. Returns (delivered, other
        members). Anyone missed picks it up later via sync, from us or anyone else who has it."""
        group = self.groups.get(group_id)
        if not group or self.ident.pubkey not in group.members:
            raise ValueError("not a member of that group")
        if not self.groups.joined(group_id):
            raise ValueError("accept the invite first (/accept)")
        envelope = self.groups.seal_message(group_id, text, extra)
        self.groups.add_message(envelope)
        msg = {"type": "group_msg", "envelope": envelope, "group": group.to_dict()}
        others = [m for m in group.members if m != self.ident.pubkey]
        replies = await asyncio.gather(*(self._request_peer(pk, msg) for pk in others))
        return sum(bool(r and r.get("ok")) for r in replies), len(others)

    async def sync_group(self, group_id: str) -> int:
        """Ask every other member for messages we don't have. Returns how many new ones we got."""
        group = self.groups.get(group_id)
        if not group or not self.groups.joined(group_id):
            return 0
        req = {"group": group_id, "from": self.ident.pubkey, "ts": time.time(),
               "have": list(self.groups.logs.get(group_id, {}))}
        msg = {"type": "group_sync", "req": req, "sig": self.ident.sign(canonical(req))}
        others = [m for m in group.members if m != self.ident.pubkey]
        new = 0
        for reply in await asyncio.gather(*(self._request_peer(pk, msg) for pk in others)):
            if not reply or not reply.get("ok"):
                continue
            self.groups.merge(GroupRecord.from_dict(reply.get("group") or {}))
            for envelope in reply.get("messages", []):
                if isinstance(envelope, dict) and (inner := self.groups.add_message(envelope)):
                    new += 1
                    self._emit_group(inner, live=False)
        return new

    async def sync_all(self) -> int:
        return sum(await asyncio.gather(*(self.sync_group(g.group_id) for g in self.groups.all())))

    # --- files -----------------------------------------------------------------------------------

    async def send_file(self, path: Path, to: list[str], group: str | None = None) -> tuple[str, dict, int]:
        """Offer a file to people (or every other member of a group). Returns (file_id, entry, offers delivered)."""
        if group:
            g = self.groups.get(group)
            if not g or not self.groups.joined(group):
                raise ValueError("not a member of that group")
            to = [m for m in g.members if m != self.ident.pubkey]
        file_id, entry = self.files.share(Path(path), to, group)
        fields = {"file_id": file_id, "name": entry["name"], "size": entry["size"], "sha256": entry["sha256"],
                  "key": entry["key"], "group": group}
        results = await asyncio.gather(*(self._send_sealed("file_offer", pk, fields) for pk in to))
        return file_id, entry, sum(1 for r, _ in results if r)

    async def download(self, file_id: str) -> Path | None:
        """Accept an offer: pull it chunk by chunk (resuming a partial download), verify, save."""
        offer = self.files.incoming.get(file_id)
        if not offer or offer.get("status") not in ("pending", "failed") or file_id in self._downloads:
            return None
        self._downloads.add(file_id)
        self.files.set_status(file_id, "downloading", error=None)
        try:
            offset = self.files.resume_offset(file_id)
            self.files.part_path(file_id).parent.mkdir(parents=True, exist_ok=True)
            self.files.part_path(file_id).touch()
            last_report = 0.0
            while offset < offer["size"]:
                for _ in range(FILE_RETRIES):
                    reply = await self._request_peer(offer["from"], {"type": "file_get", "file_id": file_id, "offset": offset})
                    if reply and reply.get("ok") and (n := self.files.write_chunk(file_id, offset, reply)):
                        offset += n
                        break
                    if reply and reply.get("error") in ("unknown file", "file no longer available"):
                        raise ValueError(f"the sender no longer has it ({reply['error']})")
                else:
                    raise ValueError("couldn't reach the sender; /accept again to resume")
                if time.time() - last_report > 0.5:
                    last_report = time.time()
                    self._emit("file_progress", file_id=file_id, offer=offer, done=offset)
            saved = self.files.finish(file_id)
            self.files.set_status(file_id, "done", saved=str(saved))
            self.log(f"saved {offer['name']} ({human_size(offer['size'])}) to {saved}")
            self._emit("file_done", file_id=file_id, offer=offer, saved=str(saved))
            return saved
        except (ValueError, OSError) as e:
            self.files.set_status(file_id, "failed", error=str(e))
            self.log(f"download of {offer['name']} failed: {e}")
            self._emit("file_failed", file_id=file_id, offer=offer, error=str(e))
            return None
        finally:
            self._downloads.discard(file_id)

    def decline_file(self, file_id: str) -> None:
        self.files.set_status(file_id, "declined")
        self.files.part_path(file_id).unlink(missing_ok=True)

    def _on_file_offer(self, inner: dict) -> None:
        sender = inner["from"]
        try:
            file_id, size = str(inner["file_id"]), int(inner["size"])
            offer = {"from": sender, "name": str(inner["name"]), "size": size, "sha256": str(inner["sha256"]),
                     "key": str(inner["key"]), "group": inner.get("group"), "ts": float(inner["ts"]), "status": "pending"}
        except (KeyError, TypeError, ValueError):
            return
        if size < 0:
            return
        if size > self.max_file_mb * 1024 * 1024:
            offer["status"] = "refused"
            offer["error"] = f"bigger than your {self.max_file_mb:g} MB limit (max_file_mb)"
        if not self.files.add_offer(file_id, offer):
            return  # already have it
        self._emit("file_offer", file_id=file_id, offer=offer, trusted=self.trust.trusted(sender))
        if (offer["status"] == "pending" and self.trust.trusted(sender)
                and size <= self.auto_download_mb * 1024 * 1024):
            self._spawn(self.download(file_id))

    def _emit_group(self, inner: dict, live: bool) -> None:
        if self.on_group_message:
            self.on_group_message(self.groups.get(inner["group"]), self.phonebook.get(inner["from"]),
                                  inner["text"], inner, live)

    # --- incoming --------------------------------------------------------------------------------

    async def handle(self, msg: dict) -> dict:
        kind = msg.get("type")
        if kind == "hello":
            rec = Record.from_dict(msg.get("record") or {})
            if rec and rec.pubkey == self.ident.pubkey:
                if self.phonebook.merge(rec):
                    self._adopt_device_addrs(rec)
            elif self.phonebook.merge(rec):
                self.log(f"met {rec.name} ({short(rec.pubkey)}) at {', '.join(rec.addrs)}")
                self._spawn(self._gossip(rec, exclude=set()))
                self._spawn(self._peer_online(rec.pubkey))
            return {"ok": True, "record": self.me.to_dict(), "records": [r.to_dict() for r in self.phonebook.all()]}

        if kind == "announce":
            rec = Record.from_dict(msg.get("record") or {})
            if rec and rec.pubkey == self.ident.pubkey:
                if self.phonebook.merge(rec):
                    self._adopt_device_addrs(rec)
            elif self.phonebook.merge(rec):
                self.log(f"{rec.name} ({short(rec.pubkey)}) is now at {', '.join(rec.addrs)}")
                self._spawn(self._gossip(rec, exclude={str(msg.get("from"))}))
                self._spawn(self._peer_online(rec.pubkey))  # a fresh record means they're (back) online
                self._seen(rec.pubkey)
            return {"ok": True}

        if kind == "lookup":
            rec = self.phonebook.get(str(msg.get("pubkey")))
            return {"ok": True, "record": rec.to_dict() if rec else None}

        if kind == "forward":
            to, inner = str(msg.get("to")), msg.get("msg")
            if not isinstance(inner, dict) or inner.get("type") == "forward":
                return {"ok": False, "error": "bad forward"}
            if to == self.ident.pubkey:
                return await self.handle(_clean(inner))
            sender, item, skip = msg.get("from"), msg.get("item"), msg.get("except_device")
            only = msg.get("only_device")
            return await self.hub.forward(to, _clean(inner), str(sender) if sender else None,
                                          str(item) if item else None, str(skip) if skip else None,
                                          str(only) if only else None, bool(msg.get("fanout")))

        if kind == "device_sync":
            got = self._open_snapshot(msg.get("sealed"))
            if not got or got[0]["device"] == self.device:
                return {"ok": False, "error": "not allowed"}
            head, state = got
            self._link()
            if state is None:  # a probe: just say where we stand
                return {"ok": True, "sealed": self._seal_snapshot(self._pack(), full=False)}
            self._merge_device_state(state, head["device"])
            return {"ok": True, "sealed": self._seal_snapshot(self._pack())}

        if kind == "vault_put":
            pubkey = str((msg.get("req") or {}).get("pubkey"))
            return self.vault.put(msg, known=pubkey in self.hub.attached or self.phonebook.get(pubkey) is not None)

        if kind == "vault_get":
            return self.vault.get(msg)

        if kind == "nudge":
            peer = str(msg.get("peer"))
            if peer != self.ident.pubkey:
                self._spawn(self._peer_online(peer))
            return {"ok": True}

        if kind == "owed":
            owed = msg.get("from") if isinstance(msg.get("from"), dict) else {}
            for sender, items in owed.items():
                if self.trust.blocked(str(sender)) or not isinstance(items, list):
                    continue
                seen = self._owed_seen.setdefault(str(sender), set())
                waiting = [i for i in items if isinstance(i, str) and i not in seen
                           and not (i.startswith("msg:") and self.dms.has(str(sender), i[4:]))]
                seen.update(waiting)  # the same IOU held by several hubs is reported once
                if waiting:
                    self.log(f"{self._name(str(sender))} has {len(waiting)} thing(s) for you; "
                             f"they'll arrive when {self._name(str(sender))} is online")
                    self._emit("owed", sender=str(sender), count=len(waiting))
            return {"ok": True}

        if kind == "dm_sync":
            req, sig = msg.get("req") or {}, str(msg.get("sig"))
            asker = str(req.get("from"))
            try:
                fresh = abs(time.time() - float(req.get("ts"))) <= SYNC_MAX_AGE
            except (TypeError, ValueError):
                fresh = False
            if (not fresh or req.get("to") != self.ident.pubkey or self.trust.blocked(asker)
                    or not verify(asker, canonical(req), sig)):
                return {"ok": False, "error": "not allowed"}
            missing = self.dms.missing_for(asker, {str(i) for i in req.get("have") or []})
            try:
                enc = await self._enc_key(asker)
            except ValueError:
                return {"ok": False, "error": "no key for you"}
            return {"ok": True, "sealed": seal(enc, canonical({"messages": missing}))}

        if kind == "punch":
            peer = str(msg.get("peer"))
            try:
                addrs = [(str(a[0]), int(a[1])) for a in (msg.get("addrs") or [])[:9]]
            except (TypeError, ValueError, IndexError):
                return {"ok": False, "error": "bad addresses"}
            # Harmless to honour from anyone: we only send a few pings, and a path only forms if the
            # far end answers signed as `peer`.
            if addrs and peer != self.ident.pubkey:
                self._spawn(self._answer_punch(peer, addrs))
            return {"ok": True, "addrs": self._candidates()}

        if kind == "signal":
            inner = self._open_sealed(msg)
            if not inner or not self.trust.trusted(inner["from"]):
                return {"ok": False, "error": "not allowed"}
            sender = inner["from"]
            self._seen(sender)
            if inner.get("signal") == "typing":
                self.typing[sender] = time.time() + TYPING_SHOWN
                self._emit("typing", pubkey=sender)
            elif inner.get("signal") == "read":
                try:
                    if self.dms.mark_read(sender, float(inner.get("upto"))):
                        self._emit("receipt", pubkey=sender)
                except (TypeError, ValueError):
                    pass
            return {"ok": True}

        if kind in ("msg", "file_offer", "group_handoff"):
            inner = self._open_sealed(msg)
            if not inner:
                return {"ok": False, "error": "bad message"}
            if self.trust.blocked(inner["from"]):
                return {"ok": True}  # dropped; don't tell them they're blocked
            self._seen(inner["from"])
            self.typing.pop(inner["from"], None)  # they finished typing
            if kind == "file_offer":
                self._on_file_offer(inner)
                return {"ok": True}
            if kind == "group_handoff":
                self._spawn(self._take_over(inner))
                return {"ok": True}
            inner.setdefault("id", legacy_id(inner))
            if not self.dms.add(inner["from"], inner):
                return {"ok": True}  # already have it (a retry, or an IOU redeemed via another hub)
            if self.on_message:
                self.on_message(self.phonebook.get(inner["from"]), str(inner["text"]), inner)
            return {"ok": True}

        if kind == "file_get":
            try:
                return self.files.read_chunk(str(msg.get("file_id")), int(msg.get("offset")))
            except (TypeError, ValueError):
                return {"ok": False, "error": "bad request"}

        if kind == "group_update":
            group = GroupRecord.from_dict(msg.get("group") or {})
            if not group:
                return {"ok": False, "error": "bad group"}
            known = self.groups.get(group.group_id)
            if not known and (self.ident.pubkey not in group.members or self.trust.blocked(group.owner)):
                return {"ok": False, "error": "not a member"}
            was_member = bool(known and self.ident.pubkey in known.members)
            if self.groups.merge(group):
                inviter = self._name(group.owner)
                if group.deleted:
                    if self.groups.status.get(group.group_id) in ("accepted", "pending"):
                        self.log(f"{inviter} deleted group {group.name}")
                        self._emit("group_deleted", group=group)
                    self.groups.purge(group.group_id)
                elif known and known.owner != group.owner and self.ident.pubkey in group.members:
                    self.log(f"#{group.name} has a new owner: {self._name(group.owner)}")
                elif self.ident.pubkey not in group.members:
                    if self.groups.status.get(group.group_id) == "accepted":
                        self.groups.set_status(group.group_id, "removed")
                        self.log(f"you were removed from group {group.name}")
                elif not was_member:
                    # An invite, not a membership: nothing shows until it's accepted.
                    self.groups.set_status(group.group_id, "pending")
                    self.log(f"{inviter} invited you to group {group.name} (/accept {group.name} or /decline {group.name})")
                    self._emit("group_invite", group=group, inviter=group.owner)
                elif known.history != group.history:
                    self.log(f"group {group.name} history is now {group.history}")
                elif known.members != group.members and self.groups.joined(group.group_id):
                    self.log(f"group {group.name} now has {len(group.members)} members")
            return {"ok": True}

        if kind == "group_leave":
            req, sig = msg.get("req") or {}, str(msg.get("sig"))
            group = self.groups.get(str(req.get("group")))
            member = str(req.get("from"))
            try:
                fresh = abs(time.time() - float(req.get("ts"))) <= LEAVE_MAX_AGE
            except (TypeError, ValueError):
                fresh = False
            if (not group or group.owner != self.ident.pubkey or member not in group.members or not fresh
                    or not verify(member, canonical(req), sig)):
                return {"ok": False, "error": "not allowed"}
            self.log(f"{self._name(member)} left group {group.name}")
            self._spawn(self.remove_member(group.group_id, member))
            return {"ok": True}

        if kind == "group_msg":
            group = GroupRecord.from_dict(msg.get("group") or {})
            if group and self.ident.pubkey in group.members:
                self.groups.merge(group)
            envelope = msg.get("envelope") or {}
            gid = str(envelope.get("group"))
            if self.groups.status.get(gid) in ("declined", "left"):
                return {"ok": False, "error": "not a member"}
            if inner := self.groups.add_message(envelope):
                if self.groups.joined(gid):  # pending invites keep messages for later, quietly
                    self._emit_group(inner, live=True)
                return {"ok": True}
            dup = self.groups.has(gid, str(envelope.get("id")))
            return {"ok": True} if dup else {"ok": False, "error": "rejected"}

        if kind == "group_sync":
            req, sig = msg.get("req") or {}, str(msg.get("sig"))
            group = self.groups.get(str(req.get("group")))
            try:
                fresh = abs(time.time() - float(req.get("ts"))) <= SYNC_MAX_AGE
            except (TypeError, ValueError):
                fresh = False
            if (not group or not fresh or req.get("from") not in group.members
                    or not self.groups.joined(group.group_id)
                    or not verify(str(req.get("from")), canonical(req), sig)):
                return {"ok": False, "error": "not allowed"}
            have = {str(i) for i in req.get("have") or []}
            messages = self.groups.missing_for(group.group_id, have, requester=str(req["from"]))
            return {"ok": True, "group": group.to_dict(), "messages": messages}

        return {"ok": False, "error": f"unknown type {kind!r}"}

    def _spawn(self, coro) -> None:
        task = asyncio.create_task(coro)
        self._tasks.add(task)
        task.add_done_callback(self._tasks.discard)
