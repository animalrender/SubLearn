package com.sublearn.core.ai

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The one place SubLearn talks to a remote AI service over the network.
 *
 * The API key is passed per call and is never logged: [redactedUrl] and the logger contract in
 * :core:common make it hard to leak by accident (ENGINEERING REQUIREMENTS: secrets never logged).
 */
class OkHttpJsonClient(
    private val client: OkHttpClient = defaultClient(),
    private val maxResponseBytes: Int = 512 * 1024,
) : HttpJsonClient {
    override suspend fun post(url: String, headers: Map<String, String>, bodyJson: String): HttpJsonResponse {
        val request = Request.Builder()
            .url(url)
            .post(bodyJson.toRequestBody(JSON_MEDIA))
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .build()
        val response = execute(request)
        return response.use { resp ->
            val body = resp.body?.let { responseBody ->
                val source = responseBody.source()
                source.request(maxResponseBytes.toLong())
                source.buffer.snapshot(minOf(maxResponseBytes.toLong(), source.buffer.size)).utf8()
            }.orEmpty()
            HttpJsonResponse(status = resp.code, body = body, contentType = resp.header("content-type"))
        }
    }

    private suspend fun execute(request: Request): Response = suspendCancellableCoroutine { cont ->
        val call = client.newCall(request)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    if (cont.isActive) cont.resume(response) else response.close()
                }
            },
        )
    }

    /** Safe string to show in a UI "test connection" preview. */
    fun redactedUrl(url: String): String = url.substringBefore('?')

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        fun defaultClient(timeoutMs: Long = 30_000L): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .writeTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}
