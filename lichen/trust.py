"""Who you accept things from, and what you call them.

Every key is trusted, unknown (the default) or blocked:
- trusted: your contacts. Shown in your sidebar; their file offers notify you (and can auto-download).
- unknown: anyone else on the network. Their messages, file offers and group invites wait as requests.
- blocked: everything from them is dropped.

You trust someone by messaging them, accepting their request, or /trust.

Names people pick for themselves aren't unique (anyone can call themselves "alice"), so you can also:
- give a key your own nickname (/nick): it's what *you* see, whatever name they or anyone else uses;
- mark a key verified (/verify) after comparing safety codes with that person over another channel.
"""

import json
import time
from pathlib import Path

LEVELS = ("trusted", "unknown", "blocked")


class TrustStore:
    def __init__(self, path: Path | None = None):
        self.path = path
        self.levels: dict[str, str] = {}
        self.nicknames: dict[str, str] = {}
        self.verified: dict[str, float] = {}  # key -> when you verified it
        self.changed: dict[str, float] = {}  # key -> when anything about it last changed (for device sync)
        self.on_change = None
        if path and path.exists():
            data = json.loads(path.read_text(encoding="utf-8"))
            if "levels" not in data:  # format from before nicknames: just {key: level}
                data = {"levels": data}
            self.levels = {k: v for k, v in data.get("levels", {}).items() if v in LEVELS}
            self.nicknames = dict(data.get("nicknames", {}))
            self.verified = dict(data.get("verified", {}))
            self.changed = dict(data.get("changed", {}))

    def get(self, pubkey: str) -> str:
        return self.levels.get(pubkey, "unknown")

    def set(self, pubkey: str, level: str) -> None:
        if level not in LEVELS:
            raise ValueError(f"trust level must be one of {', '.join(LEVELS)}")
        if level == "unknown":
            self.levels.pop(pubkey, None)
        else:
            self.levels[pubkey] = level
        self._touch(pubkey)

    def trusted(self, pubkey: str) -> bool:
        return self.get(pubkey) == "trusted"

    def blocked(self, pubkey: str) -> bool:
        return self.get(pubkey) == "blocked"

    def nickname(self, pubkey: str) -> str | None:
        return self.nicknames.get(pubkey)

    def set_nickname(self, pubkey: str, nickname: str | None) -> None:
        if nickname:
            if any(c.isspace() for c in nickname) or "#" in nickname:
                raise ValueError("nicknames can't contain spaces or #")
            taken = [k for k, n in self.nicknames.items() if n.lower() == nickname.lower() and k != pubkey]
            if taken:
                raise ValueError(f"you already call someone else {nickname!r}")
            self.nicknames[pubkey] = nickname
        else:
            self.nicknames.pop(pubkey, None)
        self._touch(pubkey)

    def is_verified(self, pubkey: str) -> bool:
        return pubkey in self.verified

    def set_verified(self, pubkey: str, when: float | None) -> None:
        if when:
            self.verified[pubkey] = when
        else:
            self.verified.pop(pubkey, None)
        self._touch(pubkey)

    def _touch(self, pubkey: str) -> None:
        self.changed[pubkey] = time.time()
        self.save()
        if self.on_change:
            self.on_change()

    def device_state(self) -> dict:
        keys = set(self.levels) | set(self.nicknames) | set(self.verified) | set(self.changed)
        return {k: {"level": self.get(k), "nickname": self.nicknames.get(k), "verified": self.verified.get(k),
                    "changed": self.changed.get(k, 0)} for k in keys}

    def merge_device_state(self, state: dict) -> int:
        """Adopt another device's view of each key it changed more recently. Returns how many."""
        n = 0
        for k, e in (state or {}).items():
            try:
                if float(e.get("changed", 0)) <= self.changed.get(k, 0) or e.get("level") not in LEVELS:
                    continue
            except (TypeError, ValueError, AttributeError):
                continue
            if e["level"] == "unknown":
                self.levels.pop(k, None)
            else:
                self.levels[k] = e["level"]
            if e.get("nickname"):
                self.nicknames[k] = str(e["nickname"])
            else:
                self.nicknames.pop(k, None)
            if e.get("verified"):
                self.verified[k] = float(e["verified"])
            else:
                self.verified.pop(k, None)
            self.changed[k] = float(e["changed"])
            n += 1
        if n:
            self.save()
        return n

    def save(self) -> None:
        if self.path:
            self.path.parent.mkdir(parents=True, exist_ok=True)
            self.path.write_text(json.dumps({"levels": self.levels, "nicknames": self.nicknames,
                                             "verified": self.verified, "changed": self.changed}, indent=1),
                                 encoding="utf-8")
