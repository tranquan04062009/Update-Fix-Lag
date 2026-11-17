# Antigravity Android Engineering & UI/UX Standards

Whenever analyzing, developing, updating, refactoring, compiling, or styling this Android application, you MUST ALWAYS strictly adhere to and execute the following skills:

## 1. Core Architecture: `android-java-kotlin-architect`
- **Zero-Allocation Hot Paths**: Avoid boxing (`Integer`, `Long`), reuse static string/data buffers, and use `SparseArray` to prevent GC pauses during frame rendering.
- **Leak-Free Concurrency**: Never create anonymous Handlers/Runnables in Activities. Use `WeakReference` and dedicated background `ExecutorService`.
- **Defensive Multi-OEM Resilience**: Gracefully handle OEM variations (HyperOS, One UI, ColorOS, OriginOS, HiOS) with `try-catch (Throwable)` and package pre-verification.
- **Privileged IPC & Shizuku**: Maintain robust, non-blocking asynchronous command execution via Shizuku/ADB UID 2000.

## 2. World-Class Mobile Design: `android-native-ui-ux-master`
- **120Hz Buttery Smoothness**: Eliminate view overdraw, avoid nested weighted LinearLayouts, and use lightweight vector shapes and ripples.
- **Shadcn OLED Dark Tokens**: Canvas `#0A0C10`, Bento surfaces `#12161F`, mint accents `#2DD4B0`, purple accents `#8B7CFF`, and subtle 1px borders (`#1E2638`).
- **Touch Ergonomics & Insets**: Minimum 48dp touch targets. All scrollable views above floating bottom bars must have `paddingBottom="112dp"`.
- **Tactile Haptic Feedback**: Trigger contextual vibration clicks on toggle interactions (`VibrationEffect.EFFECT_CLICK`).

## 3. Headless Toolchain & Components: `android-app-craftsman`
- Use the 7-stage headless toolchain (`aapt`, `javac`, `d8`, `zipalign`, `apksigner`) via `build.sh`.
- Ensure high performance, zero-crash APK builds.

## 4. Strict Project Directives
- **App Name**: "QanH Adb".
- **Forbidden Words**: NEVER mention or write the word "void", "VOID", or "Void" anywhere in app code, UI, strings, comments, or user responses.
- **User Communication**: Keep all messages to the user minimalist, summarizing only high-level themes (fix lag, new settings, bug fixes) with zero technical jargon.
