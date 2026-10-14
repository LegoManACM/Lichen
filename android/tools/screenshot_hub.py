"""A hub on your LAN with a few made-up people, for store screenshots that look lived-in.

    .venv/Scripts/python.exe android/tools/screenshot_hub.py [--host 192.168.1.191] [--fresh]

First run (or --fresh): makes the people, plays out a couple of days of chats with "Sam" (backdated with a
fake clock), and writes Sam's identity to data/screenshots/me.lichen (passphrase: lichen). Import that on
the phone ("Import from my computer") and it opens with the history already there.

It then keeps running: the people stay online, answer when you write to them, and once your phone
attaches, Maya sends a photo. Later runs reuse the same people, so a phone that already imported Sam
keeps working. Ctrl+C to stop. Everything lives in data/screenshots (git-ignored).
"""

import argparse
import asyncio
import random
import shutil
import socket
import struct
import sys
import time
import zlib
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO))

from lichen.devices import export_identity  # noqa: E402
from lichen.node import Node  # noqa: E402

DATA = REPO / "data" / "screenshots"
PORT = 9300
PASSPHRASE = "lichen"
PEOPLE = ["Maya Chen", "Theo Okafor", "Priya Natarajan", "Lena Novak", "Jonas Weber"]
ME = "Sam"

# A fake clock, so the history spreads over the last couple of days instead of the last ten seconds.
# Every node runs in this process, so they all agree on it; it goes back to real time before the phone joins.
_real_time = time.time
_offset = [0.0]
time.time = lambda: _real_time() - _offset[0]


def at(hours_ago: float) -> None:
    _offset[0] = hours_ago * 3600


def later(minutes: float) -> None:
    _offset[0] = max(0.0, _offset[0] - minutes * 60)


def lan_ip() -> str:
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
        s.connect(("192.0.2.1", 9))  # no packet is sent; this just picks the outgoing interface
        return s.getsockname()[0]


def sunset_png(path: Path, w: int = 960, h: int = 640) -> None:
    """A small sunset-over-hills picture, so the photo card has something to show."""
    rows = []
    for y in range(h):
        t = y / h
        row = bytearray([0])
        for x in range(w):
            hill = h * (0.62 + 0.08 * __import__("math").sin(x / 90) + 0.04 * __import__("math").sin(x / 37))
            if y > hill:
                c = (40 + int(20 * t), 52 + int(25 * t), 38)
            else:
                c = (int(250 - 90 * t), int(170 - 110 * t), int(90 + 60 * t))
                dx, dy = x - w * 0.68, y - h * 0.5
                if dx * dx + dy * dy < 55 ** 2:
                    c = (255, 214, 140)
            row += bytes(c)
        rows.append(bytes(row))

    def chunk(tag: bytes, data: bytes) -> bytes:
        return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", zlib.crc32(tag + data))

    path.write_bytes(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0))
                     + chunk(b"IDAT", zlib.compress(b"".join(rows), 9)) + chunk(b"IEND", b""))


REPLIES = ["haha yes", "sounds good 👍", "ok, talk later!", "wait really?", "I'll check and get back to you",
           "same here", "deal", "omg 😂"]


async def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--host", default=None, help="this PC's address as the phone sees it (default: LAN IP)")
    ap.add_argument("--fresh", action="store_true", help="throw away the people and history and start over")
    args = ap.parse_args()
    host = args.host or lan_ip()
    hub_addr = f"{host}:{PORT}"
    export = DATA / "me.lichen"
    if args.fresh and DATA.exists():
        shutil.rmtree(DATA)
    scripted = not export.exists()

    hub = Node.from_dir(DATA / "hub", "Test hub", host=host, bind="0.0.0.0", port=PORT, stun=[])
    await hub.start()
    people: dict[str, Node] = {}
    loop = asyncio.get_running_loop()

    def person(name: str, auto_reply: bool = True) -> Node:
        node: Node

        def on_message(rec, text, env):
            if auto_reply and rec and rec.name == ME and not scripted_now[0]:
                async def reply():
                    await asyncio.sleep(random.uniform(1.5, 4))
                    await node.send(rec.pubkey, random.choice(REPLIES))
                loop.create_task(reply())

        node = Node.from_dir(DATA / name.split()[0].lower(), name, bootstrap=[hub_addr], relay=hub_addr,
                             direct=False, stun=[], on_message=on_message)
        return node

    scripted_now = [scripted]
    for name in PEOPLE:
        people[name] = person(name)
        await people[name].start()
    maya, theo, priya, lena, jonas = (people[n] for n in PEOPLE)

    if scripted:
        print("Playing out the chat history...")
        me = Node.from_dir(DATA / "sam", ME, bootstrap=[hub_addr], relay=hub_addr, direct=False, stun=[])
        await me.start()
        await asyncio.sleep(1)
        S = me.ident.pubkey
        for p in (maya, theo, priya, lena):  # Jonas stays a stranger: he shows up under Requests
            me.trust.set(p.ident.pubkey, "trusted")
            p.trust.set(S, "trusted")

        async def say(who: Node, to: Node, text: str, gap: float = 1.5) -> None:
            later(gap)
            await who.send(to.ident.pubkey, text)

        async def post(who: Node, gid: str, text: str, gap: float = 1.5) -> None:
            later(gap)
            await who.send_group(gid, text)

        # Two days ago: Maya sets up Saturday's climb.
        at(52)
        climb = await maya.create_group("Saturday climb 🧗", [S, theo.ident.pubkey, priya.ident.pubkey],
                                        history="shared")
        for n in (me, theo, priya):
            await n.accept_group(climb.group_id)
        g = climb.group_id
        await post(maya, g, "ok who's in for saturday? weather looks perfect")
        await post(theo, g, "me! need to borrow a harness though")
        await post(me, g, "in. I have a spare harness, Theo")
        await post(priya, g, "I can drive, 4 seats")
        await post(maya, g, "amazing. meet at mine at 8?")
        await post(theo, g, "8 is brutal but fine 😴", 4)
        await post(me, g, "I'll bring coffee")

        # Yesterday evening: the flat chat.
        at(26)
        flat = await lena.create_group("Flat 4B", [S, theo.ident.pubkey], history="shared")
        for n in (me, theo):
            await n.accept_group(flat.group_id)
        f = flat.group_id
        await post(lena, f, "who finished the oat milk 👀")
        await post(theo, f, "...guilty")
        await post(me, f, "I'm at the shop, I'll grab two")
        await post(lena, f, "hero. also bins go out tomorrow")

        at(20)
        await say(theo, me, "did you end up getting the record player?")
        await say(me, theo, "yes! came yesterday, it sounds so good")
        await say(theo, me, "bring some records saturday, we can play them at maya's after")
        await say(me, theo, "deal")

        at(3)
        await say(priya, me, "hey, are you still up for proofreading my talk?")
        await say(me, priya, "of course, send it over")
        await say(priya, me, "you're the best. it's 12 slides, no rush")
        await say(me, priya, "I'll look tonight")

        at(1)
        await say(maya, me, "found the guidebook! the north wall routes look fun")
        await say(me, maya, "nice, any 6a's?")
        await say(maya, me, "a few. and one 6c if we're feeling brave", 2)

        at(0.2)
        await say(jonas, me, "Hi! Priya gave me your key, I'm in her design class. Could I ask you about Lichen?")

        _offset[0] = 0  # real time from here on
        await asyncio.sleep(1)
        await me.stop()
        config = f"name: {ME}\nrelay: [{hub_addr}]\n"
        export_identity(DATA / "sam", export, PASSPHRASE, config=config)
        sunset_png(DATA / "saturday.png")
        scripted_now[0] = False

    sam = next(iter(maya.trust.levels))  # the one key Maya trusts
    print(f"\nHub running at {hub_addr} (phone and PC on the same Wi-Fi).")
    print(f"Import {export} on the phone, passphrase: {PASSPHRASE}")
    print("Ctrl+C to stop.\n")

    sent_photo = False
    while True:
        await asyncio.sleep(2)
        if not sent_photo and sam in hub.hub.attached:
            print("Sam's phone attached.")
            await asyncio.sleep(5)
            await maya.send(sam, "look what I found from last time")
            photo = DATA / "saturday.png"
            if not photo.exists():
                sunset_png(photo)
            await maya.send_file(photo, [sam])
            sent_photo = True
            print("Maya sent the photo.")


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        pass
