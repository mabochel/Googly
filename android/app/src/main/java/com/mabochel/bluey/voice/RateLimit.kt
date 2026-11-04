package com.mabochel.bluey.voice

import kotlin.math.min
import kotlin.math.roundToLong

/** How long to wait before retrying a reply that OpenAI refused with rate_limit_exceeded. */
object RateLimit {
    const val MAX_TRIES = 3
    private const val CAP = 20_000L
    private val hint = Regex("""try again in ([0-9]+(?:\.[0-9]+)?)\s*(ms|s)""", RegexOption.IGNORE_CASE)

    /** OpenAI's "try again in 1.2s" / "in 300ms" hint when present (0.5–20 s), else 2 s doubling per attempt. */
    fun delayMillis(message: String?, attempt: Int): Long {
        val match = message?.let { hint.find(it) }
        if (match != null) {
            val value = match.groupValues[1].toDouble()
            val millis = if (match.groupValues[2].lowercase() == "ms") value else value * 1000
            return millis.roundToLong().coerceIn(500L, CAP)
        }
        return min(CAP, 2_000L shl attempt.coerceIn(0, 4))
    }
}
