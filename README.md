# Lichen

Prototype of a decentralized, end-to-end encrypted chat where everyone keeps a phonebook of everyone else.

- **You are your key.** Each node has an Ed25519 key (identity, signatures) and an X25519 key
  (encryption). The Ed25519 public key is who you are.
- **Records are signed.** A contact record says "this key lives at these addresses, and here's how to
  encrypt to it", with a sequence number, signed by that key. Anyone can store and forward records
  without being trusted; forgeries fail verification and stale records lose to newer `seq`.
- **Moving = gossip.** When your address changes you sign a new record and send it to everyone you
  know; anyone who learns something new forwards it on.
- **Find one person, reach everyone.** `hello` to any node returns its whole phonebook.
- **Phonebook server = just a node** at a public address: an IP/domain with an open port, or a
  `wss://` URL behind Cloudflare Tunnel or any HTTPS proxy. It introduces peers so they can connect
  **directly** (UDP hole punching), and relays for anyone whose network won't allow that.
- **Hubs federate.** Point one hub at another and their phonebooks merge; users on either can reach
  users on both.
- **Stale address?** If delivery fails, the sender asks its contacts and bootstrap nodes for a fresher
  record and retries.
- **Everything is end-to-end encrypted.** Relays and phonebooks only see ciphertext and who it's for.

## Install

Needs Python 3.11+. Straight from GitHub:

```powershell
pip install git+https://github.com/LegoManACM/Lichen
lichen --init     # writes a starter lichen.yaml; put your name and your hub in it
lichen            # run it
```

(`python -m lichen` works too.)

### From a checkout (for hacking on it)

PowerShell (Windows):

```powershell
python -m venv .venv
.\.venv\Scripts\Activate.ps1          # once per terminal; prompt shows (.venv)
python -m pip install -e .            # editable install: gives you the `lichen` command
```

If activation says scripts are disabled: `Set-ExecutionPolicy -Scope CurrentUser RemoteSigned`.
In cmd use `.venv\Scripts\activate.bat`; on macOS/Linux `source .venv/bin/activate`.
All commands below assume the venv is active.

## Terminal UI

Run it in a normal terminal and you get a full-screen app:

![Lichen terminal UI](docs/tui-screenshot.svg)

- **Sidebar:** your contacts (plus strangers who wrote to you, marked `?`) and groups (invites marked
  `✉ … (invite)`). `●` green = direct peer-to-peer path, `●` cyan = they accept connections directly
  (hubs, open ports), `◐` yellow = through their hub. A number badge shows unread messages.
- **Requests** (**Ctrl+R**): group invites, file offers and messages from people who aren't your
  contacts. Type `accept NAME` / `decline NAME` there (Tab completes).
- **Sending files:** **Ctrl+F** in a conversation opens a file picker (browse, or type/paste a path
  with Tab completion). Or drag a file into the terminal window: the path gets pasted into the box and
  Enter sends it. Offers and downloads show up inline in the chat, with live progress.
- **Console** (top of the sidebar, and where the app starts): a terminal for running commands, no
  slash needed (`help`, `peers`, `group create devs alice bob`, ...), with all their output kept.
  **Ctrl+O** jumps back to it from anywhere.
- **Pick a conversation** (arrow keys + Enter, or click) and just type; Enter sends. `/commands` work
  there too, with their output shown inline (and also kept in the console).
- **Autocomplete:** as you type a command, grey ghost text suggests the rest: command names, `/group`
  subcommands, contact names, group names, `private`/`shared`. **Tab** (or →) accepts it; a hint line
  above the box shows usage and what fits.
- **Ctrl+L** shows the activity pane (connections, hole punches, relay events), **F5** syncs (groups,
  chats, and anything waiting to be delivered), **Esc** jumps back to the message box, **Ctrl+Q** quits.
- Direct-message history is kept in `data/<name>/dms.json` (group history in `groups.json`).

`ui: tui` / `ui: cli` (or `--ui`) picks one explicitly. The default `auto` uses the full-screen UI in
an interactive terminal and the plain prompt when input is piped (scripts, tests).

## Config files

Every option can live in a YAML or JSON file, so day to day you just run:

```powershell
lichen                        # uses lichen.yaml / .yml / .json in this folder
lichen --config hub.yaml      # or a specific file
lichen --init                 # writes a commented starter lichen.yaml
```

Flags on the command line override the file. Options (same names as the flags):

| option      | what it does |
|-------------|--------------|
| `name`      | your display name (your key is your real identity) |
| `relay`     | hub(s) to receive messages through: `HOST:PORT`, `ws://...` or `wss://...`; a list = several hubs |
| `bootstrap` | list of nodes/hubs to fetch phonebooks from (the relay is added automatically) |
| `port`      | port to listen on, TCP and UDP (hubs; default 9000) |
| `host`      | public IP / DNS name others can reach you at directly (hub with an open port) |
| `bind`      | interface to listen on, e.g. `0.0.0.0` |
| `url`       | public address to advertise instead of host:port, e.g. `wss://chat.example.com` |
| `stun`      | STUN servers for learning your public UDP address (`[]` disables) |
| `data`      | where keys, phonebook and messages are stored (default `data/<name>`) |
| `ui`        | `tui`, `cli` or `auto` (default: full-screen UI in a real terminal) |
| `downloads` | where accepted files are saved (default `data/<name>/downloads`) |
| `max_file_mb` | refuse file offers bigger than this (default 100) |
| `auto_download_mb` | auto-accept files up to this size from your contacts (default 0 = always ask) |
| `vault_mb`  | hubs: MB of encrypted sync data for people's *other devices* to hold (default 256; `0` = hold none, see [Using Lichen on more than one device](#using-lichen-on-more-than-one-device)) |

Ready-made examples are in [examples/](examples): `friend.yaml`, `hub-open-port.yaml`,
`hub-cloudflare.yaml`, `local-test.json`.

## Try it locally

Self-checking demo (address changes, stale phonebooks, groups, key rotation, private history,
NAT'd nodes via relay, offline delivery via IOUs, hole punching, federated hubs, a WebSocket-only hub,
invites, trust, file transfer, chat sync,
forged records):

```powershell
python demo.py
python demo_sync.py     # one identity on two devices that are never on together (the hub's vault)
```

Interactive on one PC, one terminal each:

```powershell
lichen --name hub --port 9000
lichen --name alice --relay 127.0.0.1:9000
lichen --name bob --relay ws://127.0.0.1:9000
```

(The hub accepts both raw TCP and WebSocket on the same port, so alice and bob can mix.)

Commands: `/peers`, `/msg bob hello`, `/whoami`, `/join ADDRESS`, `/move PORT`, `/quit`.
Groups: `/group create devs bob carol` (add `--shared` to let new members see old messages),
`/group history devs shared`, `/g devs hi all`, `/group add devs dave`,
`/group remove devs bob`, `/groups`, `/history devs`, `/sync`. `/help` lists everything.

State lives in `data/<name>/`: `key` and `enckey` are your private keys (don't share them),
plus `phonebook.json`, `groups.json`, and `mailbox.json` (messages held for others, if you're a relay).

## Using it over the internet

Only **one** machine needs to be reachable: the hub. Everyone else connects out to it, which works
behind any home router, mobile data, dorm or office network. Pick whichever way of hosting suits you:

### A. Cloudflare Tunnel (no port forwarding, home IP hidden)

The hub stays on `localhost`; `cloudflared` carries WebSocket traffic to it.

```powershell
cloudflared tunnel login
cloudflared tunnel create lichen
cloudflared tunnel route dns lichen chat.example.com
cloudflared tunnel run --url http://localhost:9000 lichen
```

```yaml
# hub.yaml   ->  lichen --config hub.yaml
name: hub
port: 9000
url: wss://chat.example.com
```

Friends use `relay: wss://chat.example.com`. To try it without a domain,
`cloudflared tunnel --url http://localhost:9000` prints a temporary `https://<random>.trycloudflare.com`
address; use it as `wss://<random>.trycloudflare.com` (it changes every time you restart cloudflared).
Any other HTTPS reverse proxy that passes WebSockets through (nginx, Caddy, ngrok, ...) works the same way.

### B. Open port (VPS, or your PC with a port forward)

Open or forward port 9000 for **TCP and UDP** (UDP lets the hub tell people their public address;
without it, public STUN servers are used instead). Allow Python through the firewall.

```yaml
name: hub
port: 9000
bind: 0.0.0.0
host: 203.0.113.7          # your public IP...
# host: chat.example.com   # ...or a domain pointing at it
```

Friends use `relay: 203.0.113.7:9000` (or `chat.example.com:9000`). For a home connection whose IP
changes, point a domain at it with dynamic DNS (DuckDNS, No-IP, or Cloudflare DNS in "DNS only" /
grey-cloud mode with an updater script).

### Everyone else

```yaml
name: yourname
relay: wss://chat.example.com     # or HOST:PORT, whatever the hub owner gave you
```

You'll get the hub's phonebook, show up in everyone else's, and can `/msg` anyone. Messages go
**directly between you and the other person** whenever your networks allow it; `/peers` shows
`[direct ip:port]` or `[via relay]` for each contact. If someone is offline, your message waits in
your outbox and goes out when you're both online (see [Offline delivery](#offline-delivery-ious)).
Windows may ask to allow Python on the network the first time; allow it, or direct connections can't
be made.

For redundancy, list several hubs: `relay: [wss://chat.example.com, other-hub.example.org:9000]`. You
attach to all of them, so you stay reachable if one goes down.

If you *can* accept connections yourself, add `host`/`bind`/`port` (or `url`) as well: people will
then try you directly first and only fall back to the relay.

### Several hubs

Give a hub `bootstrap: [<other hub address>]` (or run `/join <address>` on it) and the two phonebooks
merge both ways, then spread to everyone on each. People who join either hub later get the combined
phonebook, and can message and connect directly to anyone on any linked hub, whatever way each hub is
hosted. Actually *any* node that joins two hubs bridges them.

## Direct connections (hole punching)

Most people are behind a router (NAT) that blocks incoming connections. Lichen gets around it
the same way Tailscale and WebRTC do, with the hub only as an introducer:

1. Each node learns its public UDP address (the IP:port its router maps its socket to): from its hub's
   reply to a UDP ping if the hub has an open UDP port, otherwise from a public STUN server (Google,
   Cloudflare; configurable). Repeating this every 15 s keeps the router mapping open.
2. When you message someone through their relay, you also send them a small `punch` request the same
   way, carrying your public and local-network addresses; their reply carries theirs. Because it
   travels through the relay connection, this works for hubs behind Cloudflare and across hubs.
3. Both sides send UDP pings to every candidate address at once. Each router sees outgoing traffic to
   the other side and lets the replies in. The first signed reply from the right key is the direct path.
4. From then on, requests between you go peer to peer over UDP (chunked, with retransmits), with
   keepalive pings every 15 s. If a direct request fails, it falls back to the relay immediately,
   and punching is retried after a minute.

Works on most home routers and Wi-Fi, and on the same LAN. Often fails on carrier-grade NAT
(mobile data) and some corporate/university networks ("symmetric NAT"); those contacts stay
`[via relay]`, which still works, just through the hub.

## How relaying works

A node with `relay: ADDRESS` doesn't listen at all. It opens one outgoing connection to the relay
(raw TCP, or a WebSocket for `ws://`/`wss://`), proves it holds its key, and advertises its address
as `relay:ADDRESS`. To reach it, you send `{"type": "forward", "to": <key>, "msg": ...}` to the
relay, which pushes the request down that open connection and hands back the reply. So every
request type (messages, lookups, group sync, punch, file chunks) works through a relay. A keepalive
every 25 s stops proxies from closing it. If the node isn't connected, the hub doesn't store the
message: see the next section.

## Offline delivery (IOUs)

Hubs store as little as possible: **no messages, not even encrypted ones**, just IOUs. (The one
exception is the opt-out [device vault](#when-the-devices-arent-on-at-the-same-time): an encrypted
snapshot of *your own* state, for your own other devices, never anyone else's messages.)

1. You send something to someone who's offline (a message, a file offer, a group invite, a leave
   request). Their hub replies "they're away" and records an IOU: *you owe them item `<id>`*, plus a
   timestamp. If they use several hubs, each one gets the same IOU.
2. The item itself stays sealed in **your** `outbox.json`.
3. When they reconnect to any of their hubs, that hub tells them who owes them what ("alice has 1 thing
   for you") and **nudges** you to deliver. You also retry by yourself when their fresh phonebook record
   reaches you (they announce it on startup), every minute, on startup, and on `/sync`.
4. If you're offline when they come back, the IOU is redeemed when you're both online again.
   Unredeemed IOUs and outbox items expire after 30 days.

**Message ids.** Every direct message carries a random id chosen (and signed) by its author. Whatever
route a message takes (direct, relay, a retry, an IOU redeemed through two hubs at once) the
recipient keeps one copy, and conversations sort by time. **Chat sync** uses the same ids: on startup,
when someone comes back online, and on `/sync`, you and they swap "here are the ids I have" and each
sends what the other is missing (sealed, and checked against each author's signature), so gaps heal
themselves.

The trade-off: delivery needs you and the recipient online at the same time at some point; the hub
can't hand over anything on your behalf. (If you use several devices, any of them can be the one that's
online: they share the outbox.) Group chat already catches up from any member who's online,
so groups don't need IOUs.

## Replies, reactions, edits and deletes

Every message in a conversation has a number on the left. In a chat (or after `/open NAME` /
`/open #group` in the plain CLI):

| command | does |
|---|---|
| `/reply 12 sounds good` | replies to message 12 (shown with a quote of it) |
| `/react 12 +1` | reacts with 👍 (also `heart`, `laugh`, `wow`, `sad`, `fire`, `party`, `eyes`, `100`, or any emoji); again to undo |
| `/edit 12 new text` / `/edit last ...` | changes one of your messages; in the TUI, **↑** in an empty box starts editing your last one |
| `/delete 12` / `/delete last` | deletes one of your messages, for everyone |

Under the hood these are ordinary signed, encrypted messages that point at another message's id, so
they work offline (IOUs), sync, and dedupe like everything else. Edits and deletes from anyone but the
original author are ignored. Deleting is best effort: honest clients hide the text, but someone who
already received it could have kept a copy.

## Presence, receipts and typing

- **Online / last seen:** your node quietly checks in with your contacts every 30 s; anyone heard from
  in the last ~75 s shows as online (a filled dot in the sidebar), otherwise "last seen 5m ago".
- **Ticks on your direct messages:** `…` waiting in your outbox (they're offline), `✓` delivered,
  `✓✓` read (they had the chat open).
- **Typing:** "typing…" shows in the chat header while they write to you.

These are **signals**: sealed like messages, but never stored, synced or queued, and only exchanged
between contacts, so strangers can't tell when you're around or what you've read. Receipts and typing
are for direct messages only (in groups they'd be noise).

## Using Lichen on more than one device

```powershell
lichen --export me.lichen      # on the device you already use (asks for a passphrase for the file)
lichen --import me.lichen      # on the new device: no config or --name needed; then just run lichen
```

The export holds your keys, contacts (with nicknames and verifications), groups (with their keys and
history), chats, unsent messages and phonebook, **and a `lichen.yaml`** with your portable settings
(name, hubs, STUN servers, file limits), encrypted with the passphrase you choose. Settings that
describe one machine (`data`, `downloads`, `host`, `bind`, `port`, `url`) are left out. Importing
writes that `lichen.yaml` into the current folder (it never overwrites one that's there: you get
`lichen.from-export.yaml` instead) and sets up `data/<name>`. **Anyone with the file and its
passphrase can be you**, so treat it like a password and delete it once you've imported it.

After that both devices are you, at once:

- Each device attaches to your hubs with its own device id, and hubs deliver to **all** of them.
- If your devices use different hubs, they notice each other in the phonebook and advertise the
  combined addresses, so people can reach whichever one is up.
- **Everything syncs between your devices**: contacts, blocks, nicknames and verifications; groups
  (including solo groups, joins, leaves, deletes, keys and every message); all your chats with
  their delivered / read ticks; messages still waiting in the outbox (so whichever device is on
  when the recipient returns delivers them, and the other one drops its copy); file offers (a
  decline travels, downloads stay per device); and the phonebook. Whichever device changed
  something last wins; messages and unsent items are merged, so nothing is lost and the order
  doesn't matter.

### When the devices aren't on at the same time

Two ways, used together:

1. **Live.** Devices that are on at the same time compare a digest of their state through your hubs
   (or directly) and, if it differs, swap everything. This happens at startup, as soon as another
   device appears, about a second after a change, and every minute. It costs one tiny message when
   nothing differs.
2. **Through your hubs' vault.** Once an identity is on several devices, each device leaves an
   **encrypted snapshot** of its state on your hubs (a moment after each change, and when it shuts
   down). When a device starts, it collects the snapshots its siblings left, even if they are off
   right now, and merges them. So you can change things on your laptop, switch it off, and open your
   desktop an hour (or a week) later: it picks everything up when it starts, with no overlap needed.

What the vault means for privacy: a snapshot is compressed, **sealed to your own encryption key and
signed by your identity key**, so the hub sees only that your key keeps a blob of some size, which
device uploaded it and when. It can't read it, and can't make one up (an unsigned or foreign snapshot
is ignored). A hub keeps one snapshot per device (a new upload replaces the old one), at most 24 MB
per identity, only for identities it knows, and forgets a device that hasn't uploaded for 90 days.
Hub owners can turn it off with `vault_mb: 0`; the live path then still works, and devices on
other hubs still meet there. Single-device identities never upload anything: the vault only starts
once you've exported, imported, or a second device has shown up.

If your history is too big for one snapshot (about 2 MB compressed, tens of thousands of messages),
the oldest messages of each chat are left out of it; they still come from the other person's
device and from group members, as before.

Not synced between devices: files you've downloaded (each device downloads its own copy), and which
chat you're looking at / unread counts.

## Trust, requests and invites

Every key is **trusted** (a contact), **unknown**, or **blocked** (`trust.json`):

- You trust someone by messaging them, sending them a file, accepting their request, or `/trust NAME`.
  `/untrust`, `/block` and `/unblock` do what they say.
- Messages from people who aren't your contacts arrive as **requests**: kept, listed in `/requests`
  (and the TUI's Requests view), no notifications. `/accept NAME` makes them a contact.
- **Blocked** keys are ignored completely: messages, file offers and group invites are dropped.
- **Group invites need accepting.** Being added to a group is an invite: you see who invited you and
  who's in it, but no messages until you `/accept GROUP`. `/decline GROUP` or `/group leave GROUP`
  sends the owner a signed leave request, and the owner's node removes you (rotating the group key).
- Pending invites, requests, offers and trust levels are saved, so nothing is lost across restarts.

### Making sure people are who they say they are

Names aren't unique: anyone can start Lichen with `--name alice`. What can't be faked is the **key**;
everything (messages, records, invites) is signed with it. So Lichen helps you keep names and keys
straight:

- **Clashing names get a key prefix.** If two keys both call themselves "alice", you see `alice#3fK9xQ`
  and `alice#Wc1dPq`, and `/msg alice` asks which one you mean instead of guessing.
- **Lookalikes are flagged.** If a stranger uses the same name as one of your contacts, they're marked
  ⚠ and their chat opens with a warning showing the different key.
- **`/nick NAME NICKNAME`** gives someone your own name for them. Only you see it, and nobody else can
  take it over, whatever they call themselves.
- **`/verify NAME`** shows a 20-digit safety code for the two of you. Compare it with them in person, on
  a call, or in another app you trust; if it matches, `/verify NAME yes` marks them ✓ verified.
  `/whoami` shows your own fingerprint.
- **Passphrase for your keys:** `lichen --passphrase` encrypts your key files (scrypt +
  ChaCha20-Poly1305), so a copied data folder can't be used to sign as you or read your messages.
  You're asked for it at startup; unattended hubs can set `LICHEN_PASSPHRASE`. Run it again to change
  or remove it.

### Hosting a group: handing it over or deleting it

The person who created a group is its **host**: only they can invite, remove, or change settings. The
host can't just leave; `/group leave GROUP` tells them to pick one of:

- **`/group leave GROUP NAME`**: hand the group to another member and leave. (`/group transfer GROUP
  NAME` hands it over but stays a member.) The host signs "the owner is now NAME" and sends NAME a sealed
  hand-over with the group's key history (needed to give future members the right keys). NAME's node
  then republishes the group as host with a fresh key, minus the old host if they left. If NAME is
  offline, the hand-over waits in the outbox like anything else.
- **`/group delete GROUP`**: a final signed "deleted" update; members' nodes forget the group's
  messages and keys (best effort: an honest client deletes, a modified one could keep a copy).

Ownership is verifiable by anyone: the group id is derived from the founder's key, and the record
carries the chain of hand-overs, each signed by the previous host. A member who joins long after a
hand-over can still check it, and nobody can claim a group they weren't handed.

## File sharing

`/send NAME PATH` (or `/send #group PATH`, or Ctrl+F / drag-and-drop in the TUI) offers a file:

1. Your node hashes the file and sends each recipient an **offer**, sealed to them: name, size,
   SHA-256, and a random per-file key. Nothing else moves yet.
2. They decide: `/accept NAME` or `/decline NAME`. Offers from strangers wait in requests; blocked
   senders' offers are dropped; anything over `max_file_mb` is refused automatically. With
   `auto_download_mb` set, small files from **contacts** download by themselves.
3. Accepting pulls the file in 48 KB chunks, each encrypted with the per-file key (bound to its
   offset), over the direct peer-to-peer path when there is one and the relay otherwise. Relays only
   ever see ciphertext.
4. Interrupted downloads resume where they stopped (`/accept` again). The finished file must match the
   SHA-256 from the offer, then lands in `downloads/` under a safe name that never overwrites anything.

The sender has to be online while you download (files aren't stored on hubs). `/files` lists
everything offered and received.

## Encryption

- **Direct messages** are signed by the sender, then sealed to the recipient's X25519 key (ephemeral
  X25519 + HKDF-SHA256 + ChaCha20-Poly1305). The relay sees the recipient's key and nothing else.
- **Groups** use a shared key per *epoch*. Every change (create, add, remove, history setting)
  starts a new epoch with a fresh random key, which the owner seals to each member individually
  inside the signed group record. Removed members never get the new key, so they can't read
  anything sent after their removal.

## Groups

- A group is a signed member list owned by its creator. Its id is `hash(owner key, nonce)`, so no one
  else can publish records for it. Changes = owner signs a new record with a higher `seq`.
- **Sending:** the message is signed by its author, encrypted with the current epoch key, and sent to
  every member directly (or via their relay).
- **Catching up:** every member keeps the group's encrypted log. On startup and on `/sync`, you send
  the other members the ids you already have, and they reply with what you're missing. Any member can
  fill the gap. Newly added members pull the history the same way.
- Receivers only accept messages that decrypt, verify, and come from a current member.

### History setting

`/group create devs bob carol` makes a **private-history** group (the default): people added later
only see messages from when they joined. `/group create devs --shared bob carol` lets new members
catch up on everything sent while the group was shared. The owner can switch with
`/group history devs private|shared`.

This is enforced by **cryptography**, not just politeness: a member only ever receives the keys for
epochs that started after they joined, plus (while the group is shared) the keys of epochs that were
shared. Private-era messages are unreadable to later joiners even if someone hands them the
ciphertext. Switching to `shared` and then adding someone doesn't expose anything said while the
group was private. Removing and re-adding someone gives them a new join time.

## Wire protocol

One connection per request (except `attach`, which stays open), either raw TCP with one JSON object
per line, or a WebSocket with one JSON object per text message. Servers accept both on the same port.

| type           | request                                  | reply                     |
|----------------|------------------------------------------|---------------------------|
| `hello`        | `{record}`                               | `{record, records[]}`     |
| `announce`     | `{record, from}`                         | `{ok}`                    |
| `lookup`       | `{pubkey}`                               | `{record \| null}`        |
| `msg`          | `{to, sealed}`                           | `{ok}`                    |
| `forward`      | `{to, msg, from, item}`                  | the target's reply, or `{ok, queued, iou}` |
| `attach`       | `{req: {pubkey, ts}, sig}` then stays open | `{ok}`, then `{rid, msg}` / `{rid, reply}` frames |
| `group_msg`    | `{envelope, group}`                      | `{ok}`                    |
| `group_update` | `{group}`                                | `{ok}`                    |
| `group_sync`   | `{req: {group, from, ts, have[]}, sig}`  | `{group, messages[]}`     |
| `punch`        | `{peer, addrs[]}` (usually via `forward`) | `{addrs[]}`; both sides then ping each other |
| `group_leave`  | `{req: {group, from, ts}, sig}`          | `{ok}`; the owner removes the member |
| `dm_sync`      | `{req: {from, to, ts, have[]}, sig}`     | `{sealed: {messages[]}}`  |
| `file_offer`   | `{to, sealed}`                           | `{ok}`                    |
| `file_get`     | `{file_id, offset}`                      | `{n, ct}` (one encrypted chunk) |
| `nudge`        | `{peer}` (hub -> sender)                 | `{ok}`; sender delivers what it owes `peer` |
| `owed`         | `{from: {sender: [item ids]}}` (hub -> recipient) | `{ok}` |
| `device_sync`  | `{to, sealed}`: a digest probe, or your whole state (via `forward` with `fanout`) | `{sealed}` |
| `vault_put`    | `{req: {pubkey, device, rev, hash, ts}, sig, blob}` | `{ok}` |
| `vault_get`    | `{req: {pubkey, device, ts, have{}}, sig}` | `{snaps[{device, rev, blob}]}` |

Over UDP, every request above can also travel on a punched path. Datagrams are JSON:
`ping`/`pong` (signed; pong reports the address it saw), and `req`/`rep` chunks
`{id, i, n, d}` that reassemble into the same request/reply objects as over TCP.

## Not done yet (prototype limits)

- **Not security-reviewed.** The crypto uses standard building blocks from `cryptography`, but the
  protocol around them hasn't been audited. Don't rely on it for anything sensitive yet.
- **No forward secrecy for direct messages:** if someone steals your `enckey` file, they can decrypt
  past direct messages they recorded. (Groups are better: old epoch keys don't decrypt new epochs.)
- **First contact is trust-on-first-use:** until you `/verify` someone, you're trusting that the key you
  messaged is theirs. Names can be copied; compare safety codes for anyone who matters.
- **Contact records aren't encrypted:** the phonebook reveals everyone's name, key and addresses
  (including direct IPs for nodes that advertise one) to everyone.
- **Spam / abuse:** anyone can join a hub and flood the phonebook with fake identities. Strangers'
  messages and offers only land in requests, and you can block, but there's no rate limiting yet.
- **Gossip floods to everyone:** O(N²) messages per change; fine for hundreds of nodes, not millions.
- **Hubs matter for reachability:** finding people, introductions and relayed traffic go through them.
  Direct paths that already exist keep working if a hub goes down, and listing several hubs in
  `relay` keeps you reachable when one fails.
- **Offline delivery needs an overlap:** with IOUs nothing is stored on hubs, so a message only arrives
  once sender and recipient have both been online at the same time. Outbox retries need the recipient's
  address to be findable; senders retry roughly once a minute and ask everyone they know for a fresh
  record each time, which is chatty with very large phonebooks.
- **STUN servers see your IP.** With a WebSocket-only hub, your node asks Google's/Cloudflare's STUN
  servers for its public address every 15 s. Set `stun: []` to opt out (you'll still connect directly
  on the same LAN, and via relay otherwise), or point it at a STUN server you trust.
- A hub behind Cloudflare means Cloudflare can see relayed traffic metadata (who's attached, sizes,
  timing), though not message contents, which are end-to-end encrypted.
- **No IPv6** yet for direct paths (UDP is IPv4 only), and no port prediction for symmetric NATs.
- **Direct connections reveal IPs:** a peer you connect to directly learns your public IP, and
  introductions share your local-network addresses with them.
- UDP transfers are simple stop-and-wait chunks with no congestion control: fine for chat, slow for
  very large catch-up syncs over lossy links.
- Group catch-up only happens on startup and `/sync`, not continuously.
- Removing a member also drops their old messages from catch-up for anyone who syncs later.
- Only the host can change a group, from the device with their key. They can hand it over, but if
  they vanish without doing so, the group can't change membership any more.
- Groups saved by versions before encryption are ignored (contacts are kept).
- Join times come from the owner's clock and message times from the author's; a big clock difference
  only matters for which ciphertexts are offered during catch-up, not what can be decrypted.
