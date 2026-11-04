package com.rewardadguard.app.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.rewardadguard.app.R
import com.rewardadguard.app.manager.UpdateInfo
import com.rewardadguard.app.manager.UpdateManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Where the cloud-update card is in its flow. */
private sealed interface UpdateUiState {
    data object Idle : UpdateUiState
    data object Checking : UpdateUiState
    data object UpToDate : UpdateUiState
    data class Available(val info: UpdateInfo) : UpdateUiState
    data class Downloading(val info: UpdateInfo, val percent: Int) : UpdateUiState
    data class Failed(val message: String) : UpdateUiState
}

/**
 * Cloud update (2026-11-04): checks this project's GitHub releases only when
 * the user taps the button - the app never phones home on its own.
 */
@Composable
fun UpdateCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<UpdateUiState>(UpdateUiState.Idle) }
    val current = remember { UpdateManager.currentVersion(context) }

    fun check() {
        state = UpdateUiState.Checking
        scope.launch {
            state = runCatching {
                withContext(Dispatchers.IO) { UpdateManager.checkLatest(context) }
            }.fold(
                onSuccess = { info -> if (info == null) UpdateUiState.UpToDate else UpdateUiState.Available(info) },
                onFailure = { UpdateUiState.Failed(it.message ?: it.javaClass.simpleName) }
            )
        }
    }

    fun downloadAndInstall(info: UpdateInfo) {
        if (!UpdateManager.canInstall(context)) {
            UpdateManager.openInstallPermissionSettings(context)
            return
        }
        state = UpdateUiState.Downloading(info, 0)
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    UpdateManager.download(context, info) { percent ->
                        scope.launch { state = UpdateUiState.Downloading(info, percent) }
                    }
                }
            }.fold(
                onSuccess = { apk ->
                    state = UpdateUiState.Available(info)
                    UpdateManager.install(context, apk)
                },
                onFailure = { state = UpdateUiState.Failed(it.message ?: it.javaClass.simpleName) }
            )
        }
    }

    SectionCard(
        title = stringResource(R.string.update_title),
        subtitle = stringResource(R.string.update_current_version, current)
    ) {
        when (val s = state) {
            UpdateUiState.Idle -> Button(onClick = ::check) { Text(stringResource(R.string.update_check)) }
            UpdateUiState.Checking -> Text(stringResource(R.string.update_checking))
            UpdateUiState.UpToDate -> {
                Text(stringResource(R.string.update_up_to_date))
                Spacer(Modifier.height(6.dp))
                OutlinedButton(onClick = ::check) { Text(stringResource(R.string.update_check)) }
            }
            is UpdateUiState.Available -> {
                Text(stringResource(R.string.update_available, s.info.version))
                if (s.info.notes.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = s.info.notes,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(6.dp))
                Button(onClick = { downloadAndInstall(s.info) }) {
                    Text(
                        stringResource(
                            if (UpdateManager.canInstall(context)) R.string.update_install
                            else R.string.update_allow_install
                        )
                    )
                }
            }
            is UpdateUiState.Downloading -> {
                Text(stringResource(R.string.update_downloading, s.percent))
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { s.percent / 100f },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            is UpdateUiState.Failed -> {
                Text(
                    text = stringResource(R.string.update_failed, s.message),
                    color = MaterialTheme.colorScheme.error
                )
                Spacer(Modifier.height(6.dp))
                OutlinedButton(onClick = ::check) { Text(stringResource(R.string.update_check)) }
            }
        }
    }
}
