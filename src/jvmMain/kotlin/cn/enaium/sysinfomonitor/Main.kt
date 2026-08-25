@file:JvmName("SystemMonitor")

package cn.enaium.sysinfomonitor

/**
 * JVM entry point.
 *
 * Flags:
 *  --frames N      exit after N rendered frames (headless CI smoke test)
 *  --width W       window width
 *  --height H      window height
 *  --windowed      disable fullscreen (default is fullscreen ImGui host)
 *  --refresh N     sysinfo refresh rate, in Hz (default 4; clamped to [1..30])
 *  --ui N          UI rebuild rate, in Hz (default 20; clamped to [1..120])
 *  --help / -h     print this message
 */
fun main(args: Array<String>) {
    var width = 1280
    var height = 800
    var frames = Int.MAX_VALUE
    var fullscreen = true
    var refreshHz = 2
    var uiHz = 15
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--frames" -> if (i + 1 < args.size) { frames = args[++i].toIntOrNull() ?: Int.MAX_VALUE }
            "--width" -> if (i + 1 < args.size) { width = args[++i].toIntOrNull() ?: width }
            "--height" -> if (i + 1 < args.size) { height = args[++i].toIntOrNull() ?: height }
            "--windowed" -> fullscreen = false
            "--refresh" -> if (i + 1 < args.size) { refreshHz = (args[++i].toIntOrNull() ?: refreshHz).coerceIn(1, 30) }
            "--ui" -> if (i + 1 < args.size) { uiHz = (args[++i].toIntOrNull() ?: uiHz).coerceIn(1, 120) }
            "--help", "-h" -> {
                println(
                    "SysInfoMonitor [--frames N] [--width W] [--height H]\n" +
                        "              [--windowed] [--refresh N] [--ui N]"
                )
                return
            }
        }
        i++
    }
    runSystemMonitor(
        width = width,
        height = height,
        maxFrames = frames,
        fullscreen = fullscreen,
        refreshHz = refreshHz,
        uiHz = uiHz,
    )
}
