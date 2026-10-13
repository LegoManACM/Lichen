"""Who you accept things from. Every key is trusted, unknown (the default) or blocked.

- trusted: your contacts. Shown in your sidebar; their file offers notify you (and can auto-download).
- unknown: anyone else on the network. Their messages, file offers and group invites wait as requests.
- blocked: everything from them is dropped.

You trust someone by messaging them, accepting their request, or /trust.
"""

import json
from pathlib import Path

LEVELS = ("trusted", "unknown", "blocked")


class TrustStore:
    def __init__(self, path: Path | None = None):
        self.path = path
        self.levels: dict[str, str] = {}
        if path and path.exists():
            self.levels = {k: v for k, v in json.loads(path.read_text(encoding="utf-8")).items() if v in LEVELS}

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

    def save(self) -> None:
        if self.path:
            self.path.parent.mkdir(parents=True, exist_ok=True)
            self.path.write_text(json.dumps(self.levels, indent=1), encoding="utf-8")
