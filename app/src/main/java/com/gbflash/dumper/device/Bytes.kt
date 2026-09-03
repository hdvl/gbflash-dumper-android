package com.gbflash.dumper.device

/** Big-endian integer packing helpers — the GBFlash's serial protocol is big-endian throughout. */

fun ByteArray.putBE32(offset: Int, value: Long) {
    this[offset] = (value ushr 24).toByte()
    this[offset + 1] = (value ushr 16).toByte()
    this[offset + 2] = (value ushr 8).toByte()
    this[offset + 3] = value.toByte()
}

fun ByteArray.putBE16(offset: Int, value: Int) {
    this[offset] = (value ushr 8).toByte()
    this[offset + 1] = value.toByte()
}

fun beToUInt(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): Long {
    var result = 0L
    for (i in 0 until length) {
        result = (result shl 8) or (bytes[offset + i].toLong() and 0xFF)
    }
    return result
}

fun java.io.ByteArrayOutputStream.writeBE32(value: Long) {
    write((value ushr 24).toInt() and 0xFF)
    write((value ushr 16).toInt() and 0xFF)
    write((value ushr 8).toInt() and 0xFF)
    write(value.toInt() and 0xFF)
}

fun java.io.ByteArrayOutputStream.writeBE16(value: Int) {
    write((value ushr 8) and 0xFF)
    write(value and 0xFF)
}

fun Byte.u(): Int = toInt() and 0xFF
