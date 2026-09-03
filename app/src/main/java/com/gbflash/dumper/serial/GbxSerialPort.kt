package com.gbflash.dumper.serial

import com.hoho.android.usbserial.driver.UsbSerialPort
import java.io.IOException

/**
 * Thin wrapper around usb-serial-for-android's [UsbSerialPort] that mimics the blocking,
 * "read exactly N bytes or time out" semantics FlashGBX's Python code relies on (pyserial's
 * `Serial.read(size)`).
 *
 * This needs its own internal FIFO buffer, and can't just call [UsbSerialPort.read] once per
 * logical read: with a non-zero timeout, that method does one synchronous `bulkTransfer` sized
 * to *our* destination buffer. If the device's reply arrives as a single USB packet larger than
 * whatever we asked for in that call (e.g. we read 1 byte to peek a length prefix, but the
 * firmware actually put the whole reply in one packet), the remaining bytes of that packet are
 * silently dropped by the OS — they don't carry over to the next read() call. So instead we always
 * pull into a generously-sized scratch buffer and keep whatever's left over for the next call.
 */
class GbxSerialPort(private val port: GbFlashSerialDriver.GbFlashSerialPort) {

    @Volatile
    var timeoutMs: Long = 1000L

    /** Fired whenever a low-level USB read throws, with the exception's class + message. Diagnostic only. */
    var onIoError: ((String) -> Unit)? = null

    private var pending = ByteArray(0)
    private var pendingPos = 0
    private val scratch = ByteArray(SCRATCH_SIZE)

    fun open(baudRate: Int) {
        port.setParameters(baudRate, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
        // pyserial (what FlashGBX/desktop tools use) asserts both lines high by default when a
        // port is opened. Some CH340 boards wire DTR into a reset circuit, so leaving it low
        // (this library's own default) can hold the board's MCU in reset indefinitely.
        try {
            port.dtr = true
            port.rts = true
        } catch (_: Exception) {
            // Not every driver/board supports these; harmless if ignored.
        }
    }

    fun close() {
        try {
            port.close()
        } catch (_: IOException) {
        }
    }

    fun write(data: ByteArray) {
        port.write(data, WRITE_TIMEOUT_MS)
    }

    fun write(singleByte: Int) {
        write(byteArrayOf(singleByte.toByte()))
    }

    private fun pendingAvailable() = pending.size - pendingPos

    /**
     * Pulls more bytes from the USB endpoint into [pending] until at least [count] bytes are
     * buffered, or times out. Requests the *entire* scratch buffer on every underlying
     * `bulkTransfer` call, never just [count]: if the device's reply arrives as a single USB
     * packet larger than what we're currently after (e.g. we ask for 1 byte to peek a length
     * prefix, but the firmware put the whole reply in one packet), requesting a too-small buffer
     * truncates that packet — the extra bytes are gone, not carried over to the next call. A
     * generously-sized request avoids that, and still returns as soon as a packet shorter than
     * the buffer arrives, so it doesn't sit around waiting to fill 8 KiB.
     *
     * Also: each `bulkTransfer` call is given the *entire* remaining time budget rather than a
     * short slice — chopping this into repeated short polls means the host controller stops
     * listening on the endpoint between each poll, and briefly restarts it on every call; if the
     * device's reply lands in one of those gaps, it's gone.
     *
     * This is the right shape for ad hoc protocol bytes (ACKs, the firmware-info banner, where
     * several logically-separate fields can land in one physical packet) but NOT for a chunk of
     * ROM/RAM data, where we know exactly how many bytes are coming and nothing else — see
     * [fillExact] for that case.
     */
    private fun fillAtLeast(count: Int, deadline: Long): Boolean {
        while (pendingAvailable() < count) {
            val remainingTime = deadline - System.currentTimeMillis()
            if (remainingTime <= 0) return false
            val n = pollInto(scratch.size, remainingTime)
            if (n > 0) {
                val leftover = if (pendingAvailable() > 0) pending.copyOfRange(pendingPos, pending.size) else ByteArray(0)
                pending = leftover + scratch.copyOfRange(0, n)
                pendingPos = 0
            }
        }
        return true
    }

    /**
     * Same idea as [fillAtLeast], but requests exactly how many bytes are still needed instead of
     * the whole scratch buffer. Use this for a data payload we know the exact size of and that
     * isn't followed by anything else in the same exchange (a ROM/RAM read chunk): if the reply
     * happens to be an exact multiple of the endpoint's max packet size and the over-sized request
     * [fillAtLeast] makes isn't satisfied by a short/zero-length terminating packet (nothing else is
     * coming, so the device has no reason to send one), the read never completes even though the
     * data arrived — this avoids that by making the requested length exactly matchable.
     */
    private fun fillExact(count: Int, deadline: Long): Boolean {
        while (pendingAvailable() < count) {
            val remainingTime = deadline - System.currentTimeMillis()
            if (remainingTime <= 0) return false
            val requestSize = (count - pendingAvailable()).coerceAtMost(scratch.size)
            val n = pollInto(requestSize, remainingTime)
            if (n > 0) {
                val leftover = if (pendingAvailable() > 0) pending.copyOfRange(pendingPos, pending.size) else ByteArray(0)
                pending = leftover + scratch.copyOfRange(0, n)
                pendingPos = 0
            }
        }
        return true
    }

    private fun pollInto(requestSize: Int, remainingTime: Long): Int = try {
        port.readNoLivenessCheck(scratch, requestSize, remainingTime.toInt().coerceAtLeast(1))
    } catch (e: Exception) {
        onIoError?.invoke("${e.javaClass.simpleName}: ${e.message}")
        Thread.sleep(10) // avoid a tight busy-loop if this keeps throwing instantly
        0
    }

    /**
     * Reads up to [count] bytes, polling until they all arrive or [timeoutMs] elapses overall.
     * On timeout, returns whatever was captured (fewer than [count] bytes, possibly zero) instead
     * of throwing away that partial result — useful for diagnosing exactly how far a stalled
     * exchange got. Callers that only care about all-or-nothing should use [readExact].
     */
    fun read(count: Int, timeoutMs: Long = this.timeoutMs): ByteArray {
        if (count <= 0) return ByteArray(0)
        val deadline = System.currentTimeMillis() + timeoutMs
        fillAtLeast(count, deadline)
        val available = pendingAvailable().coerceAtMost(count)
        val result = pending.copyOfRange(pendingPos, pendingPos + available)
        pendingPos += available
        return result
    }

    /** Reads exactly [count] bytes, polling until they all arrive or [timeoutMs] elapses overall. Returns null on timeout. */
    fun readExact(count: Int, timeoutMs: Long = this.timeoutMs): ByteArray? {
        val result = read(count, timeoutMs)
        return if (result.size == count) result else null
    }

    /** Reads a single byte, or null on timeout. Used for ACK/status bytes. */
    fun readByte(timeoutMs: Long = this.timeoutMs): Int? {
        val b = readExact(1, timeoutMs) ?: return null
        return b[0].toInt() and 0xFF
    }

    /**
     * Reads a ROM/RAM data payload of exactly [count] bytes — the only thing expected in this
     * exchange, and nothing else. See [fillExact] for why this requests a different amount than
     * [read]/[readExact] do. Returns whatever was captured on timeout, like [read].
     */
    fun readBulk(count: Int, timeoutMs: Long = this.timeoutMs): ByteArray {
        if (count <= 0) return ByteArray(0)
        val deadline = System.currentTimeMillis() + timeoutMs
        fillExact(count, deadline)
        val available = pendingAvailable().coerceAtMost(count)
        val result = pending.copyOfRange(pendingPos, pendingPos + available)
        pendingPos += available
        return result
    }

    fun purgeBuffers() {
        pending = ByteArray(0)
        pendingPos = 0
        try {
            port.purgeHwBuffers(true, true)
        } catch (_: Exception) {
            // Not all drivers support this; harmless if it's a no-op.
        }
    }

    companion object {
        private const val WRITE_TIMEOUT_MS = 3000

        // Comfortably larger than the GBFlash's largest single reply chunk (MAX_BUFFER_READ =
        // 0x1000) so one read() call can always capture a whole incoming USB transfer.
        private const val SCRATCH_SIZE = 8192
    }
}
