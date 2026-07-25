package com.whispera.android.config

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.util.concurrent.TimeUnit

/**
 * Runtime model installer for the liteCloud flavor.
 *
 * Downloads each model spec defined in [ModelManager.INSTALL_ORDER] from its
 * `url` (a single .onnx file OR a .tar.bz2 archive) and lays it out on disk
 * under `ctx.filesDir/models/<spec.dirName>/` exactly as [ModelManager] expects,
 * so subsequent `isInstalled()` checks return true and `buildPipeline()` can run.
 *
 * Designed to be driven from a foreground coroutine in the UI; emits progress as
 * a small data class [Snapshot]. Use [installAll] to run the full sequence and
 * observe progress via [onProgress].
 *
 * Why this lives here instead of in scripts/setup_models.sh: liteCloud ships
 * WITHOUT models (the CI workflow strips them before packaging — see
 * .github/workflows/build-apk.yml). The user needs an in-app way to obtain the
 * ~500 MB of models without plugging the phone into a dev machine.
 *
 * Why commons-compress: bzip2 is not part of the Android stdlib. We depend on
 * org.apache.commons:commons-compress (added in app/build.gradle.kts) which is
 * pure Java and ~500 KB — cheap next to the 500 MB of models it unpacks.
 */
class ModelInstaller(
    private val ctx: Context,
    private val client: OkHttpClient = DEFAULT_CLIENT,
) {

    /** Compact, immutable progress snapshot; safe to snapshot for Compose state. */
    data class Snapshot(
        val phase: Phase,
        val currentSpecLabel: String,
        val downloadedBytes: Long,
        val totalBytes: Long,        // 0 = unknown until Content-Length arrives.
        val message: String,
    ) {
        enum class Phase { IDLE, DOWNLOADING, EXTRACTING, DONE, FAILED }

        /** 0..100 (clamped). Returns 0 when total is unknown. */
        val percent: Int
            get() = if (totalBytes > 0) ((downloadedBytes * 100L / totalBytes).toInt()).coerceIn(0, 100) else 0
    }

    private var cancelled = false

    /** Cancel any in-flight download; the next spec boundary breaks out cleanly. */
    fun cancel() { cancelled = true }

    /**
     * Download + lay out every spec in [ModelManager.INSTALL_ORDER] that is not
     * already installed. Calls [onProgress] on every tick (downloading/extraction
     * byte advance + spec transitions) — UI updates are cheap because callers can
     * throttle if needed.
     *
     * Returns true if all specs ended up installed; false if cancelled or any fail.
     */
    fun installAll(onProgress: (Snapshot) -> Unit): Boolean {
        cancelled = false
        for (spec in ModelManager.INSTALL_ORDER) {
            if (ModelManager.isOnDisk(ctx, spec)) {
                onProgress(Snapshot(Snapshot.Phase.DONE, spec.label, 0, 0, "${spec.label} 已存在"))
                continue
            }
            val ok = try {
                installOne(spec, onProgress)
            } catch (t: Throwable) {
                onProgress(Snapshot(Snapshot.Phase.FAILED, spec.label, 0, 0, "${spec.label}: ${t.message ?: t.javaClass.simpleName}"))
                return false
            }
            if (!ok) {
                onProgress(Snapshot(Snapshot.Phase.FAILED, spec.label, 0, 0, "${spec.label}: 取消"))
                return false
            }
        }
        onProgress(Snapshot(Snapshot.Phase.DONE, "全部完成", 0, 0, "全部模型安装完成"))
        return true
    }

    /** Fetch one spec. Returns true on success, false if cancelled. */
    private fun installOne(spec: ModelManager.ModelSpec, onProgress: (Snapshot) -> Unit): Boolean {
        val url = spec.url ?: throw IOException("spec ${spec.dirName} has no url")
        val outDir = ModelManager.modelDir(ctx, spec.dirName).apply { mkdirs() }

        if (!spec.extract) {
            // Single-file download.
            val target = File(outDir, spec.required.first())
            if (target.exists()) return true
            downloadTo(url, target, spec, onProgress)
            return true
        }

        // Archive (.tar.bz2) → stream-decompress directly into outDir without
        // buffering the whole tarball on disk (some specs are >300 MB).
        val req = Request.Builder().url(url).build()
        client.newCall(req).execute().use { resp ->
            if (resp.code != HttpURLConnection.HTTP_OK) {
                throw IOException("HTTP ${resp.code} fetching $url")
            }
            val total = resp.body?.contentLength() ?: -1L
            val teeing = CountingInputStream(resp.body?.byteStream() ?: throw IOException("empty body"))
            onProgress(Snapshot(Snapshot.Phase.DOWNLOADING, spec.label, 0, total, "下载 ${spec.label}…"))

            TarArchiveInputStream(BZip2CompressorInputStream(teeing)).use { tar ->
                while (true) {
                    if (cancelled) return false
                    val entry: TarArchiveEntry = tar.nextTarEntry ?: break
                    if (!entry.isDirectory) {
                        val baseName = File(entry.name).name
                        val wanted: Boolean = spec.required.any { it == baseName } ||
                            (spec.includeDirectory != null && entry.name.contains(spec.includeDirectory + "/"))
                        if (wanted) {
                            unpack(tar, entry, outDir, spec, onProgress, teeing.count, total)
                        }
                    }
                }
            }
        }
        return true
    }

    /** Copy one tar entry to disk; emit an EXTRACTING snapshot. */
    private fun unpack(
        tar: TarArchiveInputStream,
        entry: TarArchiveEntry,
        outDir: File,
        spec: ModelManager.ModelSpec,
        onProgress: (Snapshot) -> Unit,
        bytesSoFar: Long,
        total: Long,
    ) {
        val rel = entry.name.substringAfter('/')
        val target = File(outDir, rel)
        target.parentFile?.mkdirs()
        FileOutputStream(target).use { out ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                if (cancelled) return
                val n = tar.read(buf)
                if (n <= 0) break
                out.write(buf, 0, n)
            }
        }
        onProgress(Snapshot(Snapshot.Phase.EXTRACTING, spec.label, bytesSoFar, total,
            "解包 ${File(entry.name).name}…"))
    }

    /** Download to a single target file with progress ticks. */
    private fun downloadTo(
        url: String,
        target: File,
        spec: ModelManager.ModelSpec,
        onProgress: (Snapshot) -> Unit,
    ) {
        val req = Request.Builder().url(url).build()
        client.newCall(req).execute().use { resp ->
            if (resp.code != HttpURLConnection.HTTP_OK) {
                throw IOException("HTTP ${resp.code} fetching $url")
            }
            val total = resp.body?.contentLength() ?: -1L
            target.parentFile?.mkdirs()
            val counter = CountingInputStream(resp.body?.byteStream() ?: throw IOException("empty body"))
            onProgress(Snapshot(Snapshot.Phase.DOWNLOADING, spec.label, 0, total, "下载 ${spec.label}…"))
            target.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    if (cancelled) return
                    val n = counter.read(buf)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    onProgress(Snapshot(Snapshot.Phase.DOWNLOADING, spec.label, counter.count, total, ""))
                }
            }
        }
    }

    /** InputStream that counts the bytes it has handed out (read-only). */
    private class CountingInputStream(private val inner: java.io.InputStream) : java.io.InputStream() {
        @Volatile var count: Long = 0
            private set
        override fun read(): Int {
            val b = inner.read()
            if (b >= 0) count++
            return b
        }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = inner.read(b, off, len)
            if (n > 0) count += n
            return n
        }
    }

    companion object {
        private val DEFAULT_CLIENT: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.MINUTES)     // allow for big downloads
            .retryOnConnectionFailure(true)
            .build()
    }
}
