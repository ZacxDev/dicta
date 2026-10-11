#!/usr/bin/env bash
# Offline (no-Gradle) build for the Dicta PoC.
#
# This sandbox has no Java-capable egress proxy, so Gradle cannot resolve the
# Android Gradle Plugin from the network. This script builds the debug APK
# directly with aapt2 + kotlinc + d8 + apksigner instead.
#
# On a normal machine with internet access, prefer:
#     ./gradlew :app:assembleDebug
#
# Env overrides:
#   ANDROID_HOME  default ~/workspace/android-sdk
#   KOTLIN_HOME   default ~/workspace/android-sdk/kotlin-compiler
set -euo pipefail

REPO_DIR="$(cd "$(dirname "$0")/.." && pwd)"
APP_DIR="$REPO_DIR/app"
BUILD_DIR="$REPO_DIR/build-offline"
PKG="dev.zacx.dicta"

SDK_DIR="${ANDROID_HOME:-$HOME/workspace/android-sdk}"
KOTLIN_HOME="${KOTLIN_HOME:-$HOME/workspace/android-sdk/kotlin-compiler}"

if [ -f "$SDK_DIR/env.sh" ]; then
    # shellcheck disable=SC1090
    source "$SDK_DIR/env.sh"
else
    export JAVA_HOME="$SDK_DIR/jdk-17"
    export ANDROID_HOME="$SDK_DIR"
    export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/build-tools/34.0.0:$PATH"
fi

BT="$ANDROID_HOME/build-tools/34.0.0"
ANDROID_JAR="$ANDROID_HOME/platforms/android-34/android.jar"
STDLIB="$KOTLIN_HOME/lib/kotlin-stdlib.jar"

for f in "$BT/aapt2" "$BT/d8" "$BT/zipalign" "$BT/apksigner" \
         "$ANDROID_JAR" "$STDLIB" "$KOTLIN_HOME/bin/kotlinc"; do
    [ -e "$f" ] || { echo "missing: $f" >&2; exit 1; }
done

rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR"/{gen,dex}

echo "== aapt2 compile (resources) =="
"$BT/aapt2" compile --dir "$APP_DIR/src/main/res" -o "$BUILD_DIR/res-compiled.zip"

echo "== manifest (inject package/version attrs for aapt2 link) =="
sed 's|<manifest xmlns:android="http://schemas.android.com/apk/res/android">|<manifest xmlns:android="http://schemas.android.com/apk/res/android"\n    package="'"$PKG"'"\n    android:versionCode="1"\n    android:versionName="0.1.0">|' \
    "$APP_DIR/src/main/AndroidManifest.xml" > "$BUILD_DIR/AndroidManifest.xml"

echo "== aapt2 link =="
"$BT/aapt2" link -o "$BUILD_DIR/app-unaligned.apk" \
    -I "$ANDROID_JAR" \
    --manifest "$BUILD_DIR/AndroidManifest.xml" \
    --java "$BUILD_DIR/gen" \
    "$BUILD_DIR/res-compiled.zip"

echo "== kotlinc =="
# shellcheck disable=SC2207
SRCS=($(find "$APP_DIR/src/main/java" "$BUILD_DIR/gen" \( -name '*.kt' -o -name '*.java' \)))
"$KOTLIN_HOME/bin/kotlinc" -jvm-target 17 -cp "$ANDROID_JAR" \
    -d "$BUILD_DIR/classes.jar" "${SRCS[@]}"

echo "== d8 (dex) =="
"$BT/d8" --lib "$ANDROID_JAR" --min-api 26 --output "$BUILD_DIR/dex" \
    "$BUILD_DIR/classes.jar" "$STDLIB"

echo "== package classes.dex =="
cp "$BUILD_DIR/app-unaligned.apk" "$BUILD_DIR/app-dex.apk"
(cd "$BUILD_DIR/dex" && zip -q -uj "$BUILD_DIR/app-dex.apk" classes*.dex)

echo "== zipalign =="
"$BT/zipalign" -f -p 4 "$BUILD_DIR/app-dex.apk" "$BUILD_DIR/app-aligned.apk"

echo "== sign =="
KS="$BUILD_DIR/debug.keystore"
if [ ! -f "$KS" ]; then
    keytool -genkeypair -keystore "$KS" -alias androiddebugkey -keyalg RSA \
        -keysize 2048 -validity 10000 -storepass android -keypass android \
        -dname "CN=Android Debug,O=Android,C=US" >/dev/null 2>&1
fi
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
    --out "$BUILD_DIR/dicta-debug.apk" "$BUILD_DIR/app-aligned.apk"

echo "== verify =="
"$BT/apksigner" verify "$BUILD_DIR/dicta-debug.apk"
"$BT/aapt2" dump badging "$BUILD_DIR/dicta-debug.apk" | head -6

echo
echo "OK: $BUILD_DIR/dicta-debug.apk"
