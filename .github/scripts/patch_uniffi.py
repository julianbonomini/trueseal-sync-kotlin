"""
Patch generated UniFFI Kotlin bindings to fix two known codegen issues
present in trueseal-sync <= v0.4.x:

1. Duplicate close() methods — the base class emits
       @Synchronized override fun close() { this.destroy() }
   which conflicts with the Rust-exposed close() method that also generates
       @Throws(...) override fun close() = ...
   Fix: remove the base-class close() so only the Rust one remains.

2. `val `message`` constructor param shadows the `override val message`
   property already declared in the same exception subclass body.
   Fix: strip the `val` so it becomes a plain constructor parameter.
"""
import re
import sys
from pathlib import Path

PATTERNS = [
    # 1. Remove @Synchronized override fun close() { this.destroy() }
    (
        re.compile(
            r'\n[ \t]+@Synchronized\n[ \t]+override fun close\(\) \{\n[ \t]+this\.destroy\(\)\n[ \t]+\}'
        ),
        '',
    ),
    # 2. val `message`: kotlin.String  →  `message`: kotlin.String
    (
        re.compile(r'val `message`: kotlin\.String'),
        '`message`: kotlin.String',
    ),
]

targets = [
    'lib/src/main/java/uniffi/trueseal_noise/trueseal_noise.kt',
    'lib/src/main/java/uniffi/trueseal_sync/trueseal_sync.kt',
]

for rel in targets:
    p = Path(rel)
    if not p.exists():
        print(f'skip (not found): {rel}')
        continue
    original = p.read_text()
    patched = original
    for pattern, replacement in PATTERNS:
        patched = pattern.sub(replacement, patched)
    if patched != original:
        p.write_text(patched)
        print(f'patched: {rel}')
    else:
        print(f'no changes: {rel}')
