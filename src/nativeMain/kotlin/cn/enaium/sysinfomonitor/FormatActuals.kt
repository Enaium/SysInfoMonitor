@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package cn.enaium.sysinfomonitor

import kotlinx.cinterop.refTo
import platform.posix.fflush
import platform.posix.fprintf
import platform.posix.snprintf
import platform.posix.stderr

actual fun String.formatFixed(value: Double): String {
    val buf = ByteArray(64)
    val written = snprintf(buf.refTo(0), buf.size.toULong(), this, value)
    return if (written > 0) {
        val end = minOf(written, buf.size - 1).coerceAtLeast(0)
        buf.decodeToString(0, end)
    } else this
}

actual fun writeStderr(text: String) {
    // fputs wants a C string (CValuesRef<ByteVar>); we encode the line as a
    // null-terminated byte sequence and use fprintf which accepts %s.
    val line = text + "\n"
    fprintf(stderr, "%s", line)
    fflush(stderr)
}
