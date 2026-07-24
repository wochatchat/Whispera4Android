package com.whispera.android.llm

import com.whispera.android.config.AppConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.util.concurrent.TimeUnit

/**
 * OpenAI-compatible chat completion streaming client.
 *
 * Same wire protocol Whispera uses to talk to llama-server:
 *
 *     POST {base}/chat/completions
 *     { "model", "messages", "stream": true, "temperature", "top_p", "max_tokens" }
 *
 * The server may be either the local llama.cpp `llama-server` running on the phone
 * (wholly offline, http://127.0.0.1:8080/v1) or any remote OpenAI-compatible proxy
 * (e.g. 豆包, 中转站) for use without a local LLM.
 *
 * Emits a Flow of text deltas (per token). The pipeline feeds these into
 * [StreamingTextSegmenter] to chunk sentences for TTS.
 *
 * Note: this uses OkHttp SSE; llama-server emits proper text/event-stream headers.
 */
class LlmClient(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
    private val temperature: Float = 0.6f,
    private val topP: Float = 0.9f,
    private val maxTokens: Int = 256,
) {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private val sseClient by lazy { EventSources.createFactory(client) }

    /**
     * Stream chat-completion text deltas. Each emission is one token chunk.
     */
    fun stream(messages: List<ChatMessage>): Flow<String> = callbackFlow {
        val body = buildRequestBody(messages)
        val requestBuilder = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/chat/completions")
            .post(body.toRequestBody("application/json".toMediaType()))
        if (apiKey.isNotEmpty()) {
            requestBuilder.header("Authorization", "Bearer $apiKey")
        }

        val request = requestBuilder.build()
        val source = sseClient.newEventSource(request, object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                if (data == "[DONE]") {
                    eventSource.cancel()
                    close()
                    return
                }
                // OpenAI-style SSE: {"choices":[{"delta":{"content":"..."}}]}
                val text = extractDelta(data)
                if (text != null && text.isNotEmpty()) {
                    trySend(text)
                }
            }

            override fun onClosed(eventSource: EventSource) { close() }
            override fun onFailure(t: Throwable, response: Response?) {
                close(t)
            }
        })

        awaitClose { source.cancel() }
    }

    private fun buildRequestBody(messages: List<ChatMessage>): String {
        val messagesJson = messages.joinToString(",") { m ->
            """{"role":"${m.role}","content":${jsonEscape(m.content)}}"""
        }
        return """{"model":"${jsonEscape(model)}","messages":[$messagesJson],"stream":true,"temperature":$temperature,"top_p":$topP,"max_tokens":$maxTokens}"""
    }

    private fun extractDelta(data: String): String? {
        return try {
            // Minimal JSON parse for the delta.content path.
            val choicesIdx = data.indexOf("\"choices\":")
            if (choicesIdx < 0) return null
            val deltaIdx = data.indexOf("\"delta\":", choicesIdx)
            if (deltaIdx < 0) return null
            val contentIdx = data.indexOf("\"content\":", deltaIdx)
            if (contentIdx < 0) return null
            parseStringValue(data, contentIdx + "\"content\":".length)
        } catch (e: Exception) {
            null
        }
    }

    private fun parseStringValue(json: String, start: Int): String? {
        var i = start
        while (i < json.length && json[i].isWhitespace()) i++
        if (i >= json.length || json[i] != '"') return null
        i++
        val sb = StringBuilder()
        while (i < json.length) {
            val c = json[i]
            if (c == '\\') {
                i++
                if (i >= json.length) break
                when (val e = json[i]) {
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    'r' -> sb.append('\r')
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    '/' -> sb.append('/')
                    'u' -> {
                        val hex = json.substring(i + 1, minOf(i + 5, json.length))
                        sb.append(hex.toIntOrNull(16)?.toChar() ?: ' ')
                        i += 4
                    }
                    else -> sb.append(e)
                }
                i++
            } else if (c == '"') {
                return sb.toString()
            } else {
                sb.append(c); i++
            }
        }
        return null
    }

    private fun jsonEscape(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")

    companion object {
        data class ChatMessage(val role: String, val content: String)

        fun fromConfig(c: AppConfig): LlmClient = LlmClient(
            baseUrl = c.llmBaseUrl,
            apiKey = c.llmApiKey,
            model = c.llmModel,
            temperature = c.llmTemperature,
            topP = c.llmTopP,
            maxTokens = c.llmMaxTokens,
        )
    }
}
