package com.mutsumi.card.backup

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import android.graphics.Bitmap
import com.mutsumi.card.ui.theme.MutsumiCardTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class CloudSyncUiTest {
    @get:Rule val compose = createComposeRule()
    private fun preview(mode: CloudSyncMode, count: Int = 1) = CloudSyncPreview("test", mode,
        CloudSyncPlan(CloudSnapshotDocument(snapshot = CloudSnapshot(emptyList(), emptyList(), emptyList()), images = emptyList()),
            (1..count).map { CloudSyncEntry("card-$it", CloudConflictKind.Card,
                if (mode.deletes) CloudSyncChange.Delete else CloudSyncChange.Add, "测试卡片 $it", "数学 / 微积分", 1, 2, false,
                seenCount = 3, doubleSided = true, imageBytes = 12345) }), 100, 12345)

    @Test fun 删除短滑回位完整滑动后只能提交一次() {
        var calls = 0
        var busy by mutableStateOf(false)
        compose.setContent { MutsumiCardTheme {
            CloudSyncPreviewDialog(preview(CloudSyncMode.PullDelete), busy, null, {}, { calls++; busy = true })
        } }
        compose.onNodeWithTag("confirm-sync-delete").performTouchInput {
            down(Offset(4f, centerY)); moveTo(Offset(width * 0.35f, centerY), 250); up()
        }
        compose.runOnIdle { assertEquals(0, calls) }
        compose.onNodeWithTag("confirm-sync-delete").performTouchInput {
            down(Offset(4f, centerY)); moveTo(Offset(width - 3f, centerY), 450); up()
        }
        compose.runOnIdle { assertEquals(1, calls) }
        compose.onNodeWithTag("confirm-sync-delete").performTouchInput { swipeRight() }
        compose.runOnIdle { assertEquals(1, calls) }
        screenshot("删除预览")
    }

    @Test fun 增量操作先展示方向和元数据点击执行才提交() {
        var calls = 0
        compose.setContent { MutsumiCardTheme { CloudSyncPreviewDialog(preview(CloudSyncMode.Push), false, null, {}, { calls++ }) } }
        compose.onNodeWithText("增量推送预览").assertIsDisplayed()
        compose.onNodeWithText("本地 → 云端", substring = true).assertIsDisplayed()
        compose.onNodeWithText("复习 3 次", substring = true).assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, calls) }
        screenshot("增量预览")
        compose.onNodeWithText("执行增量推送", substring = true).performClick()
        compose.runOnIdle { assertEquals(1, calls) }
    }

    @Test fun 预览按四十项渐进加载且仍可搜索尾页记录() {
        compose.setContent { MutsumiCardTheme { CloudSyncPreviewDialog(preview(CloudSyncMode.Pull, 85), false, null, {}, {}) } }
        compose.onNodeWithText("40/85").assertIsDisplayed()
        compose.onNodeWithTag("cloud-sync-preview-list").performScrollToIndex(39)
        compose.waitUntil(5000) { compose.onAllNodesWithText("80/85").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("cloud-sync-preview-list").performScrollToIndex(79)
        compose.waitUntil(5000) { compose.onAllNodesWithText("85/85").fetchSemanticsNodes().isNotEmpty() }
        val search = compose.onNode(hasSetTextAction())
        search.performClick()
        search.performTextInput("测试卡片 85")
        search.performImeAction()
        compose.onNodeWithText("1/1").assertIsDisplayed()
        compose.waitUntil(5000) { compose.onNode(hasText("测试卡片 85") and !hasSetTextAction()).isDisplayed() }
    }

    @Test fun 同步期间可见进度且执行控件不会重复出现() {
        compose.setContent { MutsumiCardTheme { CloudSyncPreviewDialog(preview(CloudSyncMode.Pull), true,
            CloudSyncProgress("下载并校验卡面", 4, 10, 400, 1000), {}, {}) } }
        compose.onNodeWithTag("sync-progress").assertIsDisplayed()
        compose.onNodeWithText("下载并校验卡面", substring = true).assertIsDisplayed()
        compose.onAllNodesWithText("执行增量拉取", substring = true).assertCountEquals(0)
        screenshot("同步进度")
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val metrics = context.resources.displayMetrics
        val dir = requireNotNull(context.getExternalFilesDir("v010-audit")).apply { mkdirs() }
        val image = instrumentation.uiAutomation.takeScreenshot()
        val file = java.io.File(dir, "$name-${metrics.widthPixels}x${metrics.heightPixels}-${context.resources.configuration.fontScale}.png")
        file.outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        image.recycle()
    }
}
