package com.mutsumi.card.ai

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

data class AiSettings(
    val endpoint: String = "https://api.deepseek.com",
    val apiKey: String = "",
    val model: String = "deepseek-chat",
)

private val Context.aiSettingsDataStore by preferencesDataStore(name = "mutsumi-ai-settings")

class AiSettingsStore(private val dataStore: DataStore<Preferences>) {
    suspend fun load(): AiSettings {
        val values = dataStore.data.first()
        return AiSettings(
            endpoint = values[ENDPOINT] ?: AiSettings().endpoint,
            apiKey = values[API_KEY] ?: "",
            model = values[MODEL] ?: AiSettings().model,
        )
    }

    suspend fun save(settings: AiSettings) {
        require(settings.endpoint.isNotBlank()) { "AI 地址不能为空" }
        require(settings.model.isNotBlank()) { "AI 模型不能为空" }
        dataStore.edit {
            it[ENDPOINT] = settings.endpoint.trimEnd('/')
            it[API_KEY] = settings.apiKey
            it[MODEL] = settings.model.trim()
        }
    }

    companion object {
        val ENDPOINT = stringPreferencesKey("endpoint")
        val API_KEY = stringPreferencesKey("api_key")
        val MODEL = stringPreferencesKey("model")
        fun create(context: Context) = AiSettingsStore(context.applicationContext.aiSettingsDataStore)
    }
}
