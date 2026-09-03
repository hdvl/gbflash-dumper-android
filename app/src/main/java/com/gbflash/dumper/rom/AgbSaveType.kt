package com.gbflash.dumper.rom

import com.gbflash.dumper.device.GbxDevice

sealed class AgbSaveType(val sizeBytes: Int, val label: String) {
    object None : AgbSaveType(0, "No save detected")
    class Sram(size: Int) : AgbSaveType(size, "SRAM/FRAM (${size / 1024} KiB)")
    class Eeprom(val is64k: Boolean, size: Int) : AgbSaveType(size, if (is64k) "EEPROM 64Kbit" else "EEPROM 4Kbit")
    class Flash(val chipId: Int, size: Int, val known: Boolean) :
        AgbSaveType(size, if (known) "FLASH (${size / 1024} KiB)" else "FLASH (unrecognized chip 0x%04X)".format(chipId))
}

/** Known AGB save-flash JEDEC-style IDs, from FlashGBX's CartridgeTypes.AgbSaveTypes.AGB_FLASH_SAVE_CHIPS. */
object AgbFlashChips {
    private val chips = mapOf(
        0xBFD4 to 0x10000, 0x1F3D to 0x10000, 0xC21C to 0x10000, 0x321B to 0x10000,
        0xC209 to 0x20000, 0x6213 to 0x20000, 0xBF4B to 0x20000, 0xBF5B to 0x20000,
        0xBF6D to 0x20000, 0xFFFF to 0x20000,
    )

    fun isKnown(chipId: Int) = chips.containsKey(chipId)
    fun sizeOf(chipId: Int): Int? = chips[chipId]
}

/**
 * Re-implements the save-type auto-detection FlashGBX runs for AGB carts (LK_Device
 * ._DetectCartridge_Worker): try the FLASH JEDEC ID probe first, then fall back to sizing SRAM by
 * its repeat period, then to a 4K/64K EEPROM probe if the SRAM window reads back as a flat value.
 */
object AgbSaveDetector {

    fun detect(device: GbxDevice): AgbSaveType {
        readFlashSaveId(device)?.let { (chipId, _) ->
            val size = AgbFlashChips.sizeOf(chipId)
            return AgbSaveType.Flash(chipId, size ?: 0x20000, known = size != null)
        }

        val probe = device.readAgbSram(0, 0x20000) // 128 KiB — the largest SRAM/FRAM size in the wild
        if (!isFlat(probe)) {
            val size = findRepeatingSize(probe, intArrayOf(32768, 65536, 131072)) ?: probe.size
            return AgbSaveType.Sram(size)
        }

        val e64 = device.readAgbEeprom(0, 0x2000, is64k = true) // 8 KiB
        if (isFlat(e64)) return AgbSaveType.None
        val e4 = device.readAgbEeprom(0, 0x200, is64k = false) // 512 B
        return if (e4.contentEquals(e64.copyOfRange(0, e4.size))) {
            AgbSaveType.Eeprom(is64k = true, size = 8192)
        } else {
            AgbSaveType.Eeprom(is64k = false, size = 512)
        }
    }

    /** Mirrors GbxDevice/LK_Device ReadFlashSaveID: probes whether address 4 is a real flash register vs. plain SRAM. */
    private fun readFlashSaveId(device: GbxDevice): Pair<Int, Boolean>? {
        val test1 = device.readAgbSram(4, 1)[0].toInt() and 0xFF
        device.cartWriteFlashCmds(listOf(4L to (test1 xor 0xFF)))
        val test2 = device.readAgbSram(4, 1)[0].toInt() and 0xFF
        device.cartWriteFlashCmds(listOf(4L to test1))
        if (test1 != test2) return null // it's plain SRAM/FRAM: writing "through" address 4 changed it

        val v5555 = device.readAgbSram(0x5555, 1)[0].toInt() and 0xFF
        val v2AAA = device.readAgbSram(0x2AAA, 1)[0].toInt() and 0xFF
        val v0000 = device.readAgbSram(0x0000, 1)[0].toInt() and 0xFF

        device.cartWriteFlashCmds(listOf(0x5555L to 0xAA, 0x2AAAL to 0x55, 0x5555L to 0x90))
        val chipBytes = device.readAgbSram(0, 2)
        val chipId = ((chipBytes[0].toInt() and 0xFF) shl 8) or (chipBytes[1].toInt() and 0xFF)
        device.cartWriteFlashCmds(listOf(0x5555L to 0xAA, 0x2AAAL to 0x55, 0x5555L to 0xF0))
        Thread.sleep(10)
        device.cartWriteFlashCmds(listOf(0L to 0xF0))
        Thread.sleep(10)

        if (!AgbFlashChips.isKnown(chipId)) {
            // Restore whatever was there in case this was actually SRAM after all.
            device.cartWriteFlashCmds(listOf(0x5555L to v5555, 0x2AAAL to v2AAA, 0x0000L to v0000))
        }
        return chipId to AgbFlashChips.isKnown(chipId)
    }

    private fun isFlat(data: ByteArray): Boolean {
        if (data.isEmpty()) return true
        val first = data[0]
        return data.all { it == first }
    }

    private fun findRepeatingSize(data: ByteArray, candidates: IntArray): Int? {
        for (size in candidates.sorted()) {
            if (size > data.size) continue
            var repeats = true
            var offset = size
            while (offset < data.size) {
                val len = minOf(size, data.size - offset)
                if (!data.copyOfRange(offset, offset + len).contentEquals(data.copyOfRange(0, len))) {
                    repeats = false
                    break
                }
                offset += size
            }
            if (repeats) return size
        }
        return null
    }
}
