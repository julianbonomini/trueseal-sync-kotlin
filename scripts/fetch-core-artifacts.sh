#!/usr/bin/env bash
# scripts/fetch-core-artifacts.sh
#
# Download the pinned trueseal-sync Android artifacts (.so + generated UniFFI
# Kotlin bindings) into the library module, verify the checksum, and apply the
# UniFFI codegen patch. This is what CI does before building; run it after
# cloning for local development.
#
# INTERIM (SDK-1, #4): the artifacts come from THIS repo's `core-v0.6.0`
# prerelease — a byte-exact, checksum-verified mirror of trueseal-sync's
# v0.6.0 candidate — because TRUESEAL_READ_TOKEN is expired and the core's
# v0.6.0 release is still a draft. KOTLIN-3 (#11) owns the permanent
# exact-tag + checksum pin story (G8).
#
# Usage: bash scripts/fetch-core-artifacts.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

# Pinned core (G8: exact version + checksum — never releases/latest).
CORE_VERSION="v0.6.0"
CORE_ZIP_SHA256="37faff4678f426162e908c06e3c05fec329b8f7c2320247c886d3b6f54aa115d"
ASSET_URL="https://github.com/julianbonomini/trueseal-sync-kotlin/releases/download/core-${CORE_VERSION}/trueseal-sync-android-${CORE_VERSION}.zip"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

echo "▶  Downloading trueseal-sync ${CORE_VERSION} Android artifacts"
curl -sfL "$ASSET_URL" -o "$WORK/core.zip"

echo "▶  Verifying checksum"
ACTUAL="$(shasum -a 256 "$WORK/core.zip" | awk '{print $1}')"
if [[ "$ACTUAL" != "$CORE_ZIP_SHA256" ]]; then
    echo "CHECKSUM MISMATCH: expected $CORE_ZIP_SHA256, got $ACTUAL" >&2
    exit 1
fi

echo "▶  Unpacking into lib/src/main"
unzip -q "$WORK/core.zip" -d "$WORK/x"

mkdir -p \
    "$REPO_ROOT/lib/src/main/jniLibs/arm64-v8a" \
    "$REPO_ROOT/lib/src/main/jniLibs/x86_64" \
    "$REPO_ROOT/lib/src/main/java/uniffi/trueseal_sync" \
    "$REPO_ROOT/lib/src/main/java/uniffi/trueseal_noise"

cp "$WORK/x/trueseal-android-staging/jniLibs/arm64-v8a/libtrueseal_sync.so"   "$REPO_ROOT/lib/src/main/jniLibs/arm64-v8a/"
cp "$WORK/x/trueseal-android-staging/jniLibs/x86_64/libtrueseal_sync.so"      "$REPO_ROOT/lib/src/main/jniLibs/x86_64/"
cp "$WORK/x/trueseal-android-staging/kotlin/uniffi/trueseal_sync/trueseal_sync.kt"   "$REPO_ROOT/lib/src/main/java/uniffi/trueseal_sync/"
cp "$WORK/x/trueseal-android-staging/kotlin/uniffi/trueseal_noise/trueseal_noise.kt" "$REPO_ROOT/lib/src/main/java/uniffi/trueseal_noise/"

echo "▶  Applying the UniFFI codegen patch"
python3 "$REPO_ROOT/.github/scripts/patch_uniffi.py"

echo "✓  Core ${CORE_VERSION} artifacts in place"
