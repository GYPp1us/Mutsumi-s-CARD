package com.mutsumi.card

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import com.mutsumi.card.draw.DrawingDraftStore
import com.mutsumi.card.ai.AiSettingsStore
import com.mutsumi.card.ai.AiPrompts
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.rules.ExternalResource
import org.junit.Rule
import org.junit.Test
import android.content.pm.ActivityInfo

class V090WorkflowTest {
    @get:Rule(order = 0) val draftCleanup = object : ExternalResource() {
        override fun before() {
            InstrumentationRegistry.getInstrumentation().targetContext.filesDir.resolve("drawing/current-draft.zip").delete()
        }
    }
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    @Test fun AI录入提供两个独立输入分支() {
        compose.waitUntil(12000) { compose.onAllNodesWithTag("app-initialized").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("nav-aibatch").performClick()
        compose.onNodeWithText("知识库分割").assertIsDisplayed()
        compose.onNodeWithText("快速主题录入").performClick()
        compose.onNodeWithTag("ai-quick-topic").assertIsDisplayed()
    }

    @Test fun 手机快速主题首屏提供完整输入框且补充要求可达() {
        compose.activity.runOnUiThread { compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        try {
            waitFor("app-initialized")
            compose.onNodeWithTag("nav-aibatch").performClick()
            compose.onNodeWithText("快速主题录入").performClick()
            compose.waitForIdle()
            val field = compose.onNodeWithTag("ai-quick-topic").fetchSemanticsNode().boundsInRoot
            assertTrue("主题输入框首屏必须完整可见", field.height >= 64f * compose.activity.resources.displayMetrics.density)
            compose.onNodeWithTag("ai-quick-topic").performClick().performTextInput("二叉树")
            compose.activity.runOnUiThread {
                androidx.core.view.WindowCompat.getInsetsController(compose.activity.window, compose.activity.window.decorView)
                    .show(androidx.core.view.WindowInsetsCompat.Type.ime())
            }
            compose.waitUntil(8000) {
                androidx.core.view.ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
                    ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true
            }
            compose.waitForIdle()
            assertTrue("键盘打开后主题输入框必须仍完整可见",
                compose.onNodeWithTag("ai-quick-topic").fetchSemanticsNode().boundsInRoot.height >= 64f * compose.activity.resources.displayMetrics.density)
            hideKeyboard()
            compose.onNodeWithTag("ai-quick-instructions").performScrollTo().performTextInput("加入例子")
            hideKeyboard()
        } finally {
            compose.activity.runOnUiThread { compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
        }
    }

    @Test fun 短横屏主题输入在真实键盘打开后仍可见() {
        compose.activity.runOnUiThread { compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        try {
            waitFor("app-initialized")
            compose.onNodeWithTag("nav-aibatch").performClick()
            compose.onNodeWithText("快速主题录入").performClick()
            compose.onNodeWithTag("ai-quick-topic").performScrollTo().performClick().performTextInput("网络分层")
            compose.activity.runOnUiThread {
                androidx.core.view.WindowCompat.getInsetsController(compose.activity.window, compose.activity.window.decorView)
                    .show(androidx.core.view.WindowInsetsCompat.Type.ime())
            }
            compose.waitUntil(8000) {
                androidx.core.view.ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
                    ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true
            }
            compose.waitForIdle()
            val field = compose.onNodeWithTag("ai-quick-topic").fetchSemanticsNode().boundsInRoot
            val rootBottom = compose.onNodeWithTag("app-shell").fetchSemanticsNode().boundsInRoot.bottom
            val imeBottom = androidx.core.view.ViewCompat.getRootWindowInsets(compose.activity.window.decorView)!!
                .getInsets(androidx.core.view.WindowInsetsCompat.Type.ime()).bottom
            assertTrue("短横屏键盘打开后必须保留可见的主题输入行",
                minOf(field.bottom, rootBottom - imeBottom) - field.top >= 48f * compose.activity.resources.displayMetrics.density)
            hideKeyboard()
        } finally {
            compose.activity.runOnUiThread { compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
        }
    }

    @Test fun 草稿保存只持久化且收起滑块悬浮展开短滑不制作() {
        openDraw()
        compose.onNodeWithTag("draw-key-input").performTextInput("两段式制作")
        compose.onNodeWithTag("draw-key-input").performImeAction()
        val back = compose.onNodeWithTag("drawing-canvas-back")
        back.performTouchInput { down(center); moveTo(center + Offset(30f, 20f), 60); up() }
        compose.onNodeWithTag("draw-key-lock").performClick()
        compose.waitForIdle()
        val canvasWidth = compose.onNodeWithTag("draw-face-front").fetchSemanticsNode().boundsInRoot.width
        val originalWidth = compose.onNodeWithTag("save-card").fetchSemanticsNode().boundsInRoot.width
        compose.onNodeWithTag("save-card").performClick()
        waitFor("complete-card")
        compose.waitForIdle()
        assertTrue(compose.onNodeWithTag("complete-card").fetchSemanticsNode().boundsInRoot.width > originalWidth + 80f)
        assertTrue(kotlin.math.abs(compose.onNodeWithTag("draw-face-front").fetchSemanticsNode().boundsInRoot.width - canvasWidth) < 1f)
        compose.onNodeWithTag("study-card").assertDoesNotExist()
        compose.onNodeWithTag("complete-card").performTouchInput {
            down(Offset(22f, height / 2f)); moveTo(Offset(width * 0.4f, height / 2f), 220); up()
        }
        compose.onNodeWithTag("complete-card").assertIsDisplayed()
        back.performTouchInput { down(center); moveTo(center + Offset(10f, 60f), 60); up() }
        compose.onNodeWithTag("complete-card").assertDoesNotExist()
        compose.onNodeWithTag("save-card").performClick()
        waitFor("complete-card")
        complete()
        waitFor("study-card")
        assertTrue(!draftFile().exists())
    }

    @Test fun Activity重建从磁盘恢复双面内容并保留滑动入口() {
        openDraw()
        compose.onNodeWithTag("draw-key-input").performTextInput("重启恢复测试")
        compose.onNodeWithTag("draw-key-input").performImeAction()
        compose.onNodeWithTag("drawing-canvas-front").performTouchInput { down(center); moveTo(center + Offset(12f, 30f), 50); up() }
        compose.onNodeWithTag("draw-face-selector-back").performClick()
        compose.onNodeWithTag("draw-tool-markdown").performClick()
        compose.onNodeWithTag("draw-markdown-back").performTextInput("# 恢复后的文档")
        hideKeyboard()
        compose.onNodeWithTag("draw-toggle-markdown").performScrollTo().performClick()
        compose.onNodeWithTag("save-card").performClick()
        waitFor("complete-card")
        val saved = requireNotNull(DrawingDraftStore(draftFile()).load())
        assertTrue(saved.front.strokes.isNotEmpty() && saved.back.markdown == "# 恢复后的文档")
        compose.activityRule.scenario.recreate()
        waitFor("complete-card")
        compose.onNodeWithTag("draw-key-input").assertTextContains("重启恢复测试")
        assertTrue(requireNotNull(DrawingDraftStore(draftFile()).load()).sameContent(saved))
        compose.onNodeWithTag("draw-toggle-markdown").performScrollTo().performClick()
        compose.onNodeWithTag("draw-markdown-back").assertTextContains("# 恢复后的文档")
    }

    @Test fun 详情重新编辑取消替换保留草稿确认后实际载入底图() {
        openDraw()
        compose.onNodeWithTag("draw-key-input").performTextInput("不要丢失的草稿")
        compose.onNodeWithTag("draw-key-input").performImeAction()
        compose.onNodeWithTag("nav-cards").performClick()
        compose.waitUntil(12000) { compose.onAllNodesWithTag("card-list-item").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithTag("card-list-item")[0].performClick()
        compose.onNodeWithTag("card-edit-as-base").performScrollTo().performClick()
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithTag("nav-draw").performClick()
        compose.onNodeWithTag("draw-key-input").assertTextContains("不要丢失的草稿")
        compose.onNodeWithTag("nav-cards").performClick()
        compose.onNodeWithTag("card-edit-as-base").performScrollTo().performClick()
        compose.onNodeWithText("载入底图").performClick()
        waitFor("drawing-canvas-back")
        val loaded = requireNotNull(DrawingDraftStore(draftFile()).load())
        assertTrue(loaded.back.baseImagePresent && requireNotNull(loaded.back.baseImageBytes).isNotEmpty())
        assertTrue(loaded.keyText != "不要丢失的草稿")
        assertTrue(loaded.back.camera?.zoom == 1f)
    }

    @Test fun 设置折叠保存三种系统提示词并可以恢复默认() {
        waitFor("app-initialized")
        compose.onNodeWithTag("nav-settings").performClick()
        waitFor("ai-settings-model")
        compose.onNodeWithTag("settings-group-ai-connection").performClick()
        compose.onNodeWithTag("ai-settings-model").assertDoesNotExist()
        compose.onNodeWithTag("settings-group-ai-prompts").performScrollTo().performClick()
        listOf("knowledge", "quick", "generation").forEach { suffix ->
            compose.onNodeWithTag("ai-prompt-$suffix").performScrollTo().performTextReplacement("修改的$suffix 提示词")
            hideKeyboard()
        }
        compose.onNodeWithTag("ai-settings-save").performScrollTo().performClick()
        compose.waitUntil(8000) { runBlocking { AiSettingsStore.create(compose.activity).load().generationPrompt == "修改的generation 提示词" } }
        listOf("knowledge", "quick", "generation").forEach { suffix ->
            compose.onNodeWithTag("ai-prompt-$suffix-reset").performScrollTo().performClick()
        }
        compose.onNodeWithTag("ai-settings-save").performScrollTo().performClick()
        compose.waitUntil(8000) { runBlocking { AiSettingsStore.create(compose.activity).load().generationPrompt == AiPrompts.GENERATION } }
        compose.onNodeWithTag("settings-group-ai-prompts").performScrollTo().performClick()
        compose.onNodeWithTag("ai-prompt-quick").assertDoesNotExist()
    }

    private fun openDraw() {
        waitFor("app-initialized")
        compose.onNodeWithTag("nav-draw").performClick()
        waitFor("drawing-canvas-back")
    }
    private fun draftFile() = compose.activity.filesDir.resolve("drawing/current-draft.zip")
    private fun complete() {
        compose.waitForIdle()
        compose.onNodeWithTag("complete-card").performTouchInput {
            down(Offset(22f, height / 2f)); moveTo(Offset(width - 6f, height / 2f), 350); up()
        }
    }
    private fun waitFor(tag: String) {
        compose.waitUntil(15000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }
    private fun hideKeyboard() {
        compose.activity.runOnUiThread {
            androidx.core.view.WindowCompat.getInsetsController(compose.activity.window, compose.activity.window.decorView)
                .hide(androidx.core.view.WindowInsetsCompat.Type.ime())
        }
        compose.waitForIdle()
    }
}
