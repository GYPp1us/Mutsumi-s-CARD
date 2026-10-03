package com.mutsumi.card.draw

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

class DrawingDraftStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun 双面图层与相机完整往返且新实例可以恢复() {
        val file = temporary.newFolder().resolve("draft.zip")
        val face = DraftFaceData(
            strokes = listOf(DraftStrokeData(listOf(DraftPointData(12f, -30f)), -1, 4f)),
            baseImagePresent = true, baseImageBytes = byteArrayOf(1,2,3),
            baseRect = DraftRectData(-50f, 60f, 1024f, 1624f),
            camera = DraftCameraData(2f, -300f, 400f), markdown = "# 未完成公式",
            markdownWidth = 180f, markdownX = 32f, markdownY = -9f,
        )
        val project = DrawingDraftProject(deckId = 9, keyText = "草稿", front = face, back = face.copy(markdown = "背面"))
        DrawingDraftStore(file).save(project)
        val restored = requireNotNull(DrawingDraftStore(file).load())
        assertThat(restored.sameContent(project)).isTrue()
        assertThat(restored.back.markdown).isEqualTo("背面")
        assertThat(restored.front.baseImageBytes).isEqualTo(byteArrayOf(1,2,3))
    }

    @Test fun 写入失败保留上一份已同步草稿() {
        val file = temporary.newFolder().resolve("draft.zip")
        val original = DrawingDraftProject(keyText = "先前内容")
        DrawingDraftStore(file).save(original)
        val broken = DrawingDraftStore(file) { pending, _ ->
            pending.writeText("不完整写入")
            throw IOException("磁盘已满")
        }
        assertThat(runCatching { broken.save(original.copy(keyText = "未保存")) }.exceptionOrNull()).isInstanceOf(IOException::class.java)
        assertThat(requireNotNull(DrawingDraftStore(file).load()).keyText).isEqualTo("先前内容")
    }

    @Test fun 工具与锁定变化不要求重存但修改内容要求重存() {
        val saved = DrawingDraftProject(keyText = "同一张卡片")
        assertThat(saved.sameContent(saved.copy(keyLocked = true, tool = "Markdown", penWidth = 18f))).isTrue()
        assertThat(saved.sameContent(saved.copy(back = DraftFaceData(markdown = "新增内容")))).isFalse()
        assertThat(saved.sameContent(saved.copy(deckId = 4))).isFalse()
    }

    @Test fun 删除后不再恢复且空草稿可保存() {
        val store = DrawingDraftStore(temporary.newFolder().resolve("draft.zip"))
        store.save(DrawingDraftProject())
        assertThat(store.load()).isNotNull()
        store.clear()
        assertThat(store.load()).isNull()
    }
}
