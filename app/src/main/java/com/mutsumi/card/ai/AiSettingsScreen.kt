package com.mutsumi.card.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.mutsumi.card.ui.components.FeedbackController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun AiSettingsScreen(
    store: AiSettingsStore,
    feedback: FeedbackController,
    modifier: Modifier = Modifier,
) {
    var settings by remember { mutableStateOf(AiSettings()) }
    var message by remember { mutableStateOf("") }
    var isSaving by remember { mutableStateOf(false) }
    var isSaved by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(store) {
        try {
            settings = store.load()
            isSaved = false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            message = "AI 设置读取失败：${error.message ?: "无法读取设置"}"
            feedback.show(message)
        }
    }

    fun updateSettings(transform: (AiSettings) -> AiSettings) {
        settings = transform(settings)
        isSaved = false
        message = ""
    }

    Column(
        modifier = modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("\u0041\u0049 \u8BBE\u7F6E")
        OutlinedTextField(
            value = settings.endpoint,
            onValueChange = { value -> updateSettings { current -> current.copy(endpoint = value) } },
            label = { Text("OpenAI \u517C\u5BB9\u5730\u5740") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        OutlinedTextField(
            value = settings.apiKey,
            onValueChange = { value -> updateSettings { current -> current.copy(apiKey = value) } },
            label = { Text("API Key") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
        )
        OutlinedTextField(
            value = settings.model,
            onValueChange = { value -> updateSettings { current -> current.copy(model = value) } },
            label = { Text("\u6A21\u578B") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Button(
            onClick = {
                val settingsToSave = settings
                isSaving = true
                message = ""
                scope.launch {
                    try {
                        store.save(settingsToSave)
                        isSaving = false
                        isSaved = settings == settingsToSave
                        if (isSaved) message = "\u5DF2\u4FDD\u5B58"
                    } catch (cancelled: CancellationException) {
                        isSaving = false
                        throw cancelled
                    } catch (error: Exception) {
                        isSaving = false
                        isSaved = false
                        message = "\u4FDD\u5B58\u5931\u8D25\uFF1A${error.message ?: "\u672A\u77E5\u9519\u8BEF"}"
                        feedback.show(message)
                    }
                }
            },
            enabled = !isSaving && !isSaved,
            colors = ButtonDefaults.buttonColors(
                disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        ) {
            Text(
                when {
                    isSaved -> "\u5DF2\u4FDD\u5B58"
                    isSaving -> "\u4FDD\u5B58\u4E2D\u2026"
                    else -> "\u4FDD\u5B58"
                },
            )
        }
        if (message.isNotBlank()) Text(message)
    }
}
