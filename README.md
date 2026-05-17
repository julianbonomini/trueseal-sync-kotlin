# trueseal-sync-kotlin

Android SDK for [trueseal-sync](https://trueseal.dev/docs/protocol/overview) — E2EE, local-first sync between devices. Device identity, pairing, encrypted delivery, and outbox replay. No accounts. No server-side keys.

[![JitPack](https://jitpack.io/v/julianbonomini/trueseal-sync-kotlin.svg)](https://jitpack.io/#julianbonomini/trueseal-sync-kotlin)

For architecture, protocol semantics, and integration patterns see the **[trueseal-sync integration guide](https://trueseal.dev/docs/guides/integrating-trueseal-sync)**.

---

## Install

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}
```

```kotlin
// app/build.gradle.kts
dependencies {
    implementation("com.github.julianbonomini:trueseal-sync-kotlin:<version>")
}
```

No Rust toolchain needed — pre-built `.so` files (`arm64-v8a`, `x86_64`) are bundled in the AAR.

**Requirements:** API 24+ · Kotlin 1.9+ · Coroutines

---

## Initialise

```kotlin
val client = TruesealSyncClient(
    context        = applicationContext,
    relayHost      = "relay.example.com",               // your trueseal-relay host
    relayPublicKey = hexToBytes("..."),                  // relay's 32-byte X25519 pubkey
    namespace      = "myapp",                            // scope to your app; see note below
)
```

`relayHost` and `relayPublicKey` are build-time constants — not user-configurable.
See [how to get the relay public key](https://trueseal.dev/docs/guides/integrating-trueseal-sync#relay-public-key).

**`namespace`:** always pass an explicit value scoped to your app (`"com.example.myapp"`).
The default is `"default"` — fine for a single app, wrong if multiple apps share the device.

**Init failure is fatal.** A thrown `TruesealSyncError` at construction means bad arguments or corrupt storage. Do not catch and retry — surface it as a crash or show an unrecoverable error screen.

---

## Startup

Seed local state from the persisted snapshot *before* collecting flows — this prevents a momentary empty member list on cold start:

```kotlin
// In your ViewModel or Application class:

// 1. Snapshot existing group members from persisted state
val initialMembers = client.members          // excludes local device; empty until first pair
val localId        = client.localNodeId
val localName      = client.localDeviceName

// 2. Then attach Flow collectors
client.memberEvents
    .onEach { event -> /* update your state */ }
    .launchIn(viewModelScope)

client.blobs
    .onEach { blob -> handleIncoming(blob) }
    .launchIn(viewModelScope)

client.connectionState
    .onEach { state -> updateRelayIndicator(state) }
    .launchIn(viewModelScope)
```

Flows complete when `destroyGroup()` is called or `close()` is invoked.

---

## Pairing

Pairing is a two-device ceremony. One device generates a token (QR code, share sheet, etc.); the other scans it.

```kotlin
// ── Device A (host) ───────────────────────────────────────────────────────────

// Generate a token — stable for the keypair lifetime, safe to show in a QR.
val token = clientA.generatePairingToken()

// Accept incoming requests (do this before sharing the token)
clientA.pairingRequests
    .onEach { request ->
        // Show request.deviceName to the user, then:
        clientA.acceptPairingRequest(request)
        clientA.cancelPairing()             // single-use: close window after accepting
    }
    .launchIn(viewModelScope)


// ── Device B (joiner) ─────────────────────────────────────────────────────────

clientB.joinGroup(token)                    // throws TruesealSyncError.InvalidPairingToken if bad
```

After `acceptPairingRequest`, both devices receive `MemberEvent.Joined` with the new member's ID and name.

Call `cancelPairing()` when the pairing UI closes, even if no request arrived. It is idempotent.

---

## Publish / receive

```kotlin
// Send to all group members — queues to outbox if relay is offline.
client.publish("hello".toByteArray())
client.publish("hello")                     // UTF-8 convenience overload

// Receive
client.blobs
    .onEach { blob ->
        val text = blob.text                // null if payload is not valid UTF-8
        val from = blob.senderPublicKey     // sender's 32-byte X25519 pubkey (stable ID)
    }
    .launchIn(viewModelScope)
```

**The relay may echo your own messages back.** Deduplicate at the app layer — check content against local storage, don't rely on sender filtering.

---

## Membership

```kotlin
// Snapshot — excludes the local device.
val members: List<SyncMember> = client.members   // each access is an FFI call; cache if needed

// Remove a member (soft removal — no key rotation).
// The removed device fires MemberEvent.RemovedSelf.
client.removeMember(members.first { it.name == "AmberFalcon" })

// Observe changes
client.memberEvents.onEach { event ->
    when (event) {
        is MemberEvent.Joined        -> { /* event.member: SyncMember */ }
        is MemberEvent.Left          -> { /* event.member: SyncMember */ }
        is MemberEvent.RemovedSelf   -> { /* this device was kicked; re-init */ }
        is MemberEvent.GroupDestroyed -> { /* full wipe; re-init */ }
    }
}.launchIn(viewModelScope)
```

Re-snapshot from `client.members` on every event rather than patching a local list — this avoids missing events from race conditions.

---

## Destroy group

Destroy is a **security primitive** (compromised device, fresh start), not a routine "leave":

```kotlin
client.destroyGroup()
// Sends Revoke to all members. Every device receives MemberEvent.GroupDestroyed.
// All Flows complete. Call close() and reinitialise with a new TruesealSyncClient.
```

There is no "leave quietly" protocol — see the [integration guide §9](https://trueseal.dev/docs/guides/integrating-trueseal-sync#9-group-exit) for the workaround.

---

## Error handling

All errors are `TruesealSyncError` — a sealed class extending `Exception`:

| Variant | When |
|---|---|
| `InvalidRelayPublicKey` | Key is not exactly 32 bytes |
| `InvalidNamespace` | Namespace contains illegal characters |
| `InvalidPairingToken` | Token is malformed |
| `NotInGroup` | Operation requires a paired group |
| `MemberNotFound` | `removeMember` target is not in the manifest |
| `PushFailed` | Relay push failed — blob is queued in outbox, **do not retry** |
| `GroupDestroyed` | Session is terminal — reinitialise |

```kotlin
try {
    client.publish(payload)
} catch (e: TruesealSyncError.PushFailed) {
    // Blob is already in the outbox. No action needed.
} catch (e: TruesealSyncError.NotInGroup) {
    // Guide user through pairing first.
} catch (e: TruesealSyncError) {
    Log.e("TruesealSync", e.message)
}
```

---

## Lifecycle

`TruesealSyncClient` implements `Closeable`. Close it to stop background tasks and complete all Flows.

```kotlin
// Activity / Fragment
override fun onDestroy() {
    super.onDestroy()
    client.close()
}
```

```kotlin
// Scoped usage
TruesealSyncClient(context, relayHost, relayPublicKey).use { client ->
    client.publish("hello")
}
```

`close()` is safe to call after `destroyGroup()`.

---

## Build from source

Requires a Rust toolchain, Android NDK, and sibling repos at the same level:

```
trueseal/
  trueseal-noise/
  trueseal-sync/
  trueseal-sync-kotlin/
```

```bash
rustup target add aarch64-linux-android x86_64-linux-android
cargo install cargo-ndk
bash scripts/build-android.sh
```

Commit `lib/src/main/jniLibs/` and `lib/src/main/java/uniffi/` after running — JitPack builds from committed artefacts.

**Release:** tag and push. The [release workflow](.github/workflows/release.yml) rebuilds native libs, assembles the AAR, and publishes to GitHub Releases.

```bash
git tag v0.1.1 && git push origin v0.1.1
```

---

## License

Apache 2.0 — see [LICENSE](LICENSE).
