package com.rewardadguard.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.rewardadguard.app.collector.TelegramReportSettings
import com.rewardadguard.app.collector.TelegramReporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 傳送紀錄到 Telegram: on/off, the user's bot token and chat id, and a test
 * message. The token is shown masked and stays in the app's private storage.
 */
@Composable
fun TelegramSettingsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val saved = remember { TelegramReportSettings.load(context) }
    var enabled by remember { mutableStateOf(saved.enabled) }
    var token by remember { mutableStateOf(saved.token) }
    var chatId by remember { mutableStateOf(saved.chatId) }
    var status by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun current() = TelegramReportSettings.Config(enabled, token.trim(), chatId.trim())

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("傳送紀錄到 Telegram") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "每次自動收取結束，把這次的紀錄（動作、廣告判定、錯誤、縮小的截圖）壓成 zip 傳到你的 Bot 並釘選。" +
                        "紀錄與截圖在手機上保留 5 天後自動刪除。",
                    style = MaterialTheme.typography.bodySmall
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("啟用", modifier = Modifier.weight(1f))
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text("Bot Token") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = chatId,
                    onValueChange = { chatId = it },
                    label = { Text("Chat ID") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                TextButton(
                    enabled = !testing,
                    onClick = {
                        testing = true
                        status = "傳送測試訊息中…"
                        scope.launch {
                            val error = withContext(Dispatchers.IO) { TelegramReporter.sendTest(current()) }
                            status = error?.let { "失敗：$it" } ?: "成功，請到 Telegram 確認"
                            testing = false
                        }
                    }
                ) { Text("傳送測試訊息") }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                TelegramReportSettings.save(context, current())
                onDismiss()
            }) { Text("儲存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
