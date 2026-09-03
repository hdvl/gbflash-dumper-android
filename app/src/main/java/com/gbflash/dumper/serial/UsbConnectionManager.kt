package com.gbflash.dumper.serial

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import androidx.core.content.ContextCompat

private const val ACTION_USB_PERMISSION = "com.gbflash.dumper.USB_PERMISSION"

/** The GBFlash exposes a WCH CH340 USB-to-serial chip: VID 0x1A86, PID 0x7523. */
const val GBFLASH_VENDOR_ID = 0x1A86
const val GBFLASH_PRODUCT_ID = 0x7523
private const val GBFLASH_BAUD_RATE = 2_000_000

/** Handles finding the GBFlash on the USB bus, requesting host permission for it, and opening a serial port to it. */
class UsbConnectionManager(private val context: Context) {

    private val usbManager: UsbManager
        get() = context.getSystemService(Context.USB_SERVICE) as UsbManager

    fun findGbFlashDevice(): UsbDevice? =
        usbManager.deviceList.values.firstOrNull {
            it.vendorId == GBFLASH_VENDOR_ID && it.productId == GBFLASH_PRODUCT_ID
        }

    fun hasPermission(device: UsbDevice): Boolean = usbManager.hasPermission(device)

    /** Shows the system "allow this app to access the USB device" dialog if needed, then calls [onResult]. */
    fun requestPermission(device: UsbDevice, onResult: (granted: Boolean) -> Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action != ACTION_USB_PERMISSION) return
                context.unregisterReceiver(this)
                onResult(intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
            }
        }
        ContextCompat.registerReceiver(
            context, receiver, IntentFilter(ACTION_USB_PERMISSION), ContextCompat.RECEIVER_NOT_EXPORTED
        )
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        val permissionIntent = PendingIntent.getBroadcast(context, 0, Intent(ACTION_USB_PERMISSION), flags)
        usbManager.requestPermission(device, permissionIntent)
    }

    /** Opens the port and configures it at the GBFlash's fixed baud rate. Returns null if it couldn't be opened. */
    fun openPort(device: UsbDevice): GbxSerialPort? {
        // A custom driver, not usb-serial-for-android's own Ch34xSerialDriver, because that one
        // aborts the connection over a strict init-sequence byte check that several cheap
        // CH340/CH341 clones don't satisfy despite working fine otherwise — see
        // GbFlashSerialDriver's file header for details.
        val driver = GbFlashSerialDriver(device)
        val connection = usbManager.openDevice(device) ?: return null
        val port = driver.gbFlashPort
        port.open(connection)
        val serialPort = GbxSerialPort(port)
        serialPort.open(GBFLASH_BAUD_RATE)
        // If DTR toggling reset the board (see GbxSerialPort.open()), give its firmware a moment
        // to finish booting before we start sending it commands.
        Thread.sleep(300)
        return serialPort
    }
}
