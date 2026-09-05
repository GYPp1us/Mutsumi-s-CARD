package com.mutsumi.card

import android.graphics.Bitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class MutsumiCardUiTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private fun capture(name: String) = captureDevice(name)

    private fun captureDevice(name: String) {
        val root = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)
            ?: error("External files directory is unavailable")
        val file = File(root, "agent-screenshots/$name.png")
        file.parentFile?.mkdirs()
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            ?: error("Could not capture device screenshot $name")
        try {
            file.outputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    "Could not write device screenshot $name"
                }
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun showSoftwareKeyboard() {
        val activity = composeRule.activity
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            WindowCompat.getInsetsController(activity.window, activity.window.decorView)
                .show(WindowInsetsCompat.Type.ime())
        }
    }

    private fun hideSoftwareKeyboard() {
        val activity = composeRule.activity
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            WindowCompat.getInsetsController(activity.window, activity.window.decorView)
                .hide(WindowInsetsCompat.Type.ime())
        }
    }

    @Test
    fun coreScreensAreInteractiveAndCapturable() {
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithTag("study-card").fetchSemanticsNodes().isNotEmpty()
        }
        capture("01-study-initial")

        composeRule.onNodeWithTag("nav-cards").assertIsDisplayed().performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithTag("card-list-item").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitForIdle()
        capture("02-cards")
        composeRule.onAllNodesWithTag("card-list-item")[0].performClick()
        composeRule.onNodeWithContentDescription("编辑 key").assertIsDisplayed().performClick()
        capture("02b-cards-key-edit")
        composeRule.onNodeWithTag("key 编辑输入").performClick().performTextInput("测试")
        showSoftwareKeyboard()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("key 编辑输入").assertIsDisplayed()
        composeRule.onNodeWithTag("save-card-key").assertIsDisplayed()
        capture("02c-cards-key-ime")
        captureDevice("02d-cards-key-ime-device")
        composeRule.onNodeWithText("取消").performClick()
        hideSoftwareKeyboard()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("nav-aibatch").assertIsDisplayed().performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithTag("ai-source-pane").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitForIdle()
        capture("03-ai-workflow")

        composeRule.onNodeWithTag("nav-draw").assertIsDisplayed().performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithTag("drawing-canvas-front").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("draw-key-lock").assertIsDisplayed().performClick()
        capture("04-draw-key-lock")

        composeRule.onNodeWithTag("nav-settings").assertIsDisplayed().performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithTag("app-update-settings").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("ai-settings-model").performScrollTo().assertIsDisplayed()
        capture("05-settings-update")

        composeRule.onNodeWithTag("nav-study").assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("study-card").assertIsDisplayed()
        capture("06-study-card")

        composeRule.onNodeWithTag("study-card").performTouchInput { swipeLeft() }
        composeRule.waitForIdle()
        capture("07-study-feedback")
    }
}
