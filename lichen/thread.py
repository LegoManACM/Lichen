"""Turning a conversation's raw messages into what you see: replies, reactions, edits and deletes.

Everything is a message (signed, encrypted, synced and deduplicated like any other); some just carry
an action pointing at another message's id:

  {"text": "...", "reply_to": id}           a normal message that quotes another one
  {"kind": "react",  "target": id, "text": emoji}   toggles that emoji from you on the target
  {"kind": "edit",   "target": id, "text": new}     replaces the target's text (author only)
  {"kind": "delete", "target": id}                  removes the target (author only)

Actions are applied in timestamp order. Edits and deletes from anyone but the target's author are
ignored, so nobody can rewrite or remove other people's messages.
"""

from dataclasses import dataclass, field

ACTIONS = ("react", "edit", "delete")
EMOJI = {"+1": "👍", "-1": "👎", "heart": "❤️", "laugh": "😂", "lol": "😂", "wow": "😮", "sad": "😢",
         "fire": "🔥", "ok": "👌", "party": "🎉", "eyes": "👀", "100": "💯", "check": "✅", "pray": "🙏"}


def emoji(text: str) -> str:
    text = text.strip()
    return EMOJI.get(text.lower().strip(":"), text)


@dataclass
class Item:
    id: str
    sender: str
    ts: float
    text: str
    number: int = 0  # 1-based position among visible messages, for /reply 3, /react 3 ...
    reply_to: str | None = None
    edited: bool = False
    deleted: bool = False
    reactions: dict[str, list[str]] = field(default_factory=dict)  # emoji -> senders, in order


def build(messages: list[dict]) -> list[Item]:
    """Visible messages, oldest first, with actions applied. Each raw message needs id/from/ts/text."""
    items: dict[str, Item] = {}
    order: list[Item] = []
    actions = []
    for m in sorted(messages, key=lambda m: m.get("ts", 0)):
        if m.get("kind") in ACTIONS:
            actions.append(m)
            continue
        item = Item(m["id"], m["from"], m["ts"], str(m.get("text", "")), reply_to=m.get("reply_to"))
        items[item.id] = item
        order.append(item)
    for a in actions:
        target = items.get(str(a.get("target")))
        if not target:
            continue  # target not here (yet); a later sync may bring it
        if a["kind"] == "react":
            senders = target.reactions.setdefault(emoji(str(a.get("text", ""))), [])
            if a["from"] in senders:
                senders.remove(a["from"])
            else:
                senders.append(a["from"])
        elif a["from"] != target.sender:
            continue  # only authors can edit or delete
        elif a["kind"] == "edit" and not target.deleted:
            target.text, target.edited = str(a.get("text", "")), True
        elif a["kind"] == "delete":
            target.text, target.deleted = "", True
    for item in order:
        item.reactions = {e: s for e, s in item.reactions.items() if s}
    for n, item in enumerate(order, 1):
        item.number = n
    return order


def by_number(items: list[Item], number: int) -> Item | None:
    return items[number - 1] if 0 < number <= len(items) else None
