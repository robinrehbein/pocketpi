package de.joinnoah.pi.remote

/** Single-sender rolling ledger of complete encrypted frame bytes and frame count. */
internal class OutboundFrameBudget {
    private data class Entry(val time: Long, val bytes: Int)
    private val entries = ArrayDeque<Entry>()
    private var bytes = 0
    private var lastObserved: Long? = null

    companion object {
        private const val WINDOW_MILLIS = 1_100L
        private const val MAX_FRAMES = 24
        private const val MAX_BYTES = 768 * 1024

        /** GCM ciphertext/tag plus unpadded base64url and conservative JSON envelope. */
        fun encryptedFrameUpperBound(plaintextBytes: Int): Int {
            require(plaintextBytes in 0..Wire.MAX_PLAINTEXT)
            val cipherBytes = plaintextBytes.toLong() + 16L
            val result = (cipherBytes * 4L + 2L) / 3L + 1024L
            require(result <= MAX_BYTES)
            return result.toInt()
        }
    }

    private fun validate(nowMillis: Long, size: Int) {
        require(size in 0..MAX_BYTES)
        require(lastObserved == null || nowMillis >= lastObserved!!)
    }

    // With nondecreasing signed timestamps, a negative subtraction means elapsed time
    // exceeded Long.MAX_VALUE and is necessarily beyond the short rolling window.
    private fun expired(nowMillis: Long, time: Long): Boolean {
        val age = nowMillis - time
        return age < 0 || age >= WINDOW_MILLIS
    }

    /** Time until this frame fits; it never reserves a sequence number or frame capacity. */
    fun delayMillis(nowMillis: Long, estimatedBytes: Int): Long {
        validate(nowMillis, estimatedBytes)
        val delay = calculateDelay(nowMillis, estimatedBytes)
        lastObserved = nowMillis
        return delay
    }

    private fun calculateDelay(nowMillis: Long, estimatedBytes: Int): Long {
        var count = entries.size
        var used = bytes
        for (entry in entries) {
            if (!expired(nowMillis, entry.time)) break
            count--
            used -= entry.bytes
        }
        if (count < MAX_FRAMES && estimatedBytes <= MAX_BYTES - used) return 0
        for (entry in entries) {
            if (expired(nowMillis, entry.time)) continue
            val age = nowMillis - entry.time
            count--
            used -= entry.bytes
            if (count < MAX_FRAMES && estimatedBytes <= MAX_BYTES - used) return WINDOW_MILLIS - age
        }
        error("Validated frame must fit in an empty budget")
    }

    /** Record only at the possible-write boundary, with the actual serialized size. */
    fun record(nowMillis: Long, actualBytes: Int) {
        validate(nowMillis, actualBytes)
        require(calculateDelay(nowMillis, actualBytes) == 0L)
        while (entries.isNotEmpty() && expired(nowMillis, entries.first().time)) {
            bytes -= entries.removeFirst().bytes
        }
        entries.addLast(Entry(nowMillis, actualBytes))
        bytes += actualBytes
        lastObserved = nowMillis
    }

    fun reset() {
        entries.clear()
        bytes = 0
        lastObserved = null
    }
}

/** The transport synchronizes this counter and releases each successful admission exactly once. */
internal class OutboundQueueBudget {
    private data class Reservation(val bytes: Int, val control: Boolean)
    private val reservations = mutableMapOf<Reservation, Int>()
    private var count = 0
    private var bytes = 0

    fun tryReserve(estimatedBytes: Int, control: Boolean): Boolean {
        val maxCount = if (control) 64 else 56
        val maxBytes = if (control) 512 * 1024 else 496 * 1024
        if (estimatedBytes < 0 || count >= maxCount || estimatedBytes > maxBytes - bytes) return false
        val key = Reservation(estimatedBytes, control)
        reservations[key] = (reservations[key] ?: 0) + 1
        count++
        bytes += estimatedBytes
        return true
    }

    fun release(estimatedBytes: Int, control: Boolean) {
        val key = Reservation(estimatedBytes, control)
        val reserved = reservations[key] ?: throw IllegalArgumentException("No matching reservation")
        if (reserved == 1) reservations.remove(key) else reservations[key] = reserved - 1
        count--
        bytes -= estimatedBytes
    }

    fun reset() {
        reservations.clear()
        count = 0
        bytes = 0
    }
}
