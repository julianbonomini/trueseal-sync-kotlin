# `:jvm-test` — the test-only JVM lane (KOTLIN-2)

The headless Kotlin lane for the e2e release gate ([e2e ADR-0001](https://github.com/julianbonomini/trueseal-e2e/blob/main/docs/adr/0001-developer-preview-release-gate.md)).
It compiles the **same sources** as the published Android AAR — no copied or
forked files (`sourceSets` point into `:lib`) — and runs them on plain JVM
against the pinned Linux x64 core library.

**Plain JVM is not a supported Kotlin target.** This module has no
publication and never will (`scripts/check-jvm-test-only.sh` fails CI if one
is added), it is not an input to the AAR or any release path, and the SDK
docs continue to state Android as the only supported target.

## Running it

```bash
bash scripts/fetch-core-artifacts.sh          # UniFFI bindings (Android zip)
bash scripts/fetch-core-artifacts.sh --linux  # the pinned Linux x64 core
./gradlew :jvm-test:test
```

The native tests skip locally unless the pinned library is present on a
Linux host; CI sets `TRUSEAL_JVM_LANE=require`, so there a missing or
unloadable library **fails** the lane (a lane that can't run counts as a
failure — e2e ADR-0001).

## What trueseal-e2e consumes (E2E-1)

The Scenario Driver entry point is `dev.trueseal.sync.driver.Main`. This
module applies no `application` plugin (it is not a product), so there is
no `:jvm-test:run` or `installDist`: the e2e harness compiles the classes
and resolves the runtime classpath itself (an init-script task — see
trueseal-e2e `scripts/kotlin-driver-classpath.init.gradle` and
`lib/sdk-drivers.mjs`, which never modify this checkout), then spawns a
plain `java -cp <resolved-classpath> -Djna.library.path=jvm-test/native
dev.trueseal.sync.driver.Main`. E2E-1 starts it as a subprocess and
speaks the JSON control protocol below over stdin/stdout.

## The JSON control protocol (v0 draft — E2E-1 owns its evolution)

One JSON object per line. Requests carry an `id`; responses echo it.
Asynchronous traffic (messages, events, issues) is pushed as **id-less
notifications**. Every SDK driver speaks this same shape so one Scenario
Driver can run the matrix.

**Request:** `{"id": 1, "command": "<name>", ...args}`
**Response:** `{"id": 1, "ok": true, "result": {...}}` or
`{"id": 1, "ok": false, "error": {"code": "<canonical TrueSealError code>", "message": "..."}}`

| Command | Args | Result |
|---|---|---|
| `open` | `storagePath`, `relay`, `namespace?`, `maxPayloadBytes?` | `{me}` |
| `close` | — | `{}` |
| `info` | — (no session needed) | `{sdkVersion, transportVersion, endToEndVersion, maxPayloadBytes, maxGroupSize}` |
| `send` | `bodyHex` | `{messageId}` (`tsm1_…`) |
| `startPairing` | — | `{token}` |
| `accept` | `requestId` | `{}` |
| `cancelPairing` | — | `{}` |
| `join` | `token` | `{}` |
| `cancelJoin` | — | `{}` |
| `remove` | `memberId` | `{}` |
| `leave` / `destroyGroup` | — | `{}` |
| `status` / `me` / `members` / `connection` | — | state snapshots |
| `receive` | — | registers the message handler; messages arrive as notifications |
| `listenEvents` / `listenIssues` | — | events/issues arrive as notifications |

**Notifications:**

```json
{"event": "message", "message": {"id": "tsm1_…", "from": {"id": "…", "name": "…"}, "bodyHex": "…"}}
{"event": "event",  "kind": "statusChanged", "status": "member", "reason": "joined"}
{"event": "event",  "kind": "joinRequest", "requestId": "…", "name": "…"}
{"event": "event",  "kind": "membersChanged" | "connectionChanged" | "admissionDropped", …}
{"event": "issue",  "kind": "unreadable" | "unauthorized" | "heldForUpgrade" | "handlerGaveUp" | "sendFailed", …}
```

Error `code`s are the canonical `TrueSealError` codes (G12 parity —
`docs/shared-facts.json` on the core); `driver`/`malformed` are the
driver's own protocol errors, never SDK errors.

**Pairing scenario sketch** (what E2E-1's first scenario looks like against
two drivers A and B): A `open`, B `open` → A `startPairing` → B `join` with
the token → A reads the `joinRequest` notification → A `accept` → both see
`statusChanged(member)` → B `receive` → A `send` → B reads the `message`
notification → both `close`.
