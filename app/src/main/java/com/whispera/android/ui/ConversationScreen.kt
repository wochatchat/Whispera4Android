package com.whispera.android.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.whispera.android.config.ModelInstaller
import com.whispera.android.pipeline.RealtimePipeline
import kotlinx.coroutines.launch

/**
 * Fullscreen conversation UI:
 *  - Top bar: title + state chip.
 *  - Body: scrollable message bubbles (user / assistant) and a live assistant partial.
 *  - Bottom: Start / Stop button + settings sheet (LLM baseURL, model, API key; TTS speaker id).
 *
 * Styling follows the dark desktop aesthetic of the original Whispera Electron app:
 *   #101318 window bg, user bubble #2A2F38, assistant bubble #1E3A5F.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationScreen(
    pipeline: RealtimePipeline,
    onStart: () -> Unit,
    onStop: () -> Unit,
    modelsReady: Boolean,
    onSettingsChange: () -> Unit,
    settings: ConversationSettings,
    onUpdateSettings: (ConversationSettings) -> Unit,
) {
    val state by pipeline.state.collectAsState()
    val transcript by pipeline.transcript.collectAsState()
    val partial by pipeline.partialAssistant.collectAsState()
    var micGranted by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Detect the default unconfigured LLM endpoint (no API key, pointing at localhost)
    // — the App won't get any reply without a remote LLM or a local llama-server. Surface
    // this prominently instead of the cryptic [error: ...] red bubble alone.
    val llmUnconfigured = settings.llmApiKey.isBlank() &&
        settings.llmBaseUrl.startsWith("http://127.0.0.1")

    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> micGranted = granted }

    LaunchedEffect(Unit) {
        if (micGranted.not()) micLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Whispera", color = Color(0xFFECEFF4)) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF1B1F26)),
                actions = {
                    StateChip(state)
                    IconButton(onClick = { showSettings = true }) {
                        Text("⚙", color = Color(0xFF7DD3FC), fontSize = 22.sp)
                    }
                }
            )
        },
        containerColor = Color(0xFF101318),
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp)
        ) {
            if (llmUnconfigured) {
                LlmUnconfiguredBanner(onOpenSettings = { showSettings = true })
                Spacer(Modifier.height(8.dp))
            }
            LazyColumn(
                modifier = Modifier.weight(1f),
                reverseLayout = true,
            ) {
                item {
                    val footer = partial.ifBlank { "" }
                    if (footer.isNotBlank() && state == RealtimePipeline.State.THINKING) {
                        MessageBubble(
                            role = RealtimePipeline.TurnRole.ASSISTANT,
                            text = footer + "▍",
                        )
                    }
                }
                items(transcript.reversed()) { turn ->
                    MessageBubble(role = turn.role, text = turn.text)
                    Spacer(Modifier.height(6.dp))
                }
            }

            // Bottom controls
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                Button(
                    onClick = {
                        scope.launch {
                            if (state == RealtimePipeline.State.IDLE) onStart() else onStop()
                        }
                    },
                    enabled = modelsReady && micGranted,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF38BDF8)),
                ) {
                    Text(
                        if (state == RealtimePipeline.State.IDLE) "▶ 开始对话" else "⏹ 停止",
                        color = Color.White,
                    )
                }
            }
            if (!modelsReady) {
                Text(
                    "尚未安装模型。请先运行 scripts/setup_models.sh。",
                    color = Color(0xFFFBBF24),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(8.dp)
                )
            }
        }
    }

    if (showSettings) {
        SettingsSheet(
            visible = showSettings,
            settings = settings,
            onUpdate = onUpdateSettings,
            onDismiss = { showSettings = false },
        )
    }
}

@Composable
private fun StateChip(state: RealtimePipeline.State) {
    val (color, label) = when (state) {
        RealtimePipeline.State.IDLE -> Color(0xFF9CA3AF) to "空闲"
        RealtimePipeline.State.LISTENING -> Color(0xFF7DD3FC) to "聆听"
        RealtimePipeline.State.THINKING -> Color(0xFFFBBF24) to "思考"
        RealtimePipeline.State.SPEAKING -> Color(0xFF38BDF8) to "说话"
    }
    Surface(
        color = color.copy(alpha = 0.2f),
        shape = RoundedCornerShape(10.dp),
    ) {
        Text(label, color = color, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
    }
}

@Composable
private fun LlmUnconfiguredBanner(onOpenSettings: () -> Unit) {
    Surface(
        color = Color(0xFF7F1D1D).copy(alpha = 0.85f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                "⚠ 未配置 \"大脑\" — 听不到回复",
                color = Color(0xFFFEE2E2),
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "本App内置语音识别和语音合成，但没有内置大语言模型。" +
                    "请点击右上角 ⚙ 填入一个 OpenAI 兼容的 API 地址（如 https://api.deepseek.com/v1）" +
                    "和 API Key（无需本地模型）。或在本机起 llama-server（参考 README）。",
                color = Color(0xFFFCA5A5),
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onOpenSettings, contentPadding = PaddingValues(0.dp)) {
                Text("打开设置", fontSize = 13.sp, color = Color(0xFFFCA5A5), fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun MessageBubble(role: RealtimePipeline.TurnRole, text: String) {
    val isUser = role == RealtimePipeline.TurnRole.USER
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            color = if (isUser) Color(0xFF2A2F38) else Color(0xFF1E3A5F),
            shape = RoundedCornerShape(14.dp, 14.dp, if (isUser) 14.dp else 0.dp, if (isUser) 0.dp else 14.dp),
            modifier = Modifier.widthIn(max = 320.dp)
        ) {
            Text(
                text,
                color = Color(0xFFECEFF4),
                fontSize = 14.sp,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
            )
        }
    }
    Spacer(Modifier.height(6.dp))
}

data class ConversationSettings(
    var llmBaseUrl: String,
    var llmApiKey: String,
    var llmModel: String,
    var ttsSpeakerId: Int,
    var ttsSpeed: Float,
    var asrLanguage: String,
)

/**
 * Used by [MainActivity] before any model is installed. It reuses the same look
 * but offers only a disabled button and the install warning banner. In liteCloud
 * builds (no bundled models) it also hosts a one-tap "下载模型" button that drives
 * [ModelInstaller] and shows live progress; in offlineFull builds the same button
 * is hidden because models should already be present in assets.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationScreenUnready(
    settings: ConversationSettings,
    onUpdateSettings: (ConversationSettings) -> Unit,
    onInstallModels: () -> Unit,
    onCancelInstall: () -> Unit,
    installerSnapshot: ModelInstaller.Snapshot?,
) {
    var showSettings by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Whispera", color = Color(0xFFECEFF4)) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF1B1F26)),
                actions = {
                    Surface(color = Color(0x22676767), shape = RoundedCornerShape(10.dp)) {
                        Text("未就绪", color = Color(0xFFFBBF24), fontSize = 12.sp, modifier = Modifier.padding(10.dp, 4.dp))
                    }
                    IconButton(onClick = { showSettings = true }) {
                        Text("⚙", color = Color(0xFF7DD3FC), fontSize = 22.sp)
                    }
                }
            )
        },
        containerColor = Color(0xFF101318),
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "尚未安装模型",
                color = Color(0xFFECEFF4),
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "首次使用需下载 VAD + ASR + TTS 模型 (~500 MB)。",
                color = Color(0xFFFBBF24),
                fontSize = 13.sp,
            )
            Spacer(Modifier.height(32.dp))

            ModelDownloadPanel(
                snapshot = installerSnapshot,
                onInstall = onInstallModels,
                onCancel = onCancelInstall,
            )

            Spacer(Modifier.height(40.dp))
            Button(
                onClick = {},
                enabled = false,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF374151)),
            ) {
                Text("▶ 开始对话", color = Color(0xFF6B7280))
            }
        }
    }
    if (showSettings) {
        SettingsSheet(visible = true, settings = settings, onUpdate = onUpdateSettings, onDismiss = { showSettings = false })
    }
}

@Composable
private fun ModelDownloadPanel(
    snapshot: ModelInstaller.Snapshot?,
    onInstall: () -> Unit,
    onCancel: () -> Unit,
) {
    Surface(
        color = Color(0xFF15191F),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(0.92f)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            when {
                snapshot == null -> {
                    Text("点击下方按钮下载并解包模型到设备。", fontSize = 13.sp, color = Color(0xFF9CA3AF))
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = onInstall,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF38BDF8)),
                    ) { Text("⬇ 下载模型 (~500 MB)", color = Color.White) }
                }
                snapshot.phase == ModelInstaller.Snapshot.Phase.DONE -> {
                    Text("✓ 模型已就绪", fontSize = 14.sp, color = Color(0xFF34D399), fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Text(snapshot.message, fontSize = 12.sp, color = Color(0xFF9CA3AF))
                }
                snapshot.phase == ModelInstaller.Snapshot.Phase.FAILED -> {
                    Text("✗ 下载失败", fontSize = 14.sp, color = Color(0xFFF87171), fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Text(snapshot.message, fontSize = 12.sp, color = Color(0xFFFCA5A5))
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onInstall, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF38BDF8))) {
                        Text("重试", color = Color.White)
                    }
                }
                else -> {
                    val phaseLabel = when (snapshot.phase) {
                        ModelInstaller.Snapshot.Phase.DOWNLOADING -> "下载中"
                        ModelInstaller.Snapshot.Phase.EXTRACTING -> "解包中"
                        else -> "进行中"
                    }
                    Text("${snapshot.currentSpecLabel} · $phaseLabel", fontSize = 13.sp, color = Color(0xFFECEFF4))
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = snapshot.percent / 100f,
                        modifier = Modifier.fillMaxWidth(),
                        color = Color(0xFF38BDF8),
                        trackColor = Color(0xFF2A2F38),
                    )
                    Spacer(Modifier.height(6.dp))
                    val mb = if (snapshot.totalBytes > 0) {
                        "%.1f / %.1f MB".format(snapshot.downed(), snapshot.total())
                    } else {
                        "%.1f MB".format(snapshot.downed())
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("$mb  ·  ${snapshot.percent}%", fontSize = 12.sp, color = Color(0xFF9CA3AF))
                        TextButton(onClick = onCancel) { Text("取消", fontSize = 12.sp, color = Color(0xFFF87171)) }
                    }
                    if (snapshot.message.isNotBlank()) {
                        Spacer(Modifier.height(2.dp))
                        Text(snapshot.message, fontSize = 11.sp, color = Color(0xFF7DD3FC))
                    }
                }
            }
        }
    }
}

private fun ModelInstaller.Snapshot.downed() = downloadedBytes / 1_000_000.0
private fun ModelInstaller.Snapshot.total() = totalBytes / 1_000_000.0

