package com.mabochel.bluey.focus

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mabochel.bluey.link.BlueyJson
import com.mabochel.bluey.link.MacLink
import com.mabochel.bluey.link.Packet
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.io.File

/** The phone's view of the Mac's focus timer: synced state, a heartbeat, and commands. Main thread only. */
class FocusClient(context: Context) {
    var current by mutableStateOf<FocusSession?>(null)
        private set
    var history by mutableStateOf<List<FocusSession>>(emptyList())
        private set
    /** True while the Mac's state is recent enough to act on. */
    var fresh by mutableStateOf(false)
        private set
    var developerMode by mutableStateOf(false)
        private set
    var nudgeText by mutableStateOf("")
        private set
    var message by mutableStateOf("")

    var onFinished: (() -> Unit)? = null

    private var revision = -1
    private var receivedAt = 0L
    private var lastNudgedAt = 0L
    private var link: MacLink? = null
    private val main = Handler(Looper.getMainLooper())
    private val file = File(context.filesDir, "bluey-focus-history.json")
    private val sessions = ListSerializer(FocusSession.serializer())

    init {
        history = runCatching { BlueyJson.decodeFromString(sessions, file.readText()) }.getOrDefault(emptyList())
    }

    private val tick = object : Runnable {
        override fun run() {
            if (link?.connected == true) link?.send(Packet(command = "focusPing"))
            if (SystemClock.elapsedRealtime() - receivedAt > 5000) fresh = false
            main.postDelayed(this, 1000)
        }
    }

    fun attach(link: MacLink) {
        this.link = link
        main.removeCallbacks(tick)
        main.post(tick)
    }

    fun connectionChanged(connected: Boolean) {
        fresh = false
        revision = -1
        if (connected) link?.send(Packet(command = "focusSync"))
    }

    fun receive(text: String) {
        val state = runCatching { BlueyJson.decodeFromString(FocusSync.serializer(), text) }.getOrNull() ?: return
        if (state.revision <= revision) return
        val old = current
        val finished = old != null && old.id == state.current?.id && !old.isEnded && state.current?.isEnded == true
        revision = state.revision
        receivedAt = SystemClock.elapsedRealtime()
        fresh = true
        if (old?.id != state.current?.id) nudgeText = ""
        current = state.current
        history = state.history
        developerMode = state.developerMode ?: false
        runCatching { file.writeText(BlueyJson.encodeToString(sessions, history)) }
        if (finished) onFinished?.invoke()
    }

    fun receivedNudge(text: String) {
        nudgeText = text
        val stamp = SystemClock.elapsedRealtime()
        lastNudgedAt = stamp
        main.postDelayed({ if (lastNudgedAt == stamp) nudgeText = "" }, 10_000)
    }

    fun command(command: String, task: String? = null, minutes: Double = 30.0, developerMode: Boolean? = null) {
        val link = link
        if (link == null || !link.connected) { message = "Connect your paired Mac first."; return }
        val args = buildJsonObject {
            put("task", JsonPrimitive(task ?: ""))
            put("minutes", JsonPrimitive(minutes))
            if (developerMode != null) put("developerMode", JsonPrimitive(developerMode))
        }
        link.request(Packet(command = command, text = args.toString())) { reply ->
            message = reply?.text ?: "The Mac didn't answer. Monitoring will pause if disconnected."
        }
    }
}
