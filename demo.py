"""End-to-end demo / smoke test. Runs three hubs + 8 people in one process on localhost.
The third hub is only reachable by WebSocket URL, the way a hub behind Cloudflare Tunnel would be.
dave, erin, frank and gina simulate being behind NAT: they never advertise a direct address, only a
relay, and they get direct peer-to-peer paths by UDP hole punching through their hub.

    python demo.py   (with .venv activated)
"""

import asyncio
import json
import os
import tempfile
from pathlib import Path

from lichen.crypto import encrypt
from lichen.files import CHUNK
from lichen import keyfile
from lichen.commands import Commands
from lichen.groups import GroupRecord
from lichen.identity import Identity, canonical, safety_code
from lichen.node import Node
from lichen.records import Record
from lichen.wire import request

PHONEBOOK = "127.0.0.1:9100"
HUB2 = "127.0.0.1:9200"
WSHUB = "ws://127.0.0.1:9300"
PUNCH = 1.0  # seconds to let a hole punch finish
SETTLE = 0.5  # seconds for gossip to propagate


async def wait_until(cond, timeout: float = 5.0) -> bool:
    for _ in range(int(timeout / 0.1)):
        if cond():
            return True
        await asyncio.sleep(0.1)
    return False


def step(text: str) -> None:
    print(f"\n== {text}")


async def main() -> None:
    tmp = Path(tempfile.mkdtemp(prefix="lichen-"))
    inbox: dict[str, list[str]] = {}
    group_inbox: dict[str, list[tuple[str, bool]]] = {}

    def make(name: str, port: int, bootstrap: list[str], relay=None, url: str | None = None) -> Node:
        def on_message(rec, text, env):
            inbox.setdefault(name, []).append(f"{rec.name if rec else '?'}: {text}")
            print(f"   [{name} inbox] <{rec.name if rec else '?'}> {text}")

        def on_group_message(group, rec, text, env, live):
            group_inbox.setdefault(name, []).append((text, live))
            print(f"   [{name} {group.name}] <{rec.name if rec else '?'}> {text}{'' if live else '  (caught up)'}")

        return Node.from_dir(tmp / name, name, port=port, bootstrap=bootstrap, relay=relay, direct=relay is None, stun=[], url=url,
                             on_message=on_message, on_group_message=on_group_message,
                             log=lambda s, n=name: print(f"   {n}: {s}"))

    step("phonebook server starts at a static address")
    pb = make("phonebook", 9100, [])
    await pb.start()

    step("alice, bob, carol each know ONLY the phonebook address")
    alice, bob, carol = make("alice", 9101, [PHONEBOOK]), make("bob", 9102, [PHONEBOOK]), make("carol", 9103, [PHONEBOOK])
    for n in (alice, bob, carol):
        await n.start()
    await asyncio.sleep(SETTLE)
    for n in (pb, alice, bob, carol):
        names = sorted(r.name for r in n.phonebook.all())
        print(f"   {n.name:<9} knows {names}")
        assert len(names) == 4, f"{n.name} should know everyone"

    step("alice messages bob directly (no server in the path)")
    assert await alice.send(bob.ident.pubkey, "hi bob!")

    step("alice's address changes; she gossips it")
    await alice.move(9151)
    await asyncio.sleep(SETTLE)
    assert bob.phonebook.get(alice.ident.pubkey).addrs == ["127.0.0.1:9151"]
    assert carol.phonebook.get(alice.ident.pubkey).addrs == ["127.0.0.1:9151"]
    assert await carol.send(alice.ident.pubkey, "found you at your new address")

    step("bob goes offline, alice moves AGAIN while he's away, bob returns on a new port")
    await bob.stop()
    await alice.move(9152)
    await asyncio.sleep(SETTLE)
    # Same key + stale phonebook on disk, and no explicit bootstrap: he only has what he saved.
    bob = make("bob", 9162, [])
    stale = bob.phonebook.get(alice.ident.pubkey).addrs  # what he saved before going offline
    print(f"   bob's stored address for alice: {stale}")
    assert stale == ["127.0.0.1:9151"], "bob should start out with a stale record"
    await bob.start()  # catching up on his chat with alice already finds her new address
    assert await bob.send(alice.ident.pubkey, "back online, phonebook was stale but I found you")
    await asyncio.sleep(SETTLE)
    assert alice.phonebook.get(bob.ident.pubkey).addrs == ["127.0.0.1:9162"]

    step("alice creates group 'devs' (history shared) with bob and carol, and posts to it")
    devs = await alice.create_group("devs", [bob.ident.pubkey, carol.ident.pubkey], history="shared")
    gid = devs.group_id
    assert bob.groups.get(gid) and carol.groups.get(gid)
    assert [g.name for g in bob.pending_groups()] == ["devs"], "being added is an invite"
    await bob.accept_group(gid)
    await carol.accept_group(gid)
    assert await alice.send_group(gid, "standup in 5") == (2, 2)

    step("carol goes offline; bob posts twice while she's gone")
    await carol.stop()
    print(f"   bob delivered to {await bob.send_group(gid, 'I will be 2 min late')} (delivered, members)")
    assert await bob.send_group(gid, "ok joining now") == (1, 2)

    step("carol comes back on a new port and catches up from the others")
    carol = make("carol", 9173, [])
    await carol.start()
    assert [t for t, _ in group_inbox["carol"]][-2:] == ["I will be 2 min late", "ok joining now"]
    assert len(carol.groups.messages(gid)) == 3

    step("alice adds the phonebook node; it pulls the history")
    await alice.add_member(gid, pb.ident.pubkey)
    await asyncio.sleep(SETTLE)
    await pb.accept_group(gid)
    assert len(pb.groups.messages(gid)) == 3
    assert len(carol.groups.get(gid).members) == 4

    step("alice removes bob; he can't read new messages or post")
    await alice.remove_member(gid, bob.ident.pubkey)
    await asyncio.sleep(SETTLE)
    try:
        await bob.send_group(gid, "hello?")
        raise AssertionError("bob should know he was removed")
    except ValueError as e:
        print(f"   bob: {e}")
    await alice.send_group(gid, "bob is gone, new key")
    current = carol.groups.get(gid).epoch
    assert current not in bob.groups.keys[gid], "bob must not get the new epoch key"
    print(f"   bob has keys for epochs {sorted(bob.groups.keys[gid])}, group is on epoch {current}")
    # Bob hand-crafts a message under the last key he had, attaching the old member list.
    old_epoch = max(bob.groups.keys[gid])
    inner = {"group": gid, "from": bob.ident.pubkey, "ts": 0, "nonce": "x", "text": "sneaky", "private": False}
    inner["sig"] = bob.ident.sign(canonical(inner))
    box = encrypt(bob.groups.keys[gid][old_epoch], canonical(inner), canonical({"group": gid, "epoch": old_epoch}))
    from lichen.groups import _msg_id
    sneaky = {"type": "group_msg", "group": devs.to_dict(),
              "envelope": {"group": gid, "epoch": old_epoch, **box, "id": _msg_id(box["ct"])}}
    reply = await request(carol.addr, sneaky)
    assert reply == {"ok": False, "error": "rejected"}, reply
    print("   carol rejected bob's message (and ignored the stale member list he attached)")

    step("private group: alice and carol talk, then alice flips to shared and adds the phonebook node")
    secret = await alice.create_group("secret", [carol.ident.pubkey])  # history private by default
    sid = secret.group_id
    await carol.accept_group(sid)
    await alice.send_group(sid, "the surprise party is on friday")
    await carol.send_group(sid, "don't tell phonebook")
    await alice.set_history(sid, "shared")
    await carol.send_group(sid, "(sent after the switch to shared)")
    await alice.add_member(sid, pb.ident.pubkey)
    await asyncio.sleep(SETTLE)
    await pb.accept_group(sid)
    seen = [e["text"] for e in pb.groups.messages(sid)]
    print(f"   phonebook can see: {seen}")
    assert seen == ["(sent after the switch to shared)"], "private-era messages must not be synced"
    assert 1 not in pb.groups.keys[sid], "phonebook must not hold the private-era key"
    print(f"   phonebook holds keys for epochs {sorted(pb.groups.keys[sid])}; epoch 1 (private) can't be decrypted")
    await alice.send_group(sid, "welcome!")
    assert pb.groups.messages(sid)[-1]["text"] == "welcome!"

    step("dave and erin are behind NAT: they only reach the world through the phonebook's relay")
    dave = make("dave", 9180, [PHONEBOOK], relay=PHONEBOOK)
    erin = make("erin", 9181, [PHONEBOOK], relay=PHONEBOOK)
    await dave.start()
    await erin.start()
    await asyncio.sleep(SETTLE)
    print(f"   erin's address as alice sees it: {alice.phonebook.get(erin.ident.pubkey).addrs}")
    assert alice.phonebook.get(erin.ident.pubkey).addrs == [f"relay:{PHONEBOOK}"]
    assert await dave.send(erin.ident.pubkey, "hi erin, via the relay") == "delivered"
    assert await alice.send(dave.ident.pubkey, "direct node -> NAT node works too") == "delivered"

    step("that first relayed message triggered a hole punch; the next ones skip the hub")
    await asyncio.sleep(PUNCH)
    assert dave.udp.path(erin.ident.pubkey) and erin.udp.path(dave.ident.pubkey)
    relayed = pb.hub.forwarded
    assert await dave.send(erin.ident.pubkey, "this one went peer to peer") == "delivered"
    assert await erin.send(dave.ident.pubkey, "so did this reply") == "delivered"
    assert pb.hub.forwarded == relayed, "direct messages must not touch the relay"
    print(f"   hub relayed 0 of those 2 messages (dave's path to erin: {dave.udp.path(erin.ident.pubkey)})")

    step("erin goes offline; the hub keeps only an IOU (no message), and nudges dave when she's back")
    await erin.stop()
    await asyncio.sleep(SETTLE)
    assert await dave.send(erin.ident.pubkey, "the secret word is pineapple") == "waiting"
    stored = json.dumps(pb.hub.ious)
    assert pb.hub.owed_to(erin.ident.pubkey) == {dave.ident.pubkey: [next(iter(dave.outbox.items))]}
    assert "pineapple" not in stored and "sealed" not in stored, "the hub holds no message, not even ciphertext"
    print(f"   hub stores only: {{erin: {{dave: [item id]}}}} ({len(stored)} bytes); the message waits in dave's outbox")
    erin = make("erin", 9181, [PHONEBOOK], relay=PHONEBOOK)
    await erin.start()
    await asyncio.sleep(SETTLE)
    assert inbox["erin"][-1] == "dave: the secret word is pineapple"
    assert not pb.hub.owed_to(erin.ident.pubkey) and not dave.outbox.items
    assert inbox["erin"].count("dave: the secret word is pineapple") == 1

    step("a group of NAT'd and direct nodes")
    mixed = await dave.create_group("mixed", [erin.ident.pubkey, alice.ident.pubkey])
    await asyncio.sleep(SETTLE)
    await erin.accept_group(mixed.group_id)
    await alice.accept_group(mixed.group_id)
    assert await erin.send_group(mixed.group_id, "hello from behind NAT") == (2, 2)
    assert alice.groups.messages(mixed.group_id)[-1]["text"] == "hello from behind NAT"

    step("a second hub starts and joins the first: their phonebooks merge")
    hub2 = make("hub2", 9200, [PHONEBOOK])
    await hub2.start()
    frank = make("frank", 9201, [HUB2], relay=HUB2)
    await frank.start()
    await asyncio.sleep(SETTLE)
    print(f"   frank (on hub2) knows: {sorted(r.name for r in frank.phonebook.all())}")
    assert dave.phonebook.get(frank.ident.pubkey), "hub1 users learn about hub2 users"
    assert frank.phonebook.get(dave.ident.pubkey), "hub2 users learn about hub1 users"
    gina = make("gina", 9202, [PHONEBOOK], relay=PHONEBOOK)
    await gina.start()
    assert gina.phonebook.get(frank.ident.pubkey), "someone joining hub1 later sees hub2's users"
    print(f"   gina joins hub1 later and already knows frank: {bool(gina.phonebook.get(frank.ident.pubkey))}")

    step("dave (hub1) and frank (hub2) talk; hub2 introduces them and they go direct")
    assert await dave.send(frank.ident.pubkey, "hello from the other hub") == "delivered"
    await asyncio.sleep(PUNCH)
    assert dave.udp.path(frank.ident.pubkey), "should have punched through via frank's hub"
    relayed = hub2.hub.forwarded
    assert await frank.send(dave.ident.pubkey, "hi dave, this is direct") == "delivered"
    assert hub2.hub.forwarded == relayed and inbox["dave"][-1] == "frank: hi dave, this is direct"

    step("a hub reachable only by WebSocket URL (like one behind Cloudflare Tunnel) joins the network")
    wshub = make("wshub", 9300, [PHONEBOOK], url=WSHUB)
    await wshub.start()
    hank = make("hank", 9301, [WSHUB], relay=WSHUB)
    await hank.start()
    await asyncio.sleep(SETTLE)
    print(f"   hank's address as dave sees it: {dave.phonebook.get(hank.ident.pubkey).addrs}")
    assert dave.phonebook.get(hank.ident.pubkey).addrs == [f"relay:{WSHUB}"]
    assert pb.phonebook.get(wshub.ident.pubkey).addrs == [WSHUB]
    assert hank.phonebook.get(frank.ident.pubkey), "hank sees users from all three hubs"
    assert await dave.send(hank.ident.pubkey, "hi hank, through your websocket hub") == "delivered"
    await asyncio.sleep(PUNCH)
    assert dave.udp.path(hank.ident.pubkey), "punch request travelled over the websocket relay"
    relayed = wshub.hub.forwarded
    assert await hank.send(dave.ident.pubkey, "and back, peer to peer") == "delivered"
    assert wshub.hub.forwarded == relayed and inbox["dave"][-1] == "hank: and back, peer to peer"
    print("   hank <-> dave went direct; the websocket hub only introduced them")

    step("group invites: nothing shows until you accept; declining removes you")
    mal_key = Identity.generate()
    club = await alice.create_group("club", [carol.ident.pubkey, bob.ident.pubkey])
    await asyncio.sleep(SETTLE)
    before = len(group_inbox.get("carol", []))
    await alice.send_group(club.group_id, "welcome to the club")
    await asyncio.sleep(SETTLE)
    assert len(group_inbox.get("carol", [])) == before, "pending invites stay quiet"
    assert [g.name for g in carol.pending_groups()] == ["club"]
    try:
        await carol.send_group(club.group_id, "hi?")
        raise AssertionError("can't post before accepting")
    except ValueError as e:
        print(f"   carol before accepting: {e}")
    await carol.accept_group(club.group_id)
    assert [m["text"] for m in carol.groups.messages(club.group_id)] == ["welcome to the club"]
    await bob.leave_group(club.group_id, status="declined")
    await asyncio.sleep(SETTLE)
    assert bob.ident.pubkey not in alice.groups.get(club.group_id).members, "owner removes a decliner"
    assert bob.ident.pubkey not in carol.groups.get(club.group_id).members
    print("   bob declined; alice's node removed him and rotated the key")

    step("the host can't just leave: they hand the group to a successor (or delete it)")
    try:
        await alice.leave_group(club.group_id)
        raise AssertionError("the host shouldn't be able to just walk away")
    except ValueError as e:
        print(f"   alice: {e}")
    assert await alice.transfer_group(club.group_id, carol.ident.pubkey, leave=True) == "delivered"
    assert await wait_until(lambda: carol.groups.get(club.group_id).owner == carol.ident.pubkey)
    await asyncio.sleep(SETTLE)
    rec = carol.groups.get(club.group_id)
    assert alice.ident.pubkey not in rec.members and rec.transfers[0]["to"] == carol.ident.pubkey
    assert alice.groups.get(club.group_id).owner == carol.ident.pubkey, "alice saw the hand-over too"
    assert GroupRecord.from_dict(rec.to_dict()), "anyone can verify the ownership chain from scratch"
    forged = rec.to_dict()
    forged["transfers"] = [{**forged["transfers"][0], "to": mal_key.pubkey}]
    assert GroupRecord.from_dict(forged) is None, "a tampered chain is rejected"
    try:
        await alice.add_member(club.group_id, bob.ident.pubkey)
        raise AssertionError("the old host can't manage it any more")
    except ValueError:
        pass
    print("   carol is the host now; alice is out; the chain verifies and can't be forged")
    await carol.add_member(club.group_id, pb.ident.pubkey)
    await asyncio.sleep(SETTLE)
    await pb.accept_group(club.group_id)
    await carol.send_group(club.group_id, "new management")
    assert await wait_until(lambda: any(m["text"] == "new management" for m in pb.groups.messages(club.group_id)))
    assert pb.groups.messages(club.group_id)[-1]["text"] == "new management"
    print("   the new host can invite people and the group carries on")
    await carol.delete_group(club.group_id)
    await asyncio.sleep(SETTLE)
    assert not pb.groups.joined(club.group_id) and not pb.groups.messages(club.group_id)
    assert pb.groups.status[club.group_id] == "deleted" and not carol.groups.messages(club.group_id)
    print("   carol deleted it: members' copies of the messages and keys are gone")
    solo = await alice.create_group("solo", [])
    try:
        await alice.leave_group(solo.group_id)
    except ValueError as e:
        print(f"   alice (only member of #solo): {e}")

    step("trust: strangers' messages are requests; blocked senders are dropped")
    mal = make("mal", 9190, [PHONEBOOK], relay=PHONEBOOK)
    await mal.start()
    await asyncio.sleep(SETTLE)
    assert dave.trust.get(mal.ident.pubkey) == "unknown"
    assert await mal.send(dave.ident.pubkey, "hi, you don't know me") == "delivered"
    assert dave.dms.messages(mal.ident.pubkey)[-1]["text"] == "hi, you don't know me"
    assert dave.trust.get(mal.ident.pubkey) == "unknown", "receiving doesn't trust them; it's a request"
    assert dave.trust.trusted(erin.ident.pubkey), "dave messaged erin, so she's a contact"
    dave.trust.set(mal.ident.pubkey, "blocked")
    await mal.send(dave.ident.pubkey, "let me in")
    await asyncio.sleep(SETTLE)
    assert dave.dms.messages(mal.ident.pubkey)[-1]["text"] == "hi, you don't know me", "blocked: dropped"
    print("   mal's first message waits as a request; after /block, nothing gets through")

    step("files: an offer, then an encrypted chunked download (direct path), verified by hash")
    blob = tmp / "holiday.bin"
    blob.write_bytes(os.urandom(300_000))
    fid, entry, offered = await dave.send_file(blob, [erin.ident.pubkey])
    await asyncio.sleep(SETTLE)
    assert offered == 1 and erin.files.incoming[fid]["status"] == "pending", "nothing downloads unasked"
    print(f"   erin got an offer: {erin.files.incoming[fid]['name']} ({entry['size']} bytes); accepting")
    saved = await erin.download(fid)
    assert saved and saved.read_bytes() == blob.read_bytes() and erin.files.incoming[fid]["status"] == "done"
    assert saved.parent == tmp / "erin" / "downloads"
    print(f"   saved to {saved.relative_to(tmp)}; bytes match")

    step("files: an interrupted download resumes where it stopped")
    fid2, _, _ = await dave.send_file(blob, [erin.ident.pubkey])
    await asyncio.sleep(SETTLE)
    part = erin.files.part_path(fid2)
    part.parent.mkdir(parents=True, exist_ok=True)
    part.write_bytes(blob.read_bytes()[:CHUNK + 1000])  # one whole chunk plus a torn one
    assert erin.files.resume_offset(fid2) == CHUNK
    saved2 = await erin.download(fid2)
    assert saved2 and saved2.read_bytes() == blob.read_bytes() and saved2.name == "holiday (1).bin"
    print(f"   resumed from {CHUNK} bytes; saved as {saved2.name} (no overwrite)")

    step("files: sharing to a group; trusted small files can auto-download; strangers' never do")
    alice.auto_download_mb = 1
    alice.trust.set(dave.ident.pubkey, "unknown")  # she messaged him earlier; make him a stranger again
    note = tmp / "notes.txt"
    note.write_text("agenda: everything")
    fid3, _, offered = await dave.send_file(note, [], group=mixed.group_id)
    assert offered == 2
    await asyncio.sleep(SETTLE)
    assert erin.files.incoming[fid3]["status"] == "pending", "erin didn't opt in to auto-download"
    assert alice.trust.get(dave.ident.pubkey) == "unknown"
    assert alice.files.incoming[fid3]["status"] == "pending", "alice doesn't trust dave yet: asks first"
    alice.trust.set(dave.ident.pubkey, "trusted")
    fid4, _, _ = await dave.send_file(note, [alice.ident.pubkey])
    assert await wait_until(lambda: alice.files.incoming.get(fid4, {}).get("status") == "done")
    print("   alice auto-downloaded the second offer once she trusted dave")
    erin.max_file_mb = 0.1
    fid5, _, _ = await dave.send_file(blob, [erin.ident.pubkey])
    await asyncio.sleep(SETTLE)
    assert erin.files.incoming[fid5]["status"] == "refused"
    print(f"   over erin's size limit: {erin.files.incoming[fid5]['error']}")

    step("IOUs at two hubs, one message: ivy uses both hubs; dave goes offline too")
    ivy = make("ivy", 0, [PHONEBOOK], relay=[PHONEBOOK, HUB2])
    await ivy.start()
    await asyncio.sleep(SETTLE)
    assert dave.phonebook.get(ivy.ident.pubkey).addrs == [f"relay:{PHONEBOOK}", f"relay:{HUB2}"]
    await ivy.stop()
    await asyncio.sleep(SETTLE)
    assert await dave.send(ivy.ident.pubkey, "left at two hubs") == "waiting"
    item = next(iter(dave.outbox.items))
    assert pb.hub.owed_to(ivy.ident.pubkey) == hub2.hub.owed_to(ivy.ident.pubkey) == {dave.ident.pubkey: [item]}
    print("   both hubs hold the same IOU item id")
    await dave.stop()  # now nobody can deliver it
    ivy = make("ivy", 0, [PHONEBOOK], relay=[PHONEBOOK, HUB2])
    await ivy.start()
    await asyncio.sleep(SETTLE)
    assert "left at two hubs" not in "".join(inbox.get("ivy", [])), "dave's offline: nothing to deliver yet"
    print("   ivy is back; both hubs told her dave owes her something, and tried to nudge dave (offline)")
    dave = make("dave", 9180, [PHONEBOOK], relay=PHONEBOOK)
    await dave.start()  # flushes his outbox on startup
    await asyncio.sleep(SETTLE)
    assert inbox["ivy"].count("dave: left at two hubs") == 1 and not dave.outbox.items
    print("   dave came back online and delivered it: exactly once")

    step("both hubs nudge at once: still delivered once")
    await ivy.stop()
    await asyncio.sleep(SETTLE)
    assert await dave.send(ivy.ident.pubkey, "nudged twice") == "waiting"
    ivy = make("ivy", 0, [PHONEBOOK], relay=[PHONEBOOK, HUB2])
    await ivy.start()
    assert await wait_until(lambda: "dave: nudged twice" in inbox.get("ivy", []))
    await asyncio.sleep(SETTLE)
    assert inbox["ivy"].count("dave: nudged twice") == 1
    print("   two nudges, one message (outbox flushes are per-peer, and ids dedupe anyway)")

    step("chat sync: a gap in a conversation heals, in order, without duplicates")
    convo = erin.dms.convos[dave.ident.pubkey]
    lost = convo.pop(1)
    erin.dms._ids[dave.ident.pubkey].discard(lost["id"])
    erin.dms.save()
    assert await erin.sync_dm(dave.ident.pubkey) == 1
    assert erin.dms.messages(dave.ident.pubkey)[1]["id"] == lost["id"], "restored in its place, by time"
    assert await erin.sync_dm(dave.ident.pubkey) == 0
    print(f"   erin was missing {lost['text']!r}; sync restored it (and a second sync found nothing new)")

    step("identity: an impostor takes the name 'erin'; names get disambiguated and flagged")
    fake = Node.from_dir(tmp / "fake-erin", "erin", port=0, bootstrap=[PHONEBOOK], relay=PHONEBOOK,
                         direct=False, stun=[])
    await fake.start()
    await asyncio.sleep(SETTLE)
    said = []
    cmds = Commands(dave, said.append)
    real_name, fake_name = cmds.name_of(erin.ident.pubkey), cmds.name_of(fake.ident.pubkey)
    print(f"   dave now sees: {real_name} (his contact) and {fake_name}")
    assert real_name.startswith("erin#") and fake_name.startswith("erin#") and real_name != fake_name
    assert not cmds.impostor_warning(erin.ident.pubkey)
    assert "different key" in cmds.impostor_warning(fake.ident.pubkey)
    assert cmds.peer("erin") is None and "several people match" in said[-1]
    await cmds.run(f"/nick {real_name} erin")
    assert cmds.name_of(erin.ident.pubkey) == "erin" and cmds.peer("erin").pubkey == erin.ident.pubkey
    print(f"   after /nick: the real one is just 'erin' to dave; the impostor stays {cmds.name_of(fake.ident.pubkey)} (flagged)")
    await fake.send(dave.ident.pubkey, "hi it's erin, new account")
    assert dave.trust.get(fake.ident.pubkey) == "unknown", "the impostor's message is only a request"

    step("identity: safety codes match on both sides; /verify marks the contact")
    erin_cmds = Commands(erin, lambda _: None)
    assert safety_code(dave.ident.pubkey, erin.ident.pubkey) == safety_code(erin.ident.pubkey, dave.ident.pubkey)
    assert safety_code(dave.ident.pubkey, fake.ident.pubkey) != safety_code(dave.ident.pubkey, erin.ident.pubkey)
    await cmds.run("/verify erin")
    assert safety_code(dave.ident.pubkey, erin.ident.pubkey) in said[-4]
    await cmds.run("/verify erin yes")
    assert dave.trust.is_verified(erin.ident.pubkey)
    print(f"   code {safety_code(dave.ident.pubkey, erin.ident.pubkey)} matches for both; erin is verified")
    await fake.stop()

    step("identity: passphrase-protected keys are useless without the passphrase")
    locked = tmp / "locked"
    Node.from_dir(locked, "locked")
    keyfile.change_passphrase(locked, None, "hunter2")
    for attempt in (None, "wrong"):
        try:
            Node.from_dir(locked, "locked", passphrase=attempt)
            raise AssertionError("opened without the right passphrase")
        except (keyfile.PassphraseNeeded, keyfile.WrongPassphrase) as e:
            print(f"   passphrase {attempt!r}: refused ({type(e).__name__})")
    assert Node.from_dir(locked, "locked", passphrase="hunter2").ident.pubkey

    step("mallory forges a record claiming alice lives at mallory's address")
    mallory = Identity.generate()
    forged = Record(alice.ident.pubkey, "alice", ["127.0.0.1:6666"], alice.me.seq + 10**9)
    forged.sig = mallory.sign(b"whatever")
    await request(PHONEBOOK, {"type": "announce", "record": forged.to_dict()})
    await asyncio.sleep(SETTLE)
    assert pb.phonebook.get(alice.ident.pubkey).addrs == ["127.0.0.1:9152"]
    print("   rejected: signature doesn't match alice's key")

    for n in (pb, hub2, wshub, alice, bob, carol, dave, erin, frank, gina, hank, mal, ivy):
        await n.stop()

    assert inbox["bob"] and len(inbox["alice"]) == 2
    assert ("I will be 2 min late", False) in group_inbox["carol"]  # recovered, not live
    print("\nALL CHECKS PASSED")


if __name__ == "__main__":
    asyncio.run(main())
