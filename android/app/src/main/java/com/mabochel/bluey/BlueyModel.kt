package com.mabochel.bluey

import android.app.Application
import com.mabochel.bluey.focus.FocusClient
import com.mabochel.bluey.link.MacLink
import com.mabochel.bluey.link.Mood
import com.mabochel.bluey.link.Packet
import com.mabochel.bluey.sessions.SessionStore
import com.mabochel.bluey.ui.FaceAnimator
import com.mabochel.bluey.voice.LiveVoice

/** Lives as long as the app process, so nothing resets when Android recreates the screen. */
class BlueyApplication : Application() {
    val model by lazy { BlueyModel(this) }
}

/** Connects the pieces like RootView.swift does: link ↔ voice ↔ focus ↔ face ↔ history. */
class BlueyModel(app: Application) {
    val link = MacLink(app)
    val live = LiveVoice(app)
    val store = SessionStore(app)
    val focus = FocusClient(app)
    val animator = FaceAnimator()

    /** Set by the screen: open the focus dashboard (e.g. when a session finishes). */
    var showFocus: () -> Unit = {}

    init {
        store.link = link
        focus.attach(link)
        focus.onFinished = { showFocus(); live.sleep() }
        link.onFocusState = { focus.receive(it) }
        link.onFace = { animator.receive(it, System.nanoTime() / 1e9) }
        link.onConnectedChanged = { connected ->
            focus.connectionChanged(connected)
            if (!connected) live.sleep() else store.resyncRecent()
        }
        animator.localTalk = { live.level }
        wireLiveVoice()
    }

    private fun wireLiveVoice() {
        live.requestToken = { done -> link.request(Packet(command = "realtimeToken")) { done(it?.text) } }
        live.runTool = { name, arguments, done ->
            link.request(Packet(command = "tool", tool = name, text = arguments)) { reply ->
                done(reply?.text ?: "The Mac didn't answer.", reply?.image)
            }
        }
        // Focus voice is ephemeral: no meeting audio is recorded, only the text history.
        live.onSessionStart = { store.start() }
        live.onSessionEnd = { store.end() }
        live.resumeContext = { store.recentText() }
        live.onUserTurn = { item, asked -> store.placeholder(item, asked) }
        live.onUserWords = { item, text, asked -> store.heard(item, text, asked) }
        live.onReply = { store.reply(it) }
        live.onReport = { store.report(it) }
        live.onCodexNote = { store.codex(it) }
        live.onCaption = { text, finished -> link.send(Packet(command = if (finished) "captionDone" else "caption", text = text)) }
        live.onStateChange = { state ->
            when (state) {
                LiveVoice.State.Asleep -> {
                    animator.awake = false; animator.localMood = null
                    link.send(Packet(command = "asleep"))
                }
                LiveVoice.State.Waking -> { animator.awake = true; animator.localMood = Mood.happy }
                LiveVoice.State.Listening -> {
                    animator.awake = true; animator.localMood = null
                    link.send(Packet(command = "awake"))
                }
                LiveVoice.State.Asking -> {
                    animator.awake = true; animator.localMood = Mood.listening // all ears while you hold
                    link.send(Packet(command = "awake"))
                }
                LiveVoice.State.Thinking -> animator.localMood = Mood.thinking
                LiveVoice.State.Speaking -> animator.localMood = Mood.talking
            }
        }
        link.onCommand = { command, text ->
            when (command) {
                "wake" -> live.wake()
                "sleep" -> live.sleep()
                "codexNote" -> live.codexNote(text ?: "")
                "codexChirp" -> live.chirpAlone()
                "focusChirp" -> {
                    focus.receivedNudge(text ?: "Are you sure you're on task right now?")
                    live.focusChirp()
                }
            }
        }
    }

    /** The app went out of sight: like the iPhone, pause focus monitoring, sleep, and drop the link. */
    fun backgrounded() {
        focus.command("focusPause")
        live.sleep()
        link.stop()
    }

    fun foregrounded() {
        link.start()
    }
}
