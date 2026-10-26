package com.mutsumi.card.backup

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Test

class WebDavPublicationLockTest {
    /** 模拟真实服务器：故意忽略条件请求头，但 MKCOL 互斥。 */
    private class Server : Interceptor {
        var locked = false
        var content: ByteArray? = "旧索引".encodeToByteArray()
        var indexWrites = 0
        var releases = 0
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            var bytes = byteArrayOf()
            val lock = request.url.encodedPath.endsWith(".library-write-lock")
            val code = when {
                request.method == "MKCOL" && lock -> if (locked) 405 else { locked = true; 201 }
                request.method == "DELETE" && lock -> { locked = false; releases++; 204 }
                request.method == "GET" -> content?.let { bytes = it; 200 } ?: 404
                request.method == "PUT" -> { content = Buffer().also { request.body!!.writeTo(it) }.readByteArray(); indexWrites++; 201 }
                else -> error("不支持 ${request.method}")
            }
            return Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("test")
                .header("ETag", "\"fixed-version\"").body(bytes.toResponseBody()).build()
        }
    }
    private fun remote(server: Server) = WebDavClient(CloudBackupConfig("https://example.test/dav", "user", "private"),
        OkHttpClient.Builder().addInterceptor(server).build())

    @Test fun `忽略条件请求头的服务器也必须先拿锁核对内容再发布`() = runTest {
        val server = Server(); val remote = remote(server)
        val previous = requireNotNull(remote.getWithVersion("library-index.json"))
        assertThat(previous.version).isEqualTo(sha256(previous.bytes))
        assertThat(remote.putIfUnchanged("library-index.json", "新索引".encodeToByteArray(), "application/json", previous.version)).isTrue()
        assertThat(server.content?.decodeToString()).isEqualTo("新索引")
        assertThat(server.indexWrites).isEqualTo(1); assertThat(server.releases).isEqualTo(1); assertThat(server.locked).isFalse()
    }

    @Test fun `固定服务器版本下内容变化仍拒绝覆盖并释放自己的锁`() = runTest {
        val server = Server(); val remote = remote(server)
        val previous = remote.getWithVersion("library-index.json")
        server.content = "另一设备新内容".encodeToByteArray()
        assertThat(remote.putIfUnchanged("library-index.json", "不应写入".encodeToByteArray(), "application/json", previous?.version)).isFalse()
        assertThat(server.content?.decodeToString()).isEqualTo("另一设备新内容")
        assertThat(server.indexWrites).isEqualTo(0); assertThat(server.releases).isEqualTo(1)
    }

    @Test fun `已有提交锁时不写入且绝不释放其他设备的锁`() = runTest {
        val server = Server().apply { locked = true }; val remote = remote(server)
        assertThat(runCatching { remote.putIfUnchanged("library-index.json", byteArrayOf(1), "application/json", null) }.exceptionOrNull())
            .isInstanceOf(CloudBackupException::class.java)
        assertThat(server.indexWrites).isEqualTo(0); assertThat(server.releases).isEqualTo(0); assertThat(server.locked).isTrue()
    }

    @Test fun `首次创建也不能覆盖锁内发现的既有索引`() = runTest {
        val server = Server(); val remote = remote(server)
        assertThat(remote.putIfUnchanged("library-index.json", byteArrayOf(1), "application/json", null)).isFalse()
        server.content = null
        assertThat(remote.putIfUnchanged("library-index.json", "首次".encodeToByteArray(), "application/json", null)).isTrue()
        assertThat(server.indexWrites).isEqualTo(1)
    }

    @Test fun `地址拒绝内嵌凭据查询参数和片段`() {
        listOf("https://user:secret@example.test", "https://example.test/?password=private", "https://example.test/#private").forEach { url ->
            assertThat(runCatching { WebDavClient.validateConfig(CloudBackupConfig(url, "user", "private")) }.isFailure).isTrue()
        }
    }
}
