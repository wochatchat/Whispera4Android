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
 * but offers only a disabled button and the install warning banner.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationScreenUnready(
    settings: ConversationSettings,
    onUpdateSettings: (ConversationSettings) -> Unit,
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
                "请在开发机上运行 ./scripts/setup_models.sh 下载模型到 app/src/main/assets/models",
                color = Color(0xFFFBBF24),
                fontSize = 13.sp,
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

