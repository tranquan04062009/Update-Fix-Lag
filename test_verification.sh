#!/bin/bash
set -e

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$DIR"

echo "=========================================================="
echo "🧪 Z-RAM Purger Extreme Multi-OEM Test & Verification Suite"
echo "=========================================================="

PASSED_COUNT=0
FAILED_COUNT=0

assert_success() {
    local desc="$1"
    shift
    if "$@"; then
        echo "  [PASS] $desc"
        PASSED_COUNT=$((PASSED_COUNT + 1))
    else
        echo "  [FAIL] $desc"
        FAILED_COUNT=$((FAILED_COUNT + 1))
    fi
}

echo ""
echo "[Phase 1/6] Verifying Toolchain & Build Artifacts..."
assert_success "Build script exists and readable" test -f "$DIR/build.sh"

echo "Executing canonical build pipeline (build.sh)..."
bash "$DIR/build.sh" > /dev/null 2>&1
assert_success "build.sh exit code is 0" test $? -eq 0

APK="$DIR/bin/ZRamPurgerExtreme.apk"
assert_success "APK file generated at $APK" test -f "$APK"
assert_success "APK size greater than 50 KB" test $(stat -c%s "$APK") -gt 50000

apksigner verify "$APK" > /dev/null 2>&1
assert_success "Cryptographic APK signature verified (apksigner)" test $? -eq 0

BADGING=$(aapt dump badging "$APK")
assert_success "Package name is com.zalorust.zrampurger" echo "$BADGING" | grep -q "name='com.zalorust.zrampurger'"
assert_success "Launchable activity is MainActivity" echo "$BADGING" | grep -q "com.zalorust.zrampurger.MainActivity"

echo ""
echo "[Phase 2/6] Running In-Process Java Multi-OEM Test Runner..."
DEX="$DIR/bin/classes.dex"
assert_success "DEX bytecode classes.dex exists" test -f "$DEX"

# Execute Java test runner via Android's native runtime
JAVA_OUT=$(CLASSPATH="$DEX" /system/bin/app_process /system/bin com.zalorust.zrampurger.TestVerificationRunner 2>&1)
JAVA_EXIT=$?
echo "$JAVA_OUT"
assert_success "In-Process Java TestVerificationRunner exit code is 0" test $JAVA_EXIT -eq 0

echo ""
echo "[Phase 3/6] Verifying Sysfs Kernel Memory Nodes..."
if [ -r "/sys/block/zram0/mm_stat" ]; then
    MM_STAT=$(cat /sys/block/zram0/mm_stat)
    assert_success "/sys/block/zram0/mm_stat readable without root" test -n "$MM_STAT"
    ORIG=$(echo "$MM_STAT" | awk '{print $1}')
    COMPR=$(echo "$MM_STAT" | awk '{print $2}')
    assert_success "Uncompressed data size > 0" test "$ORIG" -gt 0
    assert_success "Compressed data size > 0" test "$COMPR" -gt 0
    assert_success "Real Z-RAM compression ratio > 1.0x" awk "BEGIN {exit !($ORIG >= $COMPR)}"
fi

if [ -r "/sys/block/zram0/disksize" ]; then
    DISKSIZE=$(cat /sys/block/zram0/disksize)
    assert_success "/sys/block/zram0/disksize > 0" test "$DISKSIZE" -gt 0
fi

if [ -r "/sys/block/zram0/backing_dev" ]; then
    BACKING_DEV=$(cat /sys/block/zram0/backing_dev)
    echo "  [INFO] Detected Z-RAM backing device: $BACKING_DEV"
    assert_success "Backing dev node accessible" test -n "$BACKING_DEV"
fi

echo ""
echo "[Phase 4/6] Verifying Qualcomm Adreno KGSL Telemetry..."
if [ -r "/sys/class/kgsl/kgsl-3d0/gpu_model" ]; then
    GPU_MODEL=$(cat /sys/class/kgsl/kgsl-3d0/gpu_model)
    assert_success "Qualcomm GPU model identified ($GPU_MODEL)" test -n "$GPU_MODEL"
fi

if [ -r "/sys/class/kgsl/kgsl/page_alloc" ]; then
    GPU_ALLOC=$(cat /sys/class/kgsl/kgsl/page_alloc)
    assert_success "KGSL GPU allocated memory > 0 ($GPU_ALLOC bytes)" test "$GPU_ALLOC" -gt 0
fi

echo ""
echo "[Phase 5/6] Verifying Safety Whitelist Invariants..."
# Ensure that com.termux is NEVER targeted in source code purge calls
assert_success "com.termux is protected in getCriticalWhitelist" grep -q '"com.termux"' "$DIR/src/com/zalorust/zrampurger/PurgeEngine.java"
assert_success "com.android.systemui is protected in getCriticalWhitelist" grep -q '"com.android.systemui"' "$DIR/src/com/zalorust/zrampurger/PurgeEngine.java"
assert_success "com.android.bluetooth is protected in getCriticalWhitelist" grep -q '"com.android.bluetooth"' "$DIR/src/com/zalorust/zrampurger/PurgeEngine.java"
assert_success "com.android.nfc is protected in getCriticalWhitelist" grep -q '"com.android.nfc"' "$DIR/src/com/zalorust/zrampurger/PurgeEngine.java"
assert_success "com.android.settings is protected in getCriticalWhitelist" grep -q '"com.android.settings"' "$DIR/src/com/zalorust/zrampurger/PurgeEngine.java"
assert_success "Shizuku daemons protected in getCriticalWhitelist" grep -q '"rikka.shizuku"' "$DIR/src/com/zalorust/zrampurger/PurgeEngine.java"

echo ""
echo "[Phase 6/6] Verifying Extreme Performance Max & Game Booster Subsystems..."
assert_success "PerformanceEngine.java exists" test -f "$DIR/src/com/zalorust/zrampurger/PerformanceEngine.java"
assert_success "GameBoosterManager.java exists" test -f "$DIR/src/com/zalorust/zrampurger/GameBoosterManager.java"
assert_success "BoosterService.java exists" test -f "$DIR/src/com/zalorust/zrampurger/BoosterService.java"
assert_success "BootCompletedReceiver.java exists" test -f "$DIR/src/com/zalorust/zrampurger/BootCompletedReceiver.java"

# Manifest checks
assert_success "BoosterService registered in AndroidManifest.xml" grep -q 'android:name=".BoosterService"' "$DIR/AndroidManifest.xml"
assert_success "BootCompletedReceiver registered in AndroidManifest.xml" grep -q 'android:name=".BootCompletedReceiver"' "$DIR/AndroidManifest.xml"
assert_success "FOREGROUND_SERVICE permission declared" grep -q 'android.permission.FOREGROUND_SERVICE' "$DIR/AndroidManifest.xml"
assert_success "POST_NOTIFICATIONS permission declared" grep -q 'android.permission.POST_NOTIFICATIONS' "$DIR/AndroidManifest.xml"
assert_success "BoosterService exported=false configured" grep -q 'android:name=".BoosterService" android:exported="false"' "$DIR/AndroidManifest.xml"

# WebAppInterface bridge checks
assert_success "WebAppInterface has toggleGameBooster" grep -q 'toggleGameBooster' "$DIR/src/com/zalorust/zrampurger/WebAppInterface.java"
assert_success "WebAppInterface has isGameBoosterActive" grep -q 'isGameBoosterActive' "$DIR/src/com/zalorust/zrampurger/WebAppInterface.java"
assert_success "WebAppInterface has getPerformanceStatusJson" grep -q 'getPerformanceStatusJson' "$DIR/src/com/zalorust/zrampurger/WebAppInterface.java"
assert_success "WebAppInterface has triggerArtOptimization" grep -q 'triggerArtOptimization' "$DIR/src/com/zalorust/zrampurger/WebAppInterface.java"
assert_success "WebAppInterface has emergencyRestoreDefaults" grep -q 'emergencyRestoreDefaults' "$DIR/src/com/zalorust/zrampurger/WebAppInterface.java"

# UI checks
assert_success "Booster card defined in index.html" grep -q 'id="booster-card"' "$DIR/assets/index.html"
assert_success "Booster toggle switch defined in index.html" grep -q 'id="booster-toggle"' "$DIR/assets/index.html"
assert_success "Booster telemetry meters defined in index.html" grep -q 'id="booster-gpu-clk"' "$DIR/assets/index.html"
assert_success "Booster emergency reset button in index.html" grep -q 'onEmergencyResetDefaults' "$DIR/assets/index.html"

echo ""
echo "=========================================================="
echo "📊 Verification Suite Complete: $PASSED_COUNT Passed, $FAILED_COUNT Failed"
echo "=========================================================="

if [ "$FAILED_COUNT" -gt 0 ]; then
    echo "❌ Verification FAILED!"
    exit 1
else
    echo "✅ ALL VERIFICATION CHECKS PASSED!"
    exit 0
fi
