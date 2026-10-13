"""Every node keeps a phonebook of verified records, newest seq wins."""

import json
from pathlib import Path

from .records import Record


class Phonebook:
    def __init__(self, path: Path | None = None):
        self.path = path
        self.records: dict[str, Record] = {}
        if path and path.exists():
            for d in json.loads(path.read_text()):
                rec = Record.from_dict(d)
                if rec:
                    self.records[rec.pubkey] = rec

    def merge(self, rec: Record | None) -> bool:
        """Store rec if it is valid and newer than what we have. Returns True if it changed anything."""
        if rec is None or not rec.valid():
            return False
        cur = self.records.get(rec.pubkey)
        if cur and cur.seq >= rec.seq:
            return False
        self.records[rec.pubkey] = rec
        self.save()
        return True

    def get(self, pubkey: str) -> Record | None:
        return self.records.get(pubkey)

    def find(self, query: str) -> list[Record]:
        """Match by exact name, or by public key prefix."""
        return [r for r in self.records.values() if r.name == query or r.pubkey.startswith(query)]

    def all(self) -> list[Record]:
        return list(self.records.values())

    def save(self) -> None:
        if self.path:
            self.path.parent.mkdir(parents=True, exist_ok=True)
            self.path.write_text(json.dumps([r.to_dict() for r in self.records.values()], indent=1))
