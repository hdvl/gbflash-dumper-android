package com.gbflash.dumper.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect

class MainActivity : ComponentActivity() {

    private val viewModel: DumperViewModel by viewModels()

    private val createDocumentLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val target = viewModel.state.value.pendingDumpTarget
        if (uri != null && target != null) {
            viewModel.performDump(target, uri)
        } else {
            viewModel.clearPendingDump()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val state by viewModel.state.collectAsState()

            LaunchedEffect(state.pendingDumpTarget, state.suggestedFileName) {
                if (state.pendingDumpTarget != null) {
                    createDocumentLauncher.launch(state.suggestedFileName)
                }
            }

            DumperScreen(
                state = state,
                onConnect = viewModel::connect,
                onDisconnect = viewModel::disconnect,
                onSelectMode = viewModel::selectMode,
                onRequestDump = viewModel::requestDump,
                onCancelDump = viewModel::cancelDump,
                onDismissError = viewModel::dismissError,
                onDismissLastDump = viewModel::dismissLastDump,
            )
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-check the connection when returning to the app (e.g. after granting USB permission
        // in a system dialog, or after re-plugging the cable).
        if (viewModel.state.value.connectionStatus == ConnectionStatus.DISCONNECTED) {
            viewModel.connectIfDevicePresent()
        }
    }
}
