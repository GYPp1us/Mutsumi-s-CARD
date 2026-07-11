package com.mutsumi.card.draw

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import com.google.common.truth.Truth.assertThat
import com.mutsumi.card.draw.model.CanvasPoint
import com.mutsumi.card.draw.model.DrawingStroke
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
class DrawingViewModelTest {
    @Test
    fun ViewModel持有步骤工具文档历史和持久相机状态() {
        val viewModel = DrawingViewModel(saveCard = { _, _ -> 1L })
        val camera = CanvasCamera.initial(360f, 640f).copy(
            scale = 1.2f,
            offsetX = 100f,
            offsetY = 500f,
        ).clamp()
        val stroke = strokeAt(300f, 500f)

        viewModel.updateKey("线粒体")
        viewModel.enterCanvas()
        viewModel.selectTool(DrawingTool.Eraser)
        viewModel.updatePen(colorArgb = Color.BLUE, width = 24f)
        viewModel.updateCamera(camera)
        viewModel.appendStroke(stroke)
        viewModel.undo()
        viewModel.redo()

        val state = viewModel.state.value
        assertThat(state.keyText).isEqualTo("线粒体")
        assertThat(state.step).isEqualTo(DrawingStep.Canvas)
        assertThat(state.tool).isEqualTo(DrawingTool.Eraser)
        assertThat(state.penColorArgb).isEqualTo(Color.BLUE)
        assertThat(state.penWidth).isEqualTo(24f)
        assertThat(state.camera).isEqualTo(camera)
        assertThat(state.document.strokes).containsExactly(stroke)
        assertThat(state.canUndo).isTrue()
    }

    @Test
    fun 仅底图保存成功后才清空整个草稿() = runTest {
        var savedKey: String? = null
        var savedPng: ByteArray? = null
        val viewModel = DrawingViewModel { key, png ->
            savedKey = key
            savedPng = png
            42L
        }
        viewModel.updateKey("  叶绿体  ")
        viewModel.enterCanvas()
        viewModel.setBaseImage(solidPng(Color.GREEN))

        val cardId = viewModel.save()

        val decoded = BitmapFactory.decodeByteArray(savedPng, 0, savedPng!!.size)
        assertThat(cardId).isEqualTo(42L)
        assertThat(savedKey).isEqualTo("叶绿体")
        assertThat(decoded.width).isEqualTo(1024)
        assertThat(decoded.height).isEqualTo(2048)
        assertThat(viewModel.state.value.keyText).isEmpty()
        assertThat(viewModel.state.value.document.hasContent).isFalse()
        assertThat(viewModel.state.value.canUndo).isFalse()
        assertThat(viewModel.state.value.camera).isNull()
        assertThat(viewModel.state.value.step).isEqualTo(DrawingStep.Key)
        assertThat(viewModel.state.value.saveStatus).isEqualTo(DrawingSaveStatus.Saved(42L))
    }

    @Test
    fun 保存失败时抛出异常并完整保留草稿() = runTest {
        val failure = IOException("磁盘已满")
        val viewModel = DrawingViewModel { _, _ -> throw failure }
        val baseImage = solidPng(Color.RED)
        viewModel.updateKey("失败草稿")
        viewModel.setBaseImage(baseImage)
        viewModel.appendStroke(strokeAt(10f, 20f))
        val before = viewModel.state.value

        val thrown = runCatching { viewModel.save() }.exceptionOrNull()

        assertThat(thrown).isSameInstanceAs(failure)
        assertThat(viewModel.state.value.keyText).isEqualTo(before.keyText)
        assertThat(viewModel.state.value.document.strokes).isEqualTo(before.document.strokes)
        assertThat(viewModel.state.value.document.baseImage).isEqualTo(baseImage)
        assertThat(viewModel.state.value.canUndo).isEqualTo(before.canUndo)
        assertThat(viewModel.state.value.saveStatus)
            .isEqualTo(DrawingSaveStatus.Failed("磁盘已满"))
    }

    @Test
    fun 空文档拒绝保存且不会调用持久化() = runTest {
        var saveCalls = 0
        val viewModel = DrawingViewModel { _, _ ->
            saveCalls += 1
            1L
        }
        viewModel.updateKey("没有图片")

        val error = runCatching { viewModel.save() }.exceptionOrNull()

        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(error).hasMessageThat().contains("图片")
        assertThat(saveCalls).isEqualTo(0)
    }

    @Test
    fun 空key拒绝进入绘图和保存() = runTest {
        val viewModel = DrawingViewModel(saveCard = { _, _ -> 1L })
        viewModel.setBaseImage(solidPng(Color.RED))

        val enterError = runCatching { viewModel.enterCanvas() }.exceptionOrNull()
        val saveError = runCatching { viewModel.save() }.exceptionOrNull()

        assertThat(enterError).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(saveError).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(viewModel.state.value.step).isEqualTo(DrawingStep.Key)
    }

    @Test
    fun 保存挂起期间拒绝编辑且成功后清稿() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val viewModel = DrawingViewModel { _, _ ->
            started.complete(Unit)
            release.await()
            8L
        }
        viewModel.updateKey("挂起保存")
        viewModel.appendStroke(strokeAt(10f, 20f))

        val save = async { viewModel.save() }
        started.await()

        val editError = runCatching { viewModel.appendStroke(strokeAt(30f, 40f)) }.exceptionOrNull()
        assertThat(editError).isInstanceOf(IllegalStateException::class.java)
        assertThat(editError).hasMessageThat().contains("保存")
        assertThat(viewModel.state.value.document.strokes).hasSize(1)

        release.complete(Unit)
        assertThat(save.await()).isEqualTo(8L)
        assertThat(viewModel.state.value.document.hasContent).isFalse()
    }

    @Test
    fun 取消挂起保存后保留原稿并恢复可编辑() = runTest {
        val started = CompletableDeferred<Unit>()
        val neverComplete = CompletableDeferred<Unit>()
        val viewModel = DrawingViewModel { _, _ ->
            started.complete(Unit)
            neverComplete.await()
            9L
        }
        val original = strokeAt(10f, 20f)
        viewModel.updateKey("  取消保存  ")
        viewModel.appendStroke(original)

        val save = launch { viewModel.save() }
        started.await()
        save.cancelAndJoin()

        assertThat(viewModel.state.value.keyText).isEqualTo("  取消保存  ")
        assertThat(viewModel.state.value.document.strokes).containsExactly(original)
        assertThat(viewModel.state.value.saveStatus).isEqualTo(DrawingSaveStatus.Idle)
        viewModel.appendStroke(strokeAt(30f, 40f))
        assertThat(viewModel.state.value.document.strokes).hasSize(2)
    }

    private fun strokeAt(x: Float, y: Float) = DrawingStroke(
        points = listOf(CanvasPoint(x, y)),
        colorArgb = Color.BLACK,
        width = 12f,
    )

    private fun solidPng(color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(4, 8, Bitmap.Config.ARGB_8888).apply {
            eraseColor(color)
        }
        return ByteArrayOutputStream().use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            output.toByteArray()
        }
    }
}
