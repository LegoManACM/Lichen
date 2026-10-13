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

## Phases 2 and 3 (later)
2. Requests screen, read/delivered receipts, typing, presence, device sync (`device_sync`, vault).
3. Groups (done), files, UDP hole punching.

### Groups (phase 3, done)
`store/GroupStore.kt` is groups.py (same groups.json, `deviceState()`/`mergeDeviceState()`), `node/Groups.kt` is the
groups section of node.py plus the `group_*` handlers, `node/GroupsClient.kt` + `api/GroupsApi.kt` the UI side
(`ChatClient.groups`). One deviation: outbox/IOU item ids for fan-out items are `group_update:<gid>:<seq>:<pubkey>`
(Python reuses one id for every recipient, so its outbox only queues the first absentee).
