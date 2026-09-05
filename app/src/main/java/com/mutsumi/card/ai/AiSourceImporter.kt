package com.mutsumi.card.ai

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.IOException

data class AiSourceImportIssue(
    val relativePath: String,
    val reason: String,
) {
    val displayText: String
        get() = "$relativePath：$reason"
}

data class AiSourceImportResult(
    val files: List<ImportedAiFile>,
    val issues: List<AiSourceImportIssue>,
)

/** 导入缓存只保留可供工作流选择的有限文本，防止大型文件夹把内存耗尽。 */
data class AiSourceImportLimits(
    val maxFiles: Int = 500,
    val maxCharactersPerFile: Int = 100_000,
    val maxTotalCharacters: Int = 1_000_000,
) {
    init {
        require(maxFiles > 0) { "最大文件数必须大于零" }
        require(maxCharactersPerFile > 0) { "单文件字符上限必须大于零" }
        require(maxTotalCharacters > 0) { "素材总字符上限必须大于零" }
    }
}

internal class AiSourceImportBudget(
    private val limits: AiSourceImportLimits,
    initialImportedFiles: Int = 0,
    initialImportedCharacters: Int = 0,
) {
    private var importedFiles = initialImportedFiles
    private var importedCharacters = initialImportedCharacters

    init {
        require(initialImportedFiles >= 0) { "已有素材数量不能为负数" }
        require(initialImportedCharacters >= 0) { "已有素材字符数不能为负数" }
    }

    val maximumReadableCharacters: Int
        get() = when {
            importedFiles >= limits.maxFiles -> 0
            else -> minOf(limits.maxCharactersPerFile, limits.maxTotalCharacters - importedCharacters)
                .coerceAtLeast(0)
        }

    fun rejectionReasonFor(contentLength: Int): String? = when {
        importedFiles >= limits.maxFiles -> "素材库文件数量最多为 ${limits.maxFiles} 份"
        contentLength > limits.maxCharactersPerFile -> "单个文件超过 ${limits.maxCharactersPerFile} 字符上限"
        contentLength > limits.maxTotalCharacters - importedCharacters ->
            "素材库内容总量超过 ${limits.maxTotalCharacters} 字符上限"
        else -> null
    }

    fun record(contentLength: Int) {
        require(contentLength > 0) { "导入内容不能为空" }
        require(rejectionReasonFor(contentLength) == null) { "导入素材超过缓存上限" }
        importedFiles += 1
        importedCharacters += contentLength
    }
}

/** 通过 SAF 导入文本笔记，并将每个可恢复的读取失败保留为带相对路径的反馈。 */
class AiSourceImporter(
    private val context: Context,
    private val limits: AiSourceImportLimits = AiSourceImportLimits(),
) {
    private val resolver = context.contentResolver

    fun readDocuments(
        uris: List<Uri>,
        existingFiles: List<ImportedAiFile> = emptyList(),
    ): AiSourceImportResult {
        val session = ImportSession(limits, existingFiles)
        for (uri in uris) {
            if (session.isAlreadyImported(uri.toString())) continue
            if (!session.canReadAnotherFile()) {
                session.reportCapacityReached()
                break
            }
            val document = DocumentFile.fromSingleUri(context, uri)
            val name = document?.name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "未命名.txt"
            readFile(uri, name, name, session)
        }
        return session.result()
    }

    fun readTree(
        treeUri: Uri,
        existingFiles: List<ImportedAiFile> = emptyList(),
    ): AiSourceImportResult {
        val root = DocumentFile.fromTreeUri(context, treeUri)
            ?: throw AiWorkflowException("无法打开所选文件夹")
        val session = ImportSession(limits, existingFiles)

        fun visit(node: DocumentFile, prefix: String) {
            if (!session.canReadAnotherFile()) {
                session.reportCapacityReached()
                return
            }
            val children = try {
                node.listFiles().sortedBy { it.name.orEmpty() }
            } catch (error: SecurityException) {
                session.issues += AiSourceImportIssue(
                    relativePath = prefix.ifBlank { "所选文件夹" },
                    reason = error.message ?: "没有读取权限",
                )
                return
            }
            children.forEach { child ->
                if (!child.isDirectory && session.isAlreadyImported(child.uri.toString())) return@forEach
                if (!session.canReadAnotherFile()) {
                    session.reportCapacityReached()
                    return@forEach
                }
                val name = child.name
                if (name.isNullOrBlank()) {
                    session.issues += AiSourceImportIssue(prefix.ifBlank { "未命名文件" }, "无法获取文件名")
                } else if (child.isDirectory) {
                    visit(child, "$prefix$name/")
                } else if (isSupported(name)) {
                    readFile(child.uri, "$prefix$name", name, session)
                }
            }
        }

        visit(root, "")
        return session.result()
    }

    private fun readFile(
        uri: Uri,
        relativePath: String,
        name: String,
        session: ImportSession,
    ) {
        if (!isSupported(name)) {
            session.issues += AiSourceImportIssue(relativePath, "仅支持 .txt、.md、.markdown")
            return
        }
        if (session.isAlreadyImported(uri.toString())) return
        val maximumCharacters = session.budget.maximumReadableCharacters
        if (maximumCharacters == 0) {
            session.reportCapacityReached()
            return
        }
        try {
            val content = resolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
                reader.readAtMost(maximumCharacters)
            }
                ?: throw IOException("内容提供方没有返回可读取的数据")
            val limitReason = session.budget.rejectionReasonFor(content.length)
            when {
                limitReason != null -> session.issues += AiSourceImportIssue(relativePath, limitReason)
                content.isBlank() -> session.issues += AiSourceImportIssue(relativePath, "文件内容为空")
                else -> {
                    session.recordImported(uri.toString(), content.length)
                    session.files += ImportedAiFile(name = relativePath, content = content, id = uri.toString())
                }
            }
        } catch (error: SecurityException) {
            session.issues += AiSourceImportIssue(relativePath, error.message ?: "没有读取权限")
        } catch (error: IOException) {
            session.issues += AiSourceImportIssue(relativePath, error.message ?: "读取失败")
        }
    }

    private fun isSupported(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in SUPPORTED_EXTENSIONS

    private fun java.io.BufferedReader.readAtMost(maximumCharacters: Int): String {
        val result = StringBuilder(minOf(maximumCharacters + 1, READ_BUFFER_SIZE))
        val buffer = CharArray(minOf(READ_BUFFER_SIZE, maximumCharacters + 1))
        while (result.length <= maximumCharacters) {
            val remaining = maximumCharacters + 1 - result.length
            val read = read(buffer, 0, minOf(buffer.size, remaining))
            if (read < 0) break
            result.append(buffer, 0, read)
        }
        return result.toString()
    }

    private class ImportSession(
        limits: AiSourceImportLimits,
        existingFiles: List<ImportedAiFile>,
    ) {
        val budget = AiSourceImportBudget(
            limits = limits,
            initialImportedFiles = existingFiles.size,
            initialImportedCharacters = existingFiles.sumOf { it.content.length },
        )
        val files = mutableListOf<ImportedAiFile>()
        val issues = mutableListOf<AiSourceImportIssue>()
        private val importedIds = existingFiles.mapTo(mutableSetOf(), ImportedAiFile::id)
        private var capacityReported = false

        fun canReadAnotherFile(): Boolean = budget.maximumReadableCharacters > 0

        fun isAlreadyImported(id: String): Boolean = id in importedIds

        fun recordImported(id: String, contentLength: Int) {
            budget.record(contentLength)
            importedIds += id
        }

        fun reportCapacityReached() {
            if (!capacityReported) {
                issues += AiSourceImportIssue("素材库", "已达到导入缓存上限，未继续读取其余文件")
                capacityReported = true
            }
        }

        fun result(): AiSourceImportResult = AiSourceImportResult(files, issues)
    }

    private companion object {
        val SUPPORTED_EXTENSIONS = setOf("txt", "md", "markdown")
        const val READ_BUFFER_SIZE = 8_192
    }
}
