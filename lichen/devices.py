"""Using one identity on several devices.

Linking is a file you carry over yourself (USB stick, cloud drive...):

    lichen --export me.lichen          on the device you already use
    lichen --import me.lichen          on the new one

The file holds your keys, contacts / nicknames / verifications, groups (with their keys and history),
chats, outbox and phonebook, plus a lichen.yaml (your portable settings: name, hubs...), encrypted with
a passphrase you choose (scrypt + ChaCha20-Poly1305, as for key files). Anyone with the file *and* its
passphrase can be you, so treat it like a password.

After that both devices are you: each attaches to your hubs with its own device id, hubs deliver to
all of them, and from then on they keep each other up to date (node.py, "syncing your own devices"),
live when both are on and through your hubs' encrypted vault (vault.py) when they aren't.
"""

import json
import os
from pathlib import Path

from cryptography.hazmat.primitives.ciphers.aead import ChaCha20Poly1305

from .identity import b64, unb64
from .keyfile import KEY_FILES, _kdf, read_secret, write_secret

PREFIX = "lichen-identity-v1:"
STATE_FILES = ("trust.json", "phonebook.json", "groups.json", "dms.json", "outbox.json")
LINKED = "linked"  # marker: this identity lives on more than one device (so sync uses the hubs' vault)


def device_id(data_dir: Path) -> str:
    """This device's id (random, made once per data folder; not part of exports)."""
    path = data_dir / "device"
    if path.exists():
        return path.read_text(encoding="utf-8").strip()
    data_dir.mkdir(parents=True, exist_ok=True)
    ident = b64(os.urandom(6))
    path.write_text(ident, encoding="utf-8")
    return ident


def is_linked(data_dir: Path) -> bool:
    return (data_dir / LINKED).exists()


def mark_linked(data_dir: Path) -> None:
    data_dir.mkdir(parents=True, exist_ok=True)
    (data_dir / LINKED).write_text("1", encoding="utf-8")


def export_identity(data_dir: Path, dest: Path, passphrase: str, key_passphrase: str | None = None,
                    config: str | None = None) -> None:
    """Write everything another device needs to be you into dest, encrypted with passphrase.
    config is the text of a lichen.yaml to travel with it."""
    if not passphrase:
        raise ValueError("an export needs a passphrase: anyone with the file and passphrase can be you")
    if not (data_dir / "key").exists():
        raise ValueError(f"no identity in {data_dir}")
    bundle = {"keys": {name: b64(read_secret(data_dir / name, key_passphrase)) for name in KEY_FILES},
              "files": {name: (data_dir / name).read_text(encoding="utf-8")
                        for name in STATE_FILES if (data_dir / name).exists()}}
    if config:
        bundle["config"] = config
    salt, nonce = os.urandom(16), os.urandom(12)
    ct = ChaCha20Poly1305(_kdf(passphrase, salt)).encrypt(nonce, json.dumps(bundle).encode(), b"lichen-identity")
    dest.write_text(PREFIX + json.dumps({"salt": b64(salt), "n": b64(nonce), "ct": b64(ct)}), encoding="utf-8")
    mark_linked(data_dir)  # from now on this identity has another device to keep in step with


def open_export(src: Path, passphrase: str) -> dict:
    """Decrypt an export file into its bundle: {"keys": ..., "files": ..., "config": text (optional)}."""
    text = src.read_text(encoding="utf-8")
    if not text.startswith(PREFIX):
        raise ValueError(f"{src} isn't a Lichen identity export")
    box = json.loads(text[len(PREFIX):])
    try:
        plain = ChaCha20Poly1305(_kdf(passphrase, unb64(box["salt"]))).decrypt(
            unb64(box["n"]), unb64(box["ct"]), b"lichen-identity")
    except Exception as e:
        raise ValueError("wrong passphrase for this export") from e
    return json.loads(plain)


def export_config(src: Path, passphrase: str) -> str | None:
    """The lichen.yaml inside an export, if it has one (exports from older versions don't)."""
    return open_export(src, passphrase).get("config")


def import_identity(src: Path, data_dir: Path, passphrase: str, key_passphrase: str | None = None) -> str | None:
    """Set up data_dir from an export. key_passphrase protects the key files on this device.
    Returns the export's lichen.yaml text, if it carried one (the caller decides where it goes)."""
    if (data_dir / "key").exists():
        raise ValueError(f"{data_dir} already has an identity; import into a new --data folder")
    bundle = open_export(src, passphrase)
    data_dir.mkdir(parents=True, exist_ok=True)
    for name, content in bundle.get("files", {}).items():
        if name in STATE_FILES:
            (data_dir / name).write_text(content, encoding="utf-8")
    for name in KEY_FILES:
        write_secret(data_dir / name, unb64(bundle["keys"][name]), key_passphrase)
    mark_linked(data_dir)
    return bundle.get("config")
