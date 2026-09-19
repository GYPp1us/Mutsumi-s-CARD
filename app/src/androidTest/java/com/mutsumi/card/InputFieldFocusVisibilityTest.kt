package com.mutsumi.card

import android.content.pm.ActivityInfo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.ViewCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InputFieldFocusVisibilityTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun 横屏下焦点输入框会自动回到可视区域() {
        waitForNode("app-initialized", timeoutMillis = 60_000)
        compose.activity.runOnUiThread {
            compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        compose.waitForIdle()

        compose.onNodeWithTag("nav-settings").performClick()
        waitForNode("ai-settings-model")
        assertFocusedFieldsVisible(
            "ai-settings-endpoint",
            "ai-settings-api-key",
            "ai-settings-model",
        )

        compose.onNodeWithTag("nav-backup").performClick()
        waitForCloudForm()
        assertFocusedFieldsVisible(
            "backup-cloud-server-url",
            "backup-cloud-username",
            "backup-cloud-password",
            "backup-cloud-remote-directory",
            scrollContainerTag = "backup-cloud-scroll",
        )

        compose.onNodeWithTag("nav-cards").performClick()
        compose.waitUntil(timeoutMillis = 12_000) {
            compose.onAllNodesWithTag("card-list-item").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onAllNodesWithTag("card-list-item")[0].performClick()
        compose.onNodeWithContentDescription("编辑 key").performClick()
        waitForNode("key 编辑输入")
        assertFocusedFieldsVisible("key 编辑输入", scrollContainerTag = "card-details-scroll")

        compose.onNodeWithTag("nav-aibatch").performClick()
        waitForNode("ai-source-scroll")
        assertFocusedFieldsVisible(
            "ai-manual-source",
            scrollContainerTag = "ai-source-scroll",
        )

        compose.onNodeWithTag("nav-draw").performClick()
        waitForNode("draw-key-input")
        assertFocusedFieldsVisible("draw-key-input", scrollBeforeFocus = false)
        compose.onNodeWithTag("draw-tool-markdown").performClick()
        waitForNode("draw-markdown-front")
        assertFocusedFieldsVisible("draw-markdown-front", scrollBeforeFocus = false)
    }

    @After
    fun 恢复系统方向() {
        compose.activity.runOnUiThread {
            compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    private fun waitForCloudForm() {
        compose.waitUntil(timeoutMillis = 12_000) {
            compose.onAllNodesWithTag("backup-cloud-server-url").fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodesWithContentDescription("连接设置").fetchSemanticsNodes().isNotEmpty()
        }
        if (compose.onAllNodesWithTag("backup-cloud-server-url").fetchSemanticsNodes().isEmpty()) {
            compose.onNodeWithContentDescription("连接设置").performClick()
        }
        waitForNode("backup-cloud-server-url")
    }

    private fun assertFocusedFieldsVisible(
        vararg tags: String,
        scrollContainerTag: String? = null,
        scrollBeforeFocus: Boolean = true,
    ) {
        tags.forEach { tag ->
            hideSoftwareKeyboard()
            if (scrollContainerTag != null) {
                revealInScrollContainer(scrollContainerTag, tag)
            } else {
                // 隐藏键盘会触发页面重排，先等待目标重新进入语义树再滚动。
                waitForNode(tag)
                if (scrollBeforeFocus) compose.onNodeWithTag(tag).performScrollTo()
            }
            waitForNode(tag)
            compose.onNodeWithTag(tag).performClick()
            compose.waitForIdle()
            showSoftwareKeyboard()
            compose.waitForIdle()
            compose.onNodeWithTag(tag).assertIsDisplayed()
        }
    }

    private fun waitForNode(tag: String, timeoutMillis: Long = 12_000) {
        compose.waitUntil(timeoutMillis = timeoutMillis) {
            compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun revealInScrollContainer(scrollContainerTag: String, targetTag: String) {
        if (scrollContainerTag == "ai-source-scroll" || scrollContainerTag == "card-details-scroll") {
            compose.onNodeWithTag(scrollContainerTag).performScrollToNode(hasTestTag(targetTag))
            compose.onNodeWithTag(targetTag).assertIsDisplayed()
            return
        }
        repeat(8) {
            if (isDisplayed(targetTag)) return
            compose.onNodeWithTag(scrollContainerTag).performTouchInput { swipeUp() }
            compose.waitForIdle()
        }
        compose.onNodeWithTag(targetTag).assertIsDisplayed()
    }

    private fun isDisplayed(tag: String): Boolean = runCatching {
        compose.onNodeWithTag(tag).assertIsDisplayed()
    }.isSuccess

    private fun showSoftwareKeyboard() {
        compose.activity.runOnUiThread {
            val decorView = compose.activity.window.decorView
            decorView.post {
                WindowCompat.getInsetsController(compose.activity.window, decorView)
                    .show(WindowInsetsCompat.Type.ime())
            }
        }
        compose.waitUntil(timeoutMillis = 5_000) {
            ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
                ?.isVisible(WindowInsetsCompat.Type.ime()) == true
        }
    }

    private fun hideSoftwareKeyboard() {
        compose.activity.runOnUiThread {
            WindowCompat.getInsetsController(compose.activity.window, compose.activity.window.decorView)
                .hide(WindowInsetsCompat.Type.ime())
        }
        compose.waitUntil(timeoutMillis = 5_000) {
            ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
                ?.isVisible(WindowInsetsCompat.Type.ime()) != true
        }
    }

}
