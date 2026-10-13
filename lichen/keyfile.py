"""Private key files, optionally protected by a passphrase.

A key file holds 32 raw private-key bytes, either as plain base64 (the default, and the format
older versions wrote) or, once you set a passphrase with `lichen --passphrase`, as

    lichen-key-v1:{"salt": ..., "n": ..., "ct": ...}

where the key is encrypted with ChaCha20-Poly1305 under scrypt(passphrase, salt). Without the
passphrase, a copied data folder is useless: it can't sign as you or read your messages.
"""

import json
import os
from pathlib import Path

from cryptography.hazmat.primitives.ciphers.aead import ChaCha20Poly1305
from cryptography.hazmat.primitives.kdf.scrypt import Scrypt

from .identity import b64, unb64

PREFIX = "lichen-key-v1:"
SCRYPT_N = 2 ** 15  # ~0.1 s per unlock; slows down guessing


class PassphraseNeeded(Exception):
    """The key file is encrypted and no passphrase was given."""


class WrongPassphrase(Exception):
    pass


def _kdf(passphrase: str, salt: bytes) -> bytes:
    return Scrypt(salt=salt, length=32, n=SCRYPT_N, r=8, p=1).derive(passphrase.encode())


def is_encrypted(path: Path) -> bool:
    return path.exists() and path.read_text(encoding="utf-8").startswith(PREFIX)


def read_secret(path: Path, passphrase: str | None = None) -> bytes:
    text = path.read_text(encoding="utf-8").strip()
    if not text.startswith(PREFIX):
        return unb64(text)
    if passphrase is None:
        raise PassphraseNeeded(str(path))
    box = json.loads(text[len(PREFIX):])
    try:
        return ChaCha20Poly1305(_kdf(passphrase, unb64(box["salt"]))).decrypt(unb64(box["n"]), unb64(box["ct"]), b"lichen-key")
    except Exception as e:  # InvalidTag
        raise WrongPassphrase(str(path)) from e


def write_secret(path: Path, secret: bytes, passphrase: str | None = None) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    if not passphrase:
        text = b64(secret)
    else:
        salt, nonce = os.urandom(16), os.urandom(12)
        ct = ChaCha20Poly1305(_kdf(passphrase, salt)).encrypt(nonce, secret, b"lichen-key")
        text = PREFIX + json.dumps({"salt": b64(salt), "n": b64(nonce), "ct": b64(ct)})
    tmp = path.with_suffix(".tmp")
    tmp.write_text(text, encoding="utf-8")
    os.replace(tmp, path)  # never leave a half-written key file


KEY_FILES = ("key", "enckey")


def needs_passphrase(data_dir: Path) -> bool:
    return any(is_encrypted(data_dir / name) for name in KEY_FILES)


def change_passphrase(data_dir: Path, old: str | None, new: str | None) -> None:
    """Re-encrypt both key files (new=None or "" removes the passphrase). Raises WrongPassphrase."""
    secrets = {name: read_secret(data_dir / name, old) for name in KEY_FILES if (data_dir / name).exists()}
    for name, secret in secrets.items():
        write_secret(data_dir / name, secret, new or None)
