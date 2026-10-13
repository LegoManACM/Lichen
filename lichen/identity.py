"""Ed25519 identities. A person *is* their public key; addresses are just where they currently live."""

import base64
import json
from pathlib import Path

from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey, Ed25519PublicKey
from cryptography.hazmat.primitives.serialization import Encoding, NoEncryption, PrivateFormat, PublicFormat


def b64(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).decode().rstrip("=")


def unb64(text: str) -> bytes:
    return base64.urlsafe_b64decode(text + "=" * (-len(text) % 4))


def canonical(obj) -> bytes:
    """Deterministic JSON encoding, so signer and verifier hash the same bytes."""
    return json.dumps(obj, sort_keys=True, separators=(",", ":")).encode()


def short(pubkey: str) -> str:
    return pubkey[:8]


def fingerprint(pubkey: str) -> str:
    """A key in readable groups, for comparing out loud: e.g. 3fK9 xQ2m 7aLp Wc1d."""
    return " ".join(pubkey[i:i + 4] for i in range(0, 16, 4))


def safety_code(a: str, b: str) -> str:
    """Same 20 digits on both sides of a conversation; compare them to check you have the right key."""
    import hashlib

    digest = hashlib.sha256("|".join(sorted([a, b])).encode()).digest()
    digits = str(int.from_bytes(digest[:12], "big")).zfill(29)[-20:]
    return " ".join(digits[i:i + 5] for i in range(0, 20, 5))


class Identity:
    def __init__(self, key: Ed25519PrivateKey):
        self._key = key
        self.pubkey = b64(key.public_key().public_bytes(Encoding.Raw, PublicFormat.Raw))

    @classmethod
    def generate(cls) -> "Identity":
        return cls(Ed25519PrivateKey.generate())

    @classmethod
    def load_or_create(cls, path: Path, passphrase: str | None = None) -> "Identity":
        from .keyfile import read_secret, write_secret  # keyfile imports b64 helpers from here

        if path.exists():
            return cls(Ed25519PrivateKey.from_private_bytes(read_secret(path, passphrase)))
        ident = cls.generate()
        write_secret(path, ident._key.private_bytes(Encoding.Raw, PrivateFormat.Raw, NoEncryption()), passphrase)
        return ident

    def sign(self, data: bytes) -> str:
        return b64(self._key.sign(data))


def verify(pubkey: str, data: bytes, sig: str) -> bool:
    try:
        Ed25519PublicKey.from_public_bytes(unb64(pubkey)).verify(unb64(sig), data)
        return True
    except (InvalidSignature, ValueError):
        return False
