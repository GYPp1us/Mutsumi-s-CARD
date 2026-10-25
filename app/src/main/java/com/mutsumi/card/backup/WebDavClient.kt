package com.mutsumi.card.backup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import okhttp3.Credentials
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

data class CloudRemoteFile(val bytes: ByteArray, val version: String?)

interface CloudRemoteStore {
    suspend fun ensureDirectories()
    suspend fun get(path: String): ByteArray?
    suspend fun getWithVersion(path: String): CloudRemoteFile?
    suspend fun put(path: String, bytes: ByteArray, contentType: String)
    /** 仅当远端资源仍为 [expectedVersion] 时原子发布；null 表示资源必须尚不存在。 */
    suspend fun putIfUnchanged(path: String, bytes: ByteArray, contentType: String, expectedVersion: String?): Boolean
    suspend fun delete(path: String)
}

class WebDavClient(
    private val config: CloudBackupConfig,
    client: OkHttpClient,
) : CloudRemoteStore {
    private val client = client.newBuilder().followRedirects(false).followSslRedirects(false).build()
    private val baseUrl = validateConfig(config)
    private val authorization = Credentials.basic(config.username, config.password, Charsets.UTF_8)
    private val rootSegments = config.remoteDirectory.split('/')

    override suspend fun ensureDirectories() {
        val accumulated = mutableListOf<String>()
        rootSegments.forEach { segment ->
            accumulated += segment
            mkcol(accumulated, includeRoot = false)
        }
        mkcol(listOf("snapshots"))
        mkcol(listOf("objects"))
        mkcol(listOf("library-v3"))
        mkcol(listOf("library-v3", "snapshots"))
        mkcol(listOf("library-v3", "objects"))
    }

    override suspend fun get(path: String): ByteArray? = getWithVersion(path)?.bytes

    override suspend fun getWithVersion(path: String): CloudRemoteFile? = execute(
        Request.Builder().url(resolve(path)).get().authorized().build(),
    ) { code, bytes, _ ->
        when (code) {
            200 -> CloudRemoteFile(bytes, sha256(bytes))
            404 -> null
            else -> throw responseError("读取", code)
        }
    }

    override suspend fun put(path: String, bytes: ByteArray, contentType: String) {
        val request = Request.Builder()
            .url(resolve(path))
            .put(bytes.toRequestBody(contentType.toMediaType()))
            .authorized()
            .build()
        execute(request) { code, _, _ ->
            if (code !in 200..204) throw responseError("上传", code)
        }
    }

    override suspend fun putIfUnchanged(
        path: String,
        bytes: ByteArray,
        contentType: String,
        expectedVersion: String?,
    ): Boolean {
        val lockPath = ".library-write-lock"
        val request = Request.Builder().url(resolve(lockPath)).method("MKCOL", null).authorized().build()
        withContext(NonCancellable + Dispatchers.IO) { execute(request) { code, _, _ ->
            when (code) {
                200, 201, 204 -> Unit
                405, 409, 423 -> throw CloudBackupException("另一项云端提交尚未完成，请稍后重试；若应用曾意外中断，请先处理遗留提交锁")
                else -> throw responseError("建立提交锁", code)
            }
        } }
        var published = false
        var failure: Throwable? = null
        try {
            // 真实 AList 会忽略条件请求头；目录锁内核对内容哈希，不能信任 ETag。
            if (getWithVersion(path)?.version != expectedVersion) return false
            put(path, bytes, contentType)
            published = true
            val written = get(path)
            if (written == null || !written.contentEquals(bytes)) throw CloudBackupException("云端提交回读不一致，请停止同步并保全现有数据")
            return true
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                try { delete(lockPath) }
                catch (cleanup: Exception) {
                    if (failure != null) failure.addSuppressed(cleanup)
                    else throw CloudBackupException(if (published) "索引已发布，但提交锁未能释放，请先处理连接状态" else "未发布索引，但提交锁未能释放", cleanup)
                }
            }
        }
    }

    override suspend fun delete(path: String) {
        val request = Request.Builder().url(resolve(path)).delete().authorized().build()
        execute(request) { code, _, _ ->
            if (code !in 200..204 && code != 404) throw responseError("删除", code)
        }
    }

    private suspend fun mkcol(segments: List<String>, includeRoot: Boolean = true) {
        val url = if (includeRoot) resolveSegments(rootSegments + segments) else resolveSegments(segments)
        val request = Request.Builder().url(url).method("MKCOL", null).authorized().build()
        execute(request) { code, _, _ ->
            if (code !in listOf(200, 201, 204, 405)) throw responseError("创建目录", code)
        }
    }

    private fun resolve(path: String): HttpUrl {
        val segments = safeRelativePath(path)
        return resolveSegments(rootSegments + segments)
    }

    private fun resolveSegments(segments: List<String>): HttpUrl = baseUrl.newBuilder().apply {
        segments.forEach(::addPathSegment)
    }.build()

    private fun Request.Builder.authorized(): Request.Builder = header("Authorization", authorization)

    private suspend fun <T> execute(request: Request, consume: (Int, ByteArray, Headers) -> T): T =
        withContext(Dispatchers.IO) {
            try {
                client.newCall(request).execute().use { response ->
                    val maximum = if (request.url.encodedPath.endsWith(".png")) 16L * 1024 * 1024 else 8L * 1024 * 1024
                    val bytes = response.body?.byteStream()?.use { stream ->
                        val output = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val count = stream.read(buffer)
                            if (count < 0) break
                            if (output.size().toLong() + count > maximum) throw CloudBackupException("云端响应过大，已停止读取")
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray()
                    } ?: byteArrayOf()
                    consume(response.code, bytes, response.headers)
                }
            } catch (error: CloudBackupException) {
                throw error
            } catch (error: java.io.IOException) {
                throw CloudBackupException("WebDAV 连接失败：${error.message ?: "网络不可用"}", error)
            }
        }

    private fun responseError(action: String, code: Int): CloudBackupException = when (code) {
        401, 403 -> CloudBackupException("WebDAV 认证失败，请检查用户名、密码和目录权限")
        else -> CloudBackupException("WebDAV ${action}失败：HTTP $code")
    }

    companion object {
        fun validateConfig(config: CloudBackupConfig): HttpUrl {
            val server = try {
                config.serverUrl.trim().trimEnd('/').toHttpUrl()
            } catch (error: IllegalArgumentException) {
                throw CloudBackupException("WebDAV 地址无效", error)
            }
            if (!server.isHttps) throw CloudBackupException("WebDAV 地址必须使用 HTTPS")
            if (server.username.isNotEmpty() || server.password.isNotEmpty() || server.query != null || server.fragment != null) {
                throw CloudBackupException("WebDAV 地址不能包含账号密码、查询参数或片段，请使用独立的用户名和密码字段")
            }
            if (config.username.isBlank()) throw CloudBackupException("WebDAV 用户名不能为空")
            if (config.password.isBlank()) throw CloudBackupException("WebDAV 密码不能为空")
            safeRelativePath(config.remoteDirectory)
            return server
        }
    }
}

private fun safeRelativePath(path: String): List<String> {
    if (path.isBlank() || path.startsWith('/') || path.startsWith('\\') || path.contains('\\')) {
        throw CloudBackupException("WebDAV 远端路径无效")
    }
    return path.split('/').also { segments ->
        if (segments.any { it.isBlank() || it == "." || it == ".." || it.any { char -> char.code < 0x20 } }) {
            throw CloudBackupException("WebDAV 远端路径无效")
        }
    }
}
