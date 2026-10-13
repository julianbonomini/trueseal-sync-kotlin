#!/usr/bin/env bash
# SDK-3 (#6): no version or protocol-limit literal is hand-written in SDK
# sources. TrueSeal.info / maxPayloadBytes / maxGroupSize are pass-throughs
# from the linked core (see TrueSeal.kt), so a hand-typed 61,440 / 65,519 /
# sdkVersion in the SDK would be a drift bug by construction. This guard
# fails the build on one; the jvm-test lane pins the actual values against
# the ADRs (NativeLaneTest), and the generated UniFFI bindings are exempt
# (they ARE the core's surface). Mirrors the Swift SDK-3 grep (swift#12).
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
fail=0

# Hand-written Kotlin sources only: lib/src/main/kotlin (the generated
# bindings live under lib/src/main/java/uniffi and are exempt).
MAIN_KOTLIN="$REPO_ROOT/lib/src/main/kotlin"

# 1. Numeric protocol limits must never appear in hand-written sources.
if grep -rnE '61440|61_440|65519|65_519' "$MAIN_KOTLIN"; then
    echo "ERROR: a protocol-limit literal in hand-written SDK sources — read it from the core (TrueSeal.maxPayloadBytes)"
    fail=1
fi

# 2. Version literals must never be assigned in hand-written sources.
#    (Comments and docs are fine; an assignment is a drift bug.)
if grep -rnE '(sdkVersion|transportVersion|endToEndVersion)\s*=\s*"' "$MAIN_KOTLIN"; then
    echo "ERROR: a version literal assigned in hand-written SDK sources — read it from the core (TrueSeal.info)"
    fail=1
fi
if grep -rnE '(transportVersion|endToEndVersion)\s*=\s*[0-9]' "$MAIN_KOTLIN"; then
    echo "ERROR: a numeric protocol-version assignment in hand-written SDK sources — read it from the core (TrueSeal.info)"
    fail=1
fi

# 3. The README must not restate the numeric limits (it may link the docs).
if grep -nE '\b61,?440\b|\b65,?519\b' "$REPO_ROOT/README.md"; then
    echo "ERROR: a protocol-limit literal in the README — link the docs page instead"
    fail=1
fi

if [ "$fail" -ne 0 ]; then
    exit 1
fi
echo "check-no-literal-limits: clean"
