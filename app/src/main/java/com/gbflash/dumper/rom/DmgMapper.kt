package com.gbflash.dumper.rom

/** ROM/RAM bank sizes are fixed by the Game Boy's address bus regardless of mapper. */
const val DMG_ROM_BANK_SIZE = 0x4000
const val DMG_RAM_BANK_SIZE = 0x2000

/** A bank switch returns where the newly-selected bank appears in address space, and how big the visible window is. */
data class BankWindow(val startAddress: Int, val size: Int)

/**
 * Supported memory bank controllers for v1: no mapper, MBC1, MBC2, MBC3 (RTC ignored — read-only
 * plain SRAM access still works), MBC5. Bank-switching sequences mirror FlashGBX's Mapper.py.
 * [write] should perform a single-byte cartridge write (GbxDevice.cartWrite).
 */
sealed class DmgMapper(protected val write: (address: Long, value: Int) -> Unit) {

    abstract val name: String

    open fun enableRam(enable: Boolean) {
        write(0x0000, if (enable) 0x0A else 0x00)
    }

    open fun selectRomBank(index: Int): BankWindow {
        write(0x2100, index and 0xFF)
        return BankWindow(if (index == 0) 0 else 0x4000, DMG_ROM_BANK_SIZE)
    }

    open fun selectRamBank(index: Int): BankWindow {
        write(0x4000, index and 0xFF)
        return BankWindow(0, DMG_RAM_BANK_SIZE)
    }

    class None(write: (Long, Int) -> Unit) : DmgMapper(write) {
        override val name = "None"
    }

    class Mbc1(write: (Long, Int) -> Unit) : DmgMapper(write) {
        override val name = "MBC1"

        override fun enableRam(enable: Boolean) {
            if (enable) {
                write(0x6000, 0x01)
                write(0x0000, 0x0A)
            } else {
                write(0x0000, 0x00)
                write(0x6000, 0x00)
            }
        }

        override fun selectRomBank(index: Int): BankWindow {
            write(0x6000, 1)
            write(0x2000, index)
            write(0x4000, index ushr 5)
            return BankWindow(if ((index and 0x1F) != 0) 0x4000 else 0, DMG_ROM_BANK_SIZE)
        }
    }

    class Mbc2(write: (Long, Int) -> Unit) : DmgMapper(write) {
        override val name = "MBC2"
        // MBC2 has a fixed 512x4-bit RAM: no real bank switching, always the same window.
        override fun selectRamBank(index: Int): BankWindow = BankWindow(0, DMG_RAM_BANK_SIZE)
    }

    class Mbc3(write: (Long, Int) -> Unit) : DmgMapper(write) {
        override val name = "MBC3"
        // RAM bank select (0x4000) also selects RTC registers 0x08-0x0C on real MBC3 hardware;
        // we never write those values so RTC state is left untouched.
    }

    class Mbc5(write: (Long, Int) -> Unit) : DmgMapper(write) {
        override val name = "MBC5"

        override fun selectRomBank(index: Int): BankWindow {
            write(0x3000, (index ushr 8) and 0xFF) // high bit, for >256 bank (>4 MiB) ROMs
            write(0x2100, index and 0xFF)
            return BankWindow(if (index == 0) 0 else 0x4000, DMG_ROM_BANK_SIZE)
        }
    }

    companion object {
        /** [cartTypeByte] is the ROM header byte at 0x0147. */
        fun forCartType(cartTypeByte: Int, write: (Long, Int) -> Unit): DmgMapper? = when (cartTypeByte) {
            0x00, 0x08, 0x09 -> None(write)
            0x01, 0x02, 0x03 -> Mbc1(write)
            0x05, 0x06 -> Mbc2(write)
            0x0F, 0x10, 0x11, 0x12, 0x13 -> Mbc3(write)
            0x19, 0x1A, 0x1B, 0x1C, 0x1D, 0x1E -> Mbc5(write)
            else -> null // unsupported in this v1 (MBC6, MBC7, TAMA5, HuC-1/3, G-MMC1, unlicensed mappers, ...)
        }
    }
}
