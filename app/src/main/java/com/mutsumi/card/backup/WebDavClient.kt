package com.mutsumi.card.backup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

interface CloudRemoteStore {
    suspend fun ensureDirectories()
    suspend fun get(path: String): ByteArray?
    suspend fun put(path: String, bytes: ByteArray, contentType: String)
    suspend fun delete(path: String)
}

class WebDavClient(
    private val config: CloudBackupConfig,
    private val client: OkHttpClient,
) : CloudRemoteStore {
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
    }

    override suspend fun get(path: String): ByteArray? = execute(
        Request.Builder().url(resolve(path)).get().authorized().build(),
    ) { code, bytes ->
        when (code) {
            200 -> bytes
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
        execute(request) { code, _ ->
            if (code !in 200..204) throw responseError("上传", code)
        }
    }

    override suspend fun delete(path: String) {
        val request = Request.Builder().url(resolve(path)).delete().authorized().build()
        execute(request) { code, _ ->
            if (code !in 200..204 && code != 404) throw responseError("删除", code)
        }
    }

    private suspend fun mkcol(segments: List<String>, includeRoot: Boolean = true) {
        val url = if (includeRoot) resolveSegments(rootSegments + segments) else resolveSegments(segments)
        val request = Request.Builder().url(url).method("MKCOL", null).authorized().build()
        execute(request) { code, _ ->
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

    private suspend fun <T> execute(request: Request, consume: (Int, ByteArray) -> T): T =
        withContext(Dispatchers.IO) {
            try {
                client.newCall(request).execute().use { response ->
                    consume(response.code, response.body?.bytes() ?: byteArrayOf())
                }
            } catch (error: CloudBackupException) {
                throw error
            } catch (error: java.io.IOException) {
                throw CloudBackupException("WebDAV 连接失败：${error.message ?: "网络不可用"}", error)
            }
        }

    private fun responseError(action: String, code: Int): CloudBackupException = when (code) {
        401, 403 -> CloudBackupException("WebDAV 认证失败，请检查用户名、密码和目录权限")
        else -> CloudBackupException("WebDAV $action失败：HTTP $code")
    }

    companion object {
        fun validateConfig(config: CloudBackupConfig): HttpUrl {
            val server = try {
                config.serverUrl.trim().trimEnd('/').toHttpUrl()
            } catch (error: IllegalArgumentException) {
                throw CloudBackupException("WebDAV 地址无效", error)
            }
            if (!server.isHttps) throw CloudBackupException("WebDAV 地址必须使用 HTTPS")
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
