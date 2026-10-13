"""Local history of direct messages, so conversations survive restarts (groups keep their own log).

Every message has an id (random, chosen by its author and covered by their signature). The store
keeps one copy per id and orders each conversation by timestamp, so it doesn't matter how often or
by which route a message arrives: a retry, an IOU redeemed through two hubs, or a chat sync.

Messages are kept with their signatures so they can be handed back during a chat sync and verified
by the other side. Stored decrypted on your own disk, like groups.json; it never leaves this machine
except to the other person in that conversation.
"""

import hashlib
import json
from pathlib import Path

HISTORY_LIMIT = 2000  # per conversation; oldest dropped first


def legacy_id(m: dict) -> str:
    """Stable id for messages stored before ids existed."""
    return "legacy-" + hashlib.sha256(f"{m.get('from')}|{m.get('ts')}|{m.get('text')}".encode()).hexdigest()[:20]


class DmStore:
    def __init__(self, path: Path | None = None):
        self.path = path
        self.convos: dict[str, list[dict]] = {}  # peer pubkey -> [{id, from, to, ts, text, sig}], by ts
        self._ids: dict[str, set[str]] = {}
        # What we know about messages we sent: peer -> {"delivered": [ids], "read_ts": newest read}
        self.receipts: dict[str, dict] = {}
        self.on_change = None  # called after any change (device sync hooks in here)
        if path and path.exists():
            data = json.loads(path.read_text(encoding="utf-8"))
            if "convos" in data:  # current format; older files were just {peer: [messages]}
                self.receipts = data.get("receipts", {})
                data = data["convos"]
            for peer, log in data.items():
                for m in log:
                    m.setdefault("id", legacy_id(m))
                self.convos[peer] = sorted(log, key=lambda m: m["ts"])
                self._ids[peer] = {m["id"] for m in log}

    def add(self, peer: str, message: dict) -> bool:
        """Store a message in the conversation with peer. Returns False if we already had it."""
        ids = self._ids.setdefault(peer, set())
        if message["id"] in ids:
            return False
        log = self.convos.setdefault(peer, [])
        entry = dict(message)  # keep every signed field (replies, reactions...) so sync can re-verify it
        log.append(entry)
        if len(log) > 1 and log[-2]["ts"] > entry["ts"]:  # arrived out of order (sync, late retry)
            log.sort(key=lambda m: m["ts"])
        ids.add(message["id"])
        if len(log) > HISTORY_LIMIT:
            for old in log[:-HISTORY_LIMIT]:
                ids.discard(old["id"])
            del log[:-HISTORY_LIMIT]
        self.save()
        self._changed()
        return True

    def has(self, peer: str, msg_id: str) -> bool:
        return msg_id in self._ids.get(peer, set())

    def ids(self, peer: str) -> list[str]:
        return [m["id"] for m in self.convos.get(peer, [])]

    def messages(self, peer: str) -> list[dict]:
        return self.convos.get(peer, [])

    def missing_for(self, peer: str, have: set[str], limit: int = 500) -> list[dict]:
        """Signed messages in this conversation that the other side doesn't have."""
        return [m for m in self.convos.get(peer, []) if m["id"] not in have and "sig" in m][-limit:]

    # --- receipts (for messages we sent) ---------------------------------------------------------

    def mark_delivered(self, peer: str, msg_id: str) -> None:
        r = self.receipts.setdefault(peer, {"delivered": [], "read_ts": 0})
        if msg_id not in r["delivered"]:
            r["delivered"] = (r["delivered"] + [msg_id])[-HISTORY_LIMIT:]
            self.save()
            self._changed()

    def mark_read(self, peer: str, upto_ts: float) -> bool:
        r = self.receipts.setdefault(peer, {"delivered": [], "read_ts": 0})
        if upto_ts <= r["read_ts"]:
            return False
        r["read_ts"] = upto_ts
        self.save()
        self._changed()
        return True

    def receipt(self, peer: str, message: dict) -> str | None:
        """"read", "delivered" or None (no news) for one of our messages to peer."""
        r = self.receipts.get(peer)
        if not r:
            return None
        if message["ts"] <= r["read_ts"]:
            return "read"
        return "delivered" if message["id"] in r["delivered"] else None

    def _changed(self) -> None:
        if self.on_change:
            self.on_change()

    # --- syncing between your own devices ---------------------------------------------------------

    def device_state(self, keep: int | None = None) -> dict:
        """Every conversation (the newest `keep` messages of each, if given) and our receipts, in a
        fixed order, so two devices holding the same data produce the same bytes."""
        def newest(log):
            log = sorted(log, key=lambda m: (m["ts"], m["id"]))
            return log[-keep:] if keep else log

        return {"convos": {peer: newest(log) for peer, log in sorted(self.convos.items()) if log},
                "receipts": {peer: {"delivered": sorted(r.get("delivered", [])), "read_ts": r.get("read_ts", 0)}
                             for peer, r in sorted(self.receipts.items())}}

    def merge_device_state(self, state: dict, me: str) -> int:
        """Add what another device of ours has that we lack. The state arrives inside a message signed
        by our own key, so it is as trustworthy as our own disk; we only check that each message
        belongs to a conversation between us and `peer`. Returns how many messages were new."""
        new = 0
        for peer, log in ((state or {}).get("convos") or {}).items():
            if not isinstance(log, list) or peer == me:
                continue
            for m in log:
                try:
                    if (isinstance(m, dict) and {m["from"], m["to"]} == {me, peer} and isinstance(m["ts"], (int, float))
                            and m["id"] not in self._ids.get(peer, set())):
                        self._add_quiet(peer, m)
                        new += 1
                except (KeyError, TypeError):
                    continue
        changed = False
        for peer, r in ((state or {}).get("receipts") or {}).items():
            try:
                mine = self.receipts.setdefault(peer, {"delivered": [], "read_ts": 0})
                extra = [i for i in r.get("delivered", []) if isinstance(i, str) and i not in mine["delivered"]]
                if extra:
                    mine["delivered"] = (mine["delivered"] + extra)[-HISTORY_LIMIT:]
                    changed = True
                if float(r.get("read_ts", 0)) > mine["read_ts"]:
                    mine["read_ts"] = float(r["read_ts"])
                    changed = True
            except (TypeError, ValueError, AttributeError):
                continue
        if new or changed:
            self.save()
        return new

    def _add_quiet(self, peer: str, message: dict) -> None:
        """add() without saving or notifying (a merge saves once, at the end)."""
        ids = self._ids.setdefault(peer, set())
        log = self.convos.setdefault(peer, [])
        log.append(dict(message))
        ids.add(message["id"])
        log.sort(key=lambda m: m["ts"])
        if len(log) > HISTORY_LIMIT:
            for old in log[:-HISTORY_LIMIT]:
                ids.discard(old["id"])
            del log[:-HISTORY_LIMIT]

    def save(self) -> None:
        if self.path:
            self.path.parent.mkdir(parents=True, exist_ok=True)
            self.path.write_text(json.dumps({"convos": self.convos, "receipts": self.receipts}), encoding="utf-8")
