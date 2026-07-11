package com.mutsumi.card.draw

import com.google.common.truth.Truth.assertThat
import com.mutsumi.card.draw.model.CanvasPoint
import com.mutsumi.card.draw.model.DrawingDocument
import com.mutsumi.card.draw.model.DrawingHistory
import com.mutsumi.card.draw.model.DrawingStroke
import org.junit.Test

class DrawingDocumentTest {
    @Test
    fun 提交笔迹后可以撤销并重做() {
        val first = stroke(CanvasPoint(10f, 10f), color = 0xFF112233.toInt())
        val second = stroke(CanvasPoint(20f, 20f), color = 0xFF445566.toInt())
        val withFirst = DrawingDocument().appendStroke(first)
        val withSecond = withFirst.appendStroke(second)
        val history = DrawingHistory().commit(withFirst).commit(withSecond)

        val undone = history.undo()
        val redone = undone.redo()

        assertThat(undone.document.strokes).containsExactly(first)
        assertThat(undone.canRedo).isTrue()
        assertThat(redone.document.strokes).containsExactly(first, second).inOrder()
        assertThat(redone.canUndo).isTrue()
    }

    @Test
    fun 新提交会清除重做分支() {
        val first = DrawingDocument().appendStroke(stroke(CanvasPoint(10f, 10f)))
        val second = first.appendStroke(stroke(CanvasPoint(20f, 20f)))
        val replacement = first.appendStroke(stroke(CanvasPoint(30f, 30f)))

        val branched = DrawingHistory().commit(first).commit(second).undo().commit(replacement)

        assertThat(branched.canRedo).isFalse()
        assertThat(branched.document).isEqualTo(replacement)
    }

    @Test
    fun 橡皮只删除命中笔迹并保留底图() {
        val baseImage = byteArrayOf(1, 2, 3)
        val hit = stroke(CanvasPoint(100f, 100f))
        val untouched = stroke(CanvasPoint(900f, 1800f))
        val document = DrawingDocument(
            baseImage = baseImage,
            strokes = listOf(hit, untouched),
        )

        val erased = document.eraseStrokesAt(CanvasPoint(105f, 105f), radius = 20f)

        assertThat(erased.strokes).containsExactly(untouched)
        assertThat(erased.baseImage).isEqualTo(baseImage)
    }

    @Test
    fun 仅底图属于可保存文档而空文档不可保存() {
        assertThat(DrawingDocument(baseImage = byteArrayOf(1)).hasContent).isTrue()
        assertThat(DrawingDocument().hasContent).isFalse()
    }

    @Test
    fun 添加笔迹时保留画布外原始坐标而不压到边框() {
        val outside = DrawingStroke(
            points = listOf(CanvasPoint(-100f, 100f), CanvasPoint(-100f, 1900f)),
            colorArgb = 0xFF000000.toInt(),
            width = 20f,
        )

        val document = DrawingDocument().appendStroke(outside)

        assertThat(document.strokes.single()).isEqualTo(outside)
    }

    private fun stroke(
        point: CanvasPoint,
        color: Int = 0xFF000000.toInt(),
    ) = DrawingStroke(
        points = listOf(point),
        colorArgb = color,
        width = 12f,
    )
}
