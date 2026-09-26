package com.mutsumi.card

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mutsumi.card.focus.FocusCatalog
import com.mutsumi.card.focus.FocusConfiguration
import com.mutsumi.card.focus.FocusReporterController
import com.mutsumi.card.focus.FocusReporterGateway
import com.mutsumi.card.focus.FocusSettingsSection
import com.mutsumi.card.focus.FocusSettingsStorage
import com.mutsumi.card.ui.components.FeedbackController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FocusSettingsUiTest {
    @get:Rule val compose = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Test fun 科目切换后事项菜单只显示匹配项() {
        val catalog = FocusCatalog.parse("""{"subjects":[{"id":1,"name":"408"},{"id":2,"name":"数学"}],"focus_items":[{"id":9,"subject_id":1,"name":"强化","sort_order":1},{"id":6,"subject_id":2,"name":"模拟","sort_order":2}]}""")
        val store = object : FocusSettingsStorage {
            override suspend fun loadConfiguration() = FocusConfiguration(subjectId = 1, itemId = 9)
            override suspend fun saveConfiguration(configuration: FocusConfiguration) = Unit
            override fun loadKey(): String = "1." + "a".repeat(64)
            override fun saveKey(key: String) = Unit
            override fun clearKey() = Unit
        }
        val gateway = object : FocusReporterGateway {
            override suspend fun catalog(key: String) = catalog
            override suspend fun frame(key: String, body: String) = 200
        }
        val controller = FocusReporterController(store, gateway, scope)
        compose.setContent { FocusSettingsSection(controller, FeedbackController()) }
        compose.waitUntil(10_000) { controller.state.value.catalog != null }
        compose.onNodeWithTag("focus-reporter-subject").performClick()
        compose.onNodeWithText("数学 · 2").performClick()
        compose.onNodeWithTag("focus-reporter-item").performClick()
        compose.onNodeWithText("模拟 · 6").assertIsDisplayed()
        compose.onNodeWithText("强化 · 9").assertDoesNotExist()
    }

    @After fun cleanup() { scope.cancel() }
}
