package com.whispera.android.pipeline

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import com.whispera.android.asr.AsrEngine
import com.whispera.android.config.AppConfig
import com.whispera.android.llm.LlmClient
import com.whispera.android.segmenter.StreamingTextSegmenter
import com.whispera.android.tts.TtsEngine
import com.whispera.android.vad.VadSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * End-to-end real-time voice conversation pipeline:
 *
 *   AudioRecord(16kHz mono PCM)
 *       └─→ VadSession         (Silero ONNX, barge-in detection)
 *           └─→ AsrEngine       (SenseVoice ONNX)
 *               └─→ LlmClient.stream()  (OpenAI-compatible SSE)
 *                   └─→ StreamingTextSegmenter  (sentence-level chunking)
 *                       └─→ TtsEngine.generateStreaming()  (Kokoro ONNX)
 *                           └─→ AudioTrack    (immediate playback)
 *
 * User-facing State:
 *  IDLE            : not running
 *  LISTENING       : mic captured, VAD waiting for speech
 *  THINKING        : ASR done, awaiting + streaming LLM output
 *  SPEAKING        : TTS chunks flowing to speaker
 *  INTERRUPTED     : barge-in detected during SPEAKING — TTS stops, mic re-arms
 *
 * Design notes:
 *  - One coroutine per full conversation turn; heavy inference happens on Dispatchers.Default.
 *  - Barge-in works because the audio capture loop runs concurrently with the
 *    TTS coroutine: when VAD returns INTERRUPT during SPEAKING, we cancel the
 *    current TTS job (stops AudioTrack + TtsEngine.stopGeneration), empty the
 *    LLM stream, and immediately re-arm the mic -> near-zero turn gap.
 *  - Sentence-level streaming: the segmenter emits a sentence as soon as a
 *    sentence-final punct arrives from the LLM; each sentence triggers a TTS
 *    generation. First audio typically within ~0.3-1.0 s of first LLM token,
 *    yielding the "speak-along" feel.
 */
class RealtimePipeline(
    private val config: AppConfig,
    private val vad: VadSession,
    private val asr: AsrEngine,
    private val tts: TtsEngine,
) {
    enum class State { IDLE, LISTENING, THINKING, SPEAKING }

    enum class TurnRole { USER, ASSISTANT }
    data class TurnTranscript(val role: TurnRole, val text: String)

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _transcript = MutableStateFlow<List<TurnTranscript>>(emptyList())
    val transcript: StateFlow<List<TurnTranscript>> = _transcript.asStateFlow()

    private val _partialAssistant = MutableStateFlow("")
    val partialAssistant: StateFlow<String> = _partialAssistant.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var captureJob: Job? = null
    private var ttsJob: Job? = null

    private val running = AtomicBoolean(false)
    private val conversationHistory = mutableListOf<LlmClient.ChatMessage>()

    // Audio capture
    private val sampleRate = 16000
    private val captureBufSize = AudioRecord.getMinBufferSize(
        sampleRate,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT
    ).coerceAtLeast(1024)
    private var recorder: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var audioTrackLock = Any()

    /** Start the realtime session. Returns false if mic init fails. */
    suspend fun start(): Boolean = withContext(Dispatchers.IO) {
        if (running.get()) return@withContext false
        // Init recorder
        val rec = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            captureBufSize
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return@withContext false
        }
        recorder = rec
        rec.startRecording()
        running.set(true)
        conversationHistory.clear()
        _transcript.value = emptyList()
        _partialAssistant.value = ""
        vad.reset()
        vad.setGenerating(false)
        _state.value = State.LISTENING

        captureJob = scope.launch { audioLoop() }
        true
    }

    fun stop() {
        running.set(false)
        captureJob?.cancel(); captureJob = null
        ttsJob?.cancel(); ttsJob = null
        synchronized(audioTrackLock) { audioTrack?.run { stop(); release() }; audioTrack = null }
        recorder?.run { stop(); release() }
        recorder = null
        tts.stopGeneration()
        vad.reset()
        vad.setGenerating(false)
        _state.value = State.IDLE
    }

    fun release() {
        scope.cancel()
        stop()
        vad.reset()
        asr.release()
        tts.release()
    }

    private suspend fun audioLoop() {
        val buf = ShortArray(1024)        // ~64ms at 16kHz
        while (running.get()) {
            val rec = recorder ?: return
            val n = rec.read(buf, 0, buf.size)
            if (n <= 0) continue
            val chunk = FloatArray(n) { buf[it] / 32768.0f }
            val event = vad.pushChunk(chunk)
            when (event) {
                VadSession.Event.LISTENING -> {
                    if (_state.value == State.SPEAKING || _state.value == State.THINKING) {
                        // Continue in current state.
                    } else {
                        _state.value = State.LISTENING
                    }
                }
                VadSession.Event.INTERRUPT -> {
                    onBargeIn()
                }
                VadSession.Event.SPEECH_END -> {
                    handleUserTurn()
                }
            }
        }
    }

    private fun onBargeIn() {
        if (config.enableBargeIn) {
            tts.stopGeneration()
            ttsJob?.cancel()
            synchronized(audioTrackLock) {
                audioTrack?.run { stop(); release() }
                audioTrack = null
            }
            vad.setGenerating(false)
            _state.value = State.LISTENING
        }
    }

    private fun handleUserTurn() {
        val audio = vad.getAudio()
        if (audio.size < sampleRate / 20) return   // too short — likely a click
        _state.value = State.THINKING
        val userSession = scope.launch {
            try {
                // 1) ASR
                val text = withContext(Dispatchers.Default) { asr.recognize(audio) }
                if (text.isBlank()) {
                    _state.value = State.LISTENING
                    vad.setGenerating(false)
                    return@launch
                }
                appendTranscript(TurnRole.USER, text)
                conversationHistory.add(LlmClient.ChatMessage("user", text))

                // 2) LLM streaming + segmenter + TTS
                val client = LlmClient.fromConfig(config)
                val segmenter = StreamingTextSegmenter(hardLimit = config.segmenterHardLimit)
                val assistantText = StringBuilder()

                vad.setGenerating(true)
                _state.value = State.THINKING

                client.stream(conversationHistory.withSystem(config)).collect { delta ->
                    assistantText.append(delta)
                    _partialAssistant.value = assistantText.toString()
                    val sentences = segmenter.feed(delta)
                    if (sentences.isNotEmpty() && config.enableTtsAfterLlm) {
                        for (sentence in sentences) {
                            speakSentence(sentence)
                        }
                    }
                }
                // Flush remainder
                val tail = segmenter.flush()
                if (!tail.isNullOrBlank() && config.enableTtsAfterLlm) {
                    speakSentence(tail)
                }

                val full = assistantText.toString().trim()
                if (full.isNotBlank()) {
                    conversationHistory.add(LlmClient.ChatMessage("assistant", full))
                    appendTranscript(TurnRole.ASSISTANT, full)
                }
                _partialAssistant.value = ""
                vad.setGenerating(false)
                _state.value = State.LISTENING
            } catch (t: Throwable) {
                android.util.Log.e("WhisperaTurn", "handleUserTurn FAILED", t)
                _partialAssistant.value = "[error: ${t.message ?: t.javaClass.simpleName}]"
                vad.setGenerating(false)
                _state.value = State.LISTENING
            }
        }
    }

    private suspend fun speakSentence(sentence: String) {
        _state.value = State.SPEAKING
        withContext(Dispatchers.IO) {
            // Block until the TTS generation + AudioTrack playback of this sentence has finished.
            // Time taken here bounds the time before the next segment's TTS starts; that's
            // gladly acceptable: we're feeding sentence-sized chunks, fine-grained on purpose.
            if (ttsJob?.isActive == true) ttsJob?.join()
            ttsJob = scope.launch(Dispatchers.IO) {
                try {
                    val track = ensureAudioTrack()
                    if (track.state != AudioTrack.STATE_INITIALIZED) {
                        android.util.Log.e("WhisperaTTS", "AudioTrack NOT initialized: state=${track.state}")
                        _partialAssistant.value = "[tts: AudioTrack state=${track.state}]"
                        return@launch
                    }
                    track.play()
                    android.util.Log.i("WhisperaTTS", "TTS start: '$sentence' sr=${tts.sampleRate} sid=${config.ttsSpeakerId} speed=${config.ttsSpeed}")
                    var chunks = 0
                    tts.generateStreaming(
                        text = sentence,
                        sid = config.ttsSpeakerId,
                        speed = config.ttsSpeed,
                    ) { samples ->
                        synchronized(audioTrackLock) {
                            val t = audioTrack ?: return@generateStreaming
                            val pcm = ShortArray(samples.size) { (samples[it] * 32767f).toInt().toShort() }
                            t.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING)
                        }
                        chunks++
                    }
                    android.util.Log.i("WhisperaTTS", "TTS done: chunks=$chunks")
                } catch (t: Throwable) {
                    android.util.Log.e("WhisperaTTS", "TTS FAILED", t)
                    _partialAssistant.value = "[tts error: ${t.message ?: t.javaClass.simpleName}]"
                }
            }
            ttsJob?.join()
        }
    }

    private fun ensureAudioTrack(): AudioTrack {
        synchronized(audioTrackLock) {
            val existing = audioTrack
            if (existing != null) return existing
            val size = AudioTrack.getMinBufferSize(tts.sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
                .coerceAtLeast(tts.sampleRate / 4)
            val t = AudioTrack.Builder()
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setSampleRate(tts.sampleRate)
                        .build()
                )
                .setBufferSizeInBytes(size)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            audioTrack = t
            return t
        }
    }

    private fun appendTranscript(role: TurnRole, text: String) {
        val current = _transcript.value.toMutableList()
        current.add(TurnTranscript(role, text))
        _transcript.value = current
    }

    private fun List<LlmClient.ChatMessage>.withSystem(c: AppConfig): List<LlmClient.ChatMessage> {
        return listOf(LlmClient.ChatMessage("system", c.llmSystemPrompt)) + this
    }
}
