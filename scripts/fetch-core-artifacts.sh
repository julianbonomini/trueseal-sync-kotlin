#!/usr/bin/env bash
# scripts/fetch-core-artifacts.sh
#
# THE single pinned, checksum-verifying path for fetching the trueseal-sync
# Android artifacts (.so + generated UniFFI Kotlin bindings). Both workflows
# call this script (KOTLIN-3, #11 — inline fetch copies drift), and
# scripts/check-pins.sh fails the build on any other fetch path or on a
# floating `releases/latest` reference. Run it after cloning for local
# development.
#
# THE PIN (G8, sync ADR-0030): CORE_VERSION + CORE_ZIP_SHA256 below are the
# permanent pin — an exact version and its SHA-256, committed, verified on
# every fetch, never a moving pointer. Bump them deliberately, in a commit,
# when the core moves.
#
# TRANSPORT (interim until A6): the bytes come from THIS repo's
# `core-v0.6.0` prerelease — a byte-exact, checksum-verified mirror of
# trueseal-sync's v0.6.0 candidate — because TRUESEAL_READ_TOKEN is expired
# (maintainer item A6) and the core's v0.6.0 release is still a draft, so
# cross-repo reads 401. Once A6 lands and the core release is published,
# flip the default CORE_ASSET_BASE_URL below to the upstream URL in the
# comment — the pin and the checksum verification do not change.
#
# Usage: bash scripts/fetch-core-artifacts.sh
# Override (Release Conductor / SDK-4): CORE_ASSET_BASE_URL=<url> bash ...

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

# ── The pin (G8: exact version + checksum — never releases/latest) ────────
CORE_VERSION="v0.6.0"
CORE_ZIP_SHA256="37faff4678f426162e908c06e3c05fec329b8f7c2320247c886d3b6f54aa115d"

# ── Transport (see the header; flip the default once A6 lands) ────────────
# Upstream: https://github.com/julianbonomini/trueseal-sync/releases/download/${CORE_VERSION}
CORE_ASSET_BASE_URL="${CORE_ASSET_BASE_URL:-https://github.com/julianbonomini/trueseal-sync-kotlin/releases/download/core-${CORE_VERSION}}"
ASSET_URL="${CORE_ASSET_BASE_URL}/trueseal-sync-android-${CORE_VERSION}.zip"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

echo "▶  Downloading pinned trueseal-sync ${CORE_VERSION} Android artifacts"
echo "   $ASSET_URL"
curl -sfL "$ASSET_URL" -o "$WORK/core.zip"

echo "▶  Verifying checksum (pinned: $CORE_ZIP_SHA256)"
ACTUAL="$(shasum -a 256 "$WORK/core.zip" | awk '{print $1}')"
if [[ "$ACTUAL" != "$CORE_ZIP_SHA256" ]]; then
    echo "CHECKSUM MISMATCH: expected $CORE_ZIP_SHA256, got $ACTUAL" >&2
    exit 1
fi

# Record the pinned version for the build output / release notes (KOTLIN-3).
echo "▶  Pinned core version: ${CORE_VERSION}"
if [[ -n "${GITHUB_ENV:-}" ]]; then
    echo "UPSTREAM_VERSION=${CORE_VERSION}" >> "$GITHUB_ENV"
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
