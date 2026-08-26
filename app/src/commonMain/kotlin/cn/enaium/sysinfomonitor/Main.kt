package cn.enaium.sysinfomonitor

import cn.enaium.imgui.ImFontConfig
import cn.enaium.imgui.ImGui
import cn.enaium.imgui.backends.sdl.ImGuiSdlBackend
import cn.enaium.imgui.backends.sdl.ImGuiSdlRendererBackend
import cn.enaium.imgui.extensions.implot.ImPlot
import cn.enaium.sdl.SDL
import cn.enaium.sdl.SDLColor
import cn.enaium.sdl.SDLEvent
import cn.enaium.sdl.SDLInitFlags
import cn.enaium.sdl.SDLRendererFlags
import cn.enaium.sdl.SDLWindowEventType
import cn.enaium.sdl.SDLWindowFlags
import cn.enaium.sysinfomonitor.ui.SystemMonitorUi

/**
 * Common entry point.
 *
 * Important perf characteristics:
 *  - The expensive sysinfo process-table walk is amortized: the cheap
 *    path (CPU/memory deltas) runs at [refreshHz] Hz while the process
 *    walk runs at refreshHz / processRefreshEvery Hz. Default 2 Hz ×
 *    1/16 = one full refresh every 8 seconds, the single biggest
 *    speedup on systems with 1000+ live processes.
 *  - Data refresh is **skipped while the user is dragging the window**,
 *    so the 80-2000 ms sysinfo call can't stall a drag.
 *  - The ImGui draw data is rebuilt at most [uiHz] times per second when
 *    idle, and at the display refresh rate when the window is being
 *    dragged / resized (so the visible content tracks the cursor 1:1).
 *  - On idle ticks we don't present or rebuild, so the CPU is idle.
 */
fun runSystemMonitor(
    width: Int = 1280,
    height: Int = 800,
    maxFrames: Int = Int.MAX_VALUE,
    fullscreen: Boolean = true,
    refreshHz: Int = 2,
    uiHz: Int = 15,
    processRefreshEvery: Int = 16,
    /**
     * UI scale factor driven by the host's display DPI (1.0 = 160 dpi
     * baseline, 2.0 = 320 dpi, etc.). Applied as `io.FontGlobalScale`
     * so the default font renders larger and `CalcTextSize` honors
     * the new metrics; also forwarded to the UI so the few hardcoded
     * plot heights grow with it. Defaults to 1.0f on platforms that
     * don't set the `SYSINFO_DPI_SCALE` env var (desktop / JVM).
     */
    dpiScale: Float = 1.0f,
) {
    SDL.setMainReady()
    if (!SDL.init(SDLInitFlags.VIDEO or SDLInitFlags.EVENTS)) {
        SDL.setHint("SDL_VIDEO_DRIVER", "dummy")
        if (!SDL.init(SDLInitFlags.VIDEO or SDLInitFlags.EVENTS)) {
            error("SDL_Init failed: ${SDL.error()}")
        } else {
            println("video init fell back to the dummy driver (headless)")
        }
    }
    println("SDL ${SDL.version()} (${SDL.revision()}) driver=${SDL.getCurrentVideoDriver()}")
    println("Config: data refresh=$refreshHz Hz, UI rebuild up to $uiHz Hz, vsync=PRESENTVSYNC, full refresh every $processRefreshEvery data refreshes, dpi scale=$dpiScale")

    SDL.createWindow(
        title = "SysInfoMonitor",
        width = width,
        height = height,
        // HIGH_PIXEL_DENSITY (`SDL_WINDOW_ALLOW_HIGHDPI`) makes the
        // SDL renderer create a backing buffer at the device pixel
        // size (e.g. 2560x1440 on a Retina 1280x720 window). The
        // imgi-kmp SDL backend then reports
        // `io.displaySize` = window logical size and
        // `io.displayFramebufferScale` = sizeInPixels / size, which is
        // the correct high-DPI contract: ImGui lays out in logical
        // units and the renderer projects them onto the physical
        // framesuffer.
        flags = SDLWindowFlags.RESIZABLE or SDLWindowFlags.HIGH_PIXEL_DENSITY,
    ).use { window ->
        SDL.createRenderer(window, flags = SDLRendererFlags.PRESENTVSYNC).use { renderer ->
            val context = ImGui.createContext()
            try {
                val platform = ImGuiSdlBackend(window)
                val backend = ImGuiSdlRendererBackend(renderer)
                platform.init()

                // Rasterize the embedded default font (ProggyClean) via
                // ImFontConfig so it stays crisp on high-DPI displays:
                //   - sizePixels     = 13f * dpiScale (readability: 39px on
                //     Android's 480dpi/3.0, 13px on desktop where dpiScale
                //     defaults to 1.0)
                //   - rasterizerDensity = the display's framebuffer scale
                //     (1.0 on Android, 2.0 on a Retina), so the atlas is
                //     baked at `sizePixels * density` physical pixels while
                //     the logical metrics stay [sizePixels]. No TTF file
                //     needed: the font comes from imgui's embedded data.
                val fbScale = (window.sizeInPixels.x.toFloat() / window.size.x.toFloat())
                    .coerceAtLeast(1f)
                val fonts = ImGui.getIO().fonts
                fonts.addFontDefault(
                    ImFontConfig(
                        sizePixels = 13f * dpiScale,
                        rasterizerDensity = fbScale,
                    ),
                )
                check(fonts.build()) { "font atlas build failed" }
                val texData = fonts.getTexDataAsRGBA32()
                val fontTextureId = backend.uploadFontTexture(texData.pixels, texData.width, texData.height)
                fonts.setTexID(fontTextureId)

                val plotContext = ImPlot.createContext()
                ImPlot.setImGuiContext(context)

                val manager = SnapshotManager(processRefreshEvery = processRefreshEvery)
                val ui = SystemMonitorUi(dpiScale = dpiScale)
                ui.setFontTextureId(fontTextureId)
                ui.setFullscreen(fullscreen)

                val refreshInterval: ULong =
                    (1000uL / refreshHz.toUInt()).coerceAtLeast(50uL)
                val uiInterval: ULong =
                    (1000uL / uiHz.toUInt()).coerceAtLeast(20uL)

                var lastRefreshTick: ULong = 0u
                var lastUiTick: ULong = 0u
                var lastSnapshot: SystemSnapshot? = null
                var firstFrame = true

                var running = true
                var frame = 0
                while (running && frame < maxFrames) {
                    val nowTick = SDL.getTicks()

                    // ----- input -----
                    // Drain the entire SDL event queue every tick (no
                    // batching) so high-frequency events like window
                    // dragging are processed promptly. While a window is
                    // being dragged, the OS sends a stream of Window
                    // events; missing one causes the visible content to
                    // lag behind the cursor.
                    var hadInput = false
                    var windowChanged = false
                    while (true) {
                        val event = SDL.pollEvent() ?: break
                        hadInput = true
                        when (event) {
                            is SDLEvent.Quit -> running = false
                            is SDLEvent.Window -> {
                                windowChanged = true
                                if (event.type == SDLWindowEventType.CLOSE_REQUESTED) running = false
                            }
                            else -> platform.processEvent(event)
                        }
                    }

                    // ----- data (only at the refresh rate, skip during window drag) -----
                    // The sysinfo refresh can take 80-2000 ms on systems with
                    // many processes; doing it on a drag frame would block
                    // the window update and make dragging feel jerky. The
                    // user is not looking at the data right now anyway —
                    // wait until the drag finishes.
                    if (!windowChanged && (firstFrame || (nowTick - lastRefreshTick) >= refreshInterval)) {
                        val t0 = SDL.getTicks()
                        val snapshot = try {
                            manager.refresh()
                        } catch (t: Throwable) {
                            logErr("snapshot refresh failed: $t")
                            null
                        }
                        val refreshMs = SDL.getTicks() - t0
                        if (snapshot != null) {
                            lastSnapshot = snapshot
                            ui.update(snapshot)
                            lastRefreshTick = nowTick
                        }
                        if (refreshMs > 50u) {
                            println("refresh: ${refreshMs}ms procs=${snapshot?.processes?.size}")
                        }
                    }

                    val snapshot = lastSnapshot

                    // ----- UI rebuild (only when something changed) -----
                    //
                    // `windowChanged` bypasses the [uiHz] cap so that
                    // window dragging feels native (the ImGui viewport
                    // tracks the window size in real time).
                    val dataChanged = (nowTick - lastRefreshTick) < 16uL
                    val uiTickElapsed = (nowTick - lastUiTick) >= uiInterval
                    val needRebuild = snapshot != null &&
                        (firstFrame || windowChanged || dataChanged ||
                            (hadInput && uiTickElapsed) || uiTickElapsed)

                    if (needRebuild) {
                        platform.newFrame()
                        // `ImGuiSdlBackend.newFrame()` sets
                        // `io.displaySize = window.size` (logical window
                        // size) and `io.displayFramebufferScale` from
                        // `sizeInPixels / size`. Both are left unmodified:
                        // SDL reports mouse/wheel events in `window.size`
                        // coordinates on every platform, so the imgui
                        // layout must live in that same logical space for
                        // the cursor to stay aligned with widget rects
                        // under `HIGH_PIXEL_DENSITY`. The renderer backend
                        // independently projects those logical coords onto
                        // the physical framebuffer via its own
                        // `outputSize / displaySize` scale. A prior attempt
                        // set `displaySize` = `sizeInPixels`, which shrank
                        // the whole UI to 1/scale and misaligned the mouse.
                        ui.draw(snapshot!!)
                        ImGui.render()
                        lastUiTick = nowTick
                    }

                    if (needRebuild) {
                        renderer.drawColor = SDLColor(18, 18, 24, 255)
                        renderer.clear()
                        backend.renderDrawData(ImGui.getDrawData())
                        renderer.present()
                        frame++
                    }

                    firstFrame = false

                    // Spin (no sleep) while a window is being dragged or
                    // the user is interacting, so the OS keeps getting
                    // its events drained at the display refresh rate.
                    if (!windowChanged && !hadInput) {
                        val nowAfter = SDL.getTicks()
                        val elapsed = nowAfter - nowTick
                        if (elapsed < uiInterval) {
                            val remaining = (uiInterval - elapsed)
                                .coerceAtMost(Int.MAX_VALUE.toULong())
                                .toInt()
                            if (remaining > 0) SDL.delay(remaining)
                        }
                    }
                }

                ui.close()
                ImPlot.destroyContext(plotContext)
                backend.close()
                manager.close()
            } finally {
                ImGui.destroyContext(context)
            }
        }
    }
    SDL.quit()
}