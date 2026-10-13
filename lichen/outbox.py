"""Things you sent that haven't reached their recipient yet (they were offline).

Kept on your own disk, already sealed to the recipient, and retried when they come back: when their
fresh phonebook record reaches you, when their hub nudges you, every minute, and on /sync. The hub
only needs to remember "you owe them something" (see relay.py), not the message itself.
"""

import json
import os
import time
from pathlib import Path

from .identity import b64

MAX_AGE = 30 * 24 * 3600  # give up after a month


class Outbox:
    def __init__(self, path: Path | None = None):
        self.path = path
        self.items: dict[str, dict] = {}  # id -> {to, msg, ts}
        if path and path.exists():
            self.items = json.loads(path.read_text(encoding="utf-8"))
        self.expire()

    def add(self, to: str, msg: dict, item_id: str | None = None) -> str:
        """Keep msg until it reaches `to`. The item id is what hubs record in their IOUs, so the
        recipient can tell that IOUs at several hubs are for the same thing."""
        item_id = item_id or b64(os.urandom(9))
        if item_id not in self.items:
            self.items[item_id] = {"to": to, "msg": msg, "ts": time.time()}
            self.save()
        return item_id

    def for_peer(self, to: str | None = None) -> list[tuple[str, dict]]:
        return [(i, it) for i, it in self.items.items() if to is None or it["to"] == to]

    def peers(self) -> set[str]:
        return {it["to"] for it in self.items.values()}

    def remove(self, item_id: str) -> None:
        if self.items.pop(item_id, None) is not None:
            self.save()

    def expire(self) -> None:
        cutoff = time.time() - MAX_AGE
        stale = [i for i, it in self.items.items() if it["ts"] < cutoff]
        for i in stale:
            del self.items[i]
        if stale:
            self.save()

    def save(self) -> None:
        if self.path:
            self.path.parent.mkdir(parents=True, exist_ok=True)
            self.path.write_text(json.dumps(self.items), encoding="utf-8")
