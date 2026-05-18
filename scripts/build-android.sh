#!/usr/bin/env bash
# scripts/build-android.sh
#
# Cross-compile trueseal-sync for Android (arm64-v8a + x86_64), generate UniFFI
# Kotlin bindings, and copy everything into the library module ready for Gradle.
#
# Prerequisites:
#   - Rust toolchain (rustup)
#   - Android NDK r25c+ (set ANDROID_NDK_HOME or install via Android Studio)
#   - cargo-ndk: cargo install cargo-ndk
#
# Usage:
#   bash scripts/build-android.sh            # release build (default)
#   PROFILE=debug bash scripts/build-android.sh

set -euo pipefail

# ── Config ────────────────────────────────────────────────────────────────────

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
RUST_DIR="$(cd "$REPO_ROOT/../trueseal-sync" && pwd)"

PROFILE="${PROFILE:-release}"
CRATE_NAME="trueseal_sync"

# Use a writable target dir outside the Rust source tree to avoid macOS
# com.apple.provenance lock file issues.
export CARGO_TARGET_DIR="${CARGO_TARGET_DIR:-/tmp/trueseal-sync-android-build}"

# Android NDK — check common locations
NDK_HOME="${ANDROID_NDK_HOME:-}"
if [[ -z "$NDK_HOME" ]]; then
    for candidate in \
        "$HOME/Library/Android/sdk/ndk/$(ls "$HOME/Library/Android/sdk/ndk" 2>/dev/null | sort -V | tail -1)" \
        "$HOME/Android/Sdk/ndk/$(ls "$HOME/Android/Sdk/ndk" 2>/dev/null | sort -V | tail -1)" \
        "/opt/android-ndk"; do
        if [[ -d "$candidate" ]]; then
            NDK_HOME="$candidate"
            break
        fi
    done
fi

JNI_LIBS_DIR="$REPO_ROOT/lib/src/main/jniLibs"
BINDINGS_DIR="$REPO_ROOT/lib/src/main/java"

# ── Helpers ───────────────────────────────────────────────────────────────────

step() { echo -e "\n\033[1;34m▶  $*\033[0m"; }
ok()   { echo -e "\033[1;32m✓  $*\033[0m"; }
fail() { echo -e "\033[1;31m✗  $*\033[0m" >&2; exit 1; }

require() {
    command -v "$1" &>/dev/null || fail "Required tool not found: $1 — $2"
}

# ── Preflight ─────────────────────────────────────────────────────────────────

# Prefer rustup-managed cargo
[[ -x "$HOME/.cargo/bin/cargo" ]] && export PATH="$HOME/.cargo/bin:$PATH"

require cargo       "install Rust from https://rustup.rs"
require cargo-ndk   "run: cargo install cargo-ndk"

[[ -n "$NDK_HOME" && -d "$NDK_HOME" ]] || fail \
    "Android NDK not found. Set ANDROID_NDK_HOME or install via Android Studio → SDK Manager → NDK."

export ANDROID_NDK_HOME="$NDK_HOME"

step "Using NDK: $NDK_HOME"

# Install Android Rust targets (no-op if already installed)
step "Ensuring Android Rust targets are installed"
rustup target add aarch64-linux-android x86_64-linux-android

# ── Cross-compile ─────────────────────────────────────────────────────────────

TARGETS=(
    "aarch64-linux-android:arm64-v8a"
    "x86_64-linux-android:x86_64"
)

step "Cross-compiling trueseal-sync for Android targets (profile=$PROFILE)"

# 16KB-page-size alignment — required by Android 15+ devices and Play
# Store uploads from Nov 2025. Without this, dlopen fails on modern
# devices/emulators with: "program alignment (8192) cannot be smaller
# than system page size (16384)".
export RUSTFLAGS="${RUSTFLAGS:-} -C link-arg=-Wl,-z,max-page-size=16384"

for entry in "${TARGETS[@]}"; do
    RUST_TARGET="${entry%%:*}"
    ABI="${entry##*:}"

    step "  cargo-ndk → $RUST_TARGET ($ABI)"
    (
        cd "$RUST_DIR"
        cargo ndk \
            --target "$RUST_TARGET" \
            -- build $([ "$PROFILE" = "release" ] && echo "--release") 2>&1 | tail -5
    )

    LIB_PATH="$CARGO_TARGET_DIR/$RUST_TARGET/$PROFILE/lib${CRATE_NAME}.so"
    [[ -f "$LIB_PATH" ]] || fail "Missing $LIB_PATH after build"

    mkdir -p "$JNI_LIBS_DIR/$ABI"
    cp "$LIB_PATH" "$JNI_LIBS_DIR/$ABI/lib${CRATE_NAME}.so"
    ok "$ABI → $JNI_LIBS_DIR/$ABI/lib${CRATE_NAME}.so"
done

# ── UniFFI: generate Kotlin bindings ──────────────────────────────────────────

step "Generating UniFFI Kotlin bindings"

# Use the host dylib (macOS) for metadata extraction — same FFI surface.
# Falls back to any release dylib if the host build isn't present.
HOST_TARGET="aarch64-apple-darwin"
HOST_DYLIB="$CARGO_TARGET_DIR/$HOST_TARGET/$PROFILE/lib${CRATE_NAME}.dylib"

if [[ ! -f "$HOST_DYLIB" ]]; then
    step "  Host dylib not found — building for $HOST_TARGET first"
    (
        cd "$RUST_DIR"
        cargo build $([ "$PROFILE" = "release" ] && echo "--release") \
            --target "$HOST_TARGET" 2>&1 | tail -3
    )
fi

[[ -f "$HOST_DYLIB" ]] || fail "Host dylib missing: $HOST_DYLIB"

BINDGEN_TMP="/tmp/trueseal-kotlin-bindings-$$"
mkdir -p "$BINDGEN_TMP"

(
    cd "$RUST_DIR"
    cargo run --bin uniffi-bindgen -- generate \
        --library "$HOST_DYLIB" \
        --language kotlin \
        --out-dir "$BINDGEN_TMP" \
        2>&1 | tail -5
)

# Copy generated files into the library source tree
mkdir -p "$BINDINGS_DIR/uniffi/trueseal_sync" "$BINDINGS_DIR/uniffi/trueseal_noise"
cp "$BINDGEN_TMP/uniffi/trueseal_sync/trueseal_sync.kt"   "$BINDINGS_DIR/uniffi/trueseal_sync/trueseal_sync.kt"
cp "$BINDGEN_TMP/uniffi/trueseal_noise/trueseal_noise.kt" "$BINDINGS_DIR/uniffi/trueseal_noise/trueseal_noise.kt"
rm -rf "$BINDGEN_TMP"

ok "Bindings written to $BINDINGS_DIR"

# ── Done ─────────────────────────────────────────────────────────────────────

echo ""
echo "╔══════════════════════════════════════════════════════════════╗"
echo "║  Android .so files + Kotlin bindings ready.                ║"
echo "║  Commit lib/src/main/jniLibs/ and lib/src/main/java/        ║"
echo "║  then push — JitPack will assemble the AAR on the next tag. ║"
echo "╚══════════════════════════════════════════════════════════════╝"
