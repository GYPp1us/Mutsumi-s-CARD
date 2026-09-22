package com.mutsumi.card

import android.content.pm.ActivityInfo
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mutsumi.card.draw.DrawCameraCenterXKey
import com.mutsumi.card.draw.DrawMarkdownOffsetXKey
import com.mutsumi.card.draw.DrawMarkdownWidthKey
import com.mutsumi.card.draw.DrawLayerStyleKey
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LandscapeDrawFlowTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun Markdown预览连续移动只影响选中面且边框与工具一致() {
        openLandscapeDrawEditor()
        compose.onNodeWithTag("draw-tool-markdown").performClick()
        compose.onNodeWithTag("draw-markdown-front").performTextInput("# 位置测试")
        hideSoftwareKeyboard()
        compose.onNodeWithTag("draw-toggle-markdown").performClick()
        val front = compose.onNodeWithTag("drawing-canvas-front")
        val back = compose.onNodeWithTag("drawing-canvas-back")
        val camera = front.fetchSemanticsNode().config[DrawCameraCenterXKey]
        front.performTouchInput {
            down(center)
            repeat(8) { moveTo(center + Offset((it + 1) * 5f, 0f), 16) }
            up()
        }
        assertTrue(front.fetchSemanticsNode().config[DrawMarkdownOffsetXKey] > 0f)
        assertTrue(back.fetchSemanticsNode().config[DrawMarkdownOffsetXKey] == 0f)
        assertTrue(front.fetchSemanticsNode().config[DrawCameraCenterXKey] == camera)
        assertTrue(front.fetchSemanticsNode().config[DrawMarkdownWidthKey] == 256)
        assertTrue(compose.onNodeWithTag("draw-tool-markdown").fetchSemanticsNode().config[DrawLayerStyleKey] == "虚线")
        assertTrue(compose.onNodeWithTag("draw-face-front").fetchSemanticsNode().config[DrawLayerStyleKey] == "虚线")
    }

    @Test
    fun Markdown双指缩放重新排版且三种工具线型可区分() {
        openLandscapeDrawEditor()
        compose.onNodeWithTag("draw-tool-markdown").performClick()
        compose.onNodeWithTag("draw-markdown-front").performTextInput("# 缩放测试\n\n双指改变字号和换行。")
        hideSoftwareKeyboard()
        compose.onNodeWithTag("draw-toggle-markdown").performClick()
        val front = compose.onNodeWithTag("drawing-canvas-front")
        front.performTouchInput {
            val gap = width * 0.1f
            down(0, center - Offset(gap, 0f))
            down(1, center + Offset(gap, 0f))
            repeat(8) { index ->
                val next = gap * (1f + (index + 1) * 0.1f)
                updatePointerTo(0, center - Offset(next, 0f))
                updatePointerTo(1, center + Offset(next, 0f))
                move(16)
            }
            up(0); up(1)
        }
        assertTrue(front.fetchSemanticsNode().config[DrawMarkdownWidthKey] < 180)
        compose.onNodeWithTag("draw-tool-base").performClick()
        assertTrue(compose.onNodeWithTag("draw-face-front").fetchSemanticsNode().config[DrawLayerStyleKey] == "双线")
        assertTrue(compose.onNodeWithTag("draw-tool-base").fetchSemanticsNode().config[DrawLayerStyleKey] == "双线")
        compose.onNodeWithTag("draw-tool-pen").performClick()
        assertTrue(compose.onNodeWithTag("draw-face-front").fetchSemanticsNode().config[DrawLayerStyleKey] == "单实线")
    }

    @Test
    fun 双面录入在横屏下切换Markdown绘图并保存到学习卡片() {
        openLandscapeDrawEditor()
        compose.onNodeWithTag("drawing-canvas-back").assertIsDisplayed()

        compose.onNodeWithTag("draw-tool-markdown").performClick()
        compose.onNodeWithTag("draw-markdown-front").assertIsDisplayed()
        compose.onAllNodesWithTag("draw-markdown-back").assertCountEquals(0)

        compose.onNodeWithTag("draw-face-selector-back").performClick()
        compose.onNodeWithTag("draw-markdown-back").assertIsDisplayed()
        compose.onAllNodesWithTag("draw-markdown-front").assertCountEquals(0)

        compose.onNodeWithTag("draw-tool-pen").performClick()
        compose.onNodeWithTag("drawing-canvas-front").assertIsDisplayed()
        compose.onNodeWithTag("drawing-canvas-back").performTouchInput {
            down(center)
            moveTo(center + Offset(60f, 28f), 40)
            moveTo(center + Offset(130f, 4f), 40)
            up()
        }

        compose.onNodeWithTag("draw-key-input").performClick().performTextInput("横屏双面测试")
        compose.onNodeWithTag("draw-key-input").performImeAction()
        compose.onNodeWithTag("draw-key-lock").performClick()
        compose.onNodeWithTag("save-card").performClick()
        waitUntilNodeExists("study-card")
        compose.onNodeWithTag("study-card").performTouchInput { swipeRight() }
    }

    @Test
    fun 移动工具连续拖动会累计更新相机() {
        openLandscapeDrawEditor()
        val canvas = compose.onNodeWithTag("drawing-canvas-front")
        canvas.performTouchInput {
            down(center)
            moveTo(center + Offset(48f, 0f), 60)
            up()
        }
        val before = canvas.fetchSemanticsNode().config[DrawCameraCenterXKey]

        compose.onNodeWithTag("draw-tool-move").performClick()
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
        openLandscapeDrawEditor()
        compose.onNodeWithTag("draw-tool-markdown").performClick()
        compose.onNodeWithTag("draw-markdown-front")
            .performTextInput("# 标题\n\n${'$'}E = mc^2${'$'}")
        hideSoftwareKeyboard()
        compose.onNodeWithTag("draw-toggle-markdown").performScrollTo().performClick()

        compose.onAllNodesWithTag("draw-markdown-front").assertCountEquals(0)
        waitUntilNodeExists("drawing-canvas-front")
        compose.onNodeWithTag("drawing-canvas-front").assertIsDisplayed()
        compose.onNodeWithTag("save-card").assertIsDisplayed()
    }

    @Test
    fun 锁定收起属性栏并均分空间且解锁保留内容() {
        openLandscapeDrawEditor()
        compose.onNodeWithTag("draw-key-input").performTextInput("保留这个 key")
        compose.onNodeWithTag("draw-key-input").performImeAction()
        hideSoftwareKeyboard()
        val front = compose.onNodeWithTag("draw-face-front")
        val back = compose.onNodeWithTag("draw-face-back")
        val frontBefore = front.fetchSemanticsNode().boundsInRoot.width
        val backBefore = back.fetchSemanticsNode().boundsInRoot.width
        compose.onNodeWithTag("draw-key-lock").performClick()
        compose.waitForIdle()
        compose.onAllNodesWithTag("draw-key-input").assertCountEquals(0)
        assertTrue(front.fetchSemanticsNode().boundsInRoot.width > frontBefore + 20f)
        assertTrue(back.fetchSemanticsNode().boundsInRoot.width > backBefore + 20f)
        compose.onNodeWithTag("save-card").assertIsDisplayed()
        compose.onNodeWithContentDescription("解锁文字 key").assertIsDisplayed()
        compose.onNodeWithTag("draw-tool-markdown").performClick()
        compose.onNodeWithTag("draw-markdown-front").performTextInput("# 保留文档")
        hideSoftwareKeyboard()
        compose.onNodeWithTag("draw-toggle-markdown").performScrollTo().performClick()
        compose.onNodeWithTag("draw-md-larger").performScrollTo().performClick()
        assertTrue(compose.onNodeWithTag("drawing-canvas-front").fetchSemanticsNode().config[DrawMarkdownWidthKey] < 256)
        compose.onNodeWithContentDescription("解锁文字 key").performClick()
        compose.onNodeWithTag("draw-key-input").assertTextContains("保留这个 key")
        compose.onNodeWithTag("draw-toggle-markdown").performScrollTo().performClick()
        compose.onNodeWithTag("draw-markdown-front").assertTextContains("# 保留文档")
    }

    @Test
    fun key输入框可锁定且保留清晰的解锁入口() {
        compose.onNodeWithTag("nav-draw").performClick()
        waitUntilNodeExists("draw-key-input")

        compose.onNodeWithContentDescription("锁定文字 key").performClick()

        compose.onNodeWithContentDescription("解锁文字 key").assertIsDisplayed()
    }

    @Test
    fun 进入录入页会请求传感器横屏() {
        compose.onNodeWithTag("nav-draw").performClick()
        compose.waitUntil(timeoutMillis = 12_000) {
            compose.activity.requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        waitUntilNodeExists("drawing-canvas-front")
    }

    @After
    fun 恢复系统方向() {
        compose.activity.runOnUiThread {
            compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    private fun openLandscapeDrawEditor() {
        compose.activity.runOnUiThread {
            compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        compose.waitForIdle()
        compose.onNodeWithTag("nav-draw").performClick()
        waitUntilNodeExists("drawing-canvas-front")
    }

    private fun hideSoftwareKeyboard() {
        compose.activity.runOnUiThread {
            val decorView = compose.activity.window.decorView
            WindowCompat.getInsetsController(compose.activity.window, decorView)
                .hide(WindowInsetsCompat.Type.ime())
        }
        compose.waitUntil(timeoutMillis = 5_000) {
            ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
                ?.isVisible(WindowInsetsCompat.Type.ime()) != true
        }
    }

    private fun waitUntilNodeExists(tag: String) {
        compose.waitUntil(timeoutMillis = 12_000) {
            compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }
}
