package com.mabochel.bluey.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.TrackChanges
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mabochel.bluey.BlueyModel
import com.mabochel.bluey.focus.FocusActivity
import com.mabochel.bluey.focus.FocusSession
import com.mabochel.bluey.focus.focusPercent
import com.mabochel.bluey.voice.LiveVoice
import com.mabochel.bluey.voice.Mode
import com.mabochel.bluey.voice.Modes
import com.mabochel.bluey.voice.Suggestion

/** Everything the main screen's overlay shows, so it can be drawn (and screenshot-tested) without a live Mac. */
data class HudState(
    val connected: Boolean,
    val headline: String,
    val status: String,
    val reply: String = "",
    val nudge: String = "",
    val voice: LiveVoice.State = LiveVoice.State.Asleep,
    val inputLevel: Double = 0.0,
    val session: FocusSession? = null,
    val focusStatus: String = "",
    val focusFresh: Boolean = true,
    val developerMode: Boolean = false,
    val mode: Mode = Modes.helper,
)

fun hudState(model: BlueyModel): HudState {
    val live = model.live
    val connected = model.link.connected
    return HudState(
        connected = connected,
        headline = live.lastRequest.ifEmpty {
            if (connected) "Hold Bluey or type to ask anything" else "Pair your Mac to get started"
        },
        status = if (!connected) "Pair or connect your Mac"
                 else live.activity.ifEmpty { live.feedback.ifEmpty { voiceStatus(live.state) } },
        reply = live.lastReply,
        nudge = model.focus.nudgeText,
        voice = live.state,
        // Only read the mic level while it's shown, so the screen doesn't redraw at mic rate the rest of the time.
        inputLevel = if (live.state == LiveVoice.State.Asking) live.inputLevel else 0.0,
        session = model.focus.current?.takeIf { !it.isEnded },
        focusStatus = focusStatus(model),
        focusFresh = model.focus.fresh,
        developerMode = model.focus.developerMode,
        mode = live.mode,
    )
}

/**
 * The main screen's overlay: what you asked and what he's doing (top left), the keyboard, focus and voice buttons
 * (bottom left), and either ideas to try or, only while a focus session runs, its timer (bottom right).
 */
@Composable
fun AssistantHud(
    state: HudState,
    onType: () -> Unit,
    onFocus: () -> Unit,
    onPair: () -> Unit,
    onSuggestion: (Suggestion) -> Unit,
    onFocusCommand: (String) -> Unit,
    onMode: (Mode) -> Unit = {},
) {
    val busy = state.voice == LiveVoice.State.Waking || state.voice == LiveVoice.State.Asking ||
        state.voice == LiveVoice.State.Thinking || state.voice == LiveVoice.State.Speaking
    BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 18.dp)) {
        // Ideas share the bottom edge with the buttons: show only as many as fit beside them.
        val ideas = when {
            maxWidth >= 760.dp -> 3
            maxWidth >= 580.dp -> 2
            else -> 1
        }
        Column(Modifier.align(Alignment.TopStart).widthIn(max = 380.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(state.headline, style = fredoka(22.sp), color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(state.status, style = plexMono(10.sp), color = Color.White.copy(alpha = 0.7f), maxLines = 2,
                overflow = TextOverflow.Ellipsis)
            if (state.developerMode) {
                Text("Developer Mode · 4× clock · checks every 10s", style = plexMono(10.sp),
                    color = Color.White.copy(alpha = 0.8f))
            }
            if (state.reply.isNotEmpty()) {
                Text(state.reply, style = plexSans(13.sp), color = Color.White.copy(alpha = 0.9f), maxLines = 3,
                    overflow = TextOverflow.Ellipsis)
            }
            if (state.nudge.isNotEmpty()) {
                Text(state.nudge, style = plexSans(12.sp), color = Palette.berry1, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (state.voice == LiveVoice.State.Asking) {
                Box(Modifier.width((20 + state.inputLevel * 100).dp).height(3.dp).background(Color.White, CircleShape))
            }
        }
        // Under the sound and app buttons, which sit in the top-right corner outside this padding.
        ModePicker(state.mode, onMode, Modifier.align(Alignment.TopEnd).padding(top = 44.dp))
        Row(Modifier.align(Alignment.BottomStart), horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically) {
            if (!state.connected) RoundIcon(Icons.Filled.QrCodeScanner, "Pair Mac", onClick = onPair)
            RoundIcon(Icons.Filled.Keyboard, "Type to Bluey", enabled = state.connected, onClick = onType)
            RoundIcon(Icons.Filled.TrackChanges, "Focus", onClick = onFocus)
            VoiceIcon(state.voice)
        }
        val session = state.session
        if (session != null) {
            FocusCorner(session, state.focusStatus, state.focusFresh, onFocusCommand, Modifier.align(Alignment.BottomEnd))
        } else if (state.connected && !busy) {
            Row(Modifier.align(Alignment.BottomEnd), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (idea in state.mode.ideas.take(ideas)) SuggestionChip(idea.label) { onSuggestion(idea) }
            }
        }
    }
}

/** Only while a focus session exists: its task and state, pause/resume, end, and the countdown. */
@Composable
private fun FocusCorner(session: FocusSession, status: String, fresh: Boolean, command: (String) -> Unit, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.End) {
        Text(listOf(session.task, status).filter { it.isNotEmpty() }.joinToString(" · "), style = plexSans(12.sp),
            color = Color.White.copy(alpha = 0.75f), maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 340.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            val running = session.isRunning
            RoundIcon(if (running) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                if (running) "Pause focus" else "Resume focus", enabled = fresh) {
                command(if (running) "focusPause" else "focusResume")
            }
            RoundIcon(Icons.Filled.Stop, "End focus", enabled = fresh) { command("focusEnd") }
            Text(
                FocusSession.clock(session.remaining),
                Modifier.semantics { contentDescription = "Time remaining" },
                style = TextStyle(fontFamily = BlueyFonts.fredoka, fontWeight = FontWeight.SemiBold, fontSize = 44.sp,
                    fontFeatureSettings = "tnum", color = Color.White),
                maxLines = 1,
            )
        }
    }
}

/** The current mode; tap to switch between Helper, French tutor, Interview coach, Roast and Game buddy. */
@Composable
private fun ModePicker(current: Mode, onMode: (Mode) -> Unit, modifier: Modifier) {
    var open by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(16.dp)
    Box(modifier) {
        Row(
            Modifier.heightIn(min = 32.dp).clip(shape).background(Palette.panel)
                .border(0.5.dp, Color.White.copy(alpha = 0.18f), shape).clickable { open = true }
                .semantics { contentDescription = "Mode: ${current.label}. Change mode" }
                .padding(start = 12.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(current.label, style = plexSans(12.sp, FontWeight.Medium), color = Color.White, maxLines = 1)
            Icon(Icons.Filled.ArrowDropDown, null, Modifier.size(18.dp), tint = Color.White)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (mode in Modes.all) {
                DropdownMenuItem(
                    text = { Text(mode.label, style = plexSans(14.sp)) },
                    onClick = { onMode(mode); open = false },
                    trailingIcon = if (mode.id == current.id) { { Icon(Icons.Filled.Check, null) } } else null,
                )
            }
        }
    }
}

@Composable
private fun SuggestionChip(label: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Box(
        Modifier.heightIn(min = 36.dp).clip(shape).background(Palette.panel)
            .border(0.5.dp, Color.White.copy(alpha = 0.18f), shape).clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, style = plexSans(13.sp), color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis) }
}

@Composable
private fun VoiceIcon(state: LiveVoice.State) {
    Box(Modifier.size(44.dp).semantics { contentDescription = voiceStatus(state) }, contentAlignment = Alignment.Center) {
        Box(Modifier.size(34.dp).background(Color.White.copy(alpha = 0.07f), CircleShape))
        Icon(voiceIcon(state), null, Modifier.size(18.dp),
            tint = if (state == LiveVoice.State.Asleep) Color.White.copy(alpha = 0.35f) else Color.White)
    }
}

/** Type a request instead of saying it. */
@Composable
fun TypeSheet(send: (String) -> Unit, close: () -> Unit) {
    var text by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val submit: () -> Unit = {
        if (text.isNotBlank()) {
            send(text)
            close()
        }
    }
    Box(
        // The app draws edge to edge, so make room for the keyboard ourselves; the sheet stays centred above it.
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.7f)).imePadding()
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { close() },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.widthIn(max = 620.dp).padding(16.dp).background(Palette.panel, RoundedCornerShape(22.dp))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Ask Bluey", style = fredoka(22.sp), color = Color.White)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    text, { text = it }, Modifier.weight(1f).focusRequester(focus), singleLine = true,
                    placeholder = { Text("Open Notes and type hello") }, textStyle = plexSans(15.sp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { submit() }),
                )
                TextButton(onClick = { submit() }, enabled = text.isNotBlank()) { Text("Send") }
            }
        }
    }
}

internal fun voiceIcon(state: LiveVoice.State) = when (state) {
    LiveVoice.State.Asleep -> Icons.Filled.MicOff
    LiveVoice.State.Waking -> Icons.Filled.Sync
    LiveVoice.State.Listening -> Icons.Filled.Hearing
    LiveVoice.State.Asking -> Icons.Filled.Mic
    LiveVoice.State.Thinking -> Icons.Filled.MoreHoriz
    LiveVoice.State.Speaking -> Icons.Filled.ChatBubble
}

internal fun voiceStatus(state: LiveVoice.State) = when (state) {
    LiveVoice.State.Asleep -> "Hold Bluey to speak, or tap the keyboard"
    LiveVoice.State.Waking -> "Voice connecting"
    LiveVoice.State.Listening -> "Listening. Hold Bluey to ask"
    LiveVoice.State.Asking -> "Recording your request"
    LiveVoice.State.Thinking -> "Thinking"
    LiveVoice.State.Speaking -> "Replying"
}

private fun focusStatus(model: BlueyModel): String {
    val session = model.focus.current?.takeIf { !it.isEnded } ?: return ""
    if (!model.focus.fresh) return "Connection lost · monitoring will pause"
    if (session.status == FocusSession.PAUSED) return "Paused"
    val check = session.checks.lastOrNull { it.activity != FocusActivity.PAUSED } ?: return "Focusing together"
    return when (check.activity) {
        FocusActivity.OFF_TASK -> "Off task · ${check.probability.focusPercent()} estimate" + if (check.nudged) " · nudged" else ""
        FocusActivity.ON_TASK -> "On task"
        FocusActivity.UNCERTAIN -> "Not sure yet"
        else -> "Last check unavailable"
    }
}
