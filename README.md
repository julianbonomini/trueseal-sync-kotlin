# HushSync Android SDK

Idiomatic Kotlin wrapper for the [hush-sync](../hush-sync) Rust library.

E2EE, local-first sync between devices. No FFI, no Noise Protocol, no raw keys.

---

## Requirements

- Android API 24+ (Android 7.0)
- Kotlin 1.9+ / Coroutines

---

## Add to your project

### Gradle (via JitPack)

1. Add JitPack to your settings:

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

2. Add the dependency:

```kotlin
// app/build.gradle.kts
dependencies {
    implementation("com.github.buenomini:hush-sync-kotlin:0.1.0")
}
```

No Rust toolchain required — pre-built `.so` files are committed to this repo
and JitPack assembles the AAR directly.

---

## Contributing / local development

Requires a Rust toolchain, Android NDK, and the sibling repos checked out:

```
hush/
  hush-noise/
  hush-sync/
  hush-sync-kotlin/   ← this repo
```

```bash
# Install Rust Android targets + cargo-ndk (one-time)
rustup target add aarch64-linux-android x86_64-linux-android
cargo install cargo-ndk

# Build .so files + regenerate Kotlin bindings
bash scripts/build-android.sh
```

Commit the generated files (`lib/src/main/jniLibs/` and `lib/src/main/java/uniffi/`)
after running the script — JitPack builds from these committed artefacts.

### Regenerating bindings when the Rust core changes

Run `build-android.sh` whenever `hush-sync/src/ffi.rs` changes:

```bash
bash scripts/build-android.sh
git add lib/src/main/jniLibs/ lib/src/main/java/uniffi/
git commit -m "chore: update native libs + UniFFI bindings"
```

The script:
1. Cross-compiles `hush-sync` for `arm64-v8a` and `x86_64` Android ABIs via `cargo-ndk`
2. Copies `.so` files to `lib/src/main/jniLibs/{abi}/`
3. Generates Kotlin bindings via `uniffi-bindgen` (using the host dylib for metadata)
4. Copies generated `hush_sync.kt` / `hush_noise.kt` to `lib/src/main/java/uniffi/`

### Releasing

```bash
git tag v0.1.0
git push origin v0.1.0
```

The [release workflow](.github/workflows/release.yml) builds the `.so` files,
regenerates bindings, assembles the AAR, commits the artefacts to `main`, and
creates a GitHub Release with the AAR attached.

---

## Usage

### 1. Initialise

```kotlin
import dev.hush.sync.HushSyncClient
import android.util.Base64

val client = HushSyncClient(
    context        = applicationContext,
    relayHost      = "relay.example.com",
    relayPublicKey = Base64.decode("<32-byte relay pub key, base64>", Base64.DEFAULT),
)
// storageDirectory and namespace have sensible defaults.
// The client is immediately usable — relay connects in the background.
```

`HushSyncClient` implements `Closeable`. Call `close()` in `onDestroy` or use the
`use { }` block to release native resources and complete all Flows:

```kotlin
override fun onDestroy() {
    super.onDestroy()
    client.close()
}
```

### 2. Pair two devices

**Device A** — generates a pairing token (show as QR code, share sheet, etc.):

```kotlin
val token = clientA.generatePairingToken()
// display `token` to the user
```

**Device B** — scans the token and requests to join:

```kotlin
clientB.joinGroup(token)
```

**Device A** — accepts the request:

```kotlin
clientA.pairingRequests
    .take(1)
    .onEach { request ->
        println("Pairing request from: ${request.deviceName}")
        clientA.acceptPairingRequest(request)
    }
    .launchIn(lifecycleScope)
```

### 3. Publish a payload

```kotlin
// Raw bytes:
client.publish("Hello from Android".toByteArray())

// Or the string convenience overload:
client.publish("Hello from Android")
```

### 4. Receive blobs

```kotlin
client.blobs
    .onEach { blob ->
        println(blob.text ?: "<binary, ${blob.data.size} bytes>")
    }
    .launchIn(lifecycleScope)
```

### 5. List & remove members

```kotlin
val members = client.members
println(members.map { it.name })  // ["AmberFalcon", "CrimsonOwl"]

client.removeMember(members[0])   // Soft Removal — no key rotation
```

### 6. Handle membership events

```kotlin
client.memberEvents
    .onEach { event ->
        when (event) {
            is MemberEvent.Joined        -> println("${event.member.name} joined")
            is MemberEvent.Left          -> println("${event.member.name} left")
            is MemberEvent.RemovedSelf   -> println("This device was removed from the group")
            is MemberEvent.GroupDestroyed -> println("Group destroyed — reinitialise to start fresh")
        }
    }
    .launchIn(lifecycleScope)
```

### 7. Destroy group (security incident)

```kotlin
client.destroyGroup()
// Every member receives MemberEvent.GroupDestroyed.
// All Flows complete automatically.
// Call client.close() to release native resources, then
// construct a new HushSyncClient with the same namespace.
```

---

## Error handling

All errors are `HushSyncError` — a sealed class extending `Exception`.

```kotlin
try {
    client.publish("hello")
} catch (e: HushSyncError.NotInGroup) {
    // Pairing not yet complete
} catch (e: HushSyncError.GroupDestroyed) {
    // Reinitialise the client
} catch (e: HushSyncError) {
    println(e.message)
}
```

---

## Architecture

```
Your App
   │  import dev.hush.sync
   ▼
HushSyncClient          ← idiomatic Kotlin (this SDK)
   │  (internal)
   ▼
uniffi.hush_sync        ← UniFFI-generated Kotlin (never public)
   │
   ▼
libhush_sync.so         ← compiled Rust (hush-sync + hush-noise)
```

No UniFFI types, raw bytes, or Noise Protocol concepts cross the public boundary.

---

## License

MIT — see [LICENSE](LICENSE).
