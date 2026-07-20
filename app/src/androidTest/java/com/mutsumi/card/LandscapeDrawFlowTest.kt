package com.mutsumi.card

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

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.waitUntilNodeExists(tag: String) {
        waitUntil(timeoutMillis = 12_000) {
            onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }
}
