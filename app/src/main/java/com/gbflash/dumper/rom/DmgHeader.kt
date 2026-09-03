package com.gbflash.dumper.rom

import com.gbflash.dumper.device.u

enum class DmgSaveKind { NONE, SRAM, UNSUPPORTED }

data class DmgSaveInfo(val kind: DmgSaveKind, val sizeBytes: Int)

data class DmgHeader(
    val title: String,
    val isColorOnly: Boolean,
    val isColorCompatible: Boolean,
    val cartTypeByte: Int,
    val romSizeBytes: Int,
    val ramSizeRaw: Int,
    val logoCorrect: Boolean,
    val headerChecksumOk: Boolean,
    val mapper: DmgMapper?,
    val raw: ByteArray,
) {
    val mapperName: String get() = mapper?.name ?: "Unknown/unsupported (0x%02X)".format(cartTypeByte)
    val isMapperSupported: Boolean get() = mapper != null

    val save: DmgSaveInfo
        get() = when (cartTypeByte) {
            // MBC2 has a built-in fixed 512x4-bit RAM, regardless of the header's RAM-size byte
            // or whether it's battery-backed (0x06) or not (0x05, mostly homebrew).
            0x05, 0x06 -> DmgSaveInfo(DmgSaveKind.SRAM, 512)
            0x00, 0x01, 0x0B, 0x0F, 0x11, 0x19, 0x1C -> DmgSaveInfo(DmgSaveKind.NONE, 0)
            0x02, 0x03, 0x08, 0x09, 0x0D, 0x10, 0x12, 0x13, 0x1A, 0x1B, 0x1D, 0x1E -> {
                // Header RAM-size byte (0x0149): 0=none, 1=2KiB, 2=8KiB, 3=32KiB, 4=128KiB, 5=64KiB.
                val size = when (ramSizeRaw) {
                    0 -> 0
                    1 -> 0x800
                    2 -> 0x2000
                    3 -> 0x8000
                    4 -> 0x20000
                    5 -> 0x10000
                    else -> 0
                }
                DmgSaveInfo(if (size > 0) DmgSaveKind.SRAM else DmgSaveKind.NONE, size)
            }
            // Unsupported in this v1: MBC6 (0x20), MBC7 (0x22), TAMA5 (0xFD), HuC-1/3 (0xFE/0xFF), ...
            else -> DmgSaveInfo(DmgSaveKind.UNSUPPORTED, 0)
        }

    companion object {
        private val NINTENDO_LOGO = intArrayOf(
            0xCE, 0xED, 0x66, 0x66, 0xCC, 0x0D, 0x00, 0x0B, 0x03, 0x73, 0x00, 0x83, 0x00, 0x0C, 0x00, 0x0D,
            0x00, 0x08, 0x11, 0x1F, 0x88, 0x89, 0x00, 0x0E, 0xDC, 0xCC, 0x6E, 0xE6, 0xDD, 0xDD, 0xD9, 0x99,
            0xBB, 0xBB, 0x67, 0x63, 0x6E, 0x0E, 0xEC, 0xCC, 0xDD, 0xDC, 0x99, 0x9F, 0xBB, 0xB9, 0x33, 0x3E,
        )

        /** Parses a raw 0x180-byte (or larger) header dump. [write] lets the resulting mapper issue bank-switch writes. */
        fun parse(raw: ByteArray, write: (Long, Int) -> Unit): DmgHeader {
            require(raw.size >= 0x150) { "Header dump too short" }

            var logoCorrect = true
            for (i in NINTENDO_LOGO.indices) {
                if (raw[0x104 + i].u() != NINTENDO_LOGO[i]) {
                    logoCorrect = false
                    break
                }
            }

            val cgbFlag = raw[0x143].u()
            val isColorCompatible = cgbFlag == 0x80 || cgbFlag == 0xC0
            val isColorOnly = cgbFlag == 0xC0
            val titleEnd = if (isColorCompatible) 0x143 else 0x144
            val titleBytes = raw.copyOfRange(0x134, titleEnd)
            val title = titleBytes.toString(Charsets.US_ASCII).trim { it.code <= 0x20 }

            val cartTypeByte = raw[0x147].u()
            val romSizeCode = raw[0x148].u()
            val romSizeBytes = if (romSizeCode <= 8) 0x8000 shl romSizeCode else 0x8000
            val ramSizeRaw = raw[0x149].u()

            var checksum = 0
            for (i in 0x134..0x14C) checksum = checksum - raw[i].u() - 1
            val headerChecksumOk = (checksum and 0xFF) == raw[0x14D].u()

            val mapper = DmgMapper.forCartType(cartTypeByte, write)

            return DmgHeader(
                title = title,
                isColorOnly = isColorOnly,
                isColorCompatible = isColorCompatible,
                cartTypeByte = cartTypeByte,
                romSizeBytes = romSizeBytes,
                ramSizeRaw = ramSizeRaw,
                logoCorrect = logoCorrect,
                headerChecksumOk = headerChecksumOk,
                mapper = mapper,
                raw = raw,
            )
        }
    }
}
