package com.whispera.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Bottom-drawer settings panel for runtime configuration: LLM endpoint, TTS voice and speed,
 * ASR language. Each change is debounced by 500ms and propagated to the parent via onUpdate so
 * AppConfig is reloaded while a session is running.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    visible: Boolean,
    settings: ConversationSettings,
    onUpdate: (ConversationSettings) -> Unit,
    onDismiss: () -> Unit,
) {
    if (!visible) return
    var baseUrl by remember { mutableStateOf(settings.llmBaseUrl) }
    var apiKey by remember { mutableStateOf(settings.llmApiKey) }
    var model by remember { mutableStateOf(settings.llmModel) }
    var speakerId by remember { mutableStateOf(settings.ttsSpeakerId.toString()) }
    var speed by remember { mutableStateOf(settings.ttsSpeed.toString()) }
    var asrLang by remember { mutableStateOf(settings.asrLanguage) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .padding(horizontal = 24.dp, vertical = 12.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text("大模型", color = Color.Black, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            LabeledField("API 地址", baseUrl) { baseUrl = it }
            LabeledField("密钥（可选）", apiKey) { apiKey = it }
            LabeledField("模型名", model) { model = it }

            Spacer(Modifier.height(20.dp))
            Text("语音合成", color = Color.Black, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            LabeledField("音色 ID (0-99)", speakerId) { speakerId = it }
            LabeledField("语速", speed) { speed = it }

            Spacer(Modifier.height(20.dp))
            Text("语音识别", color = Color.Black, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            LabeledField("ASR 语言 (zh/en/ja/ko/auto)", asrLang) { asrLang = it }

            Spacer(Modifier.height(24.dp))
        }
    }

    // 500 ms after any field changes, push the updated settings up to the ViewModel.
    LaunchedEffect(baseUrl, apiKey, model, speakerId, speed, asrLang) {
        kotlinx.coroutines.delay(500L)
        onUpdate(
            ConversationSettings(
                llmBaseUrl = baseUrl,
                llmApiKey = apiKey,
                llmModel = model,
                ttsSpeakerId = speakerId.toIntOrNull() ?: settings.ttsSpeakerId,
                ttsSpeed = speed.toFloatOrNull() ?: settings.ttsSpeed,
                asrLanguage = asrLang,
            )
        )
    }
}

@Composable
private fun LabeledField(label: String, value: String, onValueChange: (String) -> Unit) {
    Column(modifier = Modifier.padding(vertical = 6.dp)) {
        Text(label, color = Color(0xFF6B7280), fontSize = 12.sp)
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
