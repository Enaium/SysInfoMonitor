@file:OptIn(
    kotlinx.cinterop.ExperimentalForeignApi::class,
    kotlin.experimental.ExperimentalNativeApi::class,
)

package cn.enaium.sysinfomonitor

import cn.enaium.sdl.SDL
import kotlin.native.CName
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.toKString
import platform.posix.getenv

/**
 * SDL3 Android entry point.
 *
 * `SDLActivity` (from the SDL3 Android archive) loads `libmain.so` and
 * calls the exported `SDL_main` symbol on a dedicated SDL thread. SDL
 * itself was already initialized by the Java activity, so we just run
 * the same [runSystemMonitor] used on every other platform.
 *
 * The host Java activity sets `SYSINFO_DPI_SCALE` to
 * `Resources.displayMetrics.density` (1.0 = mdpi, 2.0 = xhdpi, 3.0 on a
 * 480dpi phone). `runSystemMonitor` rasterizes imgui's embedded default
 * font at `13f * dpiScale` logical px with `rasterizerDensity` = the
 * framebuffer scale, so the atlas is baked at the full UI scale and text
 * stays crisp — no TTF file needs to be shipped.
 */
@CName("SDL_main")
fun sdlMain(argc: Int, argv: CPointer<CPointerVar<ByteVar>>?): Int {
    val dpiScale = getenv("SYSINFO_DPI_SCALE")
        ?.toKString()
        ?.toFloatOrNull()
        ?.takeIf { it > 0f }
        ?: 1.0f
    runSystemMonitor(
        width = 0,        // SDL_WINDOW_FULLSCREEN_DESKTOP picks the display
        height = 0,
        fullscreen = true,
        // 1 Hz full data refresh keeps CPU/battery low; 15 Hz UI rebuilds
        // match a typical touch redraw rate.
        refreshHz = 1,
        uiHz = 15,
        dpiScale = dpiScale,
    )
    SDL.quit()
    return 0
}
