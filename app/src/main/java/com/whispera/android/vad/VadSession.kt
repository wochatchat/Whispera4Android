package com.whispera.android.vad

import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import java.io.File
import java.util.ArrayDeque

/**
 * Voice turn state machine — direct port of Whispera's
 * `realtime/vad_session.py:RealtimeSession`, talking to Sherpa-ONNX's [Vad].
 *
 * Feeds 16kHz PCM chunks (size=window samples, 512 for Silero) into the VAD
 * model via [Vad.compute] to get a per-window speech probability, and emits:
 *   - LISTENING: silence / below-threshold audio; nothing to transcribe yet.
 *   - INTERRUPT: speech resumes while the assistant is speaking (barge-in) —
 *                the pipeline must stop the current TTS playback immediately.
 *   - SPEECH_END: enough silence after speech — flush the buffered samples to ASR.
 *
 * The preroll ring buffer preserves ~1s of audio before speech_started so that
 * ASR gets the start of the utterance rather than clipping the first syllable.
 *
 * This class is NOT thread-safe; the pipeline owns a single instance used on a
 * dedicated background thread.
 */
class VadSession(
    private val vad: Vad,
    private val config: VadSessionConfig = VadSessionConfig(),
) {
    data class VadSessionConfig(
        val sampleRate: Int = 16000,
        val threshold: Float = 0.5f,
        val minSpeechMs: Int = 128,
        val minSilenceMs: Int = 800,
        val window: Int = 512,           // Silero VAD @16k expects 512-sample chunks
        val prerollMs: Int = 1000,
    )

    enum class Event { LISTENING, INTERRUPT, SPEECH_END }

    private val minSpeech = config.sampleRate * config.minSpeechMs / 1000
    private val minSilence = config.sampleRate * config.minSilenceMs / 1000
    private val prerollFrames = maxOf(1, (config.sampleRate * config.prerollMs / 1000 + config.window - 1) / config.window)

    private val buffer = mutableListOf<FloatArray>()
    private val ring = ArrayDeque<FloatArray>()
    private var speaking = false
    var generating = false                // set true by pipeline while LLM/TTS produces audio
        private set
    private var speechSamples = 0
    private var silenceSamples = 0
    private var tailSilence = 0

    fun setGenerating(value: Boolean) { generating = value }

    fun reset() {
        vad.reset()
        buffer.clear()
        ring.clear()
        speaking = false
        generating = false
        speechSamples = 0
        silenceSamples = 0
        tailSilence = 0
    }

    /**
     * Push a chunk of PCM samples. The chunk is processed window-by-window.
     * Returns the highest-priority Event observed during this call.
     */
    fun pushChunk(chunk: FloatArray): Event {
        var event = Event.LISTENING
        var i = 0
        while (i < chunk.size) {
            val end = minOf(i + config.window, chunk.size)
            var frame = chunk.copyOfRange(i, end)
            if (frame.size < config.window) {
                frame = frame.copyOf(config.window)
            }
            i = end
            val prob = vad.compute(frame)
            if (prob > config.threshold) {
                silenceSamples = 0
                tailSilence = 0
                speechSamples += frame.size
                buffer.add(frame)
                if (speechSamples >= minSpeech && !speaking) {
                    speaking = true
                    val preroll = ring.toList()
                    ring.clear()
                    buffer.addAll(0, preroll)
                }
                if (generating && speaking) {
                    event = Event.INTERRUPT
                    return event
                }
            } else if (speaking) {
                silenceSamples += frame.size
                tailSilence++
                buffer.add(frame)
                if (silenceSamples >= minSilence) {
                    if (tailSilence > 1) {
                        // Drop trailing silence frames (keep one for naturalness).
                        buffer.subList(buffer.size - (tailSilence - 1), buffer.size).clear()
                    }
                    speaking = false
                    speechSamples = 0
                    silenceSamples = 0
                    tailSilence = 0
                    event = Event.SPEECH_END
                    return event
                }
            } else {
                // Silence, not speaking yet: stash in preroll ring.
                if (speechSamples > 0) buffer.clear()
                speechSamples = 0
                ring.addLast(frame)
                while (ring.size > prerollFrames) ring.removeFirst()
            }
        }
        return event
    }

    /** Drain and clear the accumulated speech audio. */
    fun getAudio(): FloatArray {
        if (buffer.isEmpty()) return FloatArray(0)
        val total = buffer.sumOf { it.size }
        val out = FloatArray(total)
        var pos = 0
        for (b in buffer) {
            System.arraycopy(b, 0, out, pos, b.size)
            pos += b.size
        }
        buffer.clear()
        return out
    }

    companion object {
        /** Build a Silero VAD-backed session from a silero_vad.onnx file. */
        fun fromSilero(
            modelFile: File,
            threshold: Float = 0.5f,
            minSilenceMs: Int = 800,
            minSpeechMs: Int = 128,
        ): VadSession {
            require(modelFile.exists()) { "VAD model not found: ${modelFile.absolutePath}" }
            val vad = Vad(
                config = VadModelConfig(
                    sileroVadModelConfig = SileroVadModelConfig(
                        model = modelFile.absolutePath,
                        threshold = threshold,
                        minSilenceDuration = minSilenceMs / 1000.0f,
                        minSpeechDuration = minSpeechMs / 1000.0f,
                        windowSize = 512,
                    ),
                    sampleRate = 16000,
                    numThreads = 1,
                    provider = "cpu",
                )
            )
            return VadSession(
                vad = vad,
                config = VadSessionConfig(
                    threshold = threshold,
                    minSilenceMs = minSilenceMs,
                    minSpeechMs = minSpeechMs,
                    window = 512,
                )
            )
        }
    }
}
