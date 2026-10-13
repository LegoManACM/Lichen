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
from pathlib import Path

LEVELS = ("trusted", "unknown", "blocked")


class TrustStore:
    def __init__(self, path: Path | None = None):
        self.path = path
        self.levels: dict[str, str] = {}
        self.nicknames: dict[str, str] = {}
        self.verified: dict[str, float] = {}  # key -> when you verified it
        if path and path.exists():
            data = json.loads(path.read_text(encoding="utf-8"))
            if "levels" not in data:  # format from before nicknames: just {key: level}
                data = {"levels": data}
            self.levels = {k: v for k, v in data.get("levels", {}).items() if v in LEVELS}
            self.nicknames = dict(data.get("nicknames", {}))
            self.verified = dict(data.get("verified", {}))

    def get(self, pubkey: str) -> str:
        return self.levels.get(pubkey, "unknown")

    def set(self, pubkey: str, level: str) -> None:
        if level not in LEVELS:
            raise ValueError(f"trust level must be one of {', '.join(LEVELS)}")
        if level == "unknown":
            self.levels.pop(pubkey, None)
        else:
            self.levels[pubkey] = level
        self.save()

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
        self.save()

    def is_verified(self, pubkey: str) -> bool:
        return pubkey in self.verified

    def set_verified(self, pubkey: str, when: float | None) -> None:
        if when:
            self.verified[pubkey] = when
        else:
            self.verified.pop(pubkey, None)
        self.save()

    def save(self) -> None:
        if self.path:
            self.path.parent.mkdir(parents=True, exist_ok=True)
            self.path.write_text(json.dumps({"levels": self.levels, "nicknames": self.nicknames,
                                             "verified": self.verified}, indent=1), encoding="utf-8")
