package com.whispera.android.tts

import android.content.Context
import android.content.res.AssetManager
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.GeneratedAudio
import com.whispera.android.config.ModelManager
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * Sherpa-ONNX TTS front-end over a Kokoro ONNX model.
 *
 * Keys for the user's three priorities:
 *  - **Streaming** — [generateStreaming] uses Sherpa-ONNX's native chunk callback so audio
 *    playback starts as soon as the first audio window is synthesized.
 *  - **Low latency** — small Kokoro chunks → first audio ~200-400 ms; pipeline writes
 *    straight into AudioTrack (no intermediate file).
 *  - **Human-like** — Kokoro-82M is currently the best ONNX-runnable Chinese/multi-lingual
 *    TTS on a phone; 103 speakers selectable via [sid].
 *
 * To abort playback mid-generation (barge-in): set [stopGeneration] from another thread —
 * the next chunk callback returns non-zero to halt generation.
 */
class TtsEngine(
    private val tts: OfflineTts,
    val sampleRate: Int,
) {
    private val cancel = AtomicReference(false)

    fun numSpeakers(): Int = tts.numSpeakers()

    /** Stream audio chunks for [text] via [onChunk] callbacks until completion or [stopGeneration]. */
    fun generateStreaming(
        text: String,
        sid: Int,
        speed: Float,
        onChunk: (samples: FloatArray) -> Unit,
    ): GeneratedAudio {
        cancel.set(false)
        return tts.generateWithCallback(text = text, sid = sid, speed = speed) { samples ->
            if (!cancel.get()) {
                onChunk(samples)
                0   // continue generation
            } else {
                1   // stop: barge-in fired
            }
        }
    }

    /** Signal generation to stop on next chunk (non-blocking). */
    fun stopGeneration() { cancel.set(true) }

    fun release() { tts.release() }

    companion object {
        /** Build a [TtsEngine] over a Kokoro v1.1 model. */
        fun fromContext(ctx: Context): TtsEngine {
            val spec = ModelManager.TTS_KOKORO
            val onDisk = ModelManager.isOnDisk(ctx, spec)
            val am: AssetManager? = if (onDisk) null else ctx.assets

            val dirPrefix: String = if (onDisk) ModelManager.diskDir(ctx, spec).absolutePath else ModelManager.assetDir(spec)
            val model  = pathOf(dirPrefix, "model.onnx")
            val tokens = pathOf(dirPrefix, "tokens.txt")
            val voices = pathOf(dirPrefix, "voices.bin")
            val dataDir = pathOf(dirPrefix, "espeak-ng-data")
            val lexicon = pathOf(dirPrefix, "lexicon.txt")

            val config = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    kokoro = OfflineTtsKokoroModelConfig(
                        model = model,
                        voices = voices,
                        tokens = tokens,
                        dataDir = dataDir,
                        lexicon = lexicon,
                    ),
                    numThreads = 2,
                    debug = true,
                    provider = "cpu",
                )
            )
            val tts = OfflineTts(assetManager = am, config = config)
            android.util.Log.i("WhisperaTTS", "OfflineTts built: sampleRate=${tts.sampleRate()} numSpeakers=${tts.numSpeakers()} onDisk=$onDisk am=${if (am == null) "null" else "assets"} dir=$dirPrefix lexicon=$lexicon dataDir=$dataDir")
            return TtsEngine(tts = tts, sampleRate = tts.sampleRate())
        }

        private fun pathOf(prefix: String, name: String): String = if (prefix.endsWith("/")) "$prefix$name" else "$prefix/$name"
    }
}
