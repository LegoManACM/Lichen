"""Contact records: a signed "this key currently lives at these addresses" statement.

Records are self-certifying, so any node can store and forward anyone's record without being
trusted: a forged or tampered record fails verification, and a stale one loses to a higher seq.

An address is either "HOST:PORT" (reachable directly) or "relay:HOST:PORT" (reachable through a
relay the node keeps a connection open to; see relay.py). `enc` is the node's X25519 public key, so
anyone holding the record can encrypt to it.
"""

import time
from dataclasses import dataclass, field

from .identity import Identity, canonical, verify


@dataclass
class Record:
    pubkey: str
    name: str
    addrs: list[str]
    seq: int
    enc: str | None = None  # None only on records from before encryption existed
    sig: str = field(default="", repr=False)

    def body(self) -> dict:
        body = {"pubkey": self.pubkey, "name": self.name, "addrs": self.addrs, "seq": self.seq}
        if self.enc is not None:
            body["enc"] = self.enc
        return body

    @classmethod
    def create(cls, ident: Identity, name: str, addrs: list[str], enc: str, prev_seq: int = 0) -> "Record":
        # Microsecond clock as seq survives restarts without persisting a counter.
        seq = max(time.time_ns() // 1000, prev_seq + 1)
        rec = cls(ident.pubkey, name, list(addrs), seq, enc)
        rec.sig = ident.sign(canonical(rec.body()))
        return rec

    def valid(self) -> bool:
        return verify(self.pubkey, canonical(self.body()), self.sig)

    def to_dict(self) -> dict:
        return {**self.body(), "sig": self.sig}

    @classmethod
    def from_dict(cls, d: dict) -> "Record | None":
        try:
            enc = str(d["enc"]) if d.get("enc") is not None else None
            rec = cls(str(d["pubkey"]), str(d["name"]), [str(a) for a in d["addrs"]], int(d["seq"]), enc, str(d["sig"]))
        except (KeyError, TypeError, ValueError, AttributeError):
            return None
        return rec if rec.valid() else None
