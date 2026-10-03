package com.mutsumi.card.ai

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

data class AiSettings(
    val endpoint: String = "https://api.deepseek.com/v1",
    val apiKey: String = "",
    val model: String = "deepseek-chat",
    val knowledgePrompt: String = AiPrompts.KNOWLEDGE,
    val quickTopicPrompt: String = AiPrompts.QUICK_TOPIC,
    val generationPrompt: String = AiPrompts.GENERATION,
)

private val Context.aiSettingsDataStore by preferencesDataStore(name = "mutsumi-ai-settings")

class AiSettingsStore(private val dataStore: DataStore<Preferences>) {
    suspend fun load(): AiSettings {
        val values = dataStore.data.first()
        return AiSettings(
            endpoint = values[ENDPOINT] ?: AiSettings().endpoint,
            apiKey = values[API_KEY] ?: "",
            model = values[MODEL] ?: AiSettings().model,
            knowledgePrompt = values[KNOWLEDGE_PROMPT] ?: AiPrompts.KNOWLEDGE,
            quickTopicPrompt = values[QUICK_PROMPT] ?: AiPrompts.QUICK_TOPIC,
            generationPrompt = values[GENERATION_PROMPT] ?: AiPrompts.GENERATION,
        )
    }

    suspend fun save(settings: AiSettings) {
        require(settings.endpoint.isNotBlank()) { "AI 地址不能为空" }
        require(settings.model.isNotBlank()) { "AI 模型不能为空" }
        require(listOf(settings.knowledgePrompt, settings.quickTopicPrompt, settings.generationPrompt).all { it.isNotBlank() && it.length <= 20_000 }) {
            "系统提示词不能为空，且每项最多 20,000 字符"
        }
        dataStore.edit {
            it[ENDPOINT] = settings.endpoint.trimEnd('/')
            it[API_KEY] = settings.apiKey
            it[MODEL] = settings.model.trim()
            it[KNOWLEDGE_PROMPT] = settings.knowledgePrompt
            it[QUICK_PROMPT] = settings.quickTopicPrompt
            it[GENERATION_PROMPT] = settings.generationPrompt
        }
    }

    companion object {
        val ENDPOINT = stringPreferencesKey("endpoint")
        val API_KEY = stringPreferencesKey("api_key")
        val MODEL = stringPreferencesKey("model")
        val KNOWLEDGE_PROMPT = stringPreferencesKey("knowledge_prompt")
        val QUICK_PROMPT = stringPreferencesKey("quick_topic_prompt")
        val GENERATION_PROMPT = stringPreferencesKey("generation_prompt")
        fun create(context: Context) = AiSettingsStore(context.applicationContext.aiSettingsDataStore)
    }
}
