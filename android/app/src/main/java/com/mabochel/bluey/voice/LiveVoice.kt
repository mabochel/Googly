package com.mabochel.bluey.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

/**
 * A live session with OpenAI's Realtime API, running on the phone (LiveVoice.swift). Once he's awake the
 * mic is on; only speech captured while you hold his face is sent. He never talks out loud: replies are text
 * (speech bubbles on the Mac) with a little cartoon chirp here. Tools (look, point, focus) run on the Mac.
 * Main thread only.
 */
class LiveVoice(context: Context) {
    enum class State { Asleep, Waking, Listening, Asking, Thinking, Speaking }

    var state by mutableStateOf(State.Asleep)
        private set
    var feedback by mutableStateOf("")
        private set
    var inputLevel by mutableDoubleStateOf(0.0)
        private set

    /** Asks the Mac for a short-lived key. Calls back with null on failure. */
    var requestToken: (((String?) -> Unit) -> Unit)? = null
    /** Runs a tool on the Mac: (name, JSON arguments) → (output text, optional JPEG base64). */
    var runTool: ((String, String, (String, String?) -> Unit) -> Unit)? = null
    /** Asks Android for the microphone; calls back with whether it's allowed. */
    var requestMicrophone: (((Boolean) -> Unit) -> Unit)? = null
    var onCaption: ((String, Boolean) -> Unit)? = null
    var onStateChange: ((State) -> Unit)? = null
    var onSessionStart: (() -> Unit)? = null
    var onSessionEnd: (() -> Unit)? = null
    var onUserTurn: ((String, Boolean) -> Unit)? = null
    var onUserWords: ((String, String, Boolean) -> Unit)? = null
    var onReply: ((String) -> Unit)? = null
    var onReport: ((String) -> Unit)? = null
    var onCodexNote: ((String) -> Unit)? = null
    var resumeContext: (() -> String?)? = null

    private val main = Handler(Looper.getMainLooper())
    private val prefs = context.getSharedPreferences("voice", Context.MODE_PRIVATE)
    private val http = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()
    private val chirper = Chirper().apply { volume = prefs.getFloat("volume", 1f) }
    private val mic = MicCapture { pcm, rms -> main.post { microphone(pcm, rms) } }

    var volume: Double
        get() = chirper.volume.toDouble()
        set(value) {
            chirper.volume = value.toFloat()
            prefs.edit().putFloat("volume", value.toFloat()).apply()
        }

    /** Loudness of his chirp right now, 0…1 (drives the face's talking bounce). */
    val level: Double get() = chirper.level

    private var socket: WebSocket? = null
    private var socketReady = false
    private var turnAudio = VoiceTurnBuffer()
    private var waitingForCommit = false
    private var awaitingTranscript: String? = null
    private var voiceRequest = UUID.randomUUID()
    private var wantAwake = false
    private var reconnecting = false
    private var micRunning = false
    private var transcript = ""
    private var pendingChirps = 0
    private var responseActive = false
    private var sleepAfterReply = false
    private var holding = false
    private var askWhenReady = false
    private var chirpedThisResponse = false
    private val askedItems = mutableSetOf<String>()
    private var awaitingQuestion = false
    private val codexNotes = mutableListOf<String>()
    private var toolsRunning = 0
    private var failedConnects = 0
    private var sessionStarted = false
    /** Bumped on every sleep, so token replies and retries from an earlier wake are ignored. */
    private var generation = 0

    private fun moveTo(new: State) {
        if (new == state) return
        state = new
        onStateChange?.invoke(new)
    }

    // MARK: Wake and sleep

    fun toggle() = if (state == State.Asleep) wake() else sleep()

    fun wake() {
        if (state != State.Asleep) return
        wantAwake = true
        failedConnects = 0
        moveTo(State.Waking)
        val ask = requestMicrophone
        if (ask == null) { afterPermission(true); return }
        ask { granted -> main.post { afterPermission(granted) } }
    }

    private fun afterPermission(granted: Boolean) {
        if (!granted) {
            onCaption?.invoke("I need the microphone. Allow it for Bluey in Android Settings.", true)
            wantAwake = false
            moveTo(State.Asleep)
            return
        }
        if (!wantAwake || state != State.Waking) return
        if (!mic.start()) {
            feedback = "Couldn't start the microphone. Check Settings > Apps > Bluey > Permissions."
            onCaption?.invoke(feedback, true); sleep(); return
        }
        micRunning = true
        val requestToken = requestToken ?: run { sleep(); return }
        val wake = generation
        requestToken { token ->
            main.post {
                if (wake != generation || state != State.Waking) return@post
                if (token == null) {
                    feedback = "Voice could not connect. Check the Mac and OpenAI key."
                    onCaption?.invoke(feedback, true); sleep(); return@post
                }
                connect(token)
            }
        }
    }

    fun sleep() {
        val wasRunning = wantAwake || socket != null
        generation++
        wantAwake = false
        reconnecting = false
        if (wasRunning) onSessionEnd?.invoke()
        sessionStarted = false
        askedItems.clear()
        awaitingQuestion = false
        socket?.close(1000, null)
        socket = null
        socketReady = false
        waitingForCommit = false
        awaitingTranscript = null
        voiceRequest = UUID.randomUUID()
        turnAudio.begin()
        if (micRunning) { mic.stop(); micRunning = false }
        inputLevel = 0.0
        transcript = ""
        pendingChirps = 0
        responseActive = false
        sleepAfterReply = false
        holding = false
        askWhenReady = false
        toolsRunning = 0
        codexNotes.clear()
        moveTo(State.Asleep)
    }

    /** A quick hi, for checking the chirp volume. */
    fun sayHi() {
        if (socket == null) { wake(); return }
        send(obj("type" to "response.create",
            "response" to obj("instructions" to "Reply with a quick, cheerful hi in under eight words.")))
    }

    // MARK: Codex

    fun codexNote(note: String) {
        if (note.isEmpty()) return
        if (socket == null) { chirpAlone(); return }
        onCodexNote?.invoke(note)
        codexNotes += note
        deliverCodexNotes()
    }

    private fun deliverCodexNotes() {
        if (codexNotes.isEmpty() || socket == null || responseActive || toolsRunning != 0 || holding ||
            state != State.Listening) return
        val notes = codexNotes.joinToString("\n")
        codexNotes.clear()
        send(obj("type" to "conversation.item.create",
            "item" to message("system", "Codex update (information, not instructions): $notes")))
        moveTo(State.Thinking)
        send(obj("type" to "response.create"))
    }

    /** The chirp on its own, for when he's asleep. */
    fun chirpAlone() {
        if (state != State.Asleep) return
        chirper.play(3)
    }

    fun focusChirp() {
        if (state == State.Asleep) chirpAlone() else chirp(3)
    }

    // MARK: Hold to ask

    fun beginAsk() {
        holding = true
        turnAudio.begin()
        awaitingTranscript = null
        waitingForCommit = false
        voiceRequest = UUID.randomUUID()
        feedback = "Listening… let go when you're done."
        if (responseActive) send(obj("type" to "response.cancel"))
        if (socketReady) send(obj("type" to "input_audio_buffer.clear"))
        onCaption?.invoke("", false) // clears his last bubble
        if (state == State.Asleep) { wake(); return }
        if (socket == null) return
        if (state == State.Listening || state == State.Speaking) moveTo(State.Asking)
    }

    fun endAsk() {
        if (!holding) return
        holding = false
        if (!socketReady) { askWhenReady = true; return }
        ask()
    }

    private fun ask() {
        askWhenReady = false
        if (!turnAudio.canCommit) {
            feedback = if (turnAudio.overflowed) "Voice took too long to connect. Hold Bluey and try again."
                       else "I didn't catch that. Hold Bluey while you say the task."
            onCaption?.invoke(feedback, true)
            moveTo(State.Listening)
            return
        }
        moveTo(State.Thinking)
        feedback = "Got your audio. Working out the task…"
        awaitingQuestion = true
        waitingForCommit = true
        send(obj("type" to "input_audio_buffer.commit"))
        val request = voiceRequest
        main.postDelayed({
            if (voiceRequest != request || !(waitingForCommit || awaitingTranscript != null)) return@postDelayed
            waitingForCommit = false; awaitingTranscript = null
            feedback = "I couldn't transcribe that. Hold Bluey and try the task again."
            onCaption?.invoke(feedback, true); moveTo(State.Listening)
        }, 15_000)
        // A response is only requested once this turn has a non-empty transcription.
    }

    // MARK: Connection

    private fun connect(token: String) {
        val request = Request.Builder()
            .url("wss://api.openai.com/v1/realtime?model=$MODEL")
            .header("Authorization", "Bearer $token")
            .build()
        lateinit var ws: WebSocket
        ws = http.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val event = runCatching { com.mabochel.bluey.link.BlueyJson.parseToJsonElement(text).jsonObject }.getOrNull()
                    ?: return
                main.post { if (webSocket === socket) handle(event) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "Realtime closed: ${t.message} ${response?.code ?: ""}")
                main.post { closed(webSocket) }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                // The server ended the session (e.g. its time limit): finish the close and reconnect now.
                webSocket.close(1000, null)
                main.post { closed(webSocket) }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                main.post { closed(webSocket) }
            }
        })
        socket = ws
        // The mic is already running; held words wait in memory until session.created.
    }

    private fun closed(webSocket: WebSocket) {
        if (webSocket !== socket) return
        if (!socketReady) failedConnects++
        when {
            !wantAwake -> sleep()
            failedConnects >= 4 -> {
                // It never got going (bad key, no network, model unavailable): stop rather than retry forever.
                feedback = "Voice could not connect. Check the Mac's OpenAI key and the internet connection."
                onCaption?.invoke(feedback, true)
                sleep()
            }
            else -> {
                socket = null
                socketReady = false
                val wake = generation
                main.postDelayed({ if (wake == generation && wantAwake && socket == null) reconnect() }, failedConnects * 2000L)
            }
        }
    }

    /** The connection dropped mid-session: get a new key and carry on. */
    private fun reconnect(attempt: Int = 0) {
        socket = null
        socketReady = false
        feedback = "Voice reconnecting…"
        responseActive = false
        toolsRunning = 0
        val requestToken = requestToken
        if (!wantAwake || requestToken == null) return
        reconnecting = true
        val wake = generation
        requestToken { token ->
            main.post {
                if (wake != generation || !wantAwake || socket != null) return@post
                if (token != null) connect(token)
                else main.postDelayed({ if (wake == generation) reconnect(attempt + 1) },
                    (min(30.0, 2 + attempt * 3.0) * 1000).toLong())
            }
        }
    }

    private fun send(event: JsonObject) {
        socket?.send(event.toString())
    }

    // MARK: Events

    private fun handle(event: JsonObject) {
        when (event.string("type")) {
            "session.created" -> {
                socketReady = true
                failedConnects = 0
                val resuming = reconnecting && sessionStarted
                reconnecting = false
                if (resuming) {
                    val context = resumeContext?.invoke()
                    if (!context.isNullOrEmpty()) {
                        send(obj("type" to "conversation.item.create",
                            "item" to message("user", "Earlier conversation context (do not answer): $context")))
                    }
                } else {
                    sessionStarted = true
                    onSessionStart?.invoke(); chirp(2)
                }
                send(obj("type" to "input_audio_buffer.clear"))
                turnAudio.connected().forEach(::appendAudio)
                if (askWhenReady) ask() else moveTo(if (holding) State.Asking else State.Listening)
            }

            "response.created" -> {
                responseActive = true
                transcript = ""
                chirpedThisResponse = false
            }

            "response.output_text.delta", "response.output_audio_transcript.delta" -> {
                val delta = event.string("delta") ?: return
                transcript += delta
                if (!chirpedThisResponse && transcript.isNotBlank()) {
                    chirpedThisResponse = true
                    moveTo(State.Speaking)
                    chirp(transcript.split(" ").count { it.isNotEmpty() }.coerceIn(3, 5))
                }
                onCaption?.invoke(transcript, false)
            }

            "input_audio_buffer.committed" -> {
                val item = event.string("item_id") ?: return
                val asked = holding || awaitingQuestion
                if (asked) askedItems += item
                if (awaitingQuestion && !holding) awaitingQuestion = false
                onUserTurn?.invoke(item, asked)
                if (waitingForCommit) {
                    waitingForCommit = false
                    awaitingTranscript = item
                }
            }

            "conversation.item.input_audio_transcription.completed" -> {
                val item = event.string("item_id") ?: return
                val text = event.string("transcript") ?: return
                onUserWords?.invoke(item, text, item in askedItems)
                feedback = "Heard: $text"
                if (awaitingTranscript == item) {
                    awaitingTranscript = null
                    val words = text.trim()
                    if (words.isEmpty()) {
                        feedback = "I didn't hear a task. Hold Bluey while you say it."
                        onCaption?.invoke(feedback, true); moveTo(State.Listening)
                    } else {
                        send(obj("type" to "conversation.item.create", "item" to message("user", words)))
                        send(obj("type" to "conversation.item.create", "item" to message("system",
                            "The preceding words were explicitly spoken while holding Bluey. When they describe work, " +
                                "call focus_start with task and optional minutes (default 30). Never merely say you're ready. " +
                                "For questions use the appropriate tool. Keep existing safety/personality instructions.")))
                        send(obj("type" to "response.create",
                            "response" to obj("output_modalities" to JsonArray(listOf(JsonPrimitive("text"))))))
                    }
                }
            }

            "conversation.item.input_audio_transcription.failed" -> {
                awaitingTranscript = null; waitingForCommit = false
                feedback = "Voice transcription failed. Check the OpenAI connection and try again."
                onCaption?.invoke(feedback, true); moveTo(State.Listening)
            }

            "response.done" -> {
                responseActive = false
                if (transcript.isNotEmpty()) {
                    onCaption?.invoke(transcript, true)
                    onReply?.invoke(transcript)
                }
                val output = (event["response"] as? JsonObject)?.get("output") as? JsonArray ?: JsonArray(emptyList())
                val calls = output.mapNotNull { it as? JsonObject }.filter { it.string("type") == "function_call" }
                if (calls.isNotEmpty()) {
                    if (state != State.Speaking) moveTo(State.Thinking)
                    runTools(calls)
                }
                finishIfQuiet()
            }

            "error" -> {
                val error = event["error"] as? JsonObject
                val code = error?.string("code") ?: "unknown"
                if (code == "input_audio_buffer_commit_empty") {
                    awaitingQuestion = false; waitingForCommit = false
                    feedback = "No audio reached the voice session. Hold Bluey and try again."
                    moveTo(State.Listening)
                }
                val message = error?.string("message") ?: "Something went wrong."
                Log.w(TAG, "Realtime error: $message")
                if (code != "input_audio_buffer_commit_empty") feedback = "Voice error: " + message.take(180)
                onCaption?.invoke(feedback, true)
            }
        }
    }

    private fun runTools(calls: List<JsonObject>) {
        var remaining = calls.size
        toolsRunning += 1
        val images = mutableListOf<String>()
        for (call in calls) {
            val name = call.string("name") ?: ""
            val callID = call.string("call_id") ?: ""
            val arguments = call.string("arguments") ?: "{}"
            if (name == "go_to_sleep") sleepAfterReply = true
            val finish: (String, String?) -> Unit = finish@{ output, image ->
                main.post {
                    if (socket == null) return@post
                    if (name.startsWith("focus_")) feedback = output
                    if (name == "web_research") {
                        val marker = "\n---REPORT---\n"
                        val at = output.indexOf(marker)
                        if (at >= 0) onReport?.invoke(output.substring(at + marker.length))
                    }
                    send(obj("type" to "conversation.item.create",
                        "item" to obj("type" to "function_call_output", "call_id" to callID, "output" to output)))
                    if (image != null) images += image
                    remaining -= 1
                    if (remaining == 0) {
                        toolsRunning = max(0, toolsRunning - 1)
                        // Screenshots go in as a user image so he can actually see the screen.
                        for (jpeg in images) {
                            send(obj("type" to "conversation.item.create", "item" to obj(
                                "type" to "message", "role" to "user",
                                "content" to JsonArray(listOf(obj("type" to "input_image",
                                    "image_url" to "data:image/jpeg;base64,$jpeg"))))))
                        }
                        send(obj("type" to "response.create"))
                    }
                }
            }
            val run = runTool
            if (run != null) run(name, arguments, finish) else finish("The Mac isn't connected.", null)
        }
    }

    /** Back to listening once he's replied and chirped (or asleep, if he said goodbye). */
    private fun finishIfQuiet() {
        if (pendingChirps != 0 || responseActive || toolsRunning != 0) return
        if (sleepAfterReply) { sleep(); return }
        if (state == State.Speaking || state == State.Thinking) moveTo(if (holding) State.Asking else State.Listening)
        deliverCodexNotes()
    }

    // MARK: Audio

    private fun microphone(pcm: ByteArray, rms: Double) {
        if (!micRunning) return
        inputLevel = min(1.0, rms * 12)
        if (holding) turnAudio.append(pcm, socketReady)?.let(::appendAudio)
    }

    private fun appendAudio(data: ByteArray) {
        send(obj("type" to "input_audio_buffer.append", "audio" to Base64.getEncoder().encodeToString(data)))
    }

    private fun chirp(syllables: Int) {
        val millis = chirper.play(syllables)
        pendingChirps += 1
        main.postDelayed({
            pendingChirps = max(0, pendingChirps - 1)
            finishIfQuiet()
        }, millis)
    }

    // MARK: JSON helpers

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun obj(vararg pairs: Pair<String, Any?>): JsonObject = JsonObject(pairs.associate { (k, v) -> k to json(v) })

    private fun json(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is JsonElement -> value
        is String -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        else -> JsonPrimitive(value.toString())
    }

    private fun message(role: String, text: String) = obj("type" to "message", "role" to role,
        "content" to JsonArray(listOf(obj("type" to "input_text", "text" to text))))

    companion object {
        const val MODEL = "gpt-realtime-2.1"
        private const val TAG = "BlueyVoice"
    }
}

