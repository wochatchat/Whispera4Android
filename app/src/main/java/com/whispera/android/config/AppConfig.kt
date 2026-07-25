package com.whispera.android.config

import android.content.Context
import android.content.SharedPreferences
import java.io.File

/**
 * App-wide configuration persisted via SharedPreferences.
 *
 * Mirrors the runtime knobs of Whispera's Python backend:
 *  - LLM endpoint is OpenAI-compatible (local llama-server @ 127.0.0.1:8080 OR a remote proxy),
 *    exactly the same wire protocol the original Whispera uses with `llama-server`.
 *  - ASR: Sherpa-ONNX SenseVoice ONNX or Zipformer model directory under assets/models/.
 *  - TTS: Sherpa-ONNX Kokoro model directory under assets/models/.
 *  - VAD: Silero VAD ONNX model file.
 */
data class AppConfig(
    var llmBaseUrl: String = DEFAULT_LLM_BASE_URL,
    var llmApiKey: String = "",
    var llmModel: String = "qwen2.5:1.5b",
    var llmTemperature: Float = 0.6f,
    var llmTopP: Float = 0.9f,
    var llmMaxTokens: Int = 256,
    var llmSystemPrompt: String = DEFAULT_SYSTEM_PROMPT,
    // TTS
    var ttsModelDir: String = "kokoro-multi-lang-v1_1",
    var ttsSpeakerId: Int = DEFAULT_SPEAKER_ID,
    var ttsSpeed: Float = 1.0f,
    // ASR
    var asrModelDir: String = "sensevoice",
    var asrLanguage: String = "zh",
    var asrUseItN: Boolean = true,
    // VAD
    var vadThreshold: Float = 0.5f,
    var vadMinSilenceMs: Int = 800,
    // Streaming text segmenter
    var segmenterHardLimit: Int = 120,
    // Pipeline / UX
    var enableBargeIn: Boolean = true,
    var enableTtsAfterLlm: Boolean = true,
) {
    companion object {
        const val DEFAULT_LLM_BASE_URL = "http://127.0.0.1:8080/v1"
        const val DEFAULT_SYSTEM_PROMPT =
            "You are a concise, friendly voice assistant. Answer in spoken Chinese, keep replies short and conversational. " +
                "Never use markdown, lists, or code blocks. If asked to switch language, follow the user."

        // Kokoro zh-en multilingual v1.1 supports 103 speaker ids; #74 is a warm Chinese female voice.
        const val DEFAULT_SPEAKER_ID = 74

        fun load(prefs: SharedPreferences): AppConfig {
            val c = AppConfig()
            c.llmBaseUrl = prefs.getString("llmBaseUrl", c.llmBaseUrl) ?: c.llmBaseUrl
            c.llmApiKey = prefs.getString("llmApiKey", c.llmApiKey) ?: c.llmApiKey
            c.llmModel = prefs.getString("llmModel", c.llmModel) ?: c.llmModel
            c.llmTemperature = prefs.getFloat("llmTemperature", c.llmTemperature)
            c.llmTopP = prefs.getFloat("llmTopP", c.llmTopP)
            c.llmMaxTokens = prefs.getInt("llmMaxTokens", c.llmMaxTokens)
            c.llmSystemPrompt = prefs.getString("llmSystemPrompt", c.llmSystemPrompt) ?: c.llmSystemPrompt
            c.ttsModelDir = prefs.getString("ttsModelDir", c.ttsModelDir) ?: c.ttsModelDir
            c.ttsSpeakerId = prefs.getInt("ttsSpeakerId", c.ttsSpeakerId)
            c.ttsSpeed = prefs.getFloat("ttsSpeed", c.ttsSpeed)
            c.asrModelDir = (prefs.getString("asrModelDir", c.asrModelDir) ?: c.asrModelDir)
            // Legacy default had a stray space ("sense Voice") that broke ModelManager
            // spec lookups; normalize to the canonical directory name on load.
            .let {
                when (it.lowercase().trim()) {
                    "sensevoice", "sense voice" -> "sensevoice"
                    else -> it
                }
            }
            c.asrLanguage = prefs.getString("asrLanguage", c.asrLanguage) ?: c.asrLanguage
            c.asrUseItN = prefs.getBoolean("asrUseItN", c.asrUseItN)
            c.vadThreshold = prefs.getFloat("vadThreshold", c.vadThreshold)
            c.vadMinSilenceMs = prefs.getInt("vadMinSilenceMs", c.vadMinSilenceMs)
            c.segmenterHardLimit = prefs.getInt("segmenterHardLimit", c.segmenterHardLimit)
            c.enableBargeIn = prefs.getBoolean("enableBargeIn", c.enableBargeIn)
            c.enableTtsAfterLlm = prefs.getBoolean("enableTtsAfterLlm", c.enableTtsAfterLlm)
            return c
        }
    }

    fun save(prefs: SharedPreferences) {
        prefs.edit().apply {
            putString("llmBaseUrl", llmBaseUrl)
            putString("llmApiKey", llmApiKey)
            putString("llmModel", llmModel)
            putFloat("llmTemperature", llmTemperature)
            putFloat("llmTopP", llmTopP)
            putInt("llmMaxTokens", llmMaxTokens)
            putString("llmSystemPrompt", llmSystemPrompt)
            putString("ttsModelDir", ttsModelDir)
            putInt("ttsSpeakerId", ttsSpeakerId)
            putFloat("ttsSpeed", ttsSpeed)
            putString("asrModelDir", asrModelDir)
            putString("asrLanguage", asrLanguage)
            putBoolean("asrUseItN", asrUseItN)
            putFloat("vadThreshold", vadThreshold)
            putInt("vadMinSilenceMs", vadMinSilenceMs)
            putInt("segmenterHardLimit", segmenterHardLimit)
            putBoolean("enableBargeIn", enableBargeIn)
            putBoolean("enableTtsAfterLlm", enableTtsAfterLlm)
            apply()
        }
    }

    /**
     * Resolve the absolute path of a model directory that has been unpacked under the app's
     * private files directory (see [ModelManager]). Sherpa-ONNX APIs accept filesystem paths,
     * not Android asset URIs, so models must live on disk.
     */
    fun resolveModelDir(ctx: Context, subDir: String): File {
        return File(ctx.filesDir, "models/$subDir")
    }
}
