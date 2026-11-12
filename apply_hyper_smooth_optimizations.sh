#!/system/bin/sh
# ==============================================================================
# HYPER SMOOTH ENGINE - ANDROID FRAMEWORK & RUNTIME OPTIMIZATION SCRIPT
# Tác giả: Agent 2 - Graphics, Touch & Runtime Architecture Specialist
# ==============================================================================

echo "[+] 1. Ép ANGLE Graphics Layer chạy qua Vulkan Backend..."
settings put global angle_gl_driver_all_angle 1
settings put global show_angle_in_use_dialog_box 0
settings put global angle_defer_rules 1
setprop persist.graphics.egl angle
setprop debug.angle.backend 2

echo "[+] 2. Triệt tiêu Logcat, Telemetry ngầm và giải phóng CPU cycles..."
setprop log.tag.all SUPPRESS
setprop persist.logd.size 64K
setprop persist.logd.logpersistd false
logcat -G 64K
logcat -c
cmd device_config put statsd active false
cmd device_config put privacy statsd_upload_interval_mins 999999
setprop persist.device_config.runtime_native.statsd_enable false
setprop debug.atrace.tags.enableflags 0
setprop persist.traced.enable 0
setprop debug.binder.tracing 0

echo "[+] 3. Tối ưu Touch Latency & vô hiệu hóa Touch Resampling trễ 5ms..."
setprop ro.input.resampling 0
setprop persist.input.touch.resampling 0
setprop debug.input.latency.enabled 0
setprop windowsmgr.max_events_per_sec 240

echo "[+] 4. Khóa cứng tần số quét đỉnh (120Hz) & tối ưu hàng đợi SurfaceFlinger..."
settings put system peak_refresh_rate 120.0
settings put system min_refresh_rate 120.0
settings put global low_power_refresh_rate_change_delay_ms 0
cmd device_config put display_manager refresh_rate_in_high_zone 120
cmd device_config put display_manager refresh_rate_in_low_zone 120
cmd device_config put display_manager refresh_rate_in_hbm_sunlight 120
cmd device_config put display_manager refresh_rate_in_hbm_hdr 120
setprop debug.sf.set_idle_timer_ms 0
setprop debug.sf.auto_latch_unsignaled 1
setprop debug.sf.early_phase_offset_ns 500000
setprop debug.sf.high_fps_late_app_phase_offset_ns 1000000
setprop debug.sf.predict_hwc_composition_strategy 1
setprop debug.sf.enable_gl_backpressure 0

echo "[+] 5. Kích hoạt HWUI Skia Vulkan Engine & Partial Screen Updates..."
setprop debug.hwui.renderer skiavk
setprop debug.hwui.use_buffer_age true
setprop debug.hwui.use_partial_updates true
setprop debug.hwui.skip_empty_damage true
setprop debug.hwui.use_gpu_pixel_buffers true
setprop debug.hwui.use_hint_manager true
setprop debug.hwui.target_cpu_time_percent 70
setprop renderthread.skia.reduceopstasksplitting true

echo "[+] 6. Đóng băng ứng dụng nền siêu tốc (CachedAppOptimizer 1s Freezer)..."
cmd device_config put activity_manager_native_boot use_freezer true
cmd device_config put activity_manager_native_boot freeze_debounce_timeout 1000
cmd device_config put activity_manager_native_boot use_compaction true
cmd device_config put activity_manager_native_boot compact_throttle_1 1000
cmd device_config put activity_manager_native_boot compact_throttle_2 2000
cmd device_config put activity_manager_native_boot freezer_statsd_sample_rate 0
cmd device_config put activity_manager_native_boot compact_statsd_sample_rate 0

echo "[+] 7. Tối ưu cấu hình ART Runtime..."
setprop dalvik.vm.dex2oat-threads 4
setprop dalvik.vm.heaptargetutilization 0.75
setprop dalvik.vm.heapgrowthlimit 384m

echo "[✓] ĐÃ ÁP DỤNG THÀNH CÔNG TOÀN BỘ CẤU HÌNH TỐI ƯU SIÊU MƯỢT!"
