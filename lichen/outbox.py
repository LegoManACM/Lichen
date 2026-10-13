"""Things you sent that haven't reached their recipient yet (they were offline).

Kept on your own disk, already sealed to the recipient, and retried when they come back: when their
fresh phonebook record reaches you, when their hub nudges you, every minute, and on /sync. The hub
only needs to remember "you owe them something" (see relay.py), not the message itself.

If you use several devices they share this queue (device sync), so whichever of them is on when the
recipient returns can deliver it. An item that got through is remembered for a month, so the other
devices drop their copy instead of delivering it again.
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
        self.done: dict[str, float] = {}  # id -> when it got through (or was refused)
        self.on_change = None  # called after any change (device sync hooks in here)
        if path and path.exists():
            data = json.loads(path.read_text(encoding="utf-8"))
            if isinstance(data.get("items"), dict) and isinstance(data.get("done"), dict):
                self.items, self.done = data["items"], data["done"]
            else:  # older files were just {id: item}
                self.items = data
        self.expire()

    def add(self, to: str, msg: dict, item_id: str | None = None) -> str:
        """Keep msg until it reaches `to`. The item id is what hubs record in their IOUs, so the
        recipient can tell that IOUs at several hubs are for the same thing."""
        item_id = item_id or b64(os.urandom(9))
        if item_id not in self.items and item_id not in self.done:
            self.items[item_id] = {"to": to, "msg": msg, "ts": time.time()}
            self.save()
            self._changed()
        return item_id

    def for_peer(self, to: str | None = None) -> list[tuple[str, dict]]:
        return [(i, it) for i, it in self.items.items() if to is None or it["to"] == to]

    def peers(self) -> set[str]:
        return {it["to"] for it in self.items.values()}

    def remove(self, item_id: str) -> None:
        if self.items.pop(item_id, None) is not None:
            self.done[item_id] = time.time()
            self.save()
            self._changed()

    def expire(self) -> None:
        cutoff = time.time() - MAX_AGE
        stale = [i for i, it in self.items.items() if it["ts"] < cutoff]
        for i in stale:
            del self.items[i]
        old = [i for i, ts in self.done.items() if ts < cutoff]
        for i in old:
            del self.done[i]
        if stale or old:
            self.save()

    # --- syncing between your own devices ---------------------------------------------------------

    def device_state(self) -> dict:
        return {"items": dict(sorted(self.items.items())), "done": dict(sorted(self.done.items()))}

    def merge_device_state(self, state: dict) -> int:
        """Take on what our other devices still owe, and drop what got through anywhere.
        Returns how many items were added or dropped here."""
        changed, dirty = 0, False
        for item_id, ts in ((state or {}).get("done") or {}).items():
            try:
                ts = float(ts)
            except (TypeError, ValueError):
                continue
            if ts < self.done.get(item_id, float("inf")):  # two devices may both have delivered it: keep the
                self.done[item_id] = ts  # earlier time, so they end up with the same record
                dirty = True
            if self.items.pop(item_id, None) is not None:
                changed += 1
        for item_id, item in ((state or {}).get("items") or {}).items():
            if (item_id not in self.items and item_id not in self.done and isinstance(item, dict)
                    and isinstance(item.get("msg"), dict) and isinstance(item.get("to"), str)
                    and isinstance(item.get("ts"), (int, float))):
                self.items[item_id] = item
                changed += 1
        if changed or dirty:
            self.save()
        return changed

    def _changed(self) -> None:
        if self.on_change:
            self.on_change()

    def save(self) -> None:
        if self.path:
            self.path.parent.mkdir(parents=True, exist_ok=True)
            self.path.write_text(json.dumps({"items": self.items, "done": self.done}), encoding="utf-8")
