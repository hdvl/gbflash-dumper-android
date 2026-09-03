package com.gbflash.dumper.rom

import com.gbflash.dumper.device.u

data class AgbHeader(
    val title: String,
    val gameCode: String,
    val makerCode: String,
    val logoCorrect: Boolean,
    val fixedByteOk: Boolean,
    val raw: ByteArray,
)

object AgbHeaderParser {
    /** Parses the fixed part of a raw 0x180-byte GBA header dump. ROM size isn't stored in the header — see [findRomSize]. */
    fun parse(raw: ByteArray): AgbHeader {
        require(raw.size >= 0xC0) { "Header dump too short" }
        val title = raw.copyOfRange(0xA0, 0xAC).toString(Charsets.US_ASCII).trim { it.code <= 0x20 }
        val gameCode = raw.copyOfRange(0xAC, 0xB0).toString(Charsets.US_ASCII).trim { it.code <= 0x20 }
        val makerCode = raw.copyOfRange(0xB0, 0xB2).toString(Charsets.US_ASCII).trim { it.code <= 0x20 }
        val fixedByteOk = raw[0xB2].u() == 0x96
        // A cheap logo sanity check: real GBA logos start with this byte sequence at 0x04.
        val logoCorrect = raw.size > 0x04 + 4 &&
            raw[0x04].u() == 0x24 && raw[0x05].u() == 0xFF && raw[0x06].u() == 0xAE && raw[0x07].u() == 0x51
        return AgbHeader(title, gameCode, makerCode, logoCorrect, fixedByteOk, raw)
    }

    /**
     * GBA carts don't encode ROM size in the header, and reads past the end simply mirror back
     * from the start. Mirrors FlashGBX's approach: compare a 16-byte fingerprint near the start
     * against the same offset at increasing candidate sizes until it repeats.
     * [readRom] should read [length] bytes at [address] (both in bytes).
     */
    fun findRomSize(header: ByteArray, readRom: (address: Long, length: Int) -> ByteArray): Long {
        val fingerprint = header.copyOfRange(0xA0, 0xB0)
        var candidate = 0x10000L
        val maxSize = 0x2000000L // 32 MiB, the largest official GBA cart
        while (candidate < maxSize) {
            val probe = readRom(candidate + 0xA0, 16)
            if (probe.contentEquals(fingerprint)) return candidate
            candidate *= 2
        }
        return maxSize
    }
}
