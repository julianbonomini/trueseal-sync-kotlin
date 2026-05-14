# trueseal-sync-kotlin — Context

## What this is

Android SDK for [trueseal-sync](../trueseal-sync). Wraps the Rust core (compiled to `.so` via UniFFI) in an idiomatic Kotlin API. Published as an Android AAR via JitPack.

## Key decisions

- **Pre-built `.so` committed to repo** — JitPack builds the AAR from committed native libs; consumers need no Rust toolchain.
- **`scripts/build-android.sh`** — the single command that cross-compiles Rust → `.so` for `arm64-v8a` + `x86_64`, generates Kotlin bindings via `uniffi-bindgen`, and copies everything into the library module.
- **Flow-based public API** — all callbacks from Rust are exposed as `kotlinx.coroutines.flow.Flow`.
- **Min SDK API 24** — covers ~97% of active Android devices.
- **`Context` for storage** — `TruesealSyncClient` takes an Android `Context` and defaults storage to `context.filesDir/TruesealSync/`.
- **UniFFI proc-macro mode** — no `.udl` file; bindings generated from the compiled dylib metadata.

## Layer map

```
TruesealSyncClient          dev.trueseal.sync           ← public SDK
CallbackBridges         dev.trueseal.sync.internal  ← internal, never public
uniffi.trueseal_sync        (generated)             ← UniFFI Kotlin, never public
libtrueseal_sync.so         jniLibs/{abi}/          ← compiled Rust
```

## Repo layout

```
lib/src/main/
  kotlin/dev/trueseal/sync/       ← idiomatic Kotlin wrapper
  java/uniffi/                ← generated UniFFI bindings (committed)
  jniLibs/{abi}/              ← pre-built .so files (committed)
scripts/build-android.sh      ← regenerate bindings + .so files
```
