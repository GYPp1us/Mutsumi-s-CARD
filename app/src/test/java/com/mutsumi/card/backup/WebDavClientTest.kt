package com.mutsumi.card.backup

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test

class WebDavClientTest {
    @Test
    fun `读取的 ETag 会用于条件发布且 412 不覆盖远端`() = runTest {
        val requests = mutableListOf<Request>()
        val client = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val request = chain.request()
                requests += request
                when (requests.size) {
                    1 -> response(request, 200, "旧索引", "\"v1\"")
                    2 -> response(request, 412)
                    else -> error("不应再发起请求")
                }
            })
            .build()
        val remote = WebDavClient(config(), client)

        val index = remote.getWithVersion("index.json")
        val published = remote.putIfUnchanged(
            path = "index.json",
            bytes = "新索引".encodeToByteArray(),
            contentType = "application/json",
            expectedVersion = requireNotNull(index).version,
        )

        assertThat(index.bytes.decodeToString()).isEqualTo("旧索引")
        assertThat(published).isFalse()
        assertThat(requests[1].header("If-Match")).isEqualTo("\"v1\"")
        assertThat(requests[1].header("If-None-Match")).isNull()
    }

    @Test
    fun `缺失索引会通过 If-None-Match 原子创建`() = runTest {
        val requests = mutableListOf<Request>()
        val client = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val request = chain.request()
                requests += request
                when (requests.size) {
                    1 -> response(request, 404)
                    2 -> response(request, 201)
                    else -> error("不应再发起请求")
                }
            })
            .build()
        val remote = WebDavClient(config(), client)

        val missing = remote.getWithVersion("index.json")
        val published = remote.putIfUnchanged(
            path = "index.json",
            bytes = "新索引".encodeToByteArray(),
            contentType = "application/json",
            expectedVersion = missing?.version,
        )

        assertThat(missing).isNull()
        assertThat(published).isTrue()
        assertThat(requests[1].header("If-None-Match")).isEqualTo("*")
        assertThat(requests[1].header("If-Match")).isNull()
    }

    private fun config() = CloudBackupConfig("https://example.test/dav", "user", "password")

    private fun response(request: Request, code: Int, body: String = "", etag: String? = null): Response =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("test")
            .apply { etag?.let { header("ETag", it) } }
            .body(body.toResponseBody())
            .build()
}
