"""A hub's drop box for your devices' sync snapshots, so devices don't have to be on at the same time.

Two devices of yours (same key, see devices.py) keep each other up to date by swapping their whole
state. When both are on, they do it directly (node.py). When one isn't, the other leaves its snapshot
here, and the sleeper picks it up the next time it starts.

What a hub sees: that some key keeps a blob of N bytes, uploaded by device X at time T. The blob is
sealed to the owner's own encryption key and signed by their identity key, so the hub can neither
read it nor change it undetected. Hubs hold at most one snapshot per device (a new one replaces the
old), cap how much each identity and the whole vault may use, and forget a device that hasn't
uploaded for 90 days. Only identities the hub knows about (attached to it, or in its phonebook) can
store anything, and `vault_mb: 0` makes a hub refuse them all.

  vault_put {req: {pubkey, device, rev, hash, ts}, sig, blob}  -> {ok}
  vault_get {req: {pubkey, ts, have: {device: rev}}, sig}      -> {ok, snaps: [{device, rev, blob}]}

Requests are signed by the identity key; `rev` only goes up per device, so replaying an old upload
changes nothing, and `have` lets a device ask for just the snapshots it hasn't merged yet.
"""

import hashlib
import json
import time
from pathlib import Path

from .identity import canonical, verify

DEFAULT_MB = 256  # a hub's whole vault
PER_IDENTITY_MB = 24  # one identity's share of it
MAX_BLOB = 3_500_000  # characters per snapshot; the wire's 4 MiB line limit leaves room for the envelope
MAX_DEVICES = 8  # per identity
MAX_AGE = 90 * 24 * 3600
REQUEST_MAX_AGE = 300  # seconds a signed request stays valid


class Vault:
    def __init__(self, path: Path | None = None, limit_mb: float = DEFAULT_MB):
        self.path = path
        self.limit = int(limit_mb * 1024 * 1024)
        self.snaps: dict[str, dict[str, dict]] = {}  # pubkey -> device -> {rev, ts, size, blob}
        if path and path.exists():
            data = json.loads(path.read_text(encoding="utf-8"))
            self.snaps = data if isinstance(data, dict) else {}
        self._expire()

    @property
    def enabled(self) -> bool:
        return self.limit > 0

    def used(self, pubkey: str | None = None) -> int:
        return sum(s["size"] for pk, devs in self.snaps.items() if pubkey in (None, pk) for s in devs.values())

    @staticmethod
    def _fresh(req: dict) -> bool:
        try:
            return abs(time.time() - float(req.get("ts"))) <= REQUEST_MAX_AGE
        except (TypeError, ValueError):
            return False

    def put(self, msg: dict, known: bool) -> dict:
        """known: the hub has heard of this identity (it's attached, or in the phonebook)."""
        req, sig, blob = msg.get("req") or {}, str(msg.get("sig")), msg.get("blob")
        pubkey, device = str(req.get("pubkey")), str(req.get("device") or "default")
        if not self.enabled:
            return {"ok": False, "error": "this hub doesn't store device snapshots"}
        if (not isinstance(blob, str) or not self._fresh(req) or not verify(pubkey, canonical(req), sig)
                or req.get("hash") != hashlib.sha256(blob.encode()).hexdigest()):
            return {"ok": False, "error": "bad request"}
        if len(blob) > MAX_BLOB:
            return {"ok": False, "error": "snapshot too big"}
        if not known:
            return {"ok": False, "error": "unknown identity"}
        try:
            rev = int(req["rev"])
        except (KeyError, TypeError, ValueError):
            return {"ok": False, "error": "bad request"}
        self._expire()
        mine = self.snaps.setdefault(pubkey, {})
        old = mine.get(device)
        if old and old["rev"] >= rev:
            return {"ok": True, "stale": True}  # a replay, or an older upload racing a newer one
        if old is None and len(mine) >= MAX_DEVICES:
            return {"ok": False, "error": "too many devices"}
        freed = old["size"] if old else 0
        if (self.used(pubkey) - freed + len(blob) > PER_IDENTITY_MB * 1024 * 1024
                or self.used() - freed + len(blob) > self.limit):
            return {"ok": False, "error": "vault full"}
        mine[device] = {"rev": rev, "ts": time.time(), "size": len(blob), "blob": blob}
        self._save()
        return {"ok": True}

    def get(self, msg: dict) -> dict:
        req, sig = msg.get("req") or {}, str(msg.get("sig"))
        pubkey = str(req.get("pubkey"))
        if not self._fresh(req) or not verify(pubkey, canonical(req), sig):
            return {"ok": False, "error": "bad request"}
        have = req.get("have") if isinstance(req.get("have"), dict) else {}
        snaps = []
        for device, s in sorted(self.snaps.get(pubkey, {}).items()):
            try:
                if s["rev"] > int(have.get(device, 0)) and device != req.get("device"):
                    snaps.append({"device": device, "rev": s["rev"], "blob": s["blob"]})
            except (TypeError, ValueError):
                continue
        return {"ok": True, "snaps": snaps}

    def _expire(self) -> None:
        cutoff = time.time() - MAX_AGE
        for pk in list(self.snaps):
            self.snaps[pk] = {d: s for d, s in self.snaps[pk].items() if s["ts"] >= cutoff}
            if not self.snaps[pk]:
                del self.snaps[pk]

    def _save(self) -> None:
        if self.path:
            self.path.parent.mkdir(parents=True, exist_ok=True)
            self.path.write_text(json.dumps(self.snaps), encoding="utf-8")
