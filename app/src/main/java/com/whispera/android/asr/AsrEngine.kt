package com.whispera.android.asr

import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.FeatureConfig
import java.io.File

/**
 * Sherpa-ONNX offline ASR front-end — wraps an [OfflineRecognizer].
 *
 * Two supported models:
 *  - SenseVoice ONNX (same family Whispera serves via FunASR, exported to ONNX):
 *    good accuracy on zh/ja/ko/yue/en, ~500 MB. The right default.
 *  - Zipformer transducer (offline, pure Chinese): much smaller (~80 MB) when
 *    SenseVoice is too heavy for a given phone.
 *
 * Because this is offline (utterance-level) ASR, [recognize] is only called once
 * per finished user turn — after [VadSession] reports SPEECH_END. RTF is ~0.1
 * on arm64 CPU for short turns, so latency stays bounded.
 */
class AsrEngine(private val recognizer: OfflineRecognizer) {

    private val sampleRate = 16000

    fun recognize(samples: FloatArray): String {
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(samples, sampleRate)
            recognizer.decode(stream)
            return recognizer.getResult(stream).text.trim()
        } finally {
            stream.release()
        }
    }

    fun release() { recognizer.release() }

    companion object {
        /** Build over a SenseVoice ONNX model directory (model.onnx + tokens.txt). */
        fun fromSenseVoice(
            dir: File,
            language: String = "zh",
            useItN: Boolean = true,
            numThreads: Int = 2,
        ): AsrEngine {
            val model = File(dir, "model.onnx")
            val tokens = File(dir, "tokens.txt")
            require(model.exists() && tokens.exists()) {
                "SenseVoice model not found at $dir (need model.onnx + tokens.txt)"
            }
            val config = OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
                modelConfig = OfflineModelConfig(
                    senseVoice = OfflineSenseVoiceModelConfig(
                        model = model.absolutePath,
                        language = language,
                        useInverseTextNormalization = useItN,
                    ),
                    tokens = tokens.absolutePath,
                    numThreads = numThreads,
                    modelType = "sense_voice",
                ),
            )
            return AsrEngine(OfflineRecognizer(config = config))
        }

        /** Build over an offline Zipformer transducer directory. */
        fun fromZipformer(
            dir: File,
            numThreads: Int = 2,
        ): AsrEngine {
            val encoder = File(dir, "encoder-epoch-99-avg-1.onnx")
            val decoder = File(dir, "decoder-epoch-99-avg-1.onnx")
            val joiner = File(dir, "joiner-epoch-99-avg-1.onnx")
            val tokens = File(dir, "tokens.txt")
            require(encoder.exists() && decoder.exists() && joiner.exists() && tokens.exists()) {
                "Zipformer model file missing under $dir"
            }
            val config = OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
                modelConfig = OfflineModelConfig(
                    transducer = OfflineTransducerModelConfig(
                        encoder = encoder.absolutePath,
                        decoder = decoder.absolutePath,
                        joiner = joiner.absolutePath,
                    ),
                    tokens = tokens.absolutePath,
                    numThreads = numThreads,
                    modelType = "transducer",
                ),
            )
            return AsrEngine(OfflineRecognizer(config = config))
        }
    }
}
