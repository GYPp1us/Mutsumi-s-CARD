package com.mutsumi.card

import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mutsumi.card.draw.DrawCameraCenterXKey
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LandscapeDrawFlowTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun 双面录入在横屏下切换Markdown绘图并保存到学习卡片() {
        compose.onNodeWithTag("nav-draw").performClick()
        compose.waitUntilNodeExists("drawing-canvas-front")
        compose.onNodeWithTag("drawing-canvas-back").assertIsDisplayed()

        compose.onNodeWithText("MD").performClick()
        compose.onNodeWithTag("draw-markdown-front").assertIsDisplayed()
        compose.onAllNodesWithTag("draw-markdown-back").assertCountEquals(0)

        compose.onNodeWithTag("draw-face-selector-back").performClick()
        compose.onNodeWithTag("draw-markdown-back").assertIsDisplayed()
        compose.onAllNodesWithTag("draw-markdown-front").assertCountEquals(0)

        compose.onNodeWithText("笔").performClick()
        compose.onNodeWithTag("drawing-canvas-front").assertIsDisplayed()
        compose.onNodeWithTag("drawing-canvas-back").performTouchInput {
            down(center)
            moveTo(center + Offset(60f, 28f), 40)
            moveTo(center + Offset(130f, 4f), 40)
            up()
        }

        compose.onNodeWithTag("draw-key-input").performClick().performTextInput("横屏双面测试")
        compose.onNodeWithTag("draw-key-input").performImeAction()
        compose.onNodeWithTag("save-card").performClick()
        compose.waitUntilNodeExists("study-card")
        compose.onNodeWithTag("study-card").performTouchInput { swipeRight() }
    }

    @Test
    fun 移动工具连续拖动会累计更新相机() {
        compose.onNodeWithTag("nav-draw").performClick()
        compose.waitUntilNodeExists("drawing-canvas-front")
        val canvas = compose.onNodeWithTag("drawing-canvas-front")
        canvas.performTouchInput {
            down(center)
            moveTo(center + Offset(48f, 0f), 60)
            up()
        }
        val before = canvas.fetchSemanticsNode().config[DrawCameraCenterXKey]

        compose.onNodeWithText("移").performClick()
        canvas.performTouchInput {
            down(center)
            repeat(12) { index -> moveTo(center + Offset((index + 1) * 12f, 0f), 16) }
            up()
        }
        compose.waitUntil(timeoutMillis = 5_000) {
            canvas.fetchSemanticsNode().config[DrawCameraCenterXKey] < before - 500f
        }
        val after = canvas.fetchSemanticsNode().config[DrawCameraCenterXKey]

        assertTrue("连续拖动应累计更新相机，实际世界坐标位移为 ${before - after}", before - after > 500f)
    }

    @Test
    fun 非空Markdown进入预览后录入页保持可用() {
        compose.onNodeWithTag("nav-draw").performClick()
        compose.waitUntilNodeExists("drawing-canvas-front")
        compose.onNodeWithText("MD").performClick()
        compose.onNodeWithTag("draw-markdown-front")
            .performTextInput("# 标题\n\n${'$'}E = mc^2${'$'}")
        compose.activity.runOnUiThread {
            compose.activity.getSystemService(InputMethodManager::class.java)
                .hideSoftInputFromWindow(compose.activity.window.decorView.windowToken, 0)
        }
        compose.waitForIdle()
        compose.onNodeWithText("MD 预览").performClick()

        compose.waitUntilNodeExists("drawing-canvas-front")
        compose.onNodeWithTag("drawing-canvas-front").assertIsDisplayed()
        compose.onNodeWithTag("save-card").assertIsDisplayed()
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.waitUntilNodeExists(tag: String) {
        waitUntil(timeoutMillis = 12_000) {
            onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }
}
