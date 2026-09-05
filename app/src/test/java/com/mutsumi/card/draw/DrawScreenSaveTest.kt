package com.mutsumi.card.draw

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.IOException

class DrawScreenSaveTest {
    @Test
    fun 保存失败时不会清空草稿() = runTest {
        val failure = IOException("磁盘已满")
        var clearCalls = 0

        val thrown = runCatching {
            persistDrawnCard(
                onSave = { throw failure },
                onPersisted = { clearCalls += 1 },
                onRejected = { error("保存异常不应被转换为拒绝结果：$it") },
            )
        }.exceptionOrNull()

        assertThat(thrown).isSameInstanceAs(failure)
        assertThat(clearCalls).isEqualTo(0)
    }

    @Test
    fun 保存成功后才清空草稿() = runTest {
        var message: String? = null

        persistDrawnCard(
            onSave = { DrawSaveResult.Saved("卡片已保存") },
            onPersisted = { message = it },
            onRejected = { error("保存成功不应被拒绝：$it") },
        )

        assertThat(message).isEqualTo("卡片已保存")
    }

    @Test
    fun 被拒绝保存时不会清空草稿() = runTest {
        var clearCalls = 0
        var message: String? = null

        persistDrawnCard(
            onSave = { DrawSaveResult.Rejected("当前没有可用卡组") },
            onPersisted = { clearCalls += 1 },
            onRejected = { message = it },
        )

        assertThat(clearCalls).isEqualTo(0)
        assertThat(message).isEqualTo("当前没有可用卡组")
    }
}
