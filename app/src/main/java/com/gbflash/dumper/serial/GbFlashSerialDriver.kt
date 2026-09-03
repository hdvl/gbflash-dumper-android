package com.gbflash.dumper.serial

/*
 * This file is a modified port of Ch34xSerialDriver.java from usb-serial-for-android
 * (https://github.com/mik3y/usb-serial-for-android), MIT-licensed:
 *
 * Copyright (c) 2011-2013 Google Inc.
 * Copyright (c) 2013 Mike Wakerly
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 *
 * ---
 *
 * Why this exists: usb-serial-for-android's own Ch34xSerialDriver runs a handful of
 * `checkState()` sanity checks against magic byte values it expects back from the chip
 * during initialization, and throws IOException — aborting the connection — if a byte
 * doesn't match exactly. Several cheap CH340/CH341 clones answer those probes with
 * different-but-harmless values (e.g. 0xEC instead of
 * 0x00), which is apparently tolerated by WCH's own Windows driver but not by the strict
 * check here. This is a copy of that driver with those checks downgraded to warnings: we
 * still perform the exact same initialization writes, we just don't abort the connection
 * over a verification byte that doesn't affect the actual UART transfer.
 */

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.util.Log
import com.hoho.android.usbserial.driver.CommonUsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import java.io.IOException
import java.util.EnumSet

private const val TAG = "GbFlashSerialDriver"

class GbFlashSerialDriver(private val device: UsbDevice) : UsbSerialDriver {
    // Exposed as its concrete type (not just List<UsbSerialPort>) so callers can reach
    // readNoLivenessCheck() below.
    val gbFlashPort = GbFlashSerialPort(device)

    override fun getDevice(): UsbDevice = device
    override fun getPorts(): List<UsbSerialPort> = listOf(gbFlashPort)

    inner class GbFlashSerialPort(device: UsbDevice) : CommonUsbSerialPort(device, 0) {

        private var dtr = false
        private var rts = false

        /**
         * Same as [read], but skips CommonUsbSerialPort's own "is the connection still alive?"
         * probe (a GET_STATUS control transfer) that it otherwise runs whenever a poll comes back
         * empty. That probe reliably fails against cheap CH34x clones that don't answer it
         * properly, and throws — and, worse, issuing a control transfer on endpoint 0 in between
         * bulk IN polls seems to disrupt data the device is about to send on the bulk endpoint.
         * We already have our own retry/deadline logic one level up, so we don't need this.
         */
        fun readNoLivenessCheck(dest: ByteArray, length: Int, timeoutMs: Int): Int = read(dest, length, timeoutMs, false)

        override fun getDriver(): UsbSerialDriver = this@GbFlashSerialDriver

        override fun openInt() {
            for (i in 0 until mDevice.interfaceCount) {
                val usbIface = mDevice.getInterface(i)
                if (!mConnection.claimInterface(usbIface, true)) {
                    throw IOException("Could not claim data interface")
                }
            }

            val dataIface = mDevice.getInterface(mDevice.interfaceCount - 1)
            for (i in 0 until dataIface.endpointCount) {
                val ep = dataIface.getEndpoint(i)
                if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    if (ep.direction == UsbConstants.USB_DIR_IN) mReadEndpoint = ep else mWriteEndpoint = ep
                }
            }

            initialize()
            setBaudRate(DEFAULT_BAUD_RATE)
        }

        override fun closeInt() {
            try {
                for (i in 0 until mDevice.interfaceCount) mConnection.releaseInterface(mDevice.getInterface(i))
            } catch (_: Exception) {
            }
        }

        private fun controlOut(request: Int, value: Int, index: Int): Int {
            val reqTypeHostToDevice = UsbConstants.USB_TYPE_VENDOR or UsbConstants.USB_DIR_OUT
            return mConnection.controlTransfer(reqTypeHostToDevice, request, value, index, null, 0, USB_TIMEOUT_MILLIS)
        }

        private fun controlIn(request: Int, value: Int, index: Int, buffer: ByteArray): Int {
            val reqTypeDeviceToHost = UsbConstants.USB_TYPE_VENDOR or UsbConstants.USB_DIR_IN
            return mConnection.controlTransfer(reqTypeDeviceToHost, request, value, index, buffer, buffer.size, USB_TIMEOUT_MILLIS)
        }

        /**
         * Same wire behavior as upstream's checkState(), but a byte mismatch is only logged,
         * never thrown — see the file header for why. A negative [ret] (an actual USB-layer
         * failure to even perform the control transfer) still throws.
         */
        private fun checkState(msg: String, request: Int, value: Int, expected: IntArray) {
            val buffer = ByteArray(expected.size)
            val ret = controlIn(request, value, 0, buffer)
            if (ret < 0) throw IOException("Failed send cmd [$msg]")
            if (ret != expected.size) {
                Log.w(TAG, "Expected ${expected.size} bytes, but got $ret [$msg] — continuing anyway")
                return
            }
            for (i in expected.indices) {
                if (expected[i] == -1) continue
                val current = buffer[i].toInt() and 0xFF
                if (expected[i] != current) {
                    Log.w(TAG, "Expected 0x${expected[i].toString(16)} byte, but got 0x${current.toString(16)} [$msg] — continuing anyway (known clone-chip quirk)")
                }
            }
        }

        private fun setControlLines() {
            val value = ((if (dtr) SCL_DTR else 0) or (if (rts) SCL_RTS else 0)).inv()
            if (controlOut(0xa4, value, 0) < 0) throw IOException("Failed to set control lines")
        }

        private fun getStatus(): Byte {
            val buffer = ByteArray(2)
            if (controlIn(0x95, 0x0706, 0, buffer) < 0) throw IOException("Error getting control lines")
            return buffer[0]
        }

        private fun initialize() {
            checkState("init #1", 0x5f, 0, intArrayOf(-1, 0x00))

            if (controlOut(0xa1, 0, 0) < 0) throw IOException("Init failed: #2")

            setBaudRate(DEFAULT_BAUD_RATE)

            checkState("init #4", 0x95, 0x2518, intArrayOf(-1, 0x00))

            if (controlOut(0x9a, 0x2518, LCR_ENABLE_RX or LCR_ENABLE_TX or LCR_CS8) < 0) throw IOException("Init failed: #5")

            checkState("init #6", 0x95, 0x0706, intArrayOf(-1, -1))

            if (controlOut(0xa1, 0x501f, 0xd90a) < 0) throw IOException("Init failed: #7")

            setBaudRate(DEFAULT_BAUD_RATE)
            setControlLines()

            checkState("init #10", 0x95, 0x0706, intArrayOf(-1, -1))
        }

        private fun setBaudRate(baudRate: Int) {
            var factor: Long
            val divisor: Long
            if (baudRate == 921600) {
                divisor = 7
                factor = 0xf300
            } else {
                val baudbaseFactor = 1532620800L
                val baudbaseDivmax = 3
                factor = baudbaseFactor / baudRate
                var div = baudbaseDivmax.toLong()
                while (factor > 0xfff0 && div > 0) {
                    factor = factor shr 3
                    div--
                }
                if (factor > 0xfff0) throw UnsupportedOperationException("Unsupported baud rate: $baudRate")
                factor = 0x10000 - factor
                divisor = div
            }
            val div = divisor or 0x0080 // else ch341a waits until buffer full
            val val1 = ((factor and 0xff00) or div).toInt()
            val val2 = (factor and 0xff).toInt()
            if (controlOut(0x9a, 0x1312, val1) < 0) throw IOException("Error setting baud rate: #1")
            if (controlOut(0x9a, 0x0f2c, val2) < 0) throw IOException("Error setting baud rate: #2")
        }

        override fun setParameters(baudRate: Int, dataBits: Int, stopBits: Int, parity: Int) {
            if (baudRate <= 0) throw IllegalArgumentException("Invalid baud rate: $baudRate")
            setBaudRate(baudRate)

            var lcr = LCR_ENABLE_RX or LCR_ENABLE_TX
            lcr = lcr or when (dataBits) {
                UsbSerialPort.DATABITS_5 -> LCR_CS5
                UsbSerialPort.DATABITS_6 -> LCR_CS6
                UsbSerialPort.DATABITS_7 -> LCR_CS7
                UsbSerialPort.DATABITS_8 -> LCR_CS8
                else -> throw IllegalArgumentException("Invalid data bits: $dataBits")
            }
            lcr = lcr or when (parity) {
                UsbSerialPort.PARITY_NONE -> 0
                UsbSerialPort.PARITY_ODD -> LCR_ENABLE_PAR
                UsbSerialPort.PARITY_EVEN -> LCR_ENABLE_PAR or LCR_PAR_EVEN
                UsbSerialPort.PARITY_MARK -> LCR_ENABLE_PAR or LCR_MARK_SPACE
                UsbSerialPort.PARITY_SPACE -> LCR_ENABLE_PAR or LCR_MARK_SPACE or LCR_PAR_EVEN
                else -> throw IllegalArgumentException("Invalid parity: $parity")
            }
            when (stopBits) {
                UsbSerialPort.STOPBITS_1 -> {}
                UsbSerialPort.STOPBITS_1_5 -> throw UnsupportedOperationException("Unsupported stop bits: 1.5")
                UsbSerialPort.STOPBITS_2 -> lcr = lcr or LCR_STOP_BITS_2
                else -> throw IllegalArgumentException("Invalid stop bits: $stopBits")
            }

            if (controlOut(0x9a, 0x2518, lcr) < 0) throw IOException("Error setting control byte")
        }

        override fun getCD() = (getStatus().toInt() and GCL_CD) == 0
        override fun getCTS() = (getStatus().toInt() and GCL_CTS) == 0
        override fun getDSR() = (getStatus().toInt() and GCL_DSR) == 0
        override fun getDTR() = dtr
        override fun setDTR(value: Boolean) {
            dtr = value
            setControlLines()
        }

        override fun getRI() = (getStatus().toInt() and GCL_RI) == 0
        override fun getRTS() = rts
        override fun setRTS(value: Boolean) {
            rts = value
            setControlLines()
        }

        override fun getControlLines(): EnumSet<UsbSerialPort.ControlLine> {
            val status = getStatus().toInt()
            val set = EnumSet.noneOf(UsbSerialPort.ControlLine::class.java)
            if (rts) set.add(UsbSerialPort.ControlLine.RTS)
            if ((status and GCL_CTS) == 0) set.add(UsbSerialPort.ControlLine.CTS)
            if (dtr) set.add(UsbSerialPort.ControlLine.DTR)
            if ((status and GCL_DSR) == 0) set.add(UsbSerialPort.ControlLine.DSR)
            if ((status and GCL_CD) == 0) set.add(UsbSerialPort.ControlLine.CD)
            if ((status and GCL_RI) == 0) set.add(UsbSerialPort.ControlLine.RI)
            return set
        }

        override fun getSupportedControlLines(): EnumSet<UsbSerialPort.ControlLine> =
            EnumSet.allOf(UsbSerialPort.ControlLine::class.java)

        override fun setBreak(value: Boolean) {
            val req = ByteArray(2)
            if (controlIn(0x95, 0x1805, 0, req) < 0) throw IOException("Error getting BREAK condition")
            if (value) {
                req[0] = (req[0].toInt() and 1.inv()).toByte()
                req[1] = (req[1].toInt() and 0x40.inv()).toByte()
            } else {
                req[0] = (req[0].toInt() or 1).toByte()
                req[1] = (req[1].toInt() or 0x40).toByte()
            }
            val v = ((req[1].toInt() and 0xFF) shl 8) or (req[0].toInt() and 0xFF)
            if (controlOut(0x9a, 0x1805, v) < 0) throw IOException("Error setting BREAK condition")
        }

    }
}

// Inner classes can't have companion objects, so these live at file scope instead.
private const val USB_TIMEOUT_MILLIS = 5000
private const val DEFAULT_BAUD_RATE = 9600

private const val LCR_ENABLE_RX = 0x80
private const val LCR_ENABLE_TX = 0x40
private const val LCR_MARK_SPACE = 0x20
private const val LCR_PAR_EVEN = 0x10
private const val LCR_ENABLE_PAR = 0x08
private const val LCR_STOP_BITS_2 = 0x04
private const val LCR_CS8 = 0x03
private const val LCR_CS7 = 0x02
private const val LCR_CS6 = 0x01
private const val LCR_CS5 = 0x00

private const val GCL_CTS = 0x01
private const val GCL_DSR = 0x02
private const val GCL_RI = 0x04
private const val GCL_CD = 0x08
private const val SCL_DTR = 0x20
private const val SCL_RTS = 0x40
