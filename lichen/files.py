"""File sharing: offers, and the chunked, encrypted, resumable transfer behind them.

Flow:
  1. The sender hashes the file and makes up a random file id and a random 32-byte key, and sends
     each recipient a `file_offer` sealed to them (name, size, sha256, key; see node.py).
  2. Nothing moves until the recipient accepts. Offers from blocked senders are dropped, offers from
     anyone not trusted wait as requests, and offers over the size limit are refused.
  3. The recipient pulls the file with `file_get {file_id, offset}` requests. The sender answers with
     that chunk encrypted under the file key (the AAD binds it to the file id and offset), so relays
     and anyone guessing the id only ever see ciphertext.
  4. Chunks are appended to a .part file, so an interrupted download resumes where it stopped. The
     finished file is checked against the sha256 from the offer before it's moved into downloads/.
"""

import hashlib
import json
import os
import re
import time
from pathlib import Path

from .crypto import decrypt, encrypt
from .identity import b64, unb64

CHUNK = 48 * 1024  # bytes per file_get; fits a relay message easily and ~130 UDP datagrams
DEFAULT_MAX_MB = 100


def file_aad(file_id: str, offset: int) -> bytes:
    return f"lichen-file:{file_id}:{offset}".encode()


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def safe_name(name: str) -> str:
    """A filename that can't escape the downloads folder or upset Windows."""
    name = Path(name.replace("\\", "/")).name  # drop any directories
    name = re.sub(r'[<>:"/\\|?*\x00-\x1f]', "_", name).strip(" .")
    if name.upper().split(".")[0] in {"CON", "PRN", "AUX", "NUL", *(f"COM{i}" for i in range(1, 10)),
                                     *(f"LPT{i}" for i in range(1, 10))}:
        name = "_" + name
    return name[:180] or "file"


def human_size(n: int) -> str:
    size = float(n)
    for unit in ("B", "KB", "MB", "GB"):
        if size < 1024 or unit == "GB":
            return f"{size:.0f} {unit}" if unit == "B" else f"{size:.1f} {unit}"
        size /= 1024
    return f"{n} B"


class FileStore:
    def __init__(self, data_dir: Path | None, downloads: Path | None = None):
        self.path = data_dir / "files.json" if data_dir else None
        self.partial_dir = data_dir / "partial" if data_dir else None
        self.downloads = downloads or (data_dir / "downloads" if data_dir else Path("downloads"))
        # file_id -> {path, name, size, sha256, key, to: [pubkeys], group, ts}
        self.outgoing: dict[str, dict] = {}
        # file_id -> {from, name, size, sha256, key, group, ts, status, saved, error}
        #   status: pending | downloading | done | declined | failed | refused
        self.incoming: dict[str, dict] = {}
        self.on_change = None  # called after offers change (device sync hooks in here)
        if self.path and self.path.exists():
            data = json.loads(self.path.read_text(encoding="utf-8"))
            self.outgoing, self.incoming = data.get("outgoing", {}), data.get("incoming", {})
            for offer in self.incoming.values():
                if offer.get("status") == "downloading":  # interrupted by a restart: resumable
                    offer["status"] = "pending"

    # --- sending -----------------------------------------------------------------------------------

    def share(self, path: Path, to: list[str], group: str | None = None) -> tuple[str, dict]:
        path = path.expanduser().resolve()
        if not path.is_file():
            raise ValueError(f"no such file: {path}")
        file_id = b64(os.urandom(12))
        entry = {"path": str(path), "name": path.name, "size": path.stat().st_size,
                 "sha256": sha256_file(path), "key": b64(os.urandom(32)), "to": to, "group": group,
                 "ts": time.time()}
        self.outgoing[file_id] = entry
        self.save()
        return file_id, entry

    def read_chunk(self, file_id: str, offset: int) -> dict:
        entry = self.outgoing.get(file_id)
        if not entry:
            return {"ok": False, "error": "unknown file"}
        if offset < 0 or offset > entry["size"]:
            return {"ok": False, "error": "bad offset"}
        try:
            with open(entry["path"], "rb") as f:
                f.seek(offset)
                data = f.read(CHUNK)
        except OSError:
            return {"ok": False, "error": "file no longer available"}
        return {"ok": True, **encrypt(unb64(entry["key"]), data, file_aad(file_id, offset))}

    # --- receiving ---------------------------------------------------------------------------------

    def add_offer(self, file_id: str, offer: dict) -> bool:
        if file_id in self.incoming:
            return False
        offer.setdefault("status_ts", time.time())
        self.incoming[file_id] = offer
        self.save()
        self._changed()
        return True

    def part_path(self, file_id: str) -> Path:
        return (self.partial_dir or Path(".")) / f"{safe_name(file_id)}.part"

    def resume_offset(self, file_id: str) -> int:
        part = self.part_path(file_id)
        if not part.exists():
            return 0
        size = part.stat().st_size - part.stat().st_size % CHUNK
        with part.open("r+b") as f:  # drop any half-written tail chunk
            f.truncate(size)
        return size

    def write_chunk(self, file_id: str, offset: int, box: dict) -> int | None:
        """Decrypt and append a chunk. Returns bytes written, or None if it doesn't decrypt."""
        offer = self.incoming[file_id]
        data = decrypt(unb64(offer["key"]), box, file_aad(file_id, offset))
        if data is None:
            return None
        part = self.part_path(file_id)
        part.parent.mkdir(parents=True, exist_ok=True)
        with part.open("ab") as f:
            f.write(data)
        return len(data)

    def finish(self, file_id: str) -> Path:
        """Verify the hash and move the file into downloads/. Raises ValueError on a mismatch."""
        offer = self.incoming[file_id]
        part = self.part_path(file_id)
        if part.stat().st_size != offer["size"] or sha256_file(part) != offer["sha256"]:
            part.unlink(missing_ok=True)
            raise ValueError("downloaded file doesn't match its hash (corrupted or tampered); discarded")
        self.downloads.mkdir(parents=True, exist_ok=True)
        name = safe_name(offer["name"])
        dest, n = self.downloads / name, 1
        while dest.exists():
            stem, suffix = os.path.splitext(name)
            dest, n = self.downloads / f"{stem} ({n}){suffix}", n + 1
        os.replace(part, dest)
        return dest

    def set_status(self, file_id: str, status: str, **extra) -> None:
        self.incoming[file_id].update(status=status, status_ts=time.time(), **extra)
        self.save()
        if status != "downloading":  # progress isn't worth telling our other devices about
            self._changed()

    def _changed(self) -> None:
        if self.on_change:
            self.on_change()

    # --- syncing between your own devices ---------------------------------------------------------

    def device_state(self) -> dict:
        """Offers made to us. What was downloaded where stays on each device (every device downloads its
        own copy), so a download elsewhere counts as still waiting here; a decline travels."""
        out = {}
        for fid, o in sorted(self.incoming.items()):
            status = o.get("status")
            keep = {k: v for k, v in o.items() if k not in ("saved", "error", "status", "status_ts")}
            out[fid] = {**keep, "status": "declined" if status == "declined" else "pending",
                        "status_ts": o.get("status_ts", 0)}
        return {"incoming": out}

    def merge_device_state(self, state: dict) -> int:
        """Learn about offers our other devices received, and about ones they declined."""
        n = 0
        for fid, o in ((state or {}).get("incoming") or {}).items():
            try:
                offer = {"from": str(o["from"]), "name": str(o["name"]), "size": int(o["size"]),
                         "sha256": str(o["sha256"]), "key": str(o["key"]), "group": o.get("group"),
                         "ts": float(o["ts"]), "status": str(o.get("status", "pending")),
                         "status_ts": float(o.get("status_ts", 0))}
            except (KeyError, TypeError, ValueError):
                continue
            mine = self.incoming.get(fid)
            if mine is None:
                self.incoming[fid] = offer
                n += 1
            elif (offer["status"] == "declined" and mine.get("status") in ("pending", "failed")
                  and offer["status_ts"] > mine.get("status_ts", 0)):
                mine.update(status="declined", status_ts=offer["status_ts"])
                self.part_path(fid).unlink(missing_ok=True)
                n += 1
        if n:
            self.save()
        return n

    def find_incoming(self, query: str, statuses=("pending", "failed")) -> list[tuple[str, dict]]:
        q = query.lower()
        return [(fid, o) for fid, o in self.incoming.items()
                if o.get("status") in statuses and (fid.lower().startswith(q) or o["name"].lower() == q
                                                     or o["name"].lower().startswith(q))]

    def save(self) -> None:
        if self.path:
            self.path.parent.mkdir(parents=True, exist_ok=True)
            self.path.write_text(json.dumps({"outgoing": self.outgoing, "incoming": self.incoming}, indent=1),
                                 encoding="utf-8")
