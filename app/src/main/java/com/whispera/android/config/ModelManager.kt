package com.whispera.android.config

import android.content.Context
import android.content.res.AssetManager
import java.io.File

/**
 * Resolves model file paths in two modes:
 *
 *  - offlineFull flavor: models are bundled in app/src/main/assets/models/<spec>/, and Sherpa-ONNX
 *    APIs are constructed with an [AssetManager] so paths resolve relative to assets/models/.
 *    No file copying required — keeps the APK the only copy of the model bytes.
 *
 *  - liteCloud flavor / sideloaded models: models live under ctx.filesDir/models/<spec>/ on disk
 *    (downloaded on first launch or pushed via adb). Sherpa-ONNX APIs are constructed with assetManager=null
 *    and absolute filesystem paths.
 *
 * [ModelManager] centralizes the directory naming and readiness check so both UI and the pipeline
 * agree on the model layout.
 */
object ModelManager {
    private const val ASSET_ROOT = "models"     // app/src/main/assets/models/
    private const val FILE_ROOT = "models"      // ctx.filesDir/models/

    data class ModelSpec(
        val dirName: String,
        val required: List<String>,
        val label: String,
    )

    val VAD = ModelSpec("silero_vad", listOf("silero_vad.onnx"), "Silero VAD")
    val ASR_SENSEVOICE = ModelSpec("sensevoice", listOf("model.int8.onnx", "tokens.txt"), "SenseVoice ASR (int8)")
    val ASR_ZIPFORMER = ModelSpec(
        "zipformer",
        listOf("encoder-epoch-99-avg-1.onnx", "decoder-epoch-99-avg-1.onnx", "joiner-epoch-99-avg-1.onnx", "tokens.txt"),
        "Zipformer ASR",
    )
    val TTS_KOKORO = ModelSpec(
        "kokoro-multi-lang-v1_1",
        // espeak-ng-data/ is a directory; checked separately to avoid 1-file checklist pain.
        listOf("model.onnx", "tokens.txt", "voices.bin"),
        "Kokoro TTS v1.1",
    )

    fun rootDir(ctx: Context): File = File(ctx.filesDir, FILE_ROOT).also { it.mkdirs() }
    fun modelDir(ctx: Context, subDir: String): File = File(rootDir(ctx), subDir)

    // -------- Presence checks --------

    /** True if the model spec is available either as bundled asset or on the local filesystem. */
    fun isInstalled(ctx: Context, spec: ModelSpec): Boolean {
        // Prefer on-disk copy (liteCloud downloads / sideloaded via adb).
        if (isOnDisk(ctx, spec)) return true
        // Otherwise check bundled assets (offlineFull flavor).
        return isInAssets(ctx.assets, spec)
    }

    fun isOnDisk(ctx: Context, spec: ModelSpec): Boolean {
        val dir = modelDir(ctx, spec.dirName)
        return dir.isDirectory &&
            spec.required.all { File(dir, it).exists() } &&
            // espeak-ng-data/ must exist if listed alongside Kokoro specs (checked by presence as dir).
            (!spec.required.contains("espeak-ng-data/") || File(dir, "espeak-ng-data").isDirectory)
    }

    private fun isInAssets(am: AssetManager, spec: ModelSpec): Boolean {
        return try {
            val list = am.list("${ASSET_ROOT}/${spec.dirName}") ?: return false
            spec.required.all { requiredFile ->
                if (requiredFile.endsWith("/")) {
                    // Directory check (e.g. "espeak-ng-data/")
                    list.any { it == requiredFile.trimEnd('/') }
                } else {
                    list.any { it == requiredFile }
                }
            }
        } catch (e: Exception) {
            false
        }
    }

    // -------- Path helpers (used by engine factories) --------

    /** A model file's location, abstracted over assets vs disk for the engine factory. */
    sealed class ModelPath {
        /** Path relative to assets/models/. Use together with an AssetManager. */
        data class Asset(val relativePath: String) : ModelPath()
        /** Absolute filesystem path. Use without an AssetManager. */
        data class Disk(val absolutePath: String) : ModelPath()
    }

    /**
     * Resolve the path of [fileName] inside [spec] directory. On-disk takes precedence
     * (so users can override the bundled copy by adb-pushing an updated model); falls back to
     * the bundled asset path when only that exists.
     */
    fun resolve(ctx: Context, spec: ModelSpec, fileName: String): ModelPath {
        val onDisk = File(modelDir(ctx, spec.dirName), fileName)
        if (onDisk.exists()) return ModelPath.Disk(onDisk.absolutePath)
        return ModelPath.Asset("${ASSET_ROOT}/${spec.dirName}/$fileName")
    }

    /** Resolve the whole spec directory as an asset-relative path (for AssetManager path input). */
    fun assetDir(spec: ModelSpec): String = "${ASSET_ROOT}/${spec.dirName}"

    /** Resolve the whole spec directory as a filesystem path (for absolute-path input). */
    fun diskDir(ctx: Context, spec: ModelSpec): File = modelDir(ctx, spec.dirName)

    /** Tiny shim: copy bundled assets to ctx.filesDir/models/. Used by liteCloud first-launch
     * logic in case a flavor bundles a few essential files (e.g. just the VAD ONNX).
     * Returns the count of copied files. */
    fun copyBundledModels(ctx: Context): Int {
        val dest = rootDir(ctx)
        val assets = ctx.assets
        var copied = 0
        val list = try { assets.list(ASSET_ROOT) ?: emptyArray() } catch (e: Exception) { emptyArray() }
        for (entry in list) {
            val target = File(dest, entry)
            if (target.exists()) continue
            try {
                assets.open("$ASSET_ROOT/$entry").use { input ->
                    target.outputStream().use { input.copyTo(it) }
                }
                copied++
            } catch (e: Exception) {
                // Asset may be a directory or an empty placeholder; skip.
            }
        }
        return copied
    }
}
