# Vendored sync test vectors (G5 / G8)

`sync-v1.json` is a byte-exact copy of `trueseal-sync`'s
`docs/test-vectors/sync-v1.json` — the preview wire format, pinned so this
SDK's tests can prove the shipped native library + generated UniFFI bindings
agree with the Rust core (release gate G5, sync ADR-0022). The consumption
contract is upstream: `trueseal-sync/docs/test-vectors/README.md`.
Mirrors the Swift precedent (`trueseal-sync-swift` SyncVectorTests, swift#13).

## The pin (G8)

Every cross-repo input is pinned to an exact version and checksum — no
floating references. The pin lives at the top of
`jvm-test/src/test/kotlin/dev/trueseal/sync/jvm/SyncVectorTest.kt`:

- `SYNC_V1_SOURCE` — the upstream repo + commit + path this copy came from.
- `SYNC_V1_SHA256` — the SHA-256 of the vendored file. `loadVectors()`
  refuses to parse bytes that don't match, so an accidental edit (or a
  silent upstream drift copied in) fails every vector-consuming test.

The same file+checksum is vendored by the relay (`internal/vectors`) and the
Swift SDK, so all consumers pin the same bytes.

## What is checked through the bindings, and where the rest lives

Checked **through the Kotlin bindings** on the KOTLIN-2 JVM native lane (see
`SyncVectorTest.kt`; the lane needs the pinned Linux x64 core and runs in CI
with `TRUSEAL_JVM_LANE=require`):

- `protocol` versions + limits against `TrueSeal.info` / `maxPayloadBytes` /
  `maxGroupSize` (which are the linked core's FFI statics);
- the `rejections` taxonomy: every named typed error / Delivery Issue /
  Connection State exists in the SDK's canonical surface (compile-time
  referenced, canonical `code` strings asserted);
- the size-layer rejection behaviorally: the core consults the size gate
  FIRST, so even offline `send(limit+1)` throws `PayloadTooLarge(max)` with
  the vector's exact limit, and `send(limit)` passes the size gate (failing
  notMember offline) — the ordering is pinned from both directions;
- a negative control: a mutated expectation fails the check.

**Core-internal** (SYNC-2 exposed no test-only FFI hooks; checked by the
core's own vector test `vectors::generated_vectors_verify_against_the_code`,
and by the relay for its layers): `signing`, `addressedEncryption`,
`messageId`, `messages`, `sealedEnvelope`, `frames`,
`transportVersionPrefix`, `domains`, and the inbound rejection behaviors
(held/older/expired/malformed envelopes need a hostile peer — that is the
e2e Adversary Client's job, G3). The test asserts these sections are present
and non-empty so they can't silently vanish, and the machine-readable report
names their checker.

The G5 report is written to `$TRUSEAL_VECTOR_REPORT` when set (CI uploads it
as the `trueseal-g5-vectors-report` artifact) and always printed to the
test log.

## Updating (deliberate protocol changes only)

The vectors change only when the wire format deliberately changes (a version
bump per ADR-0022). To update:

1. Land the upstream change in trueseal-sync (its CI regenerates and
   drift-checks `sync-v1.json`).
2. Copy the new file here:
   `cp ../trueseal-sync/docs/test-vectors/sync-v1.json jvm-test/src/test/resources/vectors/`
3. Update `SYNC_V1_SOURCE` and `SYNC_V1_SHA256` in `SyncVectorTest.kt`
   (`shasum -a 256 jvm-test/src/test/resources/vectors/sync-v1.json`).

An unexpected mismatch means the wire format changed accidentally — treat it
as a bug, not a prompt to re-pin.
