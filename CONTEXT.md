# hush-sync-kotlin — Context

## What this is

Android SDK for [hush-sync](../hush-sync). Wraps the Rust core (compiled to `.so` via UniFFI) in an idiomatic Kotlin API. Published as an Android AAR via JitPack.

## Key decisions

- **Pre-built `.so` committed to repo** — JitPack builds the AAR from committed native libs; consumers need no Rust toolchain.
- **`scripts/build-android.sh`** — the single command that cross-compiles Rust → `.so` for `arm64-v8a` + `x86_64`, generates Kotlin bindings via `uniffi-bindgen`, and copies everything into the library module.
- **Flow-based public API** — all callbacks from Rust are exposed as `kotlinx.coroutines.flow.Flow`.
- **Min SDK API 24** — covers ~97% of active Android devices.
- **`Context` for storage** — `HushSyncClient` takes an Android `Context` and defaults storage to `context.filesDir/HushSync/`.
- **UniFFI proc-macro mode** — no `.udl` file; bindings generated from the compiled dylib metadata.

## Layer map

```
HushSyncClient          dev.hush.sync           ← public SDK
CallbackBridges         dev.hush.sync.internal  ← internal, never public
uniffi.hush_sync        (generated)             ← UniFFI Kotlin, never public
libhush_sync.so         jniLibs/{abi}/          ← compiled Rust
```

## Repo layout

```
lib/src/main/
  kotlin/dev/hush/sync/       ← idiomatic Kotlin wrapper
  java/uniffi/                ← generated UniFFI bindings (committed)
  jniLibs/{abi}/              ← pre-built .so files (committed)
scripts/build-android.sh      ← regenerate bindings + .so files
```
