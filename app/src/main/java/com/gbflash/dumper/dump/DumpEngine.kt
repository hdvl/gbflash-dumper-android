package com.gbflash.dumper.dump

import com.gbflash.dumper.device.CartMode
import com.gbflash.dumper.device.GbxDevice
import com.gbflash.dumper.rom.AgbHeader
import com.gbflash.dumper.rom.AgbHeaderParser
import com.gbflash.dumper.rom.AgbSaveDetector
import com.gbflash.dumper.rom.AgbSaveType
import com.gbflash.dumper.rom.DMG_RAM_BANK_SIZE
import com.gbflash.dumper.rom.DMG_ROM_BANK_SIZE
import com.gbflash.dumper.rom.DmgHeader
import com.gbflash.dumper.rom.DmgSaveKind
import java.io.OutputStream

class DumpCancelledException : Exception("Cancelled by user")

sealed class CartridgeInfo {
    data class Dmg(val header: DmgHeader) : CartridgeInfo()
    data class Agb(val header: AgbHeader, val romSizeBytes: Long, val saveType: AgbSaveType) : CartridgeInfo()
}

/**
 * Orchestrates a dump session on top of [GbxDevice]: mode selection, header/save-type detection,
 * and the actual chunked ROM/save reads. All the serial protocol detail lives in GbxDevice; this
 * class only knows about bank iteration and where bytes go.
 */
class DumpEngine(private val device: GbxDevice) {

    /** Powers on the cartridge in the requested mode and reads back its header + save type. */
    fun identify(mode: CartMode): CartridgeInfo {
        device.setMode(mode)
        device.disableAutoPowerOff()
        val raw = device.readHeaderRaw()
        return when (mode) {
            CartMode.DMG -> {
                val header = DmgHeader.parse(raw) { addr, value -> device.cartWrite(addr, value) }
                CartridgeInfo.Dmg(header)
            }
            CartMode.AGB -> {
                val header = AgbHeaderParser.parse(raw)
                val romSize = AgbHeaderParser.findRomSize(raw) { addr, len -> device.readRom(addr, len) }
                val saveType = AgbSaveDetector.detect(device)
                CartridgeInfo.Agb(header, romSize, saveType)
            }
        }
    }

    fun dumpDmgRom(header: DmgHeader, out: OutputStream, isCancelled: () -> Boolean = { false }) {
        val mapper = header.mapper ?: error("Unsupported mapper (0x%02X)".format(header.cartTypeByte))
        val totalBanks = (header.romSizeBytes + DMG_ROM_BANK_SIZE - 1) / DMG_ROM_BANK_SIZE
        for (bank in 0 until totalBanks) {
            if (isCancelled()) throw DumpCancelledException()
            val window = mapper.selectRomBank(bank)
            out.write(device.readRom(window.startAddress.toLong(), window.size))
        }
    }

    fun dumpDmgSave(header: DmgHeader, out: OutputStream, isCancelled: () -> Boolean = { false }) {
        val save = header.save
        if (save.kind != DmgSaveKind.SRAM) return
        val mapper = header.mapper ?: error("Unsupported mapper")
        val isMbc2 = header.cartTypeByte == 0x05 || header.cartTypeByte == 0x06

        mapper.enableRam(true)
        try {
            if (isMbc2) {
                // MBC2's built-in RAM is 512 nibbles: only the low 4 bits of each byte are meaningful.
                val data = device.readDmgRam(0, save.sizeBytes)
                out.write(ByteArray(data.size) { (data[it].toInt() and 0x0F).toByte() })
                return
            }

            var written = 0
            var bank = 0
            while (written < save.sizeBytes) {
                if (isCancelled()) throw DumpCancelledException()
                val window = mapper.selectRamBank(bank)
                val len = minOf(window.size, save.sizeBytes - written)
                out.write(device.readDmgRam(window.startAddress, len))
                written += len
                bank++
            }
        } finally {
            mapper.enableRam(false)
        }
    }

    fun dumpAgbRom(romSizeBytes: Long, out: OutputStream, isCancelled: () -> Boolean = { false }) {
        val chunk = 0x10000
        var pos = 0L
        while (pos < romSizeBytes) {
            if (isCancelled()) throw DumpCancelledException()
            val len = minOf(chunk.toLong(), romSizeBytes - pos).toInt()
            out.write(device.readRom(pos, len))
            pos += len
        }
    }

    fun dumpAgbSave(saveType: AgbSaveType, out: OutputStream, isCancelled: () -> Boolean = { false }) {
        when (saveType) {
            is AgbSaveType.None -> return
            is AgbSaveType.Sram -> out.write(device.readAgbSram(0, saveType.sizeBytes, maxChunk = 0x1000))
            is AgbSaveType.Eeprom -> out.write(device.readAgbEeprom(0, saveType.sizeBytes, saveType.is64k, maxChunk = 0x100))
            is AgbSaveType.Flash -> {
                val bankSize = 0x10000
                var written = 0
                var bank = 0
                while (written < saveType.sizeBytes) {
                    if (isCancelled()) throw DumpCancelledException()
                    if (bank > 0) {
                        device.cartWriteFlashCmds(listOf(0x5555L to 0xAA, 0x2AAAL to 0x55, 0x5555L to 0xB0, 0L to bank))
                    }
                    val len = minOf(bankSize, saveType.sizeBytes - written)
                    out.write(device.readAgbSram(0, len, maxChunk = 0x1000))
                    written += len
                    bank++
                }
            }
        }
    }
}
