#!/usr/bin/env bash
# scripts/check-pins.sh
#
# G8 guard (KOTLIN-3, #11): every cross-repo input in the workflows must be
# pinned. Fails if any workflow:
#   1. references `releases/latest` (a floating input — a core release would
#      silently change what Kotlin CI tests and what a Kotlin tag ships);
#   2. uses a `:latest` image tag;
#   3. downloads the core Android artifacts WITHOUT going through
#      scripts/fetch-core-artifacts.sh (the single pinned, checksum-verifying
#      fetch path — inline copies drift).
#
# Waits on: nothing. Runs in CI on every PR and push.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FAILED=0

for wf in "$REPO_ROOT"/.github/workflows/*.yml "$REPO_ROOT"/.github/workflows/*.yaml; do
    [[ -e "$wf" ]] || continue
    name="$(basename "$wf")"

    if grep -n "releases/latest" "$wf"; then
        echo "✗ $name: references releases/latest (G8 forbids floating inputs)" >&2
        FAILED=1
    fi

    # Docker-style `:latest` image tags (action pins like `uses: x@v4` are fine).
    if grep -nE "image:.*:latest|:[a-z0-9.-]*latest\b" "$wf" | grep -v "runs-on:"; then
        echo "✗ $name: uses a :latest tag" >&2
        FAILED=1
    fi

    if grep -q "trueseal-sync-android" "$wf" && ! grep -q "scripts/fetch-core-artifacts.sh" "$wf"; then
        echo "✗ $name: touches the core artifacts without scripts/fetch-core-artifacts.sh (inline fetch copies drift)" >&2
        FAILED=1
    fi
done

if [[ "$FAILED" -ne 0 ]]; then
    exit 1
fi
echo "✓ all workflows are pinned (no releases/latest, no :latest, single fetch path)"
