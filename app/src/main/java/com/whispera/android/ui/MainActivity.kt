package com.whispera.android.ui

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.whispera.android.config.AppConfig
import com.whispera.android.config.ModelInstaller
import com.whispera.android.config.ModelManager
import com.whispera.android.asr.AsrEngine
import com.whispera.android.tts.TtsEngine
import com.whispera.android.vad.VadSession
import com.whispera.android.pipeline.RealtimePipeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private lateinit var prefs: SharedPreferences
    private var config: AppConfig = AppConfig()
    private var pipeline: RealtimePipeline? = null

    /** Live snapshot of the runtime model installer (null = never started).
     *  Wrapped in a Compose-observable holder so the UI re-renders on every progress tick. */
    private var installerSnapshot by mutableStateOf<ModelInstaller.Snapshot?>(null)
    private var installer: ModelInstaller? = null

    private fun buildPipeline() {
        val cfg = config
        val vad = VadSession.fromContext(this, cfg.vadThreshold, cfg.vadMinSilenceMs)
        val asr = if (cfg.asrModelDir.startsWith("zipformer")) {
            AsrEngine.fromZipformerContext(this)
        } else {
            AsrEngine.fromContext(this, cfg.asrLanguage, cfg.asrUseItN)
        }
        val tts = TtsEngine.fromContext(this)
        pipeline = RealtimePipeline(config = cfg, vad = vad, asr = asr, tts = tts)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("whispera", Context.MODE_PRIVATE)
        config = AppConfig.load(prefs)
        ModelManager.copyBundledModels(this)

        setContent {
            // Lightweight session-scope state holders.
            var modelsReady by remember { mutableStateOf(checkModelsReady()) }
            var settings by remember { mutableStateOf(settingsToUi(config)) }

            LaunchedEffect(Unit) {
                if (modelsReady && pipeline == null) {
                    // Catch Throwable (not just Exception): sherpa-onnx native init can
                    // throw UnsatisfiedLinkError / OutOfMemoryError when a model fails to
                    // load, which are Error subclasses that would otherwise crash the app.
                    try { buildPipeline() } catch (e: Throwable) { modelsReady = false }
                }
            }

            val pipe = pipeline
            if (pipe == null) {
                ConversationScreenUnready(
                    settings = settings,
                    onUpdateSettings = { s -> persistSettings(s); settings = s },
                    onInstallModels = { runModelInstaller { modelsReady = checkModelsReady() } },
                    onCancelInstall = { installer?.cancel() },
                    installerSnapshot = installerSnapshot,
                )
            } else {
                ConversationScreen(
                    pipeline = pipe,
                    onStart = { lifecycleScope.launch { pipe.start() } },
                    onStop = { pipe.stop() },
                    modelsReady = true,
                    settings = settings,
                    onSettingsChange = { },
                    onUpdateSettings = { s -> persistSettings(s); settings = s },
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        installer?.cancel()
        pipeline?.release()
        pipeline = null
    }

    /**
     * Drives [ModelInstaller.installAll] off the main thread. Each progress snapshot
     * is published to [installerSnapshot] (a Compose-observable property) so the
     * unready screen's download panel re-renders live. On success (or if the user
     * had sideloaded models in the meantime) [onDone] re-checks readiness, which
     * flips the UI to the conversation screen via the [modelsReady] state holder.
     */
    private fun runModelInstaller(onDone: () -> Unit) {
        if (installer != null) return            // already running
        val inst = ModelInstaller(this)
        installer = inst
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                inst.installAll { snap -> installerSnapshot = snap }
            }
            installer = null
            installerSnapshot = if (ok) {
                ModelInstaller.Snapshot(ModelInstaller.Snapshot.Phase.DONE, "完成", 0, 0, "全部模型就绪")
            } else {
                // keep last snapshot visible so the user sees the failure message
                installerSnapshot ?: ModelInstaller.Snapshot(
                    ModelInstaller.Snapshot.Phase.FAILED, "?", 0, 0, "已取消或失败"
                )
            }
            onDone()
        }
    }

    private fun checkModelsReady(): Boolean {
        val v = ModelManager.isInstalled(this, ModelManager.VAD)
        val a = ModelManager.isInstalled(this, ModelManager.ASR_SENSEVOICE) ||
            ModelManager.isInstalled(this, ModelManager.ASR_ZIPFORMER)
        val t = ModelManager.isInstalled(this, ModelManager.TTS_KOKORO)
        return v && a && t
    }

    private fun persistSettings(s: ConversationSettings) {
        config.apply {
            llmBaseUrl = s.llmBaseUrl
            llmApiKey = s.llmApiKey
            llmModel = s.llmModel
            ttsSpeakerId = s.ttsSpeakerId
            ttsSpeed = s.ttsSpeed
            asrLanguage = s.asrLanguage
        }
        config.save(prefs)
    }

    private fun settingsToUi(c: AppConfig) = ConversationSettings(
        llmBaseUrl = c.llmBaseUrl,
        llmApiKey = c.llmApiKey,
        llmModel = c.llmModel,
        ttsSpeakerId = c.ttsSpeakerId,
        ttsSpeed = c.ttsSpeed,
        asrLanguage = c.asrLanguage,
    )
}
