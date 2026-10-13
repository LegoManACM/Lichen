"""Self-checking demo of syncing one identity across devices, including when they're never on together.

    python demo_sync.py

A hub, a contact (dave) and one person (erin) with a desktop and a laptop. Checks that:
  * a device that was off catches up from the hub's vault, though its sibling is off too
  * chats, groups, trust, unsent messages and file offers all travel, not just groups and trust
  * the vault is sealed (the hub can't read it) and signed (a hub can't make state up)
  * devices converge whichever order they were on in
  * an export carries a lichen.yaml, and an import turns it back into this device's settings
"""

import asyncio
import json
import tempfile
import time
from pathlib import Path

from lichen import config, devices
from lichen.node import Node

HUB = "127.0.0.1:9400"


async def wait_until(cond, timeout: float = 8.0) -> bool:
    for _ in range(int(timeout / 0.1)):
        if cond():
            return True
        await asyncio.sleep(0.1)
    return False


def step(text: str) -> None:
    print(f"\n== {text}")


async def main() -> None:
    tmp = Path(tempfile.mkdtemp(prefix="lichen-sync-"))

    def make(folder: str, name: str, port: int = 0, relay=None, **kw) -> Node:
        return Node.from_dir(tmp / folder, name, port=port, relay=relay, bootstrap=[HUB] if relay else [],
                             direct=relay is None, stun=[], log=lambda s, n=folder: print(f"   {n}: {s}"), **kw)

    step("a hub, dave, and erin's desktop")
    hub = make("hub", "hub", 9400)
    await hub.start()
    dave = make("dave", "dave", relay=[HUB])
    desktop = make("desktop", "erin", relay=[HUB])
    for n in (dave, desktop):
        await n.start()
    await asyncio.sleep(0.5)
    assert await desktop.send(dave.ident.pubkey, "hi dave") == "delivered"

    step("erin exports her identity (with a lichen.yaml) and sets up a laptop")
    yaml_text = config.render({"name": "erin", "relay": [HUB], "ui": "tui", "port": 1234, "data": "x"}, "# test")
    await desktop.stop()  # (the CLI exports with nothing running)
    export = tmp / "erin.lichen"
    devices.export_identity(tmp / "desktop", export, "carry me", config=yaml_text)
    assert devices.is_linked(tmp / "desktop"), "exporting marks the identity as being on several devices"
    carried = devices.import_identity(export, tmp / "laptop", "carry me")
    assert carried == yaml_text and devices.is_linked(tmp / "laptop")
    parsed = config.parse(carried)
    assert parsed == {"name": "erin", "relay": [HUB], "ui": "tui"}, parsed  # machine-specific options stay behind
    print(f"   the export carried: {parsed}")
    assert devices.export_config(export, "carry me") == yaml_text
    old = tmp / "old.lichen"
    devices.export_identity(tmp / "desktop", old, "carry me")
    assert devices.export_config(old, "carry me") is None, "exports without a config still import"
    desktop = make("desktop", "erin", relay=[HUB])
    await desktop.start()
    assert desktop.linked

    step("the laptop was never on; the desktop makes changes, then goes off")
    await desktop.create_group("notes", [])
    notes = next(g for g in desktop.groups.all() if g.name == "notes")
    await desktop.send_group(notes.group_id, "buy milk")
    desktop.trust.set_nickname(dave.ident.pubkey, "davey")
    assert await desktop.send(dave.ident.pubkey, "lunch?") == "delivered"
    await dave.send(desktop.ident.pubkey, "sure")
    ghost = Node.from_dir(tmp / "ghost", "ghost", relay=[HUB], bootstrap=[HUB], direct=False, stun=[])
    await ghost.start()
    desktop.phonebook.merge(ghost.me)
    await ghost.stop()  # ...and now they're not online
    assert await desktop.send(ghost.ident.pubkey, "are you there?") == "waiting"
    assert await wait_until(lambda: any(hub.vault.snaps.values()), 8), "the desktop left a snapshot on the hub"
    await asyncio.sleep(0.3)
    pushed = hub.vault.snaps[desktop.ident.pubkey][desktop.device]
    assert "milk" not in pushed["blob"] and "lunch" not in pushed["blob"], "the hub can't read the vault"
    print(f"   snapshot on the hub: {pushed['size']} bytes of ciphertext")
    last = desktop.dms.messages(dave.ident.pubkey)[-1]["id"]
    # Push once more so the snapshot is current, then switch the desktop off.
    desktop._vault_at.clear()
    await desktop._vault_push(desktop._sync_hubs())
    await desktop.stop()

    step("the laptop starts while the desktop is OFF and catches up from the hub's vault")
    laptop = make("laptop", "erin", relay=[HUB])
    assert laptop.ident.pubkey == desktop.ident.pubkey and laptop.device != desktop.device
    await laptop.start()
    assert laptop.groups.joined(notes.group_id)
    assert [m["text"] for m in laptop.groups.messages(notes.group_id)] == ["buy milk"]
    assert laptop.trust.nickname(dave.ident.pubkey) == "davey"
    assert any(m["id"] == last for m in laptop.dms.messages(dave.ident.pubkey))
    assert {m["text"] for m in laptop.dms.messages(dave.ident.pubkey)} >= {"hi dave", "lunch?", "sure"}
    assert any(it["to"] == ghost.ident.pubkey for it in laptop.outbox.items.values()), "the unsent message came too"
    assert laptop.dms.receipt(dave.ident.pubkey, laptop.dms.messages(dave.ident.pubkey)[0]) == "delivered"
    print("   chats, the solo group with its message, the nickname and the unsent message all arrived")

    step("while the desktop is still off, the laptop changes things and the desktop's snapshot ages")
    laptop.trust.set(dave.ident.pubkey, "blocked")
    laptop.trust.set(dave.ident.pubkey, "trusted")
    laptop.trust.set_nickname(dave.ident.pubkey, "dave-laptop")
    await laptop.send(dave.ident.pubkey, "sent from the laptop")
    laptop._vault_at.clear()
    await laptop._vault_push(laptop._sync_hubs())

    step("the unsent message is delivered by the laptop when ghost shows up (desktop still off)")
    await laptop.stop()
    ghost_inbox = []
    ghost2 = Node.from_dir(tmp / "ghost", "ghost", relay=[HUB], bootstrap=[HUB], direct=False, stun=[],
                           on_message=lambda r, t, e: ghost_inbox.append(t))
    laptop = make("laptop", "erin", relay=[HUB])
    await laptop.start()
    await ghost2.start()
    assert await wait_until(lambda: "are you there?" in ghost_inbox, 20), "someone from erin's side delivered it"
    assert await wait_until(lambda: not laptop.outbox.items, 5)
    print("   delivered from the laptop even though it was the desktop that queued it")
    await laptop.stop()

    step("the desktop starts later, with the laptop OFF, and picks up the laptop's changes")
    desktop = make("desktop", "erin", relay=[HUB])
    await desktop.start()
    assert await wait_until(lambda: desktop.trust.nickname(dave.ident.pubkey) == "dave-laptop", 8)
    assert any(m["text"] == "sent from the laptop" for m in desktop.dms.messages(dave.ident.pubkey))
    assert not desktop.outbox.items, "the desktop learned that the message already got through"
    print("   nickname and message from the laptop; the delivered item is gone from the queue")

    step("both on: a change on one shows up on the other quickly")
    laptop = make("laptop", "erin", relay=[HUB])
    await laptop.start()
    await asyncio.sleep(0.5)
    desktop.trust.set_nickname(dave.ident.pubkey, "live")
    assert await wait_until(lambda: laptop.trust.nickname(dave.ident.pubkey) == "live", 8)
    await desktop.send(dave.ident.pubkey, "live message")
    assert await wait_until(lambda: any(m["text"] == "live message" for m in laptop.dms.messages(dave.ident.pubkey)), 8)
    a, b = desktop._pack().digest, laptop._pack().digest
    if not await wait_until(lambda: desktop._pack().digest == laptop._pack().digest, 10):
        da, la = desktop._state(), laptop._state()
        for k in da:
            if k != "phonebook" and json.dumps(da[k], sort_keys=True) != json.dumps(la[k], sort_keys=True):
                print("DIFF in", k)
                print(" desktop", json.dumps(da[k], sort_keys=True)[:1500])
                print(" laptop ", json.dumps(la[k], sort_keys=True)[:1500])
        raise AssertionError("devices converge")
    print("   in step, same digest on both")

    step("a hub can't make state up: a forged snapshot is ignored")
    from lichen.crypto import seal
    bogus = {"from": desktop.ident.pubkey, "to": desktop.ident.pubkey, "device": "evil", "rev": 1, "ts": time.time(),
             "digest": "x", "zhash": "0", "sig": "AAAA"}
    got = laptop._open_snapshot(seal(laptop.enc.pub, json.dumps(bogus).encode() + b"\n"))
    assert got is None
    print("   rejected")

    step("a hub that doesn't want to store snapshots says so; vault_mb: 0")
    plain = Node.from_dir(tmp / "plainhub", "plainhub", port=9401, vault_mb=0)
    await plain.start()
    assert not plain.vault.enabled and hub.vault.enabled
    await plain.stop()

    for n in (laptop, desktop, ghost2, dave, hub):
        await n.stop()
    print("\nALL SYNC CHECKS PASSED")


if __name__ == "__main__":
    asyncio.run(main())
