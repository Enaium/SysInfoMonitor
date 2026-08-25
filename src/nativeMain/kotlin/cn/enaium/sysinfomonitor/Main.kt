@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

import cn.enaium.sysinfomonitor.runSystemMonitor
import kotlinx.cinterop.toKString
import platform.posix.getenv

/**
 * Kotlin/Native entry point (K/N requires the executable entry in the
 * default package). Reads environment variables for headless smoke tests.
 *
 *  IMGUI_KMP_FRAMES  exit after N rendered frames
 *  SYSINFO_WIDTH     window width
 *  SYSINFO_HEIGHT    window height
 *  SYSINFO_WINDOWED  set to "1" to disable fullscreen
 *  SYSINFO_REFRESH   sysinfo refresh rate in Hz (default 4)
 *  SYSINFO_UI        UI rebuild rate in Hz (default 20)
 */
fun main() {
    val frames = getenv("IMGUI_KMP_FRAMES")?.toKString()?.toIntOrNull() ?: Int.MAX_VALUE
    val width = getenv("SYSINFO_WIDTH")?.toKString()?.toIntOrNull() ?: 1280
    val height = getenv("SYSINFO_HEIGHT")?.toKString()?.toIntOrNull() ?: 800
    val fullscreen = getenv("SYSINFO_WINDOWED")?.toKString() != "1"
    val refreshHz = getenv("SYSINFO_REFRESH")?.toKString()?.toIntOrNull()?.coerceIn(1, 30) ?: 2
    val uiHz = getenv("SYSINFO_UI")?.toKString()?.toIntOrNull()?.coerceIn(1, 120) ?: 15
    runSystemMonitor(

        height = height,
        maxFrames = frames,
        fullscreen = fullscreen,
        refreshHz = refreshHz,
        uiHz = uiHz,
    )
}
