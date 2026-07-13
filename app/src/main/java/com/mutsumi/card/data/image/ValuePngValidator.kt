package com.mutsumi.card.data.image

import android.graphics.BitmapFactory
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.util.zip.CRC32
import java.util.zip.InflaterInputStream

fun interface PngDecoder {
    fun decodeSize(bytes: ByteArray): Pair<Int, Int>?
}

private object AndroidPngDecoder : PngDecoder {
    override fun decodeSize(bytes: ByteArray): Pair<Int, Int>? {
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        return (bitmap.width to bitmap.height).also { bitmap.recycle() }
    }
}

class ValuePngValidator(
    private val decoder: PngDecoder = AndroidPngDecoder,
) {
    fun validate(bytes: ByteArray) {
        require(bytes.size >= MINIMUM_PNG_SIZE && bytes.startsWith(PNG_SIGNATURE)) {
            "图片 value 必须是有效 PNG"
        }
        val parsed = parseChunks(bytes)
        require(parsed.width == WIDTH && parsed.height in ACCEPTED_HEIGHTS) {
            "图片 value 必须为竖向 1024×1624 PNG（兼容旧版 1024×2048）"
        }
        validatePixels(parsed)
        val decodedSize = decoder.decodeSize(bytes)
        require(decodedSize == parsed.width to parsed.height) { "图片 value 的 PNG 数据无法由系统解码" }
    }

    private fun parseChunks(bytes: ByteArray): ParsedPng {
        val input = DataInputStream(ByteArrayInputStream(bytes))
        input.skipBytes(PNG_SIGNATURE.size)
        var header: Header? = null
        var reachedEnd = false
        var chunkIndex = 0
        var idatStarted = false
        var idatEnded = false
        val compressed = ByteArrayOutputStream()
        while (input.available() > 0 && !reachedEnd) {
            require(input.available() >= CHUNK_OVERHEAD) { "PNG 数据被截断" }
            val length = input.readInt()
            require(length >= 0 && length <= input.available() - 8) { "PNG 块长度非法" }
            val type = ByteArray(4).also(input::readFully)
            val data = ByteArray(length).also(input::readFully)
            val expectedCrc = input.readInt().toUInt().toLong()
            val actualCrc = CRC32().apply {
                update(type)
                update(data)
            }.value
            require(actualCrc == expectedCrc) { "PNG 块校验失败" }

            when (type.toString(Charsets.US_ASCII)) {
                "IHDR" -> {
                    require(chunkIndex == 0 && header == null && data.size == 13) { "PNG 头部非法" }
                    header = DataInputStream(ByteArrayInputStream(data)).use { chunk ->
                        Header(
                            width = chunk.readInt(),
                            height = chunk.readInt(),
                            bitDepth = chunk.readUnsignedByte(),
                            colorType = chunk.readUnsignedByte(),
                            compression = chunk.readUnsignedByte(),
                            filter = chunk.readUnsignedByte(),
                            interlace = chunk.readUnsignedByte(),
                        )
                    }
                }
                "IDAT" -> {
                    require(header != null && !idatEnded) { "PNG 图像数据块不连续" }
                    idatStarted = true
                    compressed.write(data)
                }
                "IEND" -> {
                    require(idatStarted && data.isEmpty()) { "PNG 结束块非法" }
                    reachedEnd = true
                }
                else -> {
                    if (idatStarted) idatEnded = true
                    require(type[0].toInt() and 0x20 != 0) { "PNG 包含未知关键块" }
                }
            }
            chunkIndex += 1
        }
        require(reachedEnd && input.available() == 0) { "PNG 缺少结束块或包含尾随数据" }
        val resolvedHeader = requireNotNull(header) { "PNG 缺少头部" }
        require(compressed.size() > 0) { "PNG 缺少图像数据" }
        require(
            resolvedHeader.width > 0 &&
                resolvedHeader.height > 0 &&
                resolvedHeader.bitDepth == 8 &&
                resolvedHeader.colorType in SUPPORTED_COLOR_TYPES &&
                resolvedHeader.compression == 0 &&
                resolvedHeader.filter == 0 &&
                resolvedHeader.interlace == 0,
        ) { "PNG 编码格式不受支持" }
        return ParsedPng(resolvedHeader, compressed.toByteArray())
    }

    private fun validatePixels(png: ParsedPng) {
        val channels = if (png.colorType == COLOR_TYPE_RGB) 3 else 4
        val row = ByteArray(1 + png.width * channels)
        InflaterInputStream(ByteArrayInputStream(png.compressed)).use { inflated ->
            repeat(png.height) {
                var offset = 0
                while (offset < row.size) {
                    val count = inflated.read(row, offset, row.size - offset)
                    require(count > 0) { "PNG 图像数据被截断" }
                    offset += count
                }
                require(row[0].toInt() in 0..4) { "PNG 扫描行过滤器非法" }
            }
            require(inflated.read() == -1) { "PNG 图像数据长度非法" }
        }
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        prefix.indices.all { index -> this[index] == prefix[index] }

    private data class Header(
        val width: Int,
        val height: Int,
        val bitDepth: Int,
        val colorType: Int,
        val compression: Int,
        val filter: Int,
        val interlace: Int,
    )

    private data class ParsedPng(
        val header: Header,
        val compressed: ByteArray,
    ) {
        val width: Int get() = header.width
        val height: Int get() = header.height
        val colorType: Int get() = header.colorType
    }

    private companion object {
        const val WIDTH = 1024
        const val HEIGHT = 1624
        const val LEGACY_HEIGHT = 2048
        const val COLOR_TYPE_RGB = 2
        const val COLOR_TYPE_RGBA = 6
        const val CHUNK_OVERHEAD = 12
        const val MINIMUM_PNG_SIZE = 8 + CHUNK_OVERHEAD * 3 + 13
        val SUPPORTED_COLOR_TYPES = setOf(COLOR_TYPE_RGB, COLOR_TYPE_RGBA)
        val ACCEPTED_HEIGHTS = setOf(HEIGHT, LEGACY_HEIGHT)
        val PNG_SIGNATURE = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        )
    }
}
