package com.mabochel.bluey.sessions

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mabochel.bluey.link.BlueyJson
import com.mabochel.bluey.link.MacLink
import com.mabochel.bluey.link.Packet
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.io.File
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** One line in a session: something you said, a question you asked by holding, his replies and reports. */
@Serializable
data class TranscriptEntry(
    val id: String = UUID.randomUUID().toString(),
    val kind: String,
    val text: String,
    /** Epoch milliseconds. */
    val time: Long,
    /** The Realtime conversation item this came from, so its words can be filled in when they arrive. */
    val itemID: String? = null,
    val speaker: String? = null,
) {
    companion object {
        const val HEARD = "heard"
        const val ASKED = "asked"
        const val REPLY = "reply"
        const val REPORT = "report"
        const val TRANSCRIPT = "transcript"
        const val CODEX = "codex"
    }
}

/** Everything from one wake-to-sleep session. */
@Serializable
data class BlueySession(
    val id: String = UUID.randomUUID().toString().uppercase(),
    val started: Long,
    val ended: Long? = null,
    val entries: List<TranscriptEntry> = emptyList(),
    /** Where the Mac saved this session's notes. */
    val notesPath: String? = null,
) {
    val questionCount get() = entries.count { it.kind == TranscriptEntry.ASKED }

    val displayEntries: List<TranscriptEntry>
        get() {
            val properUntil = entries.filter { it.kind == TranscriptEntry.TRANSCRIPT }.maxOfOrNull { it.time } ?: Long.MIN_VALUE
            return entries.filter { it.text.isNotEmpty() && !(it.kind == TranscriptEntry.HEARD && it.time <= properUntil) }
                .sortedBy { it.time }
        }

    val summary: String
        get() = (entries.firstOrNull { it.kind == TranscriptEntry.ASKED && it.text.isNotEmpty() }
            ?: displayEntries.firstOrNull { it.kind == TranscriptEntry.TRANSCRIPT || it.kind == TranscriptEntry.HEARD })
            ?.text ?: "Nothing said yet"

    fun duration(now: Long = System.currentTimeMillis()) = ((ended ?: now) - started) / 1000.0

    val exportText: String
        get() {
            val time = SimpleDateFormat("h:mm:ss a", Locale.getDefault())
            val lines = mutableListOf("Bluey session, ${formatStart(started)}", "")
            for (entry in displayEntries) {
                val who = when (entry.kind) {
                    TranscriptEntry.TRANSCRIPT -> "Speaker ${entry.speaker ?: "?"}"
                    TranscriptEntry.HEARD -> "You"
                    TranscriptEntry.ASKED -> "You asked"
                    TranscriptEntry.REPLY -> "Bluey"
                    TranscriptEntry.REPORT -> "Research"
                    else -> "Codex"
                }
                lines += "[${time.format(Date(entry.time))}] $who: ${entry.text}"
            }
            return lines.joinToString("\n")
        }

    companion object {
        fun formatStart(millis: Long): String =
            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(millis))
    }
}

/** Keeps every session on the phone as one JSON file, and copies changed ones to the Mac's notes folder. */
class SessionStore(context: Context) {
    var sessions by mutableStateOf<List<BlueySession>>(emptyList()) // newest first
        private set
    var currentID by mutableStateOf<String?>(null)
        private set

    private val file = File(context.filesDir, "sessions.json")
    private val serializer = ListSerializer(BlueySession.serializer())
    private val main = Handler(Looper.getMainLooper())
    private val saveWork = Runnable { save() }
    private val syncWork = Runnable { flushSync() }
    private val dirty = mutableSetOf<String>()
    var link: MacLink? = null

    init {
        val saved = runCatching { BlueyJson.decodeFromString(serializer, file.readText()) }.getOrDefault(emptyList())
        // A session left open by a crash or force-quit is closed at its last line.
        sessions = saved.map { if (it.ended == null) it.copy(ended = it.entries.lastOrNull()?.time ?: it.started) else it }
    }

    val current get() = sessions.firstOrNull { it.id == currentID }

    fun start() {
        end()
        val session = BlueySession(started = System.currentTimeMillis())
        sessions = listOf(session) + sessions
        currentID = session.id
        save()
    }

    fun end() {
        val id = currentID ?: return
        val session = sessions.firstOrNull { it.id == id } ?: return
        currentID = null
        sessions = if (session.entries.all { it.text.isEmpty() } && System.currentTimeMillis() - session.started < 20_000) {
            sessions.filterNot { it.id == id } // a few seconds with nothing said: don't keep it
        } else {
            dirty += id
            scheduleSync()
            sessions.map {
                if (it.id == id) it.copy(ended = System.currentTimeMillis(), entries = it.entries.filter { e -> e.text.isNotEmpty() }) else it
            }
        }
        save()
    }

    /** Holds a spot for something you said, in order. The words arrive a moment later. */
    fun placeholder(itemID: String, asked: Boolean) = update { s ->
        if (s.entries.any { it.itemID == itemID }) s
        else s.copy(entries = s.entries + entry(if (asked) TranscriptEntry.ASKED else TranscriptEntry.HEARD, "", itemID))
    }

    fun heard(itemID: String, text: String, asked: Boolean) {
        val words = text.trim()
        update { s ->
            val index = s.entries.indexOfFirst { it.itemID == itemID }
            when {
                index >= 0 -> s.copy(entries = s.entries.toMutableList().also {
                    val old = it[index]
                    it[index] = old.copy(text = words, kind = if (asked) TranscriptEntry.ASKED else old.kind)
                })
                words.isNotEmpty() -> s.copy(entries = s.entries + entry(if (asked) TranscriptEntry.ASKED else TranscriptEntry.HEARD, words, itemID))
                else -> s
            }
        }
    }

    fun reply(text: String) = append(TranscriptEntry.REPLY, text)
    fun report(text: String) = append(TranscriptEntry.REPORT, text)
    fun codex(text: String) { if (currentID != null) append(TranscriptEntry.CODEX, text) }

    fun delete(id: String) {
        if (id == currentID) currentID = null
        sessions = sessions.filterNot { it.id == id }
        save()
    }

    /** Recent words from this session, so a fresh voice connection keeps context. */
    fun recentText(limit: Int = 6000): String? {
        val text = current?.displayEntries?.filter { it.kind != TranscriptEntry.REPORT }
            ?.joinToString("\n") { (if (it.kind == TranscriptEntry.REPLY) "Bluey: " else "") + it.text } ?: return null
        return text.takeIf { it.isNotEmpty() }?.takeLast(limit)
    }

    /** Re-sends recent sessions after the phone reconnects. */
    fun resyncRecent() {
        val cutoff = System.currentTimeMillis() - 3 * 86_400_000L
        dirty += sessions.filter { it.started > cutoff }.map { it.id }
        scheduleSync()
    }

    private fun append(kind: String, text: String) {
        val words = text.trim()
        if (words.isEmpty()) return
        update { it.copy(entries = it.entries + entry(kind, words, null)) }
    }

    private fun entry(kind: String, text: String, itemID: String?) =
        TranscriptEntry(kind = kind, text = text, time = System.currentTimeMillis(), itemID = itemID)

    private fun update(change: (BlueySession) -> BlueySession) {
        val id = currentID ?: return
        update(id, change)
    }

    private fun update(id: String, change: (BlueySession) -> BlueySession) {
        if (sessions.none { it.id == id }) return
        sessions = sessions.map { if (it.id == id) change(it) else it }
        dirty += id
        scheduleSync()
        main.removeCallbacks(saveWork)
        main.postDelayed(saveWork, 1000)
    }

    private fun setNotesPath(id: String, path: String) {
        if (sessions.none { it.id == id && it.notesPath != path }) return
        sessions = sessions.map { if (it.id == id) it.copy(notesPath = path) else it }
        main.removeCallbacks(saveWork)
        main.postDelayed(saveWork, 1000)
    }

    private fun save() {
        main.removeCallbacks(saveWork)
        runCatching { file.writeText(BlueyJson.encodeToString(serializer, sessions)) }
    }

    // MARK: Sync to the Mac (it writes the notes files)

    private fun scheduleSync() {
        main.removeCallbacks(syncWork)
        main.postDelayed(syncWork, 6000)
    }

    private fun flushSync() {
        val link = link ?: return
        if (!link.connected) return
        val changed = sessions.filter { it.id in dirty }
        dirty.clear()
        for (session in changed) {
            val entries = session.displayEntries.map { e ->
                buildJsonObject {
                    put("kind", JsonPrimitive(e.kind))
                    put("text", JsonPrimitive(e.text))
                    put("time", JsonPrimitive(e.time / 1000.0))
                    e.speaker?.let { put("speaker", JsonPrimitive(it)) }
                }
            }
            val json = buildJsonObject {
                put("id", JsonPrimitive(session.id))
                put("started", JsonPrimitive(session.started / 1000.0))
                put("transcribing", JsonPrimitive(0))
                put("entries", JsonArray(entries))
                session.ended?.let { put("ended", JsonPrimitive(it / 1000.0)) }
            }
            link.request(Packet(command = "notes", text = JsonObject(json).toString())) { reply ->
                reply?.text?.let { setNotesPath(session.id, it) }
            }
        }
    }
}
