package com.whispera.android.segmenter

/**
 * Streaming sentence segmenter — direct port of Whispera's
 * `realtime/text_segmenter.py:StreamingTextSegmenter`.
 *
 * Why this matters: LLM streams token deltas; TTS is sentence-level.
 * We feed the buffer into TTS only on sentence-final punctuation so each
 * generated utterance is prosodically coherent. A hard_limit force-flush
 * keeps a runaway sentence from delaying first audio beyond ~1s.
 *
 * Default hard_limit=120 characters balances first-audio latency vs
 * number of TTS invocations on a CPU device.
 */
class StreamingTextSegmenter(private val hardLimit: Int = 120) {

    private val sentencePunct = setOf('.', '!', '?', '。', '！', '？', '；', ';', '\n')
    private var buffer = StringBuilder()

    /** Feed a text delta from the LLM stream; returns zero or more ready sentences. */
    fun feed(textDelta: String): List<String> {
        val chunks = mutableListOf<String>()
        if (textDelta.isEmpty()) return chunks
        for (ch in textDelta) {
            buffer.append(ch)
            if (ch in sentencePunct) {
                flush()?.let { chunks.add(it) }
            } else if (buffer.trim().length >= hardLimit) {
                flushWordSafe()?.let { chunks.add(it) }
            }
        }
        return chunks
    }

    /** Force-flush remaining buffer at end of stream. */
    fun flush(): String? = flushInternal()

    fun reset() { buffer.setLength(0) }

    private fun flushInternal(): String? {
        val v = buffer.toString().trim()
        buffer.setLength(0)
        return if (v.isEmpty()) null else v
    }

    /** Force-flush at hard_limit without splitting a word (mirrors Python `_flush_word_safe`). */
    private fun flushWordSafe(): String? {
        val s = buffer.toString().trim()
        val cut = s.lastIndexOf(' ')
        if (cut <= 0) {
            buffer.setLength(0)
            return if (s.isEmpty()) null else s
        }
        val head = s.substring(0, cut).trim()
        buffer = StringBuilder(s.substring(cut + 1).trim())
        return if (head.isEmpty()) null else head
    }
}
