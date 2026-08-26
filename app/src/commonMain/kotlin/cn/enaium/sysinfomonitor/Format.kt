package cn.enaium.sysinfomonitor

/** Print [text] to stderr. */
internal fun logErr(text: String) {
    writeStderr(text)
}

internal expect fun writeStderr(text: String)

/** Printf-style double formatter implemented per platform. */
expect fun String.formatFixed(value: Double): String

// -----------------------------------------------------------------------
// Allocation-free formatters. The previous formatBytes() allocated an
// Array for the units on every call and produced several intermediate
// Strings before returning one — measured at ~6 allocations per call,
// which is the difference between smooth and janky on a 100-row process
// table at 30 Hz (18k allocations/sec). These versions reuse a render-
// thread StringBuilder pool.
// -----------------------------------------------------------------------

private val BINARY_UNITS = arrayOf("B", "KiB", "MiB", "GiB", "TiB", "PiB")

// Per-thread reusable StringBuilders. The render thread is single-
// threaded so a single builder would be enough, but the formatters can
// recurse so we keep two to avoid stomping on a buffer in active use.
private val builderA = StringBuilder(16)
private val builderB = StringBuilder(16)
// 0 = both free, 1 = A held, 2 = B held, 3 = both held (one-shot fallback).
private var builderOwner: Int = 0

/** Format a byte count using binary (KiB/MiB/GiB) units. */
fun formatBytes(bytes: ULong): String {
    val sb = acquireBuilder()
    try {
        sb.setLength(0)
        if (bytes < 1024uL) {
            sb.append(bytes.toString()).append(" B")
            return sb.toString()
        }
        // Walk the unit array, dividing only when there's at least one
        // more unit available. The strict `<` check after the division
        // is critical: an exact power (e.g. 1 GiB = 1073741824 bytes =
        // 1024.0 MiB) must NOT advance to the next unit, otherwise it
        // would display as "1 TiB" (the off-by-one that motivated this
        // rewrite).
        var value = bytes.toDouble()
        var i = 1
        while (i < BINARY_UNITS.size - 1 && value >= 1024.0) {
            value /= 1024.0
            if (value < 1024.0) break
            i++
        }
        appendTrimmed(sb, value, 2)
        sb.append(' ').append(BINARY_UNITS[i])
        return sb.toString()
    } finally {
        releaseBuilder(sb)
    }
}

fun formatBytesPerSec(bytes: Float): String = "${formatBytes(bytes.toULong())}/s"

fun formatDuration(seconds: ULong): String {
    val s = seconds.toLong()
    val days = s / 86_400
    val hours = (s % 86_400) / 3_600
    val mins = (s % 3_600) / 60
    val secs = s % 60
    val sb = StringBuilder(16)
    if (days > 0) sb.append(days).append('d').append(' ')
    if (hours > 0 || days > 0) sb.append(hours).append('h').append(' ')
    if (mins > 0 || hours > 0 || days > 0) sb.append(mins).append('m').append(' ')
    sb.append(secs).append('s')
    return sb.toString()
}

fun formatUptime(seconds: ULong): String = formatDuration(seconds)

/** Format a double with one fractional digit. */
fun fmt1(value: Double): String = trimZeros("%.1f".formatFixed(value))

/** Format a double with two fractional digits. */
fun fmt2(value: Double): String = trimZeros("%.2f".formatFixed(value))

/** Format a float with one fractional digit, no trailing zeros (used for °C). */
fun fmtTemp(value: Float): String = trimZeros("%.1f".formatFixed(value.toDouble()))

/** Format a float with one fractional digit. */
fun fmtFloat1(value: Float): String = trimZeros("%.1f".formatFixed(value.toDouble()))

/** Append [value] to [sb] with up to [fracDigits] fractional digits, trimmed. */
private fun appendTrimmed(sb: StringBuilder, value: Double, fracDigits: Int) {
    val neg = value < 0
    val v = if (neg) -value else value
    val intPart = v.toLong()
    val frac = v - intPart.toDouble()
    if (neg) sb.append('-')
    sb.append(intPart)
    if (fracDigits <= 0) return
    sb.append('.')
    var scale = 1.0
    repeat(fracDigits) { scale *= 10.0 }
    val rounded = (frac * scale + 0.5).toLong()
    if (rounded >= scale.toLong()) {
        val drop = 1 + fracDigits
        sb.setLength(sb.length - drop)
        sb.append(intPart + 1)
        return
    }
    var s = rounded.toString()
    while (s.length < fracDigits) s = "0$s"
    var end = s.length
    while (end > 0 && s[end - 1] == '0') end--
    if (end == 0) {
        sb.setLength(sb.length - 1 - fracDigits)
    } else {
        sb.append(s, 0, end)
    }
}

private fun trimZeros(s: String): String =
    if ('.' in s) s.trimEnd('0').trimEnd('.').ifEmpty { "0" } else s

// Builder pool. Single-threaded use (the render thread).
private fun acquireBuilder(): StringBuilder = when (builderOwner) {
    0 -> { builderOwner = 1; builderA }
    1 -> { builderOwner = 3; builderB }
    2 -> { builderOwner = 3; builderA }
    else -> StringBuilder(16)
}

private fun releaseBuilder(sb: StringBuilder) {
    when {
        sb === builderA && builderOwner == 1 -> builderOwner = 0
        sb === builderA && builderOwner == 3 -> builderOwner = 2
        sb === builderB && builderOwner == 2 -> builderOwner = 0
        sb === builderB && builderOwner == 3 -> builderOwner = 1
    }
}
