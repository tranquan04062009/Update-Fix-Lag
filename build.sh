#!/bin/bash
set -e

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$DIR"

echo "=========================================="
echo "⚡ Building Z-RAM Purger Extreme APK"
echo "=========================================="

SDK="/data/data/com.termux/files/home/android-sdk/android.jar"
KEYSTORE="/data/data/com.termux/files/home/.android/debug.keystore"

if [ ! -f "$SDK" ]; then
    echo "❌ Error: android.jar not found at $SDK"
    exit 1
fi

if [ ! -f "$KEYSTORE" ]; then
    echo "⚠️ Keystore not found, generating debug.keystore..."
    mkdir -p ~/.android
    keytool -genkey -v -keystore "$KEYSTORE" -alias androiddebugkey \
        -keyalg RSA -keysize 2048 -validity 10000 \
        -storepass android -keypass android -dname "CN=Android Debug,O=Android,C=US"
fi

echo "[1/7] Cleaning build artifacts..."
rm -rf bin gen
mkdir -p bin gen

echo "[2/7] Generating R.java resources..."
aapt package -f -m -J gen -M AndroidManifest.xml -S res -I "$SDK"

echo "[3/7] Compiling Java source files..."
CP="$SDK:libs/api.jar:libs/provider.jar:libs/aidl.jar:libs/shared.jar:libs/annotation.jar"
javac -source 8 -target 8 -Xlint:-options -J-Xmx384m -encoding UTF-8 -cp "$CP" -d bin $(find gen src -name "*.java")

echo "[4/7] Dexing bytecode with d8..."
# JVM heap limit (-J-Xmx384m) via JAVA_TOOL_OPTIONS to avoid LMKD OOM kills in Termux
JAVA_TOOL_OPTIONS="-Xmx384m" d8 --lib "$SDK" --output bin/ $(find bin -name "*.class") libs/*.jar

echo "[5/7] Packaging APK assets and resources..."
aapt package -f -M AndroidManifest.xml -S res -A assets -I "$SDK" -F bin/unsigned.apk
(cd bin && aapt add unsigned.apk classes.dex)

echo "[6/7] Zipaligning APK to 4-byte boundary..."
zipalign -f -p 4 bin/unsigned.apk bin/aligned.apk

echo "[7/7] Cryptographically signing APK..."
apksigner sign --ks "$KEYSTORE" --ks-pass pass:android --out bin/ZRamPurgerExtreme.apk bin/aligned.apk

echo "=========================================="
echo "✅ Build Completed Successfully!"
echo "APK Output: $DIR/bin/ZRamPurgerExtreme.apk"
ls -lh bin/ZRamPurgerExtreme.apk
echo "=========================================="

# Verification
apksigner verify bin/ZRamPurgerExtreme.apk && echo "🔒 APK Signature Verified: OK"
aapt dump badging bin/ZRamPurgerExtreme.apk | grep -E "package|launchable-activity"
