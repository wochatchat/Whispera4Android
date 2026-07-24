package com.whispera.android.tts

import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.GeneratedAudio
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * Sherpa-ONNX TTS front-end over a Kokoro ONNX model.
 *
 * Keys for the user's three priorities:
 *  - **Streaming**: [generateStreaming] invokes a per-chunk callback so audio can
 *    be played while still synthesizing. This is sherpa-onnx's native
 *    chunked-output path for Kokoro — we don't have to fake it at the sentence level.
 *  - **Low latency**: each Kokoro chunk is a small audio window; the pipeline
 *    starts playback on the very first callback before synthesis completes.
 *  - **Human-like voice**: Kokoro is currently the best open-source ONNX-runnable
 *    Chinese/multi-lingual TTS on a phone, supports many speaker ids, and sounds
 *    closer to a real speaker than the alternatives (VITS-zh, matcha-zh) at this size.
 *
 * To abort playback mid-generation (bargemã-in), set [cancel] to true from another
 * thread — the next chunk callback returns 1 to stop generation.
 */
class TtsEngine(
    private val tts: OfflineTts,
    val sampleRate: Int,
) {
    private val cancel = AtomicReference(false)

    fun numSpeakers(): Int = tts.numSpeakers()

    /**
     * Generate audio for [text] by feeding audio chunks to [onChunk] as soon as
     * they are produced. The pipeline pushes [onChunk] samples into AudioTrack
     * for immediate playback (~50 ms after the synthesis starts).
     *
     * Returns the full accumulated audio (all chunks). To stop generation early
     * (barged-in by user speech) set [cancel] to true via [stopGeneration].
     */
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
                0   // 0 = continue generation
            } else {
                1   // 1 = stop; signals VAD barged-in
            }
        }
    }

    /** Signal generation to stop on next chunk. Non-blocking. */
    fun stopGeneration() { cancel.set(true) }

    fun release() { tts.release() }

    companion object {
        /**
         * Build a [TtsEngine] over a Kokoro multi-lang v1.1 model directory:
         *   - model.onnx
         *   - tokens.txt
         *   - voices.bin
         *   - espeak-ng-data/       (directory)
         *   - lexicon.txt            (optional for zh)
         */
        fun fromKokoro(
            dir: File,
            numThreads: Int = 2,
        ): TtsEngine {
            val model = File(dir, "model.onnx")
            val tokens = File(dir, "tokens.txt")
            val voices = File(dir, "voices.bin")
            val dataDir = File(dir, "espeak-ng-data")
            require(model.exists() && tokens.exists() && voices.exists() && dataDir.isDirectory) {
                "Kokoro model files missing under $dir (model.onnx/tokens.txt/voices.bin/espeak-ng-data/)"
            }
            val config = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    kokoro = OfflineTtsKokoroModelConfig(
                        model = model.absolutePath,
                        voices = voices.absolutePath,
                        tokens = tokens.absolutePath,
                        dataDir = dataDir.absolutePath,
                        lexicon = File(dir, "lexicon.txt").let { if (it.exists()) it.absolutePath else "" },
                    ),
                    numThreads = numThreads,
                    debug = false,
                    provider = "cpu",
                )
            )
            val tts = OfflineTts(config = config)
            return TtsEngine(tts = tts, sampleRate = tts.sampleRate())
        }
    }
}
