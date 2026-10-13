#!/usr/bin/env bash
# scripts/check-jvm-test-only.sh
#
# KOTLIN-2 (#10, e2e ADR-0001): the :jvm-test module is a TEST-ONLY lane for
# the headless e2e gates. Plain-JVM Kotlin is NOT a supported target for the
# preview — the module must never gain a publication, and no release path may
# consume it. This script fails the build if either invariant breaks:
#
#   1. jvm-test/build.gradle.kts declares a publishing block or applies a
#      publishing plugin;
#   2. any release workflow references :jvm-test or its artifacts;
#   3. the published :lib AAR's build file references the jvm-test module.
#
# Runs in CI on every PR and push.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FAILED=0
JVM_BUILD="$REPO_ROOT/jvm-test/build.gradle.kts"

if grep -nE "maven-publish|\bpublishing\b|publish" "$JVM_BUILD" | grep -vE "^\s*[0-9]+:\s*//|never publish"; then
    echo "✗ jvm-test/build.gradle.kts declares publication machinery (test-only lane)" >&2
    FAILED=1
fi

for wf in "$REPO_ROOT"/.github/workflows/release.yml "$REPO_ROOT"/.github/workflows/promote.yml; do
    [[ -e "$wf" ]] || continue
    if grep -n "jvm-test" "$wf"; then
        echo "✗ $(basename "$wf") references the test-only :jvm-test module" >&2
        FAILED=1
    fi
done

if grep -n "jvm-test" "$REPO_ROOT/lib/build.gradle.kts"; then
    echo "✗ lib/build.gradle.kts references the test-only :jvm-test module" >&2
    FAILED=1
fi

if [[ "$FAILED" -ne 0 ]]; then
    exit 1
fi
echo "✓ :jvm-test is test-only (no publication, no release-path references)"
