package com.gbflash.dumper.device

import com.gbflash.dumper.serial.GbxSerialPort
import java.io.ByteArrayOutputStream

class GbxIoException(message: String) : Exception(message)

enum class CartMode { DMG, AGB }

/** Serial command bytes. Mirrors LK_Device.DEVICE_CMD / hw_GBFlash.py from FlashGBX (github.com/lesserkuma/FlashGBX). */
private object Cmd {
    const val QUERY_FW_INFO = 0xA1
    const val SET_MODE_AGB = 0xA2
    const val SET_MODE_DMG = 0xA3
    const val SET_VOLTAGE_3_3V = 0xA4
    const val SET_VOLTAGE_5V = 0xA5
    const val SET_VARIABLE = 0xA6
    const val SET_ADDR_AS_INPUTS = 0xA8
    const val DISABLE_PULLUPS = 0xAC
    const val GET_VARIABLE = 0xAD
    const val DMG_CART_READ = 0xB1
    const val DMG_CART_WRITE = 0xB2
    const val DMG_MBC_RESET = 0xB4
    const val AGB_CART_READ = 0xC1
    const val AGB_CART_WRITE = 0xC2
    const val AGB_CART_READ_SRAM = 0xC3
    const val AGB_CART_READ_EEPROM = 0xC5
    const val AGB_BOOTUP_SEQUENCE = 0xC9
    const val CART_WRITE_FLASH_CMD = 0xD4
    const val CART_PWR_ON = 0xF2
    const val CART_PWR_OFF = 0xF3
    const val QUERY_CART_PWR = 0xF4
}

/** Firmware variable ids, as used by SET_VARIABLE (0xA6) / GET_VARIABLE (0xAD). Mirrors LK_Device.DEVICE_VAR. */
private enum class FwVar(val sizeByte: Int, val id: Long) {
    ADDRESS(4, 0x00),
    TRANSFER_SIZE(2, 0x00),
    CART_MODE(1, 0x00),
    DMG_ACCESS_MODE(1, 0x01),
    DMG_READ_CS_PULSE(1, 0x08),
    DMG_WRITE_CS_PULSE(1, 0x09),
    DMG_READ_METHOD(1, 0x0B),
    AGB_READ_METHOD(1, 0x0C),
    AUTO_POWEROFF_ENABLED(1, 0x0F),
}

data class FirmwareInfo(
    val cfwId: Char,
    val fwVer: Int,
    val pcbVer: Int,
    val fwTs: Long,
    val pcbName: String?,
    val cartPowerCtrl: Boolean,
    val cartPresenceSwitch: Boolean,
    val cartModeSwitch: Boolean,
    val bootloaderReset: Boolean,
    val unregistered: Boolean,
) {
    val isCompatible: Boolean get() = cfwId == 'L' && fwVer >= 1
    val deviceName: String get() = pcbName ?: "GBFlash"
}

/**
 * Kotlin re-implementation of the GBFlash serial protocol, reverse-engineered from FlashGBX's
 * `hw_GBFlash.py` / `LK_Device.py` (github.com/lesserkuma/FlashGBX, GPL-3.0) for interoperability
 * with the user's own hardware. Read-only: only the operations needed to dump ROM and save data
 * are implemented (no flashing/writing).
 */
class GbxDevice(private val serial: GbxSerialPort) {

    var firmware: FirmwareInfo? = null
        private set
    var mode: CartMode? = null
        private set

    /** Maximum bytes the firmware will hand back per read command (hw_GBFlash.py: MAX_BUFFER_READ). */
    val maxBufferRead = 0x1000

    /** Called after every chunk successfully read, with the number of bytes just transferred. */
    var onBytesTransferred: ((Int) -> Unit)? = null

    /** Optional diagnostic hook, mainly useful while tracking down connection issues on a new board. */
    var onDebugLog: ((String) -> Unit)? = null
    private fun debugLog(message: String) = onDebugLog?.invoke(message)

    // ---------------------------------------------------------------- low-level framing

    private fun writeCmd(cmd: Int) = serial.write(cmd)

    /** Sends [cmd] and waits for the firmware's 1-byte ACK (0x01 or 0x03 both count as success). */
    private fun writeCmdAck(cmd: Int): Boolean {
        serial.write(cmd)
        val ack = serial.readByte()
        return ack == 1 || ack == 3
    }

    /** Writes raw bytes and waits for ACK, retrying a few times — mirrors LK_Device._try_write(). */
    private fun writeAckRetry(bytes: ByteArray, retries: Int = 3) {
        var attempts = retries
        while (attempts > 0) {
            serial.write(bytes)
            val ack = serial.readByte()
            if (ack == 1 || ack == 3) return
            attempts--
        }
        throw GbxIoException("Device did not acknowledge command 0x${bytes[0].u().toString(16)}")
    }

    private fun setVar(v: FwVar, value: Long) {
        val buf = ByteArray(2 + 4 + 4)
        buf[0] = Cmd.SET_VARIABLE.toByte()
        buf[1] = v.sizeByte.toByte()
        buf.putBE32(2, v.id)
        buf.putBE32(6, value)
        val fwVer = firmware?.fwVer ?: 0
        if (fwVer >= 12) writeAckRetry(buf) else serial.write(buf)
    }

    private fun getVar(v: FwVar): Long {
        val buf = ByteArray(2 + 4)
        buf[0] = Cmd.GET_VARIABLE.toByte()
        buf[1] = v.sizeByte.toByte()
        buf.putBE32(2, v.id)
        serial.write(buf)
        val resp = serial.readExact(4) ?: throw GbxIoException("No response to GET_VARIABLE ${v.name}")
        return beToUInt(resp)
    }

    // ---------------------------------------------------------------- connection / firmware

    /** Queries the firmware banner. Returns null if nothing answered (wrong port, device asleep, etc.). */
    fun loadFirmwareVersion(): FirmwareInfo? {
        serial.purgeBuffers()
        debugLog("-> QUERY_FW_INFO (0x${Cmd.QUERY_FW_INFO.toString(16)})")
        serial.write(Cmd.QUERY_FW_INFO)
        val size = serial.readByte(timeoutMs = 500)
        if (size == null) {
            debugLog("<- no response within 500ms (nothing came back at all)")
            return null
        }
        debugLog("<- size byte: 0x${size.toString(16)} (expected 0x08)")
        if (size != 8) return null
        val info = serial.readExact(8)
        if (info == null) {
            debugLog("<- size byte was ok but the following 8 info bytes never fully arrived")
            return null
        }
        debugLog("<- info bytes: ${info.joinToString(" ") { "%02x".format(it) }}")

        val cfwId = info[0].toInt().toChar()
        val fwVer = ((info[1].u()) shl 8) or info[2].u()
        val pcbVer = info[3].u()
        val fwTs = beToUInt(info, 4, 4)
        debugLog("<- parsed: cfw_id='$cfwId' fw_ver=$fwVer pcb_ver=$pcbVer")

        var pcbName: String? = null
        var cartPowerCtrl = false
        var cartPresenceSwitch = false
        var cartModeSwitch = false
        var bootloaderReset = false
        var unregistered = false

        if (cfwId == 'L' && fwVer >= 12) {
            val nameLenRaw = serial.readByte()
            if (nameLenRaw == null) debugLog("<- timed out reading pcb_name length byte")
            val nameLen = nameLenRaw ?: 0
            if (nameLen > 0) {
                val nameBytes = serial.readExact(nameLen)
                if (nameBytes == null) debugLog("<- timed out reading $nameLen-byte pcb_name")
                pcbName = nameBytes?.toString(Charsets.UTF_8)?.takeWhile { it.code != 0 }?.trim()
            }
            val caps1Raw = serial.readByte()
            if (caps1Raw == null) debugLog("<- timed out reading capability byte 1")
            val caps1 = caps1Raw ?: 0
            cartPowerCtrl = (caps1 and 1) == 1
            cartPresenceSwitch = ((caps1 shr 1) and 1) == 1
            cartModeSwitch = ((caps1 shr 2) and 1) == 1
            val caps2Raw = serial.readByte()
            if (caps2Raw == null) debugLog("<- timed out reading capability byte 2")
            val caps2 = caps2Raw ?: 0
            bootloaderReset = (caps2 and 1) == 1
            unregistered = (caps2 shr 7) == 1
            debugLog("<- pcb_name='$pcbName' cart_power_ctrl=$cartPowerCtrl (caps1=0x${caps1.toString(16)}, caps2=0x${caps2.toString(16)})")
        }

        val fw = FirmwareInfo(cfwId, fwVer, pcbVer, fwTs, pcbName, cartPowerCtrl, cartPresenceSwitch, cartModeSwitch, bootloaderReset, unregistered)
        firmware = fw
        return fw
    }

    fun disconnect() {
        try {
            if (mode != null) cartPowerOff()
        } catch (_: Exception) {
        }
        serial.close()
        mode = null
    }

    // ---------------------------------------------------------------- mode & power

    fun setMode(newMode: CartMode) {
        val fw = firmware ?: throw GbxIoException("Not connected")
        debugLog("setMode($newMode): cart_power_ctrl=${fw.cartPowerCtrl}")
        if (newMode == CartMode.DMG) {
            debugLog("  SET_MODE_DMG ack=${writeCmdAck(Cmd.SET_MODE_DMG)}")
            debugLog("  SET_VOLTAGE_5V ack=${writeCmdAck(Cmd.SET_VOLTAGE_5V)}")
            setVar(FwVar.DMG_READ_METHOD, 0)
        } else {
            debugLog("  SET_MODE_AGB ack=${writeCmdAck(Cmd.SET_MODE_AGB)}")
            debugLog("  SET_VOLTAGE_3_3V ack=${writeCmdAck(Cmd.SET_VOLTAGE_3_3V)}")
            setVar(FwVar.AGB_READ_METHOD, 0)
        }
        setVar(FwVar.CART_MODE, if (newMode == CartMode.DMG) 1 else 2)
        setVar(FwVar.ADDRESS, 0)
        mode = newMode

        if (fw.cartPowerCtrl) {
            cartPowerOn()
        } else {
            debugLog("  cart_power_ctrl is false — assuming the cartridge is already powered")
        }
    }

    private fun cartPowerOn() {
        val fw = firmware ?: return
        if (!fw.cartPowerCtrl) return
        writeCmd(Cmd.QUERY_CART_PWR)
        val alreadyOn = serial.readByte()
        debugLog("  QUERY_CART_PWR -> $alreadyOn")
        if (alreadyOn == 1) return

        when (mode) {
            CartMode.DMG -> debugLog("  SET_MODE_DMG ack=${writeCmdAck(Cmd.SET_MODE_DMG)}")
            CartMode.AGB -> debugLog("  SET_MODE_AGB ack=${writeCmdAck(Cmd.SET_MODE_AGB)}")
            null -> {}
        }
        debugLog("  CART_PWR_ON ack=${writeCmdAck(Cmd.CART_PWR_ON)}")
        writeCmd(Cmd.QUERY_CART_PWR)
        debugLog("  QUERY_CART_PWR (after power on) -> ${serial.readByte()}")

        when (mode) {
            CartMode.DMG -> debugLog("  DMG_MBC_RESET ack=${writeCmdAck(Cmd.DMG_MBC_RESET)}")
            CartMode.AGB -> debugLog("  AGB_BOOTUP_SEQUENCE ack=${writeCmdAck(Cmd.AGB_BOOTUP_SEQUENCE)}")
            null -> {}
        }
    }

    fun cartPowerOff() {
        val fw = firmware ?: return
        if (fw.cartPowerCtrl) {
            writeCmdAck(Cmd.CART_PWR_OFF)
        } else {
            writeCmdAck(Cmd.SET_ADDR_AS_INPUTS)
        }
    }

    fun disableAutoPowerOff() {
        try {
            setVar(FwVar.AUTO_POWEROFF_ENABLED, 0)
        } catch (_: Exception) {
            // Not fatal — just means a very slow dump could theoretically get cut off.
        }
    }

    // ---------------------------------------------------------------- ROM / header

    /** Reads the first 0x180 bytes of the cartridge — enough for the DMG or AGB header. */
    fun readHeaderRaw(): ByteArray {
        debugLog("readHeaderRaw(): mode=$mode")
        if ((firmware?.fwVer ?: 0) >= 8) debugLog("  DISABLE_PULLUPS ack=${writeCmdAck(Cmd.DISABLE_PULLUPS)}")
        when (mode) {
            CartMode.DMG -> {
                debugLog("  SET_VOLTAGE_5V ack=${writeCmdAck(Cmd.SET_VOLTAGE_5V)}")
                debugLog("  DMG_MBC_RESET ack=${writeCmdAck(Cmd.DMG_MBC_RESET)}")
                setVar(FwVar.DMG_READ_CS_PULSE, 0)
                setVar(FwVar.DMG_WRITE_CS_PULSE, 0)
            }
            CartMode.AGB -> debugLog("  SET_VOLTAGE_3_3V ack=${writeCmdAck(Cmd.SET_VOLTAGE_3_3V)}")
            null -> throw GbxIoException("Cart mode not set")
        }
        debugLog("  reading 0x180 bytes of ROM from address 0 in 64-byte chunks...")
        // FlashGBX's own ReadHeader() reads in the (small) default chunk size here rather than
        // the larger MAX_BUFFER_READ used for full ROM dumps — matching that, since a single big
        // request apparently isn't answered at all by this firmware for this command.
        val header = readRom(0, 0x180, maxChunk = 64)
        if (mode == CartMode.DMG) writeCmdAck(Cmd.SET_ADDR_AS_INPUTS)
        return header
    }

    /**
     * Reads [length] bytes of ROM starting at [address]. In AGB mode the cartridge bus is
     * 16-bit-word addressed, so [address] (a byte offset) is internally halved — same as
     * FlashGBX's ReadROM()/_cart_read().
     */
    fun readRom(address: Long, length: Int, maxChunk: Int = maxBufferRead): ByteArray {
        if (length == 0) return ByteArray(0)
        val cmd = if (mode == CartMode.DMG) Cmd.DMG_CART_READ else Cmd.AGB_CART_READ
        val addrParam = if (mode == CartMode.AGB) address ushr 1 else address
        debugLog("readRom(address=0x${address.toString(16)}, length=0x${length.toString(16)})")
        return readLoop(length, maxChunk, byteArrayOf(cmd.toByte())) {
            setVar(FwVar.ADDRESS, addrParam)
            if (mode == CartMode.DMG) setVar(FwVar.DMG_ACCESS_MODE, 1) // MODE_ROM_READ
        }
    }

    // ---------------------------------------------------------------- RAM / save data

    /** DMG cartridge RAM (SRAM/battery-backed RAM behind an MBC). [address] is relative to 0xA000. */
    fun readDmgRam(address: Int, length: Int, maxChunk: Int = 0x200): ByteArray {
        try {
            return readLoop(length, maxChunk, byteArrayOf(Cmd.DMG_CART_READ.toByte())) {
                setVar(FwVar.ADDRESS, (0xA000 + address).toLong())
                setVar(FwVar.DMG_ACCESS_MODE, 3) // MODE_RAM_READ
                setVar(FwVar.DMG_READ_CS_PULSE, 1)
            }
        } finally {
            setVar(FwVar.DMG_READ_CS_PULSE, 0)
        }
    }

    /** AGB battery-backed SRAM or FRAM (also used to read FLASH save chips — they're memory-mapped for reads). */
    fun readAgbSram(address: Long, length: Int, maxChunk: Int = 0x1000): ByteArray =
        readLoop(length, maxChunk, byteArrayOf(Cmd.AGB_CART_READ_SRAM.toByte())) {
            setVar(FwVar.ADDRESS, address)
        }

    /** AGB EEPROM save (4K or 64K variant). [address] is a byte offset; the protocol addresses EEPROM in 8-byte blocks. */
    fun readAgbEeprom(address: Long, length: Int, is64k: Boolean, maxChunk: Int = 0x100): ByteArray {
        val variant = if (is64k) 2 else 1
        return readLoop(length, maxChunk, byteArrayOf(Cmd.AGB_CART_READ_EEPROM.toByte(), variant.toByte())) {
            setVar(FwVar.ADDRESS, address / 8)
        }
    }

    /**
     * Shared chunked-read loop for both ROM and RAM/save reads. [setAddressVars] is invoked after
     * TRANSFER_SIZE is set, matching FlashGBX's own ReadROM()/ReadRAM() ordering.
     *
     * Each chunk is fetched via [GbxSerialPort.readBulk], which requests exactly the chunk size
     * rather than a generous buffer — see its doc comment for why that specifically matters here
     * (a request for more than the device is about to send can end up waiting forever for a
     * terminating packet that never comes, when the reply is an exact multiple of the USB
     * endpoint's max packet size, as ROM/RAM chunks usually are).
     */
    private fun readLoop(length: Int, maxChunk: Int, commandBytes: ByteArray, setAddressVars: () -> Unit): ByteArray {
        val out = ByteArrayOutputStream(length)
        var remaining = length
        var offset = 0
        val chunkLen = minOf(maxChunk, length)
        setVar(FwVar.TRANSFER_SIZE, chunkLen.toLong())
        setAddressVars()
        while (remaining > 0) {
            val thisChunk = minOf(chunkLen, remaining)
            if (thisChunk != chunkLen) setVar(FwVar.TRANSFER_SIZE, thisChunk.toLong())
            serial.write(commandBytes)
            val data = serial.readBulk(thisChunk, timeoutMs = 3000)
            if (data.size != thisChunk) {
                debugLog("Read failed at offset 0x${offset.toString(16)}: got only ${data.size}/$thisChunk bytes")
                throw GbxIoException("Read failed at offset 0x${offset.toString(16)}")
            }
            out.write(data)
            remaining -= thisChunk
            offset += thisChunk
            onBytesTransferred?.invoke(thisChunk)
        }
        return out.toByteArray()
    }

    // ---------------------------------------------------------------- generic single-address writes (bank switching)

    /** Writes one value to a cartridge address — used only for MBC bank-switching registers, never for flashing. */
    fun cartWrite(address: Long, value: Int) {
        when (mode) {
            CartMode.DMG -> {
                val buf = ByteArray(6)
                buf[0] = Cmd.DMG_CART_WRITE.toByte()
                buf.putBE32(1, address)
                buf[5] = value.toByte()
                writeMaybeAck(buf)
            }
            CartMode.AGB -> {
                val buf = ByteArray(7)
                buf[0] = Cmd.AGB_CART_WRITE.toByte()
                buf.putBE32(1, address ushr 1)
                buf.putBE16(5, value)
                writeMaybeAck(buf)
            }
            null -> throw GbxIoException("Cart mode not set")
        }
    }

    private fun writeMaybeAck(buf: ByteArray) {
        if ((firmware?.fwVer ?: 0) >= 12) writeAckRetry(buf) else serial.write(buf)
    }

    /**
     * Writes a sequence of (address, value) pairs as flash-style bus cycles in one shot — needed
     * only to probe the save chip's JEDEC ID (ReadFlashSaveID in FlashGBX), never to erase/program.
     * [flashcart] selects ROM-space addressing (halved) vs. save-space addressing (as-is); we only
     * ever use save-space here.
     */
    fun cartWriteFlashCmds(commands: List<Pair<Long, Int>>, flashcart: Boolean = false): Boolean {
        val buf = ByteArrayOutputStream()
        buf.write(Cmd.CART_WRITE_FLASH_CMD)
        buf.write(if (flashcart) 1 else 0)
        buf.write(commands.size)
        for ((addr, value) in commands) {
            val a = if (mode == CartMode.AGB && flashcart) addr ushr 1 else addr
            buf.writeBE32(a)
            buf.writeBE16(value)
        }
        serial.write(buf.toByteArray())
        return serial.readByte() == 1
    }
}
