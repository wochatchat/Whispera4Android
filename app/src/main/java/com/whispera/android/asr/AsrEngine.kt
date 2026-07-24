package com.whispera.android.asr

import android.content.Context
import android.content.res.AssetManager
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.whispera.android.config.ModelManager
import java.io.File

/**
 * Sherpa-ONNX offline ASR front-end.
 *
 * Models can live in two places:
 *   - Bundled assets (offlineFull flavor): passed to Sherpa via [AssetManager]
 *   - Local files (liteCloud / sideloaded): passed as absolute paths
 *
 * Either way, [fromContext] picks whichever is present and builds the recognizer.
 *
 * Offline (utterance-level) recognizer — [recognize] is invoked once per finished user turn
 * (after [VadSession] reports SPEECH_END). RTF is ~0.1 on arm64 CPU for short turns.
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
        /** Build an [AsrEngine] for the SenseVoice model under ModelManager.ASR_SENSEVOICE. */
        fun fromContext(
            ctx: Context,
            language: String = "zh",
            useItN: Boolean = true,
        ): AsrEngine {
            val spec = ModelManager.ASR_SENSEVOICE
            val modelPath = ModelManager.resolve(ctx, spec, "model.int8.onnx").let {
                if (it is ModelManager.ModelPath.Disk) it.absolutePath
                else "${ModelManager.assetDir(spec)}/model.int8.onnx"
            }
            val tokensPath = ModelManager.resolve(ctx, spec, "tokens.txt").let {
                if (it is ModelManager.ModelPath.Disk) it.absolutePath
                else "${ModelManager.assetDir(spec)}/tokens.txt"
            }
            val am = if (ModelManager.isOnDisk(ctx, spec)) null else ctx.assets
            require(modelExists(ctx, spec, modelPath)) { "SenseVoice model not available: $modelPath" }

            val config = OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
                modelConfig = OfflineModelConfig(
                    senseVoice = OfflineSenseVoiceModelConfig(
                        model = modelPath,
                        language = language,
                        useInverseTextNormalization = useItN,
                    ),
                    tokens = tokensPath,
                    numThreads = 2,
                    modelType = "sense_voice",
                ),
            )
            return AsrEngine(OfflineRecognizer(assetManager = am, config = config))
        }

        private fun modelExists(ctx: Context, spec: ModelManager.ModelSpec, modelPath: String): Boolean =
            ModelManager.isInstalled(ctx, spec)

        /** Build over an offline Zipformer transducer directory (lighter pure-Chinese alternative). */
        fun fromZipformerContext(
            ctx: Context,
        ): AsrEngine {
            val spec = ModelManager.ASR_ZIPFORMER
            val am = if (ModelManager.isOnDisk(ctx, spec)) null else ctx.assets
            val dir = if (ModelManager.isOnDisk(ctx, spec)) ModelManager.diskDir(ctx, spec) else null
            val encoder = resolveOr(spec, "encoder-epoch-99-avg-1.onnx", dir, ctx)
            val decoder = resolveOr(spec, "decoder-epoch-99-avg-1.onnx", dir, ctx)
            val joiner  = resolveOr(spec, "joiner-epoch-99-avg-1.onnx",  dir, ctx)
            val tokens  = resolveOr(spec, "tokens.txt", dir, ctx)
            val config = OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
                modelConfig = OfflineModelConfig(
                    transducer = OfflineTransducerModelConfig(
                        encoder = encoder, decoder = decoder, joiner = joiner,
                    ),
                    tokens = tokens,
                    numThreads = 2,
                    modelType = "transducer",
                ),
            )
            return AsrEngine(OfflineRecognizer(assetManager = am, config = config))
        }

        private fun resolveOr(spec: ModelManager.ModelSpec, name: String, diskDir: File?, ctx: Context): String {
            if (diskDir != null) {
                val f = File(diskDir, name)
                if (f.exists()) return f.absolutePath
            }
            // Asset path mode.
            return "${ModelManager.assetDir(spec)}/$name"
        }
    }
}
