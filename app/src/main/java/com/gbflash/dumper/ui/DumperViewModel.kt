package com.gbflash.dumper.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gbflash.dumper.device.CartMode
import com.gbflash.dumper.device.FirmwareInfo
import com.gbflash.dumper.device.GbxDevice
import com.gbflash.dumper.dump.CartridgeInfo
import com.gbflash.dumper.dump.DumpCancelledException
import com.gbflash.dumper.dump.DumpEngine
import com.gbflash.dumper.serial.GbxSerialPort
import com.gbflash.dumper.serial.UsbConnectionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ConnectionStatus { DISCONNECTED, NO_PERMISSION, CONNECTING, CONNECTED, ERROR }

/** What the "Dump" buttons are asking the UI to do — the actual file gets picked via Storage Access Framework. */
enum class DumpTarget { ROM, SAVE }

/** A just-finished dump, kept around so the UI can offer to share/re-open the file. */
data class LastDumpInfo(val target: DumpTarget, val fileName: String, val sizeBytes: Long, val uri: Uri)

data class DumperUiState(
    val connectionStatus: ConnectionStatus = ConnectionStatus.DISCONNECTED,
    val firmware: FirmwareInfo? = null,
    val cartInfo: CartridgeInfo? = null,
    val isBusy: Boolean = false,
    val busyLabel: String = "",
    val progress: Float = 0f,
    val transferredBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val pendingDumpTarget: DumpTarget? = null,
    val suggestedFileName: String = "",
    val lastDump: LastDumpInfo? = null,
    val log: List<String> = emptyList(),
    val errorMessage: String? = null,
)

class DumperViewModel(application: Application) : AndroidViewModel(application) {

    private val usbManager = UsbConnectionManager(application)
    private var serialPort: GbxSerialPort? = null
    private var device: GbxDevice? = null
    private var engine: DumpEngine? = null

    private val _state = MutableStateFlow(DumperUiState())
    val state: StateFlow<DumperUiState> = _state

    @Volatile private var cancelRequested = false

    private fun log(message: String) {
        _state.update { it.copy(log = (it.log + message).takeLast(200)) }
    }

    /** Silent variant for automatic checks (e.g. Activity.onResume) — does nothing if nothing's plugged in. */
    fun connectIfDevicePresent() {
        if (usbManager.findGbFlashDevice() != null) connect()
    }

    fun connect() {
        val usbDevice = usbManager.findGbFlashDevice()
        if (usbDevice == null) {
            _state.update { it.copy(connectionStatus = ConnectionStatus.DISCONNECTED, errorMessage = "No GBFlash found. Plug it in via a USB-OTG cable.") }
            return
        }
        if (!usbManager.hasPermission(usbDevice)) {
            _state.update { it.copy(connectionStatus = ConnectionStatus.NO_PERMISSION) }
            usbManager.requestPermission(usbDevice) { granted ->
                if (granted) connect() else _state.update { it.copy(errorMessage = "USB permission denied.") }
            }
            return
        }

        _state.update { it.copy(connectionStatus = ConnectionStatus.CONNECTING, errorMessage = null) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val port = usbManager.openPort(usbDevice) ?: throw IllegalStateException("Couldn't open the USB serial port.")
                port.onIoError = { msg -> log("  [USB] $msg") }
                val dev = GbxDevice(port)
                dev.onBytesTransferred = { n -> onBytesTransferred(n) }
                dev.onDebugLog = { msg -> log(msg) }
                val fw = dev.loadFirmwareVersion()
                if (fw == null || !fw.isCompatible) {
                    port.close()
                    _state.update {
                        it.copy(
                            connectionStatus = ConnectionStatus.ERROR,
                            errorMessage = "The device didn't answer like a GBFlash running Lesserkuma-compatible firmware.",
                        )
                    }
                    return@launch
                }
                serialPort = port
                device = dev
                engine = DumpEngine(dev)
                log("Connected: ${fw.deviceName} — firmware ${fw.cfwId}${fw.fwVer}")
                _state.update { it.copy(connectionStatus = ConnectionStatus.CONNECTED, firmware = fw) }
            } catch (e: Exception) {
                _state.update { it.copy(connectionStatus = ConnectionStatus.ERROR, errorMessage = e.message ?: "Connection failed.") }
            }
        }
    }

    fun disconnect() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                device?.disconnect()
            } catch (_: Exception) {
            }
            device = null
            engine = null
            serialPort = null
            _state.update { DumperUiState(log = it.log + "Disconnected.") }
        }
    }

    fun selectMode(mode: CartMode) {
        val eng = engine ?: return
        _state.update { it.copy(isBusy = true, busyLabel = "Reading header...", progress = 0f, errorMessage = null, cartInfo = null, lastDump = null) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val info = eng.identify(mode)
                log(describeCartridge(info))
                _state.update { it.copy(isBusy = false, cartInfo = info) }
            } catch (e: Exception) {
                _state.update { it.copy(isBusy = false, errorMessage = "Couldn't read the cartridge: ${e.message}") }
            }
        }
    }

    /** Step 1 of a dump: the UI observes [DumperUiState.pendingDumpTarget]/[DumperUiState.suggestedFileName] and launches a document picker. */
    fun requestDump(target: DumpTarget) {
        val info = _state.value.cartInfo ?: return
        val baseName = when (info) {
            is CartridgeInfo.Dmg -> info.header.title.ifBlank { "rom" }
            is CartridgeInfo.Agb -> info.header.title.ifBlank { "rom" }
        }.filter { it.isLetterOrDigit() || it == ' ' || it == '-' }.trim().ifBlank { "rom" }

        val extension = when (target) {
            DumpTarget.ROM -> when (info) {
                is CartridgeInfo.Dmg -> if (info.header.isColorCompatible) "gbc" else "gb"
                is CartridgeInfo.Agb -> "gba"
            }
            DumpTarget.SAVE -> "sav"
        }
        _state.update { it.copy(pendingDumpTarget = target, suggestedFileName = "$baseName.$extension") }
    }

    fun clearPendingDump() {
        _state.update { it.copy(pendingDumpTarget = null) }
    }

    /** Step 2: called once the user picked a destination Uri for the dump requested via [requestDump]. */
    fun performDump(target: DumpTarget, uri: Uri) {
        val eng = engine ?: return
        val info = _state.value.cartInfo ?: return
        cancelRequested = false
        val fileName = _state.value.suggestedFileName
        _state.update {
            it.copy(
                pendingDumpTarget = null, isBusy = true, progress = 0f, errorMessage = null, lastDump = null,
                busyLabel = if (target == DumpTarget.ROM) "Dumping ROM..." else "Dumping save data...",
            )
        }

        val totalBytes = when (target) {
            DumpTarget.ROM -> when (info) {
                is CartridgeInfo.Dmg -> info.header.romSizeBytes.toLong()
                is CartridgeInfo.Agb -> info.romSizeBytes
            }
            DumpTarget.SAVE -> when (info) {
                is CartridgeInfo.Dmg -> info.header.save.sizeBytes.toLong()
                is CartridgeInfo.Agb -> info.saveType.sizeBytes.toLong()
            }
        }
        if (target == DumpTarget.SAVE && totalBytes == 0L) {
            _state.update { it.copy(isBusy = false, errorMessage = "This cartridge has no save memory to dump.") }
            return
        }
        transferredBytes = 0
        totalBytesForProgress = totalBytes.coerceAtLeast(1)
        _state.update { it.copy(transferredBytes = 0, totalBytes = totalBytes) }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val resolver = getApplication<Application>().contentResolver
                val stream = resolver.openOutputStream(uri) ?: throw IllegalStateException("Couldn't open the destination file.")
                stream.use { out ->
                    when (target) {
                        DumpTarget.ROM -> when (info) {
                            is CartridgeInfo.Dmg -> eng.dumpDmgRom(info.header, out) { cancelRequested }
                            is CartridgeInfo.Agb -> eng.dumpAgbRom(info.romSizeBytes, out) { cancelRequested }
                        }
                        DumpTarget.SAVE -> when (info) {
                            is CartridgeInfo.Dmg -> eng.dumpDmgSave(info.header, out) { cancelRequested }
                            is CartridgeInfo.Agb -> eng.dumpAgbSave(info.saveType, out) { cancelRequested }
                        }
                    }
                }
                log("${if (target == DumpTarget.ROM) "ROM" else "Save"} dump complete: $totalBytes bytes.")
                _state.update {
                    it.copy(
                        isBusy = false, progress = 1f,
                        lastDump = LastDumpInfo(target, fileName, totalBytes, uri),
                    )
                }
            } catch (e: DumpCancelledException) {
                log("Dump cancelled.")
                _state.update { it.copy(isBusy = false, progress = 0f) }
            } catch (e: Exception) {
                _state.update { it.copy(isBusy = false, errorMessage = "Dump failed: ${e.message}") }
            }
        }
    }

    fun dismissLastDump() {
        _state.update { it.copy(lastDump = null) }
    }

    fun cancelDump() {
        cancelRequested = true
    }

    fun dismissError() {
        _state.update { it.copy(errorMessage = null) }
    }

    @Volatile private var transferredBytes = 0L
    @Volatile private var totalBytesForProgress = 1L

    private fun onBytesTransferred(n: Int) {
        transferredBytes += n
        val fraction = (transferredBytes.toFloat() / totalBytesForProgress.toFloat()).coerceIn(0f, 1f)
        _state.update { it.copy(progress = fraction, transferredBytes = transferredBytes) }
    }

    private fun describeCartridge(info: CartridgeInfo): String = when (info) {
        is CartridgeInfo.Dmg -> {
            val h = info.header
            "Detected: \"${h.title}\" — mapper ${h.mapperName}, ROM ${h.romSizeBytes / 1024} KiB, save ${h.save.sizeBytes} bytes"
        }
        is CartridgeInfo.Agb -> {
            val h = info.header
            "Detected: \"${h.title}\" (${h.gameCode}) — ROM ${info.romSizeBytes / 1024} KiB, save: ${info.saveType.label}"
        }
    }

    override fun onCleared() {
        super.onCleared()
        try {
            device?.disconnect()
        } catch (_: Exception) {
        }
    }
}
