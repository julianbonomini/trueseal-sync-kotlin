# trueseal-sync-kotlin — Context

## What this is

Android SDK for [trueseal-sync](../trueseal-sync). Wraps the Rust core (compiled to `.so` via UniFFI) in the canonical Kotlin `TrueSeal` API (sync ADR-0028). Published as an Android AAR (JitPack today; KOTLIN-1 moves it to Maven Central).

## Key decisions

- **Canonical API (SDK-1, #4)** — the public type is `TrueSeal` in `dev.trueseal.sync`: `open` (string or typed Relay Address), `send→MessageId`, awaited one-shot `onMessage`, `onEvent`/`onDeliveryIssue`→`Subscription`, full pairing/membership, `close()`. Exactly 14 `TrueSealError` cases with canonical `code` strings matching the core's generated `shared-facts.json` — the G12 parity check compares those codes. The old `TruesealSyncClient` API is removed outright (the break is approved, ADR-0028).
- **Core artifacts are pinned + gitignored** — the generated bindings and `.so` files are NOT committed; `scripts/fetch-core-artifacts.sh` (and CI) downloads the pinned core zip (exact version + SHA-256, G8) and applies `.github/scripts/patch_uniffi.py`. INTERIM: the zip comes from this repo's `core-v0.6.0` mirror prerelease (TRUESEAL_READ_TOKEN is expired); KOTLIN-3 (#11) owns the permanent pin story.
- **`Storage.android(context)` is the only Android-specific entry point** — it resolves under `context.noBackupFilesDir` so Session State is excluded from Android Auto Backup by construction (ADR-0032). The JVM test lane (KOTLIN-2) compiles the same sources.
- **The `:jvm-test` module is the headless e2e lane (KOTLIN-2, #10)** — the SAME sources compiled for plain JVM against the pinned Linux x64 core (`fetch-core-artifacts.sh --linux`), with compile-only android stubs for the one seam. It has NO publication (CI enforces: `scripts/check-jvm-test-only.sh`), JVM stays unsupported, and it ships the JSON-control-protocol Scenario Driver entry point (`dev.trueseal.sync.driver.Main`, protocol in `jvm-test/README.md`) that trueseal-e2e E2E-1 consumes. CI sets `TRUSEAL_JVM_LANE=require` so a missing/unloadable pinned core fails the lane.
- **State is StateFlow** — `status`, `me`, `members`, `connection` are held in lockstep with the core via an internal event listener + refresh after synchronous transitions (the ADR-0028 statusChanged mapping).
- **UniFFI proc-macro mode** — no `.udl` file; bindings generated from the compiled dylib metadata. The patch fixes a duplicate `close()` (the canonical close is infallible — no `@Throws`) and the `message` param shadowing.
- **Min SDK API 24** — covers ~97% of active Android devices.

## Layer map

```
TrueSeal + TrueSealError      dev.trueseal.sync           ← public SDK (canonical)
Storage / Models              dev.trueseal.sync           ← public; Storage.android is the only android.* seam
Bridges                       dev.trueseal.sync.internal  ← internal, never public
uniffi.trueseal_sync          (generated, gitignored)     ← UniFFI Kotlin, never public
libtrueseal_sync.so           jniLibs/{abi}/ (gitignored) ← compiled Rust
```

## Repo layout

```
lib/src/main/
  kotlin/dev/trueseal/sync/       ← the canonical Kotlin wrapper + unit tests in src/test
  java/uniffi/                ← generated UniFFI bindings (fetched, gitignored)
  jniLibs/{abi}/              ← pre-built .so files (fetched, gitignored)
jvm-test/                       ← TEST-ONLY JVM lane (KOTLIN-2): same sources, no publication
  src/main/kotlin/android/    ← compile-only stubs for the one android seam
  src/main/kotlin/dev/trueseal/sync/driver/  ← JSON-control-protocol Scenario Driver (E2E-1)
  src/test/kotlin/            ← native-lane + driver protocol tests
  native/                     ← the pinned Linux x64 core .so (fetched, gitignored)
scripts/fetch-core-artifacts.sh  ← pinned download + checksum + patch (--linux for the JVM lane)
scripts/check-jvm-test-only.sh   ← CI guard: :jvm-test never gains a publication
scripts/build-android.sh        ← legacy local Rust build (KOTLIN-3 replaces with the pin)
```
