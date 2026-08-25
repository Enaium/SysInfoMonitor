package cn.enaium.sysinfomonitor

actual fun String.formatFixed(value: Double): String =
    String.format(this, value)

actual fun writeStderr(text: String) {
    System.err.println(text)
}
