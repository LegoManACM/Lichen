"""Group chats, end-to-end encrypted.

- A group record is a member list signed by the owner. Its id is hash(owner pubkey, nonce), so only
  the owner can publish records for it; newer seq wins, exactly like contact records.
- Every change (create, add, remove, history setting) starts a new *epoch* with a fresh random key.
  The record carries a `keybox`: for each member, the epoch keys they're entitled to, sealed to that
  member's X25519 key. Removed members never get the new key, so they can't read what follows.
- A member is entitled to the keys of epochs that started after they joined, plus, while the group's
  history is "shared", the keys of every epoch that was shared. Private-era messages are therefore
  unreadable to later joiners, not just withheld.
- Messages are signed by their author, then encrypted with the epoch key. Peers store and sync the
  ciphertext ("outer"); the id is a hash of it, which is what catch-up compares.
"""

import hashlib
import json
import os
import time
from dataclasses import dataclass, field
from pathlib import Path

from .crypto import EncKey, decrypt, encrypt, open_sealed, seal
from .identity import Identity, b64, canonical, unb64, verify

FORMAT_VERSION = 2  # groups.json from before encryption is ignored
HISTORY_MODES = ("private", "shared")  # private: new members only see messages from after they joined
SYNC_LIMIT = 1000  # most recent messages a peer will hand over in one sync


def group_id_for(owner: str, nonce: str) -> str:
    return b64(hashlib.sha256(f"{owner}:{nonce}".encode()).digest())[:22]


@dataclass
class GroupRecord:
    group_id: str
    nonce: str
    name: str
    owner: str
    members: list[str]
    seq: int
    settings: dict
    joined: dict[str, float]  # member pubkey -> when the owner (last) added them, owner's clock
    epoch: int
    keybox: dict[str, dict]  # member pubkey -> sealed {epoch: key}
    sig: str = field(default="", repr=False)
    # Ownership can be handed over. The group id stays hash(founder, nonce); each transfer is
    # {"to": new owner, "sig": previous owner's signature over transfer_body(...)}. Both fields are
    # left out of the signed body while there are no transfers, so older records still verify.
    founder: str | None = None
    transfers: list[dict] = field(default_factory=list)

    def body(self) -> dict:
        body = {"group_id": self.group_id, "nonce": self.nonce, "name": self.name, "owner": self.owner,
                "members": self.members, "seq": self.seq, "settings": self.settings, "joined": self.joined,
                "epoch": self.epoch, "keybox": self.keybox}
        if self.transfers:
            body["founder"] = self.founder
            body["transfers"] = self.transfers
        return body

    @property
    def history(self) -> str:
        return self.settings.get("history", "private")

    @property
    def deleted(self) -> bool:
        return bool(self.settings.get("deleted"))

    @property
    def founder_key(self) -> str:
        return self.founder or self.owner

    def joined_at(self, pubkey: str) -> float:
        return self.joined.get(pubkey, float("inf"))

    def chain_ok(self) -> bool:
        """Each transfer is signed by the owner before it, starting from the founder, ending at owner."""
        prev = self.founder_key
        for i, t in enumerate(self.transfers):
            if not isinstance(t, dict) or not verify(prev, transfer_body(self.group_id, str(t.get("to")), i),
                                                     str(t.get("sig"))):
                return False
            prev = str(t["to"])
        return prev == self.owner

    def valid(self) -> bool:
        return (self.group_id == group_id_for(self.founder_key, self.nonce) and self.chain_ok()
                and verify(self.owner, canonical(self.body()), self.sig))

    def to_dict(self) -> dict:
        return {**self.body(), "sig": self.sig}

    @classmethod
    def from_dict(cls, d: dict) -> "GroupRecord | None":
        try:
            rec = cls(str(d["group_id"]), str(d["nonce"]), str(d["name"]), str(d["owner"]),
                      [str(m) for m in d["members"]], int(d["seq"]), dict(d["settings"]),
                      {str(k): float(v) for k, v in d["joined"].items()}, int(d["epoch"]),
                      {str(k): dict(v) for k, v in d["keybox"].items()}, str(d["sig"]),
                      str(d["founder"]) if d.get("founder") else None,
                      [dict(t) for t in d.get("transfers") or []])
        except (KeyError, TypeError, ValueError, AttributeError):
            return None
        return rec if rec.valid() else None


def transfer_body(group_id: str, to: str, index: int) -> bytes:
    """What an owner signs to hand group_id to `to` (index = position in the ownership chain)."""
    return canonical({"type": "group_transfer", "group": group_id, "to": to, "n": index})


def _aad(group_id: str, epoch: int) -> bytes:
    return canonical({"group": group_id, "epoch": epoch})


def _msg_id(ct: str) -> str:
    return b64(hashlib.sha256(ct.encode()).digest())[:22]


class GroupStore:
    def __init__(self, ident: Identity, enc: EncKey, path: Path | None = None):
        self.ident = ident
        self.enc = enc
        self.path = path
        self.groups: dict[str, GroupRecord] = {}
        self.keys: dict[str, dict[int, bytes]] = {}  # group -> epoch -> key (whatever we're entitled to)
        self.epochs: dict[str, dict[int, dict]] = {}  # owner only: group -> epoch -> {mode, start}
        # Your membership: "pending" (invited, not accepted yet), "accepted", "declined" or "left".
        self.status: dict[str, str] = {}
        self.logs: dict[str, dict[str, dict]] = {}  # group -> msg id -> {"outer", "inner"}
        if path and path.exists():
            data = json.loads(path.read_text())
            if data.get("version") == FORMAT_VERSION:
                for gid, g in data["groups"].items():
                    rec = GroupRecord.from_dict(g["record"])
                    if rec:
                        self.groups[gid] = rec
                        self.keys[gid] = {int(e): unb64(k) for e, k in g["keys"].items()}
                        self.epochs[gid] = {int(e): m for e, m in g.get("epochs", {}).items()}
                        self.logs[gid] = {m["outer"]["id"]: m for m in g["log"]}
                        self.status[gid] = g.get("status", "accepted")  # groups from before invites: joined

    # --- records and keys ------------------------------------------------------------------------

    def joined(self, group_id: str) -> bool:
        """A current member who has accepted (owners accept their own groups)."""
        rec = self.groups.get(group_id)
        return bool(rec and self.ident.pubkey in rec.members and self.status.get(group_id) == "accepted")

    def set_status(self, group_id: str, status: str) -> None:
        self.status[group_id] = status
        self.save()

    def merge(self, rec: GroupRecord | None) -> bool:
        """Store rec if valid and newer, and pick up any keys it carries for us."""
        if rec is None or not rec.valid():
            return False
        cur = self.groups.get(rec.group_id)
        if cur and cur.seq >= rec.seq:
            return False
        self.groups[rec.group_id] = rec
        self.logs.setdefault(rec.group_id, {})
        keys = self.keys.setdefault(rec.group_id, {})
        box = rec.keybox.get(self.ident.pubkey)
        opened = open_sealed(self.enc, box) if box else None
        if opened:
            keys.update({int(e): unb64(k) for e, k in json.loads(opened).items()})
        self.save()
        return True

    def purge(self, group_id: str, status: str = "deleted") -> None:
        """Forget a group's messages and keys (the record stays, so later updates are recognised)."""
        self.logs[group_id] = {}
        self.keys[group_id] = {}
        self.epochs[group_id] = {}
        self.status[group_id] = status
        self.save()

    def build_record(self, name: str, members: list[str], enc_keys: dict[str, str],
                     prev: GroupRecord | None, settings: dict, transfer: dict | None = None) -> GroupRecord:
        """Owner side: the next version of a group (or a new one), with a fresh epoch key sealed to
        every member. enc_keys maps each other member's pubkey to their X25519 key. `transfer` is the
        previous owner's signed hand-over when we're taking the group over."""
        now = time.time()
        me = self.ident.pubkey
        nonce = prev.nonce if prev else b64(os.urandom(12))
        founder = prev.founder_key if prev else me
        transfers = (list(prev.transfers) if prev else []) + ([transfer] if transfer else [])
        gid = group_id_for(founder, nonce)
        member_list = sorted(set(members) | {me})
        joined = {m: (prev.joined[m] if prev and m in prev.joined else now) for m in member_list}
        epoch = (prev.epoch if prev else 0) + 1

        keys = self.keys.setdefault(gid, {})
        epochs = self.epochs.setdefault(gid, {})
        keys[epoch] = os.urandom(32)
        epochs[epoch] = {"mode": settings["history"], "start": now}
        keybox = {}
        for m in member_list:
            if m == me:
                continue
            # Epochs we hold a key for but no metadata (shouldn't happen after a proper hand-over) are
            # only shared with nobody: better to withhold history than to leak it.
            entitled = {e: k for e, k in keys.items() if e in epochs and (
                epochs[e]["start"] >= joined[m] or (settings["history"] == "shared" and epochs[e]["mode"] == "shared"))}
            keybox[m] = seal(enc_keys[m], json.dumps({e: b64(k) for e, k in entitled.items()}).encode())

        seq = max(time.time_ns() // 1000, (prev.seq if prev else 0) + 1)
        rec = GroupRecord(gid, nonce, name, me, member_list, seq, dict(settings), joined, epoch, keybox,
                          founder=founder if transfers else None, transfers=transfers)
        rec.sig = self.ident.sign(canonical(rec.body()))
        return rec

    # --- messages --------------------------------------------------------------------------------

    def seal_message(self, group_id: str, text: str) -> dict:
        """Sign and encrypt a message under the group's current epoch. Returns the outer envelope."""
        rec = self.groups[group_id]
        inner = {"group": group_id, "from": self.ident.pubkey, "ts": time.time(),
                 "nonce": b64(os.urandom(8)), "text": text, "private": rec.history == "private"}
        inner["sig"] = self.ident.sign(canonical(inner))
        box = encrypt(self.keys[group_id][rec.epoch], canonical(inner), _aad(group_id, rec.epoch))
        return {"group": group_id, "epoch": rec.epoch, **box, "id": _msg_id(box["ct"])}

    def add_message(self, outer: dict) -> dict | None:
        """Decrypt, verify and store outer. Returns the inner message if it was new and valid."""
        try:
            gid, epoch, ct = str(outer["group"]), int(outer["epoch"]), str(outer["ct"])
        except (KeyError, TypeError, ValueError):
            return None
        rec = self.groups.get(gid)
        key = self.keys.get(gid, {}).get(epoch)
        if not rec or key is None or outer.get("id") != _msg_id(ct) or outer["id"] in self.logs[gid]:
            return None
        plain = decrypt(key, outer, _aad(gid, epoch))
        try:
            inner = json.loads(plain) if plain else None
            body = {k: v for k, v in inner.items() if k != "sig"}
            ok = (inner["group"] == gid and inner["from"] in rec.members
                  and isinstance(inner["ts"], (int, float)) and verify(inner["from"], canonical(body), inner["sig"]))
        except (TypeError, ValueError, KeyError, AttributeError):
            ok = False
        if not ok:
            return None
        clean_outer = {"group": gid, "epoch": epoch, "n": outer["n"], "ct": ct, "id": outer["id"]}
        self.logs[gid][outer["id"]] = {"outer": clean_outer, "inner": inner}
        self.save()
        return inner

    def has(self, group_id: str, msg_id: str) -> bool:
        return msg_id in self.logs.get(group_id, {})

    def messages(self, group_id: str) -> list[dict]:
        """Decrypted messages, oldest first."""
        return sorted((m["inner"] for m in self.logs.get(group_id, {}).values()), key=lambda i: i["ts"])

    def missing_for(self, group_id: str, have: set[str], requester: str) -> list[dict]:
        """Ciphertexts requester lacks. Private-era messages from before they joined are skipped:
        they couldn't decrypt them anyway, and this avoids handing out even the ciphertext."""
        rec = self.groups.get(group_id)
        since = rec.joined_at(requester) if rec else float("inf")
        entries = sorted(self.logs.get(group_id, {}).values(), key=lambda m: m["inner"]["ts"])
        visible = [m["outer"] for m in entries if m["outer"]["id"] not in have
                   and (not m["inner"].get("private") or m["inner"]["ts"] >= since)]
        return visible[-SYNC_LIMIT:]

    # --- lookup / persistence --------------------------------------------------------------------

    def get(self, group_id: str) -> GroupRecord | None:
        return self.groups.get(group_id)

    def find(self, query: str) -> list[GroupRecord]:
        return [g for g in self.groups.values() if g.name == query or g.group_id.startswith(query)]

    def all(self) -> list[GroupRecord]:
        return list(self.groups.values())

    def save(self) -> None:
        if not self.path:
            return
        groups = {gid: {"record": rec.to_dict(),
                        "keys": {e: b64(k) for e, k in self.keys.get(gid, {}).items()},
                        "epochs": self.epochs.get(gid, {}),
                        "log": list(self.logs.get(gid, {}).values()),
                        "status": self.status.get(gid, "pending")}
                  for gid, rec in self.groups.items()}
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self.path.write_text(json.dumps({"version": FORMAT_VERSION, "groups": groups}, indent=1))
