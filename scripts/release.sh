#!/usr/bin/env bash
# Cuts a release: bumps the version, commits, tags "v<version>", and with --push
# pushes branch + tag — which triggers .github/workflows/release.yml to build a
# (debug-signed) APK and publish the GitHub Release. CI builds from the *committed*
# app/build.gradle.kts at the tagged commit, so the committed versionName/versionCode
# are the source of truth for what the APK reports — the tag only names the Release
# and the kararead-v<version>.apk asset; this keeps tag and gradle file in lockstep.
# Bumping versionName also increments versionCode by 1 (Android requires a new
# versionCode for every release).
#
#   scripts/release.sh 1.1.0          # bump versionName/versionCode + README, commit, tag v1.1.0
#   scripts/release.sh 1.1.0 --push   # …also push the commit + tag (CI then publishes)
#   scripts/release.sh                # tag the current versionName as-is
#
# Usage: scripts/release.sh [X.Y[.Z]] [--push]
# Shared engine: https://github.com/L-K-M/release-tool (this stub only sets config).
set -euo pipefail

export RELEASE_APP_NAME="Kararead"
export RELEASE_KIND="gradle-android"
export RELEASE_CI_NOTE="CI (release.yml) will now build the (debug-signed) APK and publish the GitHub Release (kararead-v<version>.apk) for v<version>."
export RELEASE_INVOKED_AS="scripts/release.sh"

BIN="${LKM_RELEASE_BIN:-lkm-release}"
command -v "$BIN" >/dev/null 2>&1 || {
  echo "error: lkm-release not found — clone https://github.com/L-K-M/release-tool and run ./install.sh" >&2
  exit 1
}
exec "$BIN" "$@"
