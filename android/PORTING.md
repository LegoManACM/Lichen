# Lichen for Android: porting notes

The Android app is a Kotlin port of the Python node in `../lichen/`. The Python code is the reference:
when this file and the Python disagree, the Python wins. Read the module you are porting before
writing its Kotlin.

## Layout

```
android/
  core/   plain Kotlin/JVM: the protocol. No Android imports. Unit tests run on the PC.
          dev.lichen.core           crypto, identity, records, canonical JSON, key files, export import
          dev.lichen.core.store     phonebook, dms, outbox, trust (same JSON files as Python)
          dev.lichen.core.net       wire (TCP JSON lines + WebSocket), relay client
          dev.lichen.core.node      the node: request handling, delivery, outbox, chat sync
          dev.lichen.core.api       ChatClient / LichenSetup: the only things the app uses
  app/    Android: Compose UI, foreground service, notifications. Talks to core only via core.api.
```

Libraries: kotlinx-serialization-json (`JsonElement` is the in-memory form of every protocol message;
no `@Serializable` classes, no compiler plugin), kotlinx-coroutines, BouncyCastle `bcprov-jdk18on`
**lightweight API only** (`org.bouncycastle.crypto.*`, `org.bouncycastle.math.ec.rfc7748/rfc8032`;
never `Security.addProvider`/JCA, it clashes with Android's built-in BC), OkHttp for WebSockets.

## Building and testing

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"   # Git Bash; JDK 21 that ships with Android Studio
./gradlew :core:test            # protocol tests, no phone or SDK needed
./gradlew :app:assembleDebug    # needs android/local.properties (sdk.dir), gitignored
"$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe" install -r app/build/outputs/apk/debug/app-debug.apk
```

`local.properties` (in a fresh worktree, create it):
```
sdk.dir=C\:\\Users\\afrik\\AppData\\Local\\Android\\Sdk
```

Python for cross-checks: `C:/Users/afrik/Github/Lichen/.venv/Scripts/python.exe` has `lichen` installed
(editable) plus `cryptography`. Tests can run Python as a subprocess to generate fixtures or to start a
real node/hub on localhost; skip (JUnit assumption) rather than fail when that Python isn't there.
The test task passes the repo root as system property `lichen.repo`.

## Interop rules that are easy to get wrong

### Canonical JSON (what gets signed)
Python: `json.dumps(obj, sort_keys=True, separators=(",", ":")).encode()`. Kotlin must produce the
**same bytes** from the parsed message, or signatures fail both ways.
- Objects: keys sorted (Python sorts by code point; keys are ASCII in practice), `{"a":1,"b":2}`, no spaces.
- Strings: `ensure_ascii`. `"` → `\"`, `\` → `\\`, `\n` `\r` `\t` `\b` `\f` as such, every other char
  outside 0x20..0x7E (including 0x7F and all non-ASCII) as `\uXXXX` with **lowercase** hex, per UTF-16
  code unit (so emoji become a surrogate pair `\ud83d\ude00`). `/` is not escaped.
- Integers stay integers (`seq` is a microsecond count, ~1.7e15: Long).
- Floats are Python `repr(float)`: shortest digits that round-trip, always with a `.` or exponent
  (`1.0`, `1728750000.123456`, `1e-05`, `1e+16`). `ts` fields are floats. When re-encoding a number
  that was *parsed* from the wire, reuse its literal text (kotlinx `JsonPrimitive.content` keeps it):
  Python wrote it with repr, so the literal already is the canonical form. When Kotlin *creates* a
  float (timestamps), format it the way repr would; `Canonical.now()` gives the current time as such.
- `true` / `false` / `null`.
- Plain (non-canonical) JSON on the wire can be anything valid; only signed bytes need to be canonical.

### Base64
URL-safe alphabet, **no padding** on output, padding-tolerant on input (`identity.b64/unb64`).

### Crypto (crypto.py, identity.py, keyfile.py, devices.py)
- Identity: Ed25519; `pubkey` = b64(raw 32-byte public key). Key file `key` = b64(32-byte seed), or the
  `lichen-key-v1:` passphrase format.
- EncKey: X25519, file `enckey`, same formats.
- `seal`: ephemeral X25519 → shared secret → HKDF-SHA256(salt none, info = `lichen-seal-v1` + epk + rpk,
  32 bytes) → ChaCha20-Poly1305, 12-byte random nonce, no AAD. Box = `{"epk","n","ct"}` (b64).
  HKDF with no salt = 32 zero bytes of salt.
- `encrypt/decrypt`: ChaCha20-Poly1305 with AAD.
- Passphrase formats: scrypt N=2^15, r=8, p=1, 32 bytes; AAD `lichen-key` for key files,
  `lichen-identity` for exports (`lichen-identity-v1:` prefix).

### Records (records.py)
Signed body = `{"pubkey","name","addrs","seq"}` + `"enc"` when present; `seq = max(now_µs, prev+1)`.

## Phase 1 scope (the phone as a relay-only client)

The phone never listens and never runs a hub (`direct = false`): it attaches to its hubs
(`relay.RelayClient`) and receives every request down that connection. Its record's addrs are
`relay:<hub>` for each hub (plus addresses adopted from our other devices, `node._adopt_device_addrs`).

Requests the phone must **handle** (node.handle): `hello`, `announce`, `lookup`, `msg`, `signal`
(just mark seen / typing / read), `owed`, `nudge`, `dm_sync`. Everything else gets
`{"ok": false, "error": "unknown type ..."}` for now (`device_sync`, groups, files, `punch` are phases 2-3).

Requests the phone must **make**: `hello` (join each hub at start), `announce` (gossip own record),
`lookup` (refresh), sealed `msg` with ids and IOUs + outbox + `flush_outbox`, `dm_sync`, `forward`
(sending to `relay:` addresses), presence `signal`s are phase 2.

Device id: own random id in `device` (never copied from an export). An imported identity is `linked`.

## Phase 2 (done): signals, device sync, requests

- `node/Signals.kt`: presence `here` every 2 keepalive ticks (30 s) to trusted contacts, `sendTyping` (3 s throttle),
  `sendRead`. Signals only go to trusted contacts and are sealed like messages (`_quick` = first two addrs, no outbox).
- `node/DeviceSync.kt`: `_pack/_seal_snapshot/_open_snapshot`, live `device_sync` probe/full through the hubs
  (`forward` + `except_device`/`fanout`/`only_device`), vault push/pull (client side only; the phone answers
  `vault_put`/`vault_get` with an error), `devsync.json`, final push on stop (`Node.finalSync`, called by `NodeClient.stop`).
  Timings as in Python: change -> live sync after 1 s, vault push after 5 s with at least 15 s between uploads per hub
  (a change inside that gap waits for the next 60 s device round or the final push, same as Python).
- The synced state is a map of named sections (`DeviceSync.register(name, StateSection(inDigest, export, merge))`).
  Registered: `trust`, `dms`, `outbox`, `phonebook` (not in the digest, like Python). `groups` and `files` start as
  the empty shapes Python produces with no groups/files (`{}` and `{"incoming": {}}`), so digests agree with a Python
  device that has none. **Until the groups/files code registers real sections, a device that has groups or file
  offers will never agree on the digest with the phone** (it will keep trying a full swap; chats still merge) and
  those sections are not carried through the phone. Stores' merge functions must not call their change hook.
- `ChatClient`: `typing`, `typing(pk)`, `requests`, `safetyCode`, `setVerified`, `log`; `Contact.verified`.
  `NodeClient(dataDir, logSink)` / `FileSetup(logSink)`: the app passes `Log.i("Lichen", ...)`.
- Tests: `Phase2IntegrationTest` against `tools/py_peer.py` (which gained signal/status/receipt, `start_sibling`
  (a second Python device with the same identity), vault/digest commands).

## Phase 3 (later)
UDP hole punching.

## Files (phase 3, DM case)

Code: `store/FileStore.kt` (files.json, same format as Python), `node/Files.kt` (`FileService`: `file_offer` /
`file_get` handling, `send`, `download`, `decline`, auto-download), `api/FilesApi.kt` (`ChatClient.files`).
`Node` only has `var files: FileHandler?` and one dispatch line in `handle` for `file_offer` / `file_get`;
`NodeClient` creates the `FileService` and calls `load()` / `attach(node)` / `detach()`.

- Sent files are **copied into app storage** (`<data>/sent/<file_id>`, `Outgoing.path` points there) while the
  offer is made, so `file_get` keeps working after the picker's access ends. Never deleted automatically.
  Downloads: `<data>/partial/<id>.part`, then `<data>/downloads/<safe name>`.
- Limits (`max_file_mb`, `auto_download_mb`) are in `<data>/filelimits.json` and `FilesApi.limits/setLimits`
  (Python keeps them in lichen.yaml). Defaults 100 / 0. Auto-download needs `auto_download_mb > 0` (Python also
  auto-downloads empty files at 0; we do not).
- Offers from non-contacts are stored and shown in the chat with that person but only `trusted` senders notify
  (`ClientEvent.FileOffer.trusted`). A stranger who only sends a file does not appear in the contacts list until
  the Requests screen (phase 2) exists.
- **Device sync**: `FileService.fileStore()` gives the `FileStore`; register `deviceState()` under `"files"` and call
  `mergeDeviceState(state["files"])` (returns changed count), and set `fileStore().onChange` for "offers changed"
  (not fired for download progress or merges). `refresh()` is called on merge via `onUpdate`.
- **Groups hook**: a group file offer is `files.send(open, name, size, to = members minus us, group = groupId)`
  (= `node.send_file(group=...)`; the caller checks membership). Incoming group offers are stored with `group` set
  and listed in `files.transfers` with `FileTransfer.group != null`; the DM chat ignores those.
- A download cancelled by `stop()` goes back to `pending` (resumable from the whole chunks already on disk).

### Groups (phase 3, done)
`store/GroupStore.kt` is groups.py (same groups.json, `deviceState()`/`mergeDeviceState()`), `node/Groups.kt` is the
groups section of node.py plus the `group_*` handlers, `node/GroupsClient.kt` + `api/GroupsApi.kt` the UI side
(`ChatClient.groups`). One deviation: outbox/IOU item ids for fan-out items are `group_update:<gid>:<seq>:<pubkey>`
(Python reuses one id for every recipient, so its outbox only queues the first absentee).
`GroupStore` is the `"groups"` device-sync section (`deviceState(keep)` / `mergeDeviceState`, digest included like Python);
its `onChange` feeds `deviceSync.stateChanged()` and merges do not fire it (`onRemoteChange` instead).
Group files: the node side works (`files.send(..., to = members minus us, group = gid)`), but the group chat UI has no
attach button or file bubbles yet, and `GroupsApi` has no file call.
