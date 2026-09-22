package com.mutsumi.card

import android.graphics.Bitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import com.mutsumi.card.draw.DrawMarkdownReadyKey
import org.junit.Rule
import org.junit.Test
import java.io.File

/** 同一验收场景由外部配置不同显示尺寸与字体比例，保存真实 Android 截图。 */
class DrawingVisualAuditTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun 图层边框与横屏布局截图验收() {
        compose.onNodeWithTag("nav-draw").performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("drawing-canvas-front").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("save-card").assertIsDisplayed()
        capture("绘制")
        compose.onNodeWithTag("draw-tool-markdown").performClick()
        compose.onNodeWithTag("draw-markdown-front").performTextInput(
            "# 间隔重复\n\n把知识变成自己的记忆。\n\n## 三个步骤\n\n1. 阅读与理解\n2. 主动回忆\n3. 间隔复习\n\n${'$'}E=mc^2${'$'}\n\n| 今天 | 明天 |\n|---|---|\n| 理解 | 回顾 |",
        )
        compose.activity.runOnUiThread {
            WindowCompat.getInsetsController(compose.activity.window, compose.activity.window.decorView)
                .hide(WindowInsetsCompat.Type.ime())
        }
        compose.waitForIdle()
        compose.onNodeWithTag("draw-toggle-markdown").performClick()
        compose.waitUntil(30_000) {
            compose.onNodeWithTag("drawing-canvas-front").fetchSemanticsNode().config[DrawMarkdownReadyKey]
        }
        compose.onNodeWithTag("save-card").assertIsDisplayed()
        capture("文档")
        compose.onNodeWithTag("draw-key-lock").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("save-card").assertIsDisplayed()
        capture("锁定文档")
        compose.onNodeWithTag("draw-tool-base").performClick()
        compose.onNodeWithTag("save-card").assertIsDisplayed()
        capture("底图")
        compose.onNodeWithTag("draw-tool-pen").performClick()
        capture("锁定绘制")
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        android.os.SystemClock.sleep(350) // 等待 Surface 提交最后一帧动画后再截取屏幕。
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        val directory = requireNotNull(instrumentation.targetContext.getExternalFilesDir("ui-audit"))
        val prefix = InstrumentationRegistry.getArguments().getString("auditName", "默认")
        try {
            File(directory, "$prefix-$name.png").outputStream().use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally { bitmap.recycle() }
    }
}
