package cn.enaium.sysinfomonitor

import android.content.pm.ActivityInfo
import android.os.Build
import android.view.WindowInsets
import android.view.WindowInsetsController
import org.libsdl.app.SDLActivity

/**
 * Launcher activity hosting the SDL dashboard.
 *
 * `SDLActivity` (Java, vendored under `org/libsdl/app/` next to this
 * file — copied verbatim from SDL3's `android-project` tree) loads
 * `libmain.so` and calls its exported `SDL_main` symbol on a dedicated
 * SDL thread. `libmain.so` is built by the root module's
 * `androidNative*` link tasks and embeds the dashboard, imgui-kmp,
 * sdl-kmp and sysinfo-kmp together with a statically linked SDL3.
 */
class MainActivity : SDLActivity() {

    override fun getLibraries(): Array<String> = arrayOf("main")

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        // Forward the display density so the native side can scale the
        // UI for the current screen DPI before SDL_main reads it. The
        // value follows Android's displayMetrics.density (1.0 = mdpi,
        // 1.5 = hdpi, 2.0 = xhdpi, 3.0 = xxhdpi, 4.0 = xxxhdpi).
        // Set here, before the SDL thread starts in onResume(). Picked
        // up by `Main.android.kt` via `getenv` and forwarded to
        // `ImGui.getIO().fontGlobalScale` + the UI's plot heights.
        val density = resources.displayMetrics.density
        SDLActivity.nativeSetenv("SYSINFO_DPI_SCALE", density.toString())
    }
    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Modern path: WindowInsetsController is the supported
            // replacement for SYSTEM_UI_FLAG_* on API 30+.
            val controller = window.insetsController
            if (controller != null) {
                controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
        // SDL's setWindowStyle(true) is the cross-version path: the
        // vendored main-thread handler at SDLActivity.java:1052 applies
        // SYSTEM_UI_FLAG_FULLSCREEN | HIDE_NAVIGATION | IMMERSIVE_STICKY
        // | LAYOUT_FULLSCREEN | LAYOUT_HIDE_NAVIGATION and adds
        // FLAG_FULLSCREEN on the window. Required for API 24..29.
        setWindowStyle(true)
    }

    override fun onResume() {
        super.onResume()
        // Re-assert landscape after SDL's setOrientation() may have changed it.
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        hideSystemBars()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            // The system briefly clears the immersive flags when an
            // IME or dialog appears; re-apply when focus returns.
            hideSystemBars()
        }
    }
}
