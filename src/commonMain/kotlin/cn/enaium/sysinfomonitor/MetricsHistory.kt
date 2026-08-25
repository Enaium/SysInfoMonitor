package cn.enaium.sysinfomonitor

/**
 * A fixed-size ring buffer of float samples with a parallel "time" axis
 * expressed as the index in the buffer (0..capacity-1, oldest..newest). We
 * don't store real timestamps because ImPlot's time axis is configured
 * manually each frame to "now", so a relative index is enough.
 */
class MetricsHistory(val capacity: Int) {

    val globalCpu = FloatArray(capacity)
    val memoryUsedFraction = FloatArray(capacity)
    val swapUsedFraction = FloatArray(capacity)
    val netRxBytesPerSec = FloatArray(capacity)
    val netTxBytesPerSec = FloatArray(capacity)
    val diskReadBytesPerSec = FloatArray(capacity)
    val diskWriteBytesPerSec = FloatArray(capacity)

    internal var head: Int = 0
    internal var filled: Int = 0

    /** Number of valid samples currently stored. */
    val size: Int get() = filled

    /** Append a fresh sample. Overwrites the oldest entry when the buffer is full. */
    fun push(
        cpu: Float,
        memFrac: Float,
        swapFrac: Float,
        rx: Float,
        tx: Float,
        diskRead: Float,
        diskWrite: Float,
    ) {
        globalCpu[head] = cpu
        memoryUsedFraction[head] = memFrac
        swapUsedFraction[head] = swapFrac
        netRxBytesPerSec[head] = rx
        netTxBytesPerSec[head] = tx
        diskReadBytesPerSec[head] = diskRead
        diskWriteBytesPerSec[head] = diskWrite
        head = (head + 1) % capacity
        if (filled < capacity) filled += 1
    }

    /**
     * Read [out] from the ring in chronological order (oldest first) and
     * return the number of valid entries. [out] is resized to [capacity].
     */
    fun readOrdered(source: FloatArray, out: FloatArray): Int {
        if (filled < capacity) {
            // Buffer not yet full: oldest is index 0, newest is head-1.
            for (i in 0 until filled) out[i] = source[i]
            for (i in filled until capacity) out[i] = 0f
            return filled
        }
        val start = head
        for (i in 0 until capacity) {
            out[i] = source[(start + i) % capacity]
        }
        return capacity
    }
}
