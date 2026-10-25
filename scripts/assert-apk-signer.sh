#!/usr/bin/env bash
#
# Assert that an APK is signed by the expected certificate, by SHA-256
# fingerprint.
#
# 🔴 WHY THIS GATE EXISTS
#
# AGP auto-generates a debug keystore when none is present. On a fresh CI runner
# that means a release can build, sign, upload and publish completely green
# while carrying a signing key nobody intended. Android then refuses to install
# it over the already-installed build (INSTALL_FAILED_UPDATE_INCOMPATIBLE), and
# the only remedy is an uninstall, which destroys the on-device dictation
# history. The failure is invisible everywhere except the operator's handset, so
# it has to be caught before publication.
#
# 🔴 WHY THE TOOL IS RESOLVED RATHER THAN CALLED BARE
#
# `apksigner` is NOT on the PATH in this project's nix-shell (measured: the
# Android SDK's build-tools dir is not added to PATH, only $ANDROID_HOME/bin).
# A bare `apksigner` therefore fails with command-not-found and prints NOTHING
# on stdout — which, to a naive grep for the expected fingerprint, is
# indistinguishable from a genuine certificate MISMATCH. "Tool missing" being
# reportable as "wrong signing key" is the exact confusion that makes a gate
# untrustworthy, so this script resolves the binary under build-tools and exits
# with a DISTINCT code and message when it cannot find one.
#
# Usage:
#   assert-apk-signer.sh <apk> <expected-sha256-fingerprint>
#
# The fingerprint may be given with or without colons, in any case:
#   c6440bef6b6e...  or  C6:44:0B:EF:6B:6E:...
#
# Exit codes:
#   0  the APK's signer matches
#   2  usage error, or the APK does not exist
#   3  apksigner could not be located  (NOT a mismatch — see above)
#   4  apksigner ran but reported no SHA-256 certificate digest
#   5  MISMATCH: the APK is signed by a different certificate
set -euo pipefail

usage() {
    echo "usage: $(basename "$0") <apk> <expected-sha256-fingerprint>" >&2
}

if [[ $# -ne 2 ]]; then
    usage
    exit 2
fi

apk=$1
expected_raw=$2

if [[ ! -f $apk ]]; then
    echo "error: no such APK: $apk" >&2
    exit 2
fi

# Normalise: strip colons and whitespace, lowercase.
normalise() {
    tr -d ':[:space:]' <<<"$1" | tr '[:upper:]' '[:lower:]'
}

expected=$(normalise "$expected_raw")
if [[ ! $expected =~ ^[0-9a-f]{64}$ ]]; then
    echo "error: expected fingerprint is not 64 hex digits after normalisation:" >&2
    echo "       got ${#expected} chars from '$expected_raw'" >&2
    exit 2
fi

# --- locate apksigner --------------------------------------------------------
#
# Order: an explicit override, then every build-tools version under each SDK
# root we know about, newest first, then the PATH as a last resort. The PATH is
# last rather than first precisely because it is the one that silently is not
# there in this project's shell.
find_apksigner() {
    if [[ -n ${APKSIGNER:-} ]]; then
        [[ -x $APKSIGNER ]] && { printf '%s\n' "$APKSIGNER"; return 0; }
        echo "error: APKSIGNER=$APKSIGNER is set but not executable" >&2
        return 1
    fi

    local root bt
    for root in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}"; do
        [[ -z $root ]] && continue
        # 34.0.0 first: it is the build-tools version android.nix composes and
        # the one compileSdk 34 pins.
        for bt in "$root/build-tools/34.0.0" \
                  $(ls -d "$root"/build-tools/*/ 2>/dev/null | sort -rV); do
            if [[ -x "${bt%/}/apksigner" ]]; then
                printf '%s\n' "${bt%/}/apksigner"
                return 0
            fi
        done
    done

    if command -v apksigner >/dev/null 2>&1; then
        command -v apksigner
        return 0
    fi

    return 1
}

if ! apksigner=$(find_apksigner); then
    cat >&2 <<EOF
error: apksigner NOT FOUND — this is a TOOLING failure, not a signature
       mismatch. Nothing has been verified; do not read this as a bad key.

       Looked for, in order:
         \$APKSIGNER                                 (${APKSIGNER:-unset})
         \$ANDROID_HOME/build-tools/*/apksigner      (${ANDROID_HOME:-unset})
         \$ANDROID_SDK_ROOT/build-tools/*/apksigner  (${ANDROID_SDK_ROOT:-unset})
         apksigner on \$PATH

       Fix: run under \`nix-shell android.nix\`, or set ANDROID_HOME, or point
       \$APKSIGNER at the binary directly.
EOF
    exit 3
fi

echo "apksigner:  $apksigner"
echo "apk:        $apk"

# --- read the signer certificate --------------------------------------------
#
# stderr is kept separate: apksigner emits warnings there (e.g. about minSdk
# scheme coverage) and folding them into stdout would put arbitrary text in
# front of the grep.
certs=$("$apksigner" verify --print-certs "$apk")

# apksigner prints one line per signer, e.g.
#   Signer #1 certificate SHA-256 digest: c6440bef...
actual=$(grep -iE 'SHA-256 digest' <<<"$certs" \
    | grep -oiE '[0-9a-f]{64}' \
    | head -1 \
    | tr '[:upper:]' '[:lower:]')

if [[ -z $actual ]]; then
    echo "error: apksigner ran but printed no SHA-256 certificate digest." >&2
    echo "       Again: this is NOT a mismatch. Its output was:" >&2
    sed 's/^/       | /' <<<"$certs" >&2
    exit 4
fi

echo "expected:   $expected"
echo "actual:     $actual"

if [[ $actual != "$expected" ]]; then
    cat >&2 <<EOF
error: SIGNER MISMATCH — refusing to publish.

       expected  $expected
       actual    $actual

       An APK signed with a different certificate CANNOT be installed over the
       build already on the handset: Android fails it with
       INSTALL_FAILED_UPDATE_INCOMPATIBLE, and the only way through is an
       uninstall, which destroys the on-device dictation history.

       The usual cause in CI is AGP auto-generating a fresh debug keystore
       because the real one was not supplied — check that DEBUG_KEYSTORE_B64
       decoded and that -Pdicta.debugKeystore points at the decoded file.
EOF
    exit 5
fi

echo "OK: the APK is signed by the expected certificate"
