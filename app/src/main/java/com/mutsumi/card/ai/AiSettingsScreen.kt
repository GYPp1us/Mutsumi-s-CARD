package com.mutsumi.card.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
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
import kotlinx.coroutines.launch

@Composable
fun AiSettingsScreen(store: AiSettingsStore, modifier: Modifier = Modifier) {
    var settings by remember { mutableStateOf(AiSettings()) }
    var message by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    LaunchedEffect(store) { settings = store.load() }
    Column(
        modifier = modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("AI 设置")
        OutlinedTextField(settings.endpoint, { settings = settings.copy(endpoint = it) }, label = { Text("OpenAI 兼容地址") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(settings.apiKey, { settings = settings.copy(apiKey = it) }, label = { Text("API Key") }, modifier = Modifier.fillMaxWidth(), singleLine = true, visualTransformation = PasswordVisualTransformation())
        OutlinedTextField(settings.model, { settings = settings.copy(model = it) }, label = { Text("模型") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Button(onClick = {
            scope.launch {
                runCatching { store.save(settings) }
                    .onSuccess { message = "已保存" }
                    .onFailure { message = "保存失败：${it.message}" }
            }
        }) { Text("保存") }
        if (message.isNotBlank()) Text(message)
    }
}
