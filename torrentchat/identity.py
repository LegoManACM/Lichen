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


class Identity:
    def __init__(self, key: Ed25519PrivateKey):
        self._key = key
        self.pubkey = b64(key.public_key().public_bytes(Encoding.Raw, PublicFormat.Raw))

    @classmethod
    def generate(cls) -> "Identity":
        return cls(Ed25519PrivateKey.generate())

    @classmethod
    def load_or_create(cls, path: Path) -> "Identity":
        if path.exists():
            return cls(Ed25519PrivateKey.from_private_bytes(unb64(path.read_text().strip())))
        ident = cls.generate()
        path.parent.mkdir(parents=True, exist_ok=True)
        raw = ident._key.private_bytes(Encoding.Raw, PrivateFormat.Raw, NoEncryption())
        path.write_text(b64(raw))
        return ident

    def sign(self, data: bytes) -> str:
        return b64(self._key.sign(data))


def verify(pubkey: str, data: bytes, sig: str) -> bool:
    try:
        Ed25519PublicKey.from_public_bytes(unb64(pubkey)).verify(unb64(sig), data)
        return True
    except (InvalidSignature, ValueError):
        return False
