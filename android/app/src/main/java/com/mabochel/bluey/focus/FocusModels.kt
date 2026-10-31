package com.mabochel.bluey.focus

import kotlinx.serialization.Serializable
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max

/**
 * Mac-authoritative focus state, decoded from the Mac's Swift JSON (Shared/FocusSession.swift).
 * Swift encodes dates as seconds since 2001-01-01 and UUIDs as uppercase strings.
 */
object FocusActivity {
    const val ON_TASK = "on_task"
    const val OFF_TASK = "off_task"
    const val UNCERTAIN = "uncertain"
    const val UNAVAILABLE = "unavailable"
    const val PAUSED = "paused"
    val estimated = setOf(ON_TASK, OFF_TASK, UNCERTAIN)

    fun name(activity: String): String = when (activity) {
        ON_TASK -> "On task"
        OFF_TASK -> "Off task"
        UNCERTAIN -> "Unknown"
        UNAVAILABLE -> "Unavailable"
        PAUSED -> "Paused"
        else -> activity
    }
}

@Serializable
data class FocusCheck(
    val id: String = "",
    val elapsed: Double = 0.0,
    val activity: String = FocusActivity.UNAVAILABLE,
    val probability: Double = 0.0,
    val confidence: Double = 0.0,
    /** Full choice estimates. Missing in history from older builds. */
    val probabilities: Map<String, Double>? = null,
    /** Focus-clock seconds this observation covers; older checks used 30. */
    val sampleDuration: Double? = null,
    val nudged: Boolean = false,
) {
    fun estimate(category: String): Double? {
        if (activity !in FocusActivity.estimated || category !in FocusActivity.estimated) return null
        // Older checks kept only P(off_task). Never infer P(on_task) from its complement.
        val value = probabilities?.get(category) ?: if (category == FocusActivity.OFF_TASK) probability else null
        return value?.takeIf { it.isFinite() && it in 0.0..1.0 }
    }
}

@Serializable
data class FocusSession(
    val id: String = "",
    val task: String = "",
    val duration: Double = 1800.0,
    /** Seconds since 2001-01-01 (Swift's reference date). */
    val startedAt: Double = 0.0,
    val elapsed: Double = 0.0,
    val status: String = RUNNING,
    val detail: String = "",
    val checks: List<FocusCheck> = emptyList(),
    val developerMode: Boolean? = null,
    val usedDeveloperMode: Boolean? = null,
    val realElapsed: Double? = null,
) {
    val isDeveloperMode get() = developerMode == true
    val clockRate get() = if (isDeveloperMode) 4.0 else 1.0
    val realActiveElapsed get() = realElapsed ?: elapsed
    val remaining get() = max(0.0, duration - elapsed)
    val isEnded get() = status == ENDED
    val isRunning get() = status == RUNNING
    val startedMillis: Long get() = ((startedAt + SWIFT_EPOCH_OFFSET) * 1000).toLong()

    companion object {
        const val RUNNING = "running"
        const val PAUSED = "paused"
        const val ENDED = "ended"
        /** Seconds between 1970-01-01 and 2001-01-01. */
        const val SWIFT_EPOCH_OFFSET = 978_307_200.0

        fun clock(seconds: Double): String {
            val n = max(0, ceil(seconds).toInt())
            return String.format(Locale.US, "%02d:%02d", n / 60, n % 60)
        }
    }
}

@Serializable
data class FocusSync(
    val current: FocusSession? = null,
    val history: List<FocusSession> = emptyList(),
    val revision: Int = 0,
    val sentAt: Double = 0.0,
    val developerMode: Boolean? = null,
)

fun Double.focusPercent(): String {
    val p = this * 100
    val rounded = Math.round(p * 10) / 10.0
    return if (rounded % 1.0 == 0.0) "${rounded.toInt()}%" else String.format(Locale.getDefault(), "%.1f%%", rounded)
}
