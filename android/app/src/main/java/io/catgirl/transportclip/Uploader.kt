package io.catgirl.transportclip

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object Uploader {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS) // 等待电脑写入剪贴板的 ACK
        .retryOnConnectionFailure(true)
        .build()

    sealed class Result {
        /** 电脑已确认写入剪贴板 */
        data class Delivered(val elapsedMs: Long) : Result()

        /** 电脑不在线 */
        object Offline : Result()

        /** 其他失败(网络错误/服务器错误/超时未 ACK) */
        data class Failed(val detail: String) : Result()
    }

    enum class Connection { ONLINE, OFFLINE, UNAUTHORIZED, UNAVAILABLE }

    /** Read the relay's existing status endpoint without sending a test image. */
    fun connectionStatus(uploadUrl: String, token: String): Connection = runCatching {
        val upload = uploadUrl.toHttpUrl()
        val url = upload.resolve("status") ?: return Connection.UNAVAILABLE
        val request = Request.Builder().url(url).header("Authorization", "Bearer $token").build()
        val call = client.newCall(request)
        call.timeout().timeout(5, TimeUnit.SECONDS)
        call.execute().use { response ->
            when (response.code) {
                401 -> Connection.UNAUTHORIZED
                200 -> {
                    val status = JSONObject(response.body?.string().orEmpty())
                    val count = status.optInt("windows_online", -1)
                    when {
                        count > 0 -> Connection.ONLINE
                        count == 0 -> Connection.OFFLINE
                        else -> Connection.UNAVAILABLE
                    }
                }
                else -> Connection.UNAVAILABLE
            }
        }
    }.getOrDefault(Connection.UNAVAILABLE)

    fun upload(url: String, token: String, png: ByteArray): Result {
        return try {
            val req = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
                .header("Content-Type", "image/png")
                .post(png.toRequestBody("image/png".toMediaType()))
                .build()
            client.newCall(req).execute().use { resp ->
                val txt = resp.body?.string() ?: ""
                when (resp.code) {
                    200 -> {
                        val ms = runCatching { JSONObject(txt).optLong("elapsed_ms", -1) }.getOrDefault(-1L)
                        Result.Delivered(ms)
                    }
                    409 -> Result.Offline
                    else -> Result.Failed("HTTP ${resp.code}: ${txt.take(120)}")
                }
            }
        } catch (e: Exception) {
            Result.Failed(e.message ?: e.javaClass.simpleName)
        }
    }
}
