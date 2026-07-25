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
        // --- Runtime installer fields (liteCloud UI) ---
        /** Optional remote URL of an archive (.tar.bz2) OR a single raw file (when [extract]=false).
         *  Null means this spec has no remote source (offlineFull bundles it instead). */
        val url: String? = null,
        /** True → the URL is a tar.bz2 whose first matching directory contains the files.
         *  False → the URL is the file itself, written directly to dirName/<name in required>. */
        val extract: Boolean = true,
        /** When [extract]=true, the file inside the unpacked tarball is matched to [required]
         *  by exact name; the (*.onnx / tokens.txt / voices.bin / lexicon.txt / espeak-ng-data/).
         *  We list only leaf files we want; if [includeDirectory] is set we copy a whole subtree. */
        val includeDirectory: String? = null,
        /** Human-readable size hint shown in the download UI (purely cosmetic). */
        val sizeHint: String = "",
    )

    val VAD = ModelSpec(
        "silero_vad",
        listOf("silero_vad.onnx"),
        "Silero VAD",
        url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx",
        extract = false,
        sizeHint = "~2 MB",
    )
    val ASR_SENSEVOICE = ModelSpec(
        "sensevoice",
        listOf("model.int8.onnx", "tokens.txt"),
        "SenseVoice ASR (int8)",
        url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.tar.bz2",
        extract = true,
        sizeHint = "~163 MB",
    )
    val ASR_ZIPFORMER = ModelSpec(
        "zipformer",
        listOf("encoder-epoch-99-avg-1.onnx", "decoder-epoch-99-avg-1.onnx", "joiner-epoch-99-avg-1.onnx", "tokens.txt"),
        "Zipformer ASR",
        url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23.tar.bz2",
        extract = true,
        sizeHint = "~74 MB",
    )
    val TTS_KOKORO = ModelSpec(
        "kokoro-multi-lang-v1_1",
        // espeak-ng-data/ is a directory; checked separately to avoid 1-file checklist pain.
        listOf("model.onnx", "tokens.txt", "voices.bin"),
        "Kokoro TTS v1.1",
        url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-multi-lang-v1_1.tar.bz2",
        extract = true,
        includeDirectory = "espeak-ng-data",
        sizeHint = "~350 MB",
    )

    /** Specs the liteCloud runtime installer must materialize, in download order. */
    val INSTALL_ORDER: List<ModelSpec> = listOf(VAD, ASR_SENSEVOICE, TTS_KOKORO)

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
        if (!dir.isDirectory) return false
        if (!spec.required.all { File(dir, it).exists() }) return false
        // Optional full-directory component (e.g. espeak-ng-data/ for Kokoro).
        spec.includeDirectory?.let { if (!File(dir, it).isDirectory) return false }
        return true
    }

    private fun isInAssets(am: AssetManager, spec: ModelSpec): Boolean {
        return try {
            val list = am.list("${ASSET_ROOT}/${spec.dirName}") ?: return false
            if (!spec.required.all { list.any { entry -> entry == it } }) return false
            // Optional full-directory component.
            spec.includeDirectory?.let { dir ->
                if (list.none { it == dir }) return false
            }
            true
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
     * Returns the count of copied files.
     *
     * NOTE: previous implementation called `assets.open("$ASSET_ROOT/$entry")` on every
     * top-level entry — but those entries are *directories* (silero_vad/, sensevoice/,
     * kokoro-multi-lang-v1_1/), so open() always threw IOException and was silently
     * swallowed, meaning NOTHING was ever copied. Now we recurse. The on-disk copy is
     * prefered by [isInstalled]/[resolve] anyway, so this only matters when a flavor
     * ships partial assets that need to land on disk (e.g. a tiny VAD ONNX in lite
     * builds) — but fixing it removes a footgun and stops the swallowed-exception noise. */
    fun copyBundledModels(ctx: Context): Int {
        val dest = rootDir(ctx)
        val assets = ctx.assets
        var copied = arrayOf(0)
        val list = try { assets.list(ASSET_ROOT) ?: emptyArray() } catch (e: Exception) { emptyArray() }
        for (entry in list) {
            copyAssetTree(assets, "$ASSET_ROOT/$entry", File(dest, entry), copied)
        }
        return copied[0]
    }

    /** Recursively copy an asset path (file or directory) onto disk. */
    private fun copyAssetTree(
        am: AssetManager,
        assetPath: String,
        destFile: File,
        counter: Array<Int>,
    ) {
        // Try listing: if it returns a non-empty array, treat as directory; else, open as file.
        val children = try { am.list(assetPath) } catch (e: Exception) { null }
        if (children != null && children.isNotEmpty()) {
            destFile.mkdirs()
            for (child in children) {
                // Skip placeholder files committed to keep empty dirs in git.
                if (child == ".gitkeep") continue
                copyAssetTree(am, "$assetPath/$child", File(destFile, child), counter)
            }
            return
        }
        // File case — copy bytes.
        if (destFile.exists()) return
        try {
            destFile.parentFile?.mkdirs()
            am.open(assetPath).use { input ->
                destFile.outputStream().use { input.copyTo(it) }
            }
            counter[0] = counter[0] + 1
        } catch (e: Exception) {
            // Could not open — skip.
        }
    }
}
