package com.whispera.android.config

import android.content.Context
import java.io.File

/**
 * Manages ONNX model files on disk.
 *
 * Sherpa-ONNX APIs take filesystem paths; assets/models/ is only a bundle origin.
 * On first launch (or when a flavor ships with bundled models), models are copied
 * from the APK's assets/ to ctx.filesDir/models/ so the runtime can mmap them.
 *
 * Listing and verification are also used by the UI to show what is installed.
 */
object ModelManager {
    private const val ROOT = "models"
    private const val ASSET_ROOT = "models"

    /** Expected per-model directory with its required files. */
    data class ModelSpec(
        val dirName: String,
        val required: List<String>,
        val label: String,
    )

    val VAD = ModelSpec("silero_vad", listOf("silero_vad.onnx"), "Silero VAD")
    val ASR_SENSEVOICE = ModelSpec("sensevoice", listOf("model.onnx", "tokens.txt"), "SenseVoice ASR")
    val ASR_ZIPFORMER = ModelSpec("zipformer", listOf("encoder-epoch-99-avg-1.onnx", "decoder-epoch-99-avg-1.onnx", "joiner-epoch-99-avg-1.onnx", "tokens.txt"), "Zipformer ASR")
    val TTS_KOKORO = ModelSpec("kokoro-multi-lang-v1_1", listOf("model.onnx", "tokens.txt", "voices.bin", "espeak-ng-data", "lexicon.txt"), "Kokoro TTS")

    fun rootDir(ctx: Context): File = File(ctx.filesDir, ROOT).also { it.mkdirs() }

    fun modelDir(ctx: Context, subDir: String): File = File(rootDir(ctx), subDir)

    fun isInstalled(ctx: Context, spec: ModelSpec): Boolean {
        val dir = modelDir(ctx, spec.dirName)
        return dir.isDirectory && spec.required.all { File(dir, it).exists() }
    }

    /**
     * Copy bundled models from assets/models/ to filesDir/models/.
     * Only flavors with BUNDLE_MODELS=true pack real model bytes into assets;
     * otherwise this is a no-op and [downloadFromNetwork] is the path.
     *
     * For large model sizes we expect models to come from setup_models.sh which
     * pushes files to filesDir/models/ directly via adb, so this method is
     * intentionally tailored to small files (VAD only).
     */
    fun copyBundledModels(ctx: Context) {
        val dest = rootDir(ctx)
        val assets = ctx.assets
        val list = try { assets.list(ASSET_ROOT) ?: emptyArray() } catch (e: Exception) { emptyArray() }
        for (entry in list) {
            val target = File(dest, entry)
            if (target.exists()) continue
            try {
                assets.open("$ASSET_ROOT/$entry").use { input ->
                    target.outputStream().use { input.copyTo(it) }
                }
            } catch (e: Exception) {
                // Asset may be a directory in a future build; skip silently.
            }
        }
    }
}
