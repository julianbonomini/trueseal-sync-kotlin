"""
Patch generated UniFFI Kotlin bindings to fix two known codegen issues.
(Originally written for trueseal-sync <= v0.4.x; updated for v0.6.0 in
SDK-1, #4 — the canonical TrueSeal FFI exposes an INFALLIBLE close(), so
fix 1 must also match an override close() WITHOUT a @Throws annotation.)

1. Duplicate close() — the base class emits
       @Synchronized override fun close() { this.destroy() }
   which conflicts with the Rust-exposed close() when the Rust interface also
   defines a `close()` method (SessionNk/SessionXx — throwing; TrueSeal —
   infallible since v0.6.0).
   Fix: remove the base-class @Synchronized close() ONLY from classes that
   also contain an `override fun `close`()` (with or without @Throws) within
   5000 chars ahead (i.e. same class body). Classes with no Rust close()
   (e.g. NoiseTransportImpl) are left untouched.

2. `val `message`` constructor param shadows the `override val message` property
   already declared in the same exception subclass body, causing a
   "Conflicting declarations" / "recursive problem" error.
   Fix: rename the constructor param to rawMessage and update the getter.

3. (v0.6.0, SDK-1 #4) `TrueSealEvent.JoinRequest(val request: JoinRequest)` —
   inside the sealed class body, the property type resolves to the NESTED
   class itself, not the top-level `JoinRequest` record (Kotlin name
   resolution). The converters (correctly) produce the top-level record, so
   the file doesn't compile. Fix: qualify the property type as
   `uniffi.trueseal_sync.JoinRequest`. (Codegen bug candidate for the core —
   the Swift codegen is unaffected.)
"""
import re
from pathlib import Path

SYNC_CLOSE = re.compile(
    r'\n([ \t]+)@Synchronized\n\1override fun close\(\) \{\n\1    this\.destroy\(\)\n\1\}'
)
# A Rust-exposed close() in the same class body: with @Throws (noise
# sessions) or without (the canonical TrueSeal close is infallible).
THROWS_CLOSE = re.compile(r'(?:@Throws\([^)]+\))?override fun `close`\(\)')

NEARBY_THRESHOLD = 5_000  # chars — same-class bodies are always < this distance


def patch(path: str) -> None:
    p = Path(path)
    if not p.exists():
        print(f"skip (not found): {path}")
        return

    txt = p.read_text()

    # ── Fix 1: remove @Synchronized close() only near a @Throws close() ──────
    throws_positions = [m.start() for m in THROWS_CLOSE.finditer(txt)]

    # Process matches in reverse so offsets stay valid as we delete text.
    patches = []
    for m in SYNC_CLOSE.finditer(txt):
        ahead = [tp for tp in throws_positions if tp > m.start()]
        if ahead and (min(ahead) - m.start()) < NEARBY_THRESHOLD:
            patches.append((m.start(), m.end()))

    for start, end in reversed(patches):
        txt = txt[:start] + txt[end:]

    # ── Fix 2: rename `val `message`` → `val rawMessage` + update getters ────
    txt = re.sub(r"val `message`: kotlin\.String", "val rawMessage: kotlin.String", txt)
    txt = re.sub(
        r'get\(\) = "message=\$\{ `message` \}"',
        'get() = "message=${ rawMessage }"',
        txt,
    )

    # ── Fix 3: qualify the self-shadowing JoinRequest property type ──────────
    txt = txt.replace(
        "data class JoinRequest(\n        val `request`: JoinRequest) : TrueSealEvent() {",
        "data class JoinRequest(\n        val `request`: uniffi.trueseal_sync.JoinRequest) : TrueSealEvent() {",
    )

    p.write_text(txt)
    print(f"patched: {path}")


def die(msg: str) -> None:
    raise SystemExit(f"patch_uniffi: {msg} — the generated bindings' shape changed; update this script")


def assert_patched(path: str, kind: str) -> None:
    """Fail loudly at patch time if the codegen shape drifted, rather than
    leaving a silent no-op patch and a confusing compile error downstream
    (SDK-1 review). Re-verified against the v0.6.0 bindings:
      - fix 2 (`message` shadowing) is NOT dead: the noise bindings still
        need it (2 occurrences pre-patch); the sync bindings have none.
      - fix 3 (JoinRequest self-shadowing) applies to the sync bindings only.
    """
    txt = Path(path).read_text()
    if kind == "sync":
        if "val `request`: uniffi.trueseal_sync.JoinRequest) : TrueSealEvent()" not in txt:
            die("fix 3 did not land in trueseal_sync.kt (JoinRequest not qualified)")
        close_overrides = txt.count("override fun `close`()")
        if close_overrides != 1:
            die(
                f"trueseal_sync.kt has {close_overrides} override `close`() after patching "
                "(expected exactly 1 — TrueSeal's; a surviving base-class duplicate means fix 1 missed)"
            )
    if kind == "noise":
        if "val `message`: kotlin.String" in txt:
            die("fix 2 did not land in trueseal_noise.kt (`message` param still shadows)")
        if "rawMessage" not in txt:
            die("fix 2 did not land in trueseal_noise.kt (no rawMessage rename found)")
        close_overrides = txt.count("override fun `close`()")
        if close_overrides != 2:
            die(
                f"trueseal_noise.kt has {close_overrides} override `close`() after patching "
                "(expected exactly 2 — SessionNk + SessionXx)"
            )
    # Both files: the shadowing param must be gone everywhere.
    if "val `message`: kotlin.String" in txt:
        die(f"{path} still declares the shadowing `message` param")


NOISE = "lib/src/main/java/uniffi/trueseal_noise/trueseal_noise.kt"
SYNC = "lib/src/main/java/uniffi/trueseal_sync/trueseal_sync.kt"

patch(NOISE)
patch(SYNC)
assert_patched(NOISE, "noise")
assert_patched(SYNC, "sync")
print("patch_uniffi: all fixes applied and verified")
