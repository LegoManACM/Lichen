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
        if path and path.exists():
            for peer, log in json.loads(path.read_text(encoding="utf-8")).items():
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
        entry = {k: message[k] for k in ("id", "from", "to", "ts", "text", "sig") if k in message}
        log.append(entry)
        if len(log) > 1 and log[-2]["ts"] > entry["ts"]:  # arrived out of order (sync, late retry)
            log.sort(key=lambda m: m["ts"])
        ids.add(message["id"])
        if len(log) > HISTORY_LIMIT:
            for old in log[:-HISTORY_LIMIT]:
                ids.discard(old["id"])
            del log[:-HISTORY_LIMIT]
        self.save()
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

    def save(self) -> None:
        if self.path:
            self.path.parent.mkdir(parents=True, exist_ok=True)
            self.path.write_text(json.dumps(self.convos), encoding="utf-8")
