# trueseal-sync-kotlin

> **Developer Preview.** Every package is `0.x` and openly allowed to break — the API,
> the wire format and stored data may change without notice. Each breaking release
> carries upgrade steps. See the
> [preview lifecycle](https://trueseal.dev/agents/developer-preview).

The Android SDK for [TrueSeal](https://trueseal.dev) — end-to-end encrypted,
local-first sync between a group of devices. No accounts. The relay can't read or
forge messages; [here is exactly what it does see](https://trueseal.dev/agents/threat-model).

Idiomatic Kotlin surface over the Rust core
([trueseal-sync](https://github.com/julianbonomini/trueseal-sync)) — coroutines,
StateFlows, no FFI types.

**Platforms:** Android (API 24+) · Kotlin · Coroutines. **Android is the only
supported Kotlin target** — the `:jvm-test` module is a test-only build of the same
sources for the headless e2e lanes and is never published.

---

## Install

```kotlin
// app/build.gradle.kts
dependencies {
    implementation("dev.trueseal:trueseal-sync:0.6.0")
}
```

The Maven Central coordinates above are the stable API for installing — the Central
publish itself is landing (tracked as
[KOTLIN-1](https://github.com/julianbonomini/trueseal-sync-kotlin/issues/9)). Until
it does, download the AAR and its `.sha256` from the
[GitHub Release](https://github.com/julianbonomini/trueseal-sync-kotlin/releases)
and add the AAR as a local module. No Rust toolchain is needed either way: the
native core ships inside the AAR (`arm64-v8a`, `x86_64`).

---

## Open

You need a relay — a server that routes encrypted blobs between devices. One Relay
Address string carries its key, host and both ports; the relay CLI prints it
(`trueseal-relay address --host relay.example.com`). To run one, see
[self-hosting](https://trueseal.dev/agents/running-a-relay).

```kotlin
import dev.trueseal.sync.Storage
import dev.trueseal.sync.TrueSeal

// open is suspend — call it from a coroutine
val ts = TrueSeal.open(
    relay = "trueseal://<relay public key hex>@relay.example.com",
    storage = Storage.android(applicationContext),
)
```

Session State lives under `storage` (excluded from backups; data at rest is
protected by the OS sandbox). `open` returns before the connection is up — the
outbox queues and replays once the relay is reachable. State is observable as
StateFlows: `ts.status`, `ts.me`, `ts.members`, `ts.connection`.

---

## Pair two devices

Devices sync within a group. You bootstrap the group by pairing: one device opens a
window and hands over a token; the other joins with it; the first accepts. The token
carries a single-use secret — it does not expire on a timer; the window closes when
the device cancels it or accepts a join.

**Device A** — open the pairing window and get a token (show it as a QR code, send
via a share sheet, etc.):

```kotlin
val token = tsA.startPairing()
```

**Device B** — join with the token (status becomes `pendingJoin` until the first
manifest arrives):

```kotlin
tsB.join(token)
```

**Device A** — the request arrives as an event; accept it. Listeners must return
promptly and never call back into the object synchronously — dispatch instead:

```kotlin
val sub = tsA.onEvent { event ->
    if (event is TrueSealEvent.JoinRequestReceived) {
        Log.i("TrueSeal", "Join request from ${event.request.name}")
        scope.launch { tsA.accept(event.request.id) }
    }
}
```

After `accept`, both devices are members and can send and receive. Close the window
with `cancelPairing()` if the user backs out — unaccepted requests disappear with it.

---

## Send & receive

```kotlin
// Send — returns the Message ID once durably queued (throws typed errors while
// not a member, or over the payload limit — TrueSeal.maxPayloadBytes)
val messageId = ts.send("hello".encodeToByteArray())

// Receive — one awaited handler per object; registering it starts receiving
val sub = ts.onMessage { message ->
    println(message.body.decodeToString())
    println(message.from.name)   // sender, same id as in ts.members
    println(message.id)          // stable tsm1_… id; the library deduplicates
}
```

Delivery is at least once, in per-sender order. The message is acked only after your
handler completes — a throw retries (5 attempts, then a `handlerGaveUp` delivery
issue). The library deduplicates on `message.id`, but a crash mid-handling can
deliver a message twice — persist `message.id` alongside your own state if your
handler isn't naturally idempotent. The handler may suspend (the core awaits it) but
must not park forever: a handler parked past the core's liveness window (~75 s)
tears the session down around it. The same rule applies to `onEvent` /
`onDeliveryIssue` listeners, which share one FFI listener bus: return promptly and
dispatch to a coroutine — never block inside a listener, and never call back into
the object synchronously from one.

Call `ts.close()` when you're done — it stops the connection and handlers, keeping
Session State; every later call fails with `TrueSealError.Closed`.

---

## Reference

This README is a quickstart. The full reference lives in the docs:

- **[Human Docs](https://trueseal.dev/docs/integrate/integrating-trueseal-sync)** —
  integrating TrueSeal, pairing, membership, sending and receiving, errors and events
- **[Agent Docs: SDK API reference](https://trueseal.dev/agents/api)** — every public
  type, method, error case and limit, canonical across all three SDKs (paste-friendly
  for coding agents; the [Agent Docs tree](https://trueseal.dev/agents/) has the rest)
- **[Threat Model](https://trueseal.dev/agents/threat-model)** — every security claim,
  the tests that prove it, and the limitations
- **[Developer Preview lifecycle](https://trueseal.dev/agents/developer-preview)** —
  version rules and upgrade notes

Security reports: see [SECURITY.md](SECURITY.md).

---

## License

Apache 2.0 — see [LICENSE](LICENSE).
