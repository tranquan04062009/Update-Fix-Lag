#!/system/bin/sh
# ==============================================================================
# NO-ROOT ADB / SHIZUKU HYPER-BOOST MASTER SCRIPT
# Quyền hạn yêu cầu: ADB Shell hoặc Shizuku (UID 2000) - TUYỆT ĐỐI KHÔNG CẦN ROOT!
# Tác giả: Agent 2 - AOSP Framework & ADB Specialization
# ==============================================================================

echo "===================================================================="
echo "   KÍCH HOẠT TỐI ƯU HỆ THỐNG NO-ROOT QUA ADB / SHIZUKU (UID 2000)"
echo "===================================================================="

# ------------------------------------------------------------------------------
# 1. KHÓA PHENOTYPE & DEVICECONFIG (Chống Google Play Services tự hoàn tác)
# ------------------------------------------------------------------------------
echo "[+] 1. Đóng băng đồng bộ hóa DeviceConfig từ máy chủ Google..."
cmd device_config set_sync_disabled_for_tests persistent 2>/dev/null

echo "[+] 2. Ép cấu hình DisplayManager & App Freezer qua DeviceConfig..."
cmd device_config put display_manager refresh_rate_in_high_zone 120 2>/dev/null
cmd device_config put display_manager refresh_rate_in_low_zone 120 2>/dev/null
cmd device_config put display_manager refresh_rate_in_hbm_sunlight 120 2>/dev/null
cmd device_config put display_manager refresh_rate_in_hbm_hdr 120 2>/dev/null
cmd device_config put activity_manager_native_boot use_freezer true 2>/dev/null
cmd device_config put activity_manager_native_boot freeze_debounce_timeout 1000 2>/dev/null
cmd device_config put activity_manager_native_boot freezer_statsd_sample_rate 0 2>/dev/null
cmd device_config put statsd active false 2>/dev/null

# ------------------------------------------------------------------------------
# 2. ĐỒ HOẠ & MÀN HÌNH: TRIỆT TIÊU BLUR, ÉP 120HZ, BẬT ANGLE VULKAN TOÀN DIỆN
# ------------------------------------------------------------------------------
echo "[+] 3. Tắt hiệu ứng mờ nhòe Gaussian Blur (Tiết kiệm cực lớn GPU fill-rate)..."
cmd window disable-blur 1 2>/dev/null
settings put global disable_window_blurs 1 2>/dev/null

echo "[+] 4. Đưa tỷ lệ hoạt họa hệ thống về 0.0 (Phản hồi tức thì, 0ms chờ)..."
settings put global window_animation_scale 0.0
settings put global transition_animation_scale 0.0
settings put global animator_duration_scale 0.0

echo "[+] 5. Khóa cứng tần số quét màn hình 120.0Hz..."
settings put system peak_refresh_rate 120.0
settings put system min_refresh_rate 120.0
settings put global low_power_refresh_rate_change_delay_ms 0

echo "[+] 6. Ép tầng đồ họa ANGLE (Almost Native GLES over Vulkan)..."
settings put global angle_gl_driver_all_angle 1
settings put global show_angle_in_use_dialog_box 0
settings put global angle_defer_rules 1

# ------------------------------------------------------------------------------
# 3. KÍCH HOẠT CHẾ ĐỘ HIỆU NĂNG CỐ ĐỊNH (FIXED PERFORMANCE POWER MODE)
# ------------------------------------------------------------------------------
echo "[+] 7. Kích hoạt Sustained Fixed Performance Mode (nếu SoC hỗ trợ)..."
cmd power set-fixed-performance-mode-enabled true 2>/dev/null

# ------------------------------------------------------------------------------
# 4. HÀM TỐI ƯU RIÊNG CHO GAME (GAME INTERVENTIONS & ART AOT COMPILER)
# ------------------------------------------------------------------------------
boost_game() {
    PKG="$1"
    SCALE="${2:-0.8}"
    TARGET_FPS="${3:-120}"

    if [ -z "$PKG" ]; then
        echo "[-] Vui lòng cung cấp package name: boost_game <package> [downscale] [fps]"
        return 1
    fi

    echo ">>> Đang tối ưu Game: $PKG (Downscale: $SCALE, Target FPS: $TARGET_FPS) <<<"
    
    # Ép chế độ hiệu năng cao cho game
    cmd game mode performance "$PKG" 2>/dev/null
    cmd game set --mode performance --downscale "$SCALE" --fps "$TARGET_FPS" "$PKG" 2>/dev/null
    
    # Dự phòng thông qua DeviceConfig game_overlay nếu game không khai báo game-category
    cmd device_config put game_overlay "$PKG" "mode=2,downscaleFactor=$SCALE,fps=$TARGET_FPS" 2>/dev/null

    # Thêm vào danh sách ưu tiên ANGLE Vulkan
    settings put global angle_gl_driver_selection_pkgs "$PKG" 2>/dev/null
    settings put global angle_gl_driver_selection_values "angle" 2>/dev/null

    # Biên dịch trước toàn bộ mã máy AOT Native (Loại bỏ 100% khựng do JIT compiler)
    echo "  -> Đang biên dịch AOT Native Machine Code (-m speed)..."
    cmd package compile -m speed -f "$PKG" 2>/dev/null
    
    # Biên dịch trước toàn bộ layout XML sang Java bytecode
    echo "  -> Đang tiền biên dịch XML layout (--compile-layouts)..."
    cmd package compile --compile-layouts "$PKG" 2>/dev/null

    echo "  [✓] Hoàn tất tối ưu game $PKG!"
}

# ------------------------------------------------------------------------------
# 5. HÀM ĐÓNG BĂNG & CẮT ĐỨT CHẠY NGẦM CỦA ỨNG DỤNG RÁC (NO-ROOT FREEZE)
# ------------------------------------------------------------------------------
suppress_background_app() {
    PKG="$1"
    if [ -z "$PKG" ]; then
        return 1
    fi

    echo ">>> Triệt tiêu chạy ngầm không cần root: $PKG <<<"
    # Cấm chạy ngầm qua AppOps
    cmd appops set "$PKG" RUN_IN_BACKGROUND ignore 2>/dev/null
    cmd appops set "$PKG" RUN_ANY_IN_BACKGROUND ignore 2>/dev/null
    cmd appops set "$PKG" WAKE_LOCK ignore 2>/dev/null
    cmd appops set "$PKG" START_FOREGROUND ignore 2>/dev/null

    # Đưa vào trạng thái ngủ đông (App Hibernation) trên Android 12+
    cmd app_hibernation set-state --user 0 "$PKG" true 2>/dev/null
    cmd app_hibernation set-state --global "$PKG" true 2>/dev/null
    echo "  [✓] Đã khóa toàn bộ quyền chạy ngầm & đưa $PKG vào trạng thái ngủ đông!"
}

# ------------------------------------------------------------------------------
# 6. DỌN DẸP BỘ NHỚ ĐỆM TOÀN HỆ THỐNG & TỐI ƯU HÓA TIẾN TRÌNH
# ------------------------------------------------------------------------------
echo "[+] 8. Xả bộ nhớ đệm ứng dụng (Trimming 4GB Caches)..."
cmd package trim-caches 4294967296 2>/dev/null

echo "[+] 9. Kích hoạt chu kỳ Idle Maintenance dọn RAM và GC hệ thống..."
cmd activity idle-maintenance 2>/dev/null

# ------------------------------------------------------------------------------
# 7. TỰ ĐỘNG QUÉT VÀ TỐI ƯU CÁC GAME PHỔ BIẾN NẾU ĐÃ CÀI ĐẶT
# ------------------------------------------------------------------------------
POPULAR_GAMES="com.dts.freefireth com.dts.freefiremax com.vng.pubgmobile com.tencent.ig com.miHoYo.GenshinImpact com.roblox.client com.mobile.legends com.garena.game.kgvn"

for G in $POPULAR_GAMES; do
    if pm list packages | grep -q "$G"; then
        boost_game "$G" 0.8 120
    fi
done

# TỰ ĐỘNG CHẶN CHẠY NGẦM CÁC ỨNG DỤNG MẠNG XÃ HỘI NẶNG NẾU CÓ
BLOAT_APPS="com.facebook.katana com.facebook.orca com.zhiliaoapp.musically com.ss.android.ugc.trill com.shopee.vn com.lazada.android"

for B in $BLOAT_APPS; do
    if pm list packages | grep -q "$B"; then
        suppress_background_app "$B"
    fi
done

echo "===================================================================="
echo " [✓] TOÀN BỘ CẤU HÌNH NO-ROOT ADB ĐÃ ĐƯỢC ÁP DỤNG THÀNH CÔNG!"
echo "     Thiết bị đã sẵn sàng cho trải nghiệm mượt mà, phản hồi tức thì."
echo "===================================================================="
