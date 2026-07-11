package com.mutsumi.card.draw.model

data class DrawingHistory(
    val document: DrawingDocument = DrawingDocument(),
    private val undoStack: List<DrawingDocument> = emptyList(),
    private val redoStack: List<DrawingDocument> = emptyList(),
) {
    val canUndo: Boolean
        get() = undoStack.isNotEmpty()

    val canRedo: Boolean
        get() = redoStack.isNotEmpty()

    fun commit(next: DrawingDocument): DrawingHistory {
        if (document.contentEquals(next)) return this
        return DrawingHistory(
            document = next,
            undoStack = undoStack + document,
            redoStack = emptyList(),
        )
    }

    fun undo(): DrawingHistory {
        if (!canUndo) return this
        return DrawingHistory(
            document = undoStack.last(),
            undoStack = undoStack.dropLast(1),
            redoStack = listOf(document) + redoStack,
        )
    }

    fun redo(): DrawingHistory {
        if (!canRedo) return this
        return DrawingHistory(
            document = redoStack.first(),
            undoStack = undoStack + document,
            redoStack = redoStack.drop(1),
        )
    }
}

private fun DrawingDocument.contentEquals(other: DrawingDocument): Boolean =
    strokes == other.strokes && when {
        baseImage == null -> other.baseImage == null
        other.baseImage == null -> false
        else -> baseImage.contentEquals(other.baseImage)
    }
