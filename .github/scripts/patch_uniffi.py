"""
Patch generated UniFFI Kotlin bindings to fix two known codegen issues
present in trueseal-sync <= v0.4.x:

1. Duplicate close() — the base class emits
       @Synchronized override fun close() { this.destroy() }
   which conflicts with the Rust-exposed close() when the Rust interface also
   defines a `close()` method (e.g. SessionNk, SessionXx).
   Fix: remove the base-class @Synchronized close() ONLY from classes that
   also contain a @Throws override fun `close`() within 5000 chars ahead
   (i.e. same class body). Classes with no Rust close() (e.g. NoiseTransportImpl)
   are left untouched.

2. `val `message`` constructor param shadows the `override val message` property
   already declared in the same exception subclass body, causing a
   "Conflicting declarations" / "recursive problem" error.
   Fix: rename the constructor param to rawMessage and update the getter.
"""
import re
from pathlib import Path

SYNC_CLOSE = re.compile(
    r'\n([ \t]+)@Synchronized\n\1override fun close\(\) \{\n\1    this\.destroy\(\)\n\1\}'
)
THROWS_CLOSE = re.compile(r'@Throws\([^)]+\)override fun `close`\(\)')

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

    p.write_text(txt)
    print(f"patched: {path}")


patch("lib/src/main/java/uniffi/trueseal_noise/trueseal_noise.kt")
patch("lib/src/main/java/uniffi/trueseal_sync/trueseal_sync.kt")
