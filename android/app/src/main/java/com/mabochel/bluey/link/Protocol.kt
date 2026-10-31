package com.mabochel.bluey.link

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Everything the Mac and the phone agree on. Mirrors Shared/GooglyLink.swift exactly:
 * same Bonjour type, same JSON field names, nils left out.
 */
object GooglyService {
    const val TYPE = "_googly._tcp"
}

/** One JSON setup shared by the link, focus state and session files. */
val BlueyJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
    coerceInputValues = true
}

enum class Mood {
    listening, resting, thinking, talking, pointing, happy, sleepy;

    companion object {
        /** Unknown moods from a newer Mac fall back to listening instead of breaking the link. */
        fun of(name: String?): Mood = entries.firstOrNull { it.name == name } ?: listening
    }
}

/** What the phone should show right now. Gaze x: -1 left … 1 right, y: -1 up … 1 down. */
@Serializable
data class FaceState(
    val gazeX: Double = 0.0,
    val gazeY: Double = 0.0,
    val mood: String = "listening",
    /** Voice level 0…1, drives the talking bounce. */
    val talk: Double = 0.0,
)

@Serializable
data class Packet(
    val face: FaceState? = null,
    /** Sent once by each side after connecting, with a device name. */
    val hello: String? = null,
    val volume: Double? = null,
    /** A request or event, e.g. "realtimeToken", "tool", "focusStart", "wake". */
    val command: String? = null,
    val audio: String? = null,
    val speech: Int? = null,
    /** Pairs a request with its reply. */
    val callID: String? = null,
    val tool: String? = null,
    val text: String? = null,
    /** A JPEG (base64) that goes with a tool result. */
    val image: String? = null,
)
