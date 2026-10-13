"""Encryption. Ed25519 (identity.py) proves who wrote something; this module hides what it says.

- seal/open_sealed: anonymous public-key box (ephemeral X25519 + HKDF + ChaCha20-Poly1305), used for
  direct messages and for handing group keys to members.
- encrypt/decrypt: symmetric ChaCha20-Poly1305 with a 32-byte group key.
"""

import os
from pathlib import Path

from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric.x25519 import X25519PrivateKey, X25519PublicKey
from cryptography.hazmat.primitives.ciphers.aead import ChaCha20Poly1305
from cryptography.hazmat.primitives.kdf.hkdf import HKDF
from cryptography.hazmat.primitives.serialization import Encoding, NoEncryption, PrivateFormat, PublicFormat

from .identity import b64, unb64


class EncKey:
    """A node's X25519 key. The public half is published in its signed contact record."""

    def __init__(self, key: X25519PrivateKey):
        self._key = key
        self.pub = b64(key.public_key().public_bytes(Encoding.Raw, PublicFormat.Raw))

    @classmethod
    def load_or_create(cls, path: Path) -> "EncKey":
        if path.exists():
            return cls(X25519PrivateKey.from_private_bytes(unb64(path.read_text().strip())))
        key = cls(X25519PrivateKey.generate())
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(b64(key._key.private_bytes(Encoding.Raw, PrivateFormat.Raw, NoEncryption())))
        return key

    @classmethod
    def generate(cls) -> "EncKey":
        return cls(X25519PrivateKey.generate())


def _box_key(shared: bytes, epk: bytes, rpk: bytes) -> bytes:
    return HKDF(hashes.SHA256(), 32, salt=None, info=b"lichen-seal-v1" + epk + rpk).derive(shared)


def seal(recipient_pub: str, plaintext: bytes) -> dict:
    rpk = unb64(recipient_pub)
    eph = X25519PrivateKey.generate()
    epk = eph.public_key().public_bytes(Encoding.Raw, PublicFormat.Raw)
    key = _box_key(eph.exchange(X25519PublicKey.from_public_bytes(rpk)), epk, rpk)
    nonce = os.urandom(12)
    return {"epk": b64(epk), "n": b64(nonce), "ct": b64(ChaCha20Poly1305(key).encrypt(nonce, plaintext, None))}


def open_sealed(me: EncKey, box: dict) -> bytes | None:
    try:
        epk = unb64(box["epk"])
        rpk = unb64(me.pub)
        key = _box_key(me._key.exchange(X25519PublicKey.from_public_bytes(epk)), epk, rpk)
        return ChaCha20Poly1305(key).decrypt(unb64(box["n"]), unb64(box["ct"]), None)
    except Exception:  # malformed or not for us; any failure means "can't open"
        return None


def new_key() -> bytes:
    return ChaCha20Poly1305.generate_key()


def encrypt(key: bytes, plaintext: bytes, aad: bytes) -> dict:
    nonce = os.urandom(12)
    return {"n": b64(nonce), "ct": b64(ChaCha20Poly1305(key).encrypt(nonce, plaintext, aad))}


def decrypt(key: bytes, box: dict, aad: bytes) -> bytes | None:
    try:
        return ChaCha20Poly1305(key).decrypt(unb64(box["n"]), unb64(box["ct"]), aad)
    except Exception:
        return None
