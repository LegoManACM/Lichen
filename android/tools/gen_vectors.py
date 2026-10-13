"""Generate android/core/src/test/resources/vectors.json with the real Python `lichen` package.

    C:/Users/afrik/Github/Lichen/.venv/Scripts/python.exe android/tools/gen_vectors.py

The Kotlin tests check that the port reads/produces exactly these bytes. Everything that is random in
production (sealing nonces, salts) is random here too; the output is *committed*, so tests need no Python.
Fixed seeds keep the identities stable, so regenerating only changes the random parts.
"""

import json
import os
import random
import sys
import tempfile
import time
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO))  # this worktree's lichen/, not an installed copy

from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey  # noqa: E402
from cryptography.hazmat.primitives.asymmetric.x25519 import X25519PrivateKey  # noqa: E402

from lichen import crypto, devices, keyfile  # noqa: E402
from lichen.crypto import EncKey  # noqa: E402
from lichen.dms import DmStore  # noqa: E402
from lichen.identity import Identity, b64, canonical, fingerprint, safety_code, short, unb64, verify  # noqa: E402
from lichen.outbox import Outbox  # noqa: E402
from lichen.phonebook import Phonebook  # noqa: E402
from lichen.records import Record  # noqa: E402
from lichen.trust import TrustStore  # noqa: E402

OUT = REPO / "android" / "core" / "src" / "test" / "resources" / "vectors.json"
rnd = random.Random(1234)


def ident(n: int) -> Identity:
    return Identity(Ed25519PrivateKey.from_private_bytes(bytes([n]) * 32))


def enckey(n: int) -> EncKey:
    return EncKey(X25519PrivateKey.from_private_bytes(bytes([0x40 + n]) * 32))


def raw_seed(i: Identity) -> str:
    from cryptography.hazmat.primitives.serialization import Encoding, NoEncryption, PrivateFormat
    return b64(i._key.private_bytes(Encoding.Raw, PrivateFormat.Raw, NoEncryption()))


def raw_enc(k: EncKey) -> str:
    from cryptography.hazmat.primitives.serialization import Encoding, NoEncryption, PrivateFormat
    return b64(k._key.private_bytes(Encoding.Raw, PrivateFormat.Raw, NoEncryption()))


def shuffled(obj):
    """Same data, random key order, so Kotlin has to sort."""
    if isinstance(obj, dict):
        items = [(k, shuffled(v)) for k, v in obj.items()]
        rnd.shuffle(items)
        return dict(items)
    if isinstance(obj, list):
        return [shuffled(v) for v in obj]
    return obj


# --- canonical JSON ------------------------------------------------------------------------------------------

CANON_OBJECTS = [
    {},
    {"a": 1, "b": 2},
    {"b": [1, 2, {"z": None, "y": True, "x": False}], "a": "x"},
    {"text": "h\u00e9llo w\u00f6rld \u4e16\u754c"},
    {"emoji": "\U0001f600 \U0001f468\u200d\U0001f469\u200d\U0001f467 \U0001f3f3\ufe0f\u200d\U0001f308"},
    {"ctrl": "".join(chr(i) for i in range(0, 32)) + "\x7f\x80\x9f\xa0\xad"},
    {"quotes": 'say "hi" \\ back/slash / \u2028\u2029'},
    {"k\u00e9y": 1, "\U0001f600": 2, "\uffff": 3, "\ud7ff": 4, "A": 5, "a": 6, "": 7},
    {"deep": {"a": {"b": {"c": {"d": [[], [[]], {}]}}}}},
    {"floats": [1.0, 0.1, 1e-05, 1e16, 1728750000.123456, 0.0001, 123456789012345.0, 1.5e300, 5e-324,
                1.7976931348623157e308, -0.0, -2.5, 100.0, 1e22, 0.30000000000000004, 9007199254740993.0]},
    {"ints": [0, -1, 1, 9007199254740993, 1728750000123456, -9223372036854775808, 9223372036854775807]},
    {"seq": 1728750000123456, "ts": 1728750000.5, "id": "abc_-DEF", "from": "x", "to": "y"},
    {"mixed": [None, True, False, 0, 0.0, "", [], {}]},
    {"long": "x" * 5000 + "\u00e9"},
]


def canon_vectors():
    out = []
    for obj in CANON_OBJECTS:
        # the wire text: random key order, either ASCII-escaped or raw UTF-8, sometimes with spaces
        shuf = shuffled(obj)
        for ensure_ascii, seps in ((True, (",", ":")), (False, (", ", ": ")), (False, (",", ":"))):
            text = json.dumps(shuf, ensure_ascii=ensure_ascii, separators=seps)
            out.append({"json": text, "canonical": canonical(json.loads(text)).decode()})
    return out


def float_vectors():
    vals = [0.0, -0.0, 1.0, 0.1, 1e-05, 1e16, 1e-4, 1e15, 123456789.0, 1728750000.123456, 5e-324, 1.7976931348623157e308,
            0.5, 2.0 ** 53, 2.0 ** 63, 1e21, 1e22, 1.2345e-7, 100.0, 12345678901234567.0, 0.001, 9.999999999999999e22]
    import struct
    for _ in range(400):
        bits = rnd.getrandbits(64)
        d = struct.unpack("<d", struct.pack("<Q", bits))[0]
        if d == d and abs(d) != float("inf"):
            vals.append(d)
    for _ in range(300):
        vals.append(rnd.uniform(1.6e9, 1.9e9))  # timestamps
        vals.append(round(rnd.uniform(0, 1e6), rnd.randint(0, 6)))
        vals.append(rnd.random() * 10 ** rnd.randint(-12, 25))
    return [repr(v) for v in vals]


# --- identities, signatures ------------------------------------------------------------------------------------

def identity_vectors():
    out = []
    for n in (1, 2, 3, 7):
        i = ident(n)
        msgs = [b"", b"hello", canonical({"a": [1, 2.5, "\u00e9"], "z": None}), os.urandom(300)]
        out.append({"seed": raw_seed(i), "pubkey": i.pubkey, "fingerprint": fingerprint(i.pubkey), "short": short(i.pubkey),
                    "signatures": [{"data": b64(m), "sig": i.sign(m)} for m in msgs]})
    return out


def safety_vectors():
    keys = [ident(n).pubkey for n in (1, 2, 3)] + ["abc", "", "\u00e9"]
    return [{"a": a, "b": b, "code": safety_code(a, b)} for a in keys for b in keys]


def verify_vectors():
    i, j = ident(1), ident(2)
    data = b"payload"
    sig = i.sign(data)
    bad_sig = bytearray(unb64(sig)); bad_sig[0] ^= 1
    cases = [(i.pubkey, data, sig, True), (j.pubkey, data, sig, False), (i.pubkey, b"other", sig, False),
             (i.pubkey, data, b64(bytes(bad_sig)), False), (i.pubkey, data, "", False), (i.pubkey, data, "!!!", False),
             ("short", data, sig, False), ("", data, sig, False), (i.pubkey, data, sig + "AAAA", False)]
    for pk, d, s, expect in cases:
        assert verify(pk, d, s) == expect
    return [{"pubkey": pk, "data": b64(d), "sig": s, "valid": e} for pk, d, s, e in cases]


# --- records -----------------------------------------------------------------------------------------------------

def make_record(i: Identity, e: EncKey | None, name: str, addrs: list[str], seq: int) -> Record:
    rec = Record(i.pubkey, name, addrs, seq, e.pub if e else None)
    rec.sig = i.sign(canonical(rec.body()))
    return rec


def record_vectors():
    i, e = ident(1), enckey(1)
    good = make_record(i, e, "alice \u00e9 \U0001f600", ["relay:hub.example.org:9000", "wss://x.example.com"], 1728750000123456)
    legacy = make_record(i, None, "old", ["10.0.0.1:9000"], 5)
    out = [{"name": "valid", "record": good.to_dict(), "valid": True},
           {"name": "valid, no enc", "record": legacy.to_dict(), "valid": True}]
    d = good.to_dict()
    for field, val in (("name", "mallory"), ("addrs", ["evil:1"]), ("seq", d["seq"] + 1), ("enc", enckey(2).pub),
                       ("pubkey", ident(2).pubkey), ("sig", legacy.sig)):
        t = dict(d); t[field] = val
        out.append({"name": f"tampered {field}", "record": t, "valid": False})
    t = dict(d); del t["sig"]
    out.append({"name": "no sig", "record": t, "valid": False})
    t = dict(d); del t["addrs"]
    out.append({"name": "no addrs", "record": t, "valid": False})
    out.append({"name": "not an object", "record": ["x"], "valid": False})
    for r in out:  # make sure Python agrees with every verdict
        assert (Record.from_dict(r["record"]) is not None) == r["valid"], r["name"]
    return out


# --- encryption ----------------------------------------------------------------------------------------------------

def sealed_vectors():
    e = enckey(1)
    out = []
    for pt in (b"", b"hi", "h\u00e9llo \U0001f600".encode(), os.urandom(5000)):
        out.append({"enc_seed": raw_enc(e), "enc_pub": e.pub, "plaintext": b64(pt), "box": crypto.seal(e.pub, pt)})
    good = out[1]["box"]
    bad = dict(good); ct = bytearray(unb64(good["ct"])); ct[0] ^= 1; bad["ct"] = b64(bytes(ct))
    wrong = crypto.seal(enckey(2).pub, b"hi")
    return {"valid": out, "invalid": [bad, wrong, {"epk": good["epk"]}, {}, "nope", None]}


def aead_vectors():
    key = bytes(range(32))
    out = []
    for pt, aad in ((b"", b""), (b"group message", b"group-id"), (os.urandom(2000), "\u00e9".encode())):
        out.append({"key": b64(key), "aad": b64(aad), "plaintext": b64(pt), "box": crypto.encrypt(key, pt, aad)})
    return out


# --- key files, exports, stores ----------------------------------------------------------------------------------

def keyfile_vectors(tmp: Path):
    secret = bytes(range(32, 64))
    p = tmp / "kf"
    keyfile.write_secret(p, secret, "correct horse \u00e9")
    enc_text = p.read_text()
    keyfile.write_secret(p, secret, None)
    return {"secret": b64(secret), "plain": p.read_text(), "encrypted": enc_text, "passphrase": "correct horse \u00e9"}


def dm(frm: Identity, to: Identity, ts: float, text: str, **extra) -> dict:
    inner = {"from": frm.pubkey, "to": to.pubkey, "ts": ts, "id": b64(os.urandom(12)), "text": text, **extra}
    inner["sig"] = frm.sign(canonical(inner))
    return inner


def build_folder(d: Path):
    """A realistic Python-written data folder for alice (identity 1) with bob (2) and carol (3)."""
    me, bob, carol = ident(1), ident(2), ident(3)
    d.mkdir(parents=True, exist_ok=True)
    keyfile.write_secret(d / "key", unb64(raw_seed(me)))
    keyfile.write_secret(d / "enckey", unb64(raw_enc(enckey(1))))
    pb = Phonebook(d / "phonebook.json")
    pb.merge(make_record(me, enckey(1), "alice", ["relay:hub.example.org:9000", "relay:wss://b.example.com"], 1000000))
    pb.merge(make_record(bob, enckey(2), "bob \u00e9", ["relay:hub.example.org:9000"], 2000000))
    pb.merge(make_record(carol, enckey(3), "carol", ["1.2.3.4:9000"], 3000000))
    dms = DmStore(d / "dms.json")
    t0 = 1728750000.123456
    msgs = [dm(bob, me, t0, "hi alice \U0001f600"), dm(me, bob, t0 + 1, "hello bob \u00e9\n\ttabs \"quoted\""),
            dm(bob, me, t0 + 2.5, "third", reply="x"), dm(me, carol, t0 + 3, "carol?"), dm(me, bob, t0 + 0.5, "out of order")]
    for m in msgs:
        dms.add(m["to"] if m["from"] == me.pubkey else m["from"], m)
    dms.mark_delivered(bob.pubkey, msgs[1]["id"])
    dms.mark_read(bob.pubkey, t0 + 1)
    trust = TrustStore(d / "trust.json")
    trust.set(bob.pubkey, "trusted")
    trust.set(carol.pubkey, "blocked")
    trust.set_nickname(bob.pubkey, "bobby")
    trust.set_verified(bob.pubkey, 1728750100.5)
    for k, t in zip((bob.pubkey, carol.pubkey), (1728750200.25, 1728750300.75)):
        trust.changed[k] = t  # fixed, so regenerating is stable
    trust.save()
    ob = Outbox(d / "outbox.json")
    ob.add(carol.pubkey, {"type": "msg", "to": carol.pubkey, "sealed": crypto.seal(enckey(3).pub, b"queued")}, "item-1")
    ob.add(bob.pubkey, {"type": "msg", "to": bob.pubkey, "sealed": crypto.seal(enckey(2).pub, b"queued2")}, "item-2")
    ob.remove("item-2")
    return msgs


def store_file_texts(d: Path):
    return {n: (d / n).read_text() for n in ("phonebook.json", "dms.json", "trust.json", "outbox.json")}


def device_vectors():
    """Python's device_state() outputs and merge results, to compare with the Kotlin ports."""
    me, bob = ident(1), ident(2)
    with tempfile.TemporaryDirectory() as t:
        d = Path(t) / "a"
        build_folder(d)
        files = store_file_texts(d)
        dms, trust, ob = DmStore(d / "dms.json"), TrustStore(d / "trust.json"), Outbox(d / "outbox.json")
        out = {"files": files, "dms_state": dms.device_state(), "dms_state_keep2": dms.device_state(2),
               "trust_state": trust.device_state(), "outbox_state": ob.device_state()}

        # remote states: new messages (one bogus: belongs to another pair), newer trust, extra IOUs, done items
        t1 = 1728750500.5
        remote_msg = dm(bob, me, t1, "from the other device")
        bogus = dm(ident(3), ident(4), t1, "not ours")
        out["remote_dms"] = {"convos": {bob.pubkey: [remote_msg, bogus, {"nonsense": 1}]},
                             "receipts": {bob.pubkey: {"delivered": ["zzz", "yyy"], "read_ts": t1}}}
        out["remote_trust"] = {bob.pubkey: {"level": "blocked", "nickname": None, "verified": None, "changed": t1},
                               ident(5).pubkey: {"level": "trusted", "nickname": "eve", "verified": 5.5, "changed": t1},
                               "bad": {"level": "nonsense", "changed": t1 + 1}}
        out["remote_outbox"] = {"items": {"item-3": {"to": bob.pubkey, "msg": {"type": "msg"}, "ts": t1},
                                          "item-2": {"to": bob.pubkey, "msg": {"type": "msg"}, "ts": t1}},
                                "done": {"item-1": t1, "item-9": 5.0}}
        out["me"] = me.pubkey
        n1 = dms.merge_device_state(out["remote_dms"], me.pubkey)
        n2 = trust.merge_device_state(out["remote_trust"])
        n3 = ob.merge_device_state(out["remote_outbox"])
        out["merged"] = {"dms_new": n1, "trust_changed": n2, "outbox_changed": n3,
                         "dms": json.loads((d / "dms.json").read_text()), "trust": json.loads((d / "trust.json").read_text()),
                         "outbox": json.loads((d / "outbox.json").read_text())}
    return out


def legacy_vector():
    """dms.json as older versions wrote it ({peer: [messages without ids]}), and the ids Python gives them."""
    from lichen.dms import legacy_id
    msgs = [{"from": "alice", "to": "bob", "ts": 1.5, "text": "plain"},
            {"from": "bob", "to": "alice", "ts": 1728750000.123456, "text": "héllo 😀"},
            {"from": "bob", "to": "alice", "ts": 7, "text": "int ts"},
            {"from": "bob", "to": "alice", "ts": 2.0, "sig": "s"}]
    return {"file": json.dumps({"peerkey": msgs}), "ids": [legacy_id(m) for m in msgs]}


def export_vector():
    with tempfile.TemporaryDirectory() as t:
        d = Path(t) / "a"
        build_folder(d)
        config = "# my config\nname: alice\nrelay:\n  - hub.example.org:9000  # first\n  - wss://b.example.com\nport: 9000\n"
        dest = Path(t) / "me.lichen"
        devices.export_identity(d, dest, "pass \u00e9 phrase", config=config)
        old = Path(t) / "old.lichen"  # an export without a config (older versions)
        devices.export_identity(d, old, "pw")
        files = {n: (d / n).read_text() for n in devices.STATE_FILES if (d / n).exists()}
        return {"text": dest.read_text(), "passphrase": "pass \u00e9 phrase", "config": config, "files": files,
                "keys": {"key": raw_seed(ident(1)), "enckey": raw_enc(enckey(1))}, "pubkey": ident(1).pubkey,
                "no_config": {"text": old.read_text(), "passphrase": "pw"}}


def main():
    build = None
    with tempfile.TemporaryDirectory() as t:
        tmp = Path(t)
        vectors = {
            "canonical": canon_vectors(),
            "floats": float_vectors(),
            "identities": identity_vectors(),
            "safety": safety_vectors(),
            "verify": verify_vectors(),
            "records": record_vectors(),
            "sealed": sealed_vectors(),
            "aead": aead_vectors(),
            "keyfile": keyfile_vectors(tmp),
            "export": export_vector(),
            "stores": device_vectors(),
            "legacy": legacy_vector(),
        }
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(vectors, indent=0, ensure_ascii=True), encoding="utf-8")
    print(f"wrote {OUT} ({OUT.stat().st_size // 1024} KiB)")


if __name__ == "__main__":
    main()
