@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package cn.enaium.sysinfomonitor

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import platform.posix.fflush
import platform.posix.fprintf
import platform.posix.snprintf
import platform.posix.stderr

/**
 * Printf-style double formatting backed by C's `snprintf`.
 *
 * The K/N POSIX binding for `snprintf` has its size parameter typed as
 * `UInt` on Android native targets and `ULong` on desktop targets, so we
 * pin to `UInt` (the Android-native form) and let the C cast promote.
 */
actual fun String.formatFixed(value: Double): String = memScoped {
    val buf = allocArray<ByteVar>(64)
    val written = snprintf(buf, 64u, this@formatFixed, value)
    if (written <= 0) return@memScoped this@formatFixed
    val end = (if (written < 63) written else 63).coerceAtLeast(0)
    buf.toKString().take(end)
}

actual fun writeStderr(text: String) {
    val line = text + "\n"
    fprintf(stderr, "%s", line)
    fflush(stderr)
}
