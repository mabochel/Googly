package com.mabochel.bluey.ui

import android.os.Build
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.RoundedCorner
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.TrackChanges
import androidx.compose.material.icons.filled.VolumeDown
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mabochel.bluey.BlueyModel
import com.mabochel.bluey.focus.FocusActivity
import com.mabochel.bluey.focus.FocusSession
import com.mabochel.bluey.focus.focusPercent
import com.mabochel.bluey.link.MacLink
import com.mabochel.bluey.voice.LiveVoice
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.sin

/** Everything the screen can open, so the activity can route a pairing link here too. */
class RootUiState {
    var showFocus by mutableStateOf(false)
    var showPairSheet by mutableStateOf(false)
    var showApp by mutableStateOf(false)
    var showTyping by mutableStateOf(false)
    var showPairingIntro by mutableStateOf(true)
    var pairError by mutableStateOf("")
}

@Composable
fun RootScreen(model: BlueyModel, ui: RootUiState, scanQr: () -> Unit, pairText: (String) -> Unit) {
    val link = model.link
    val live = model.live
    LaunchedEffect(Unit) { model.showFocus = { ui.showFocus = true } }
    LaunchedEffect(link.connected) { if (link.connected) ui.showPairingIntro = false }
    val view = LocalView.current

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        FaceView(
            model.animator,
            Modifier.fillMaxSize().faceGestures(
                onDoubleTap = { live.toggle() }, // wake up / back to follow mode
                onHoldStart = { view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS); live.beginAsk() },
                onHoldEnd = { view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); live.endAsk() },
            ),
        )
        ModeIndicator(live.state)
        AssistantHud(
            hudState(model),
            onType = { ui.showTyping = true },
            onFocus = { ui.showFocus = true },
            onPair = { ui.showPairSheet = true },
            onSuggestion = { live.sendText(it.request) },
            onFocusCommand = { model.focus.command(it) },
            onMode = { live.chooseMode(it) },
        )
        SoundButton(link, live)
        // Bluey's app: sessions, transcripts and settings.
        RoundIcon(Icons.Filled.GridView, "Open Bluey", dim = true,
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 10.dp, end = 62.dp)) { ui.showApp = true }

        AnimatedVisibility(ui.showPairingIntro && !link.connected, enter = fadeIn(), exit = fadeOut(tween(300))) {
            PairingIntro(link.isPaired) { ui.showPairingIntro = false; ui.showPairSheet = true }
        }
        if (ui.showApp) {
            BackHandler { ui.showApp = false }
            BlueyAppScreen(model,
                openFocus = { ui.showApp = false; ui.showFocus = true },
                pairMac = { ui.showApp = false; ui.showPairSheet = true },
                close = { ui.showApp = false })
        }
        if (ui.showFocus) {
            BackHandler { ui.showFocus = false }
            FocusDashboard(model.focus, link) { ui.showFocus = false }
        }
        if (ui.showTyping) {
            BackHandler { ui.showTyping = false }
            TypeSheet(send = { live.sendText(it) }) { ui.showTyping = false }
        }
        if (ui.showPairSheet) {
            BackHandler { ui.showPairSheet = false }
            PairSheet(ui, scanQr = scanQr, pairText = pairText) { ui.showPairSheet = false; ui.pairError = "" }
        }
    }
}

/** Double tap wakes him or puts him back to sleep; press and hold to ask, let go and he answers. */
private fun Modifier.faceGestures(onDoubleTap: () -> Unit, onHoldStart: () -> Unit, onHoldEnd: () -> Unit) =
    pointerInput(Unit) {
        var lastTap = 0L
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            var released = false
            withTimeoutOrNull(300) { waitForUpOrCancellation(); released = true } // a quick tap isn't a hold
            if (released) {
                val now = SystemClock.uptimeMillis()
                if (now - lastTap < 350) { lastTap = 0; onDoubleTap() } else lastTap = now
            } else {
                lastTap = 0
                onHoldStart()
                do {
                    val event = awaitPointerEvent()
                } while (event.changes.any { it.pressed })
                onHoldEnd()
            }
        }
    }

/** A solid border round the screen while he's listening, pulsing while he wakes, asks or thinks. */
@Composable
fun ModeIndicator(state: LiveVoice.State) {
    val color = when (state) {
        LiveVoice.State.Asleep -> Color.Transparent
        LiveVoice.State.Waking -> Color(0xFFFFD66B)
        LiveVoice.State.Listening, LiveVoice.State.Asking -> Color(0xFF4F8BFF)
        LiveVoice.State.Thinking -> Color(0xFFC79BFF)
        LiveVoice.State.Speaking -> Color(0xFFFF9AD0)
    }
    val border = when (state) {
        LiveVoice.State.Asleep -> 0f
        LiveVoice.State.Waking -> 6f
        LiveVoice.State.Listening -> 10f
        LiveVoice.State.Asking -> 16f
        LiveVoice.State.Thinking, LiveVoice.State.Speaking -> 8f
    }
    val pulse = when (state) {
        LiveVoice.State.Waking -> 1.6
        LiveVoice.State.Asking -> 1.2
        LiveVoice.State.Thinking -> 1.1
        else -> 0.0
    }
    val animatedColor by animateColorAsState(color, tween(250), label = "border color")
    val width by animateFloatAsState(border, tween(250), label = "border width")
    var time by remember { mutableLongStateOf(0L) }
    LaunchedEffect(pulse) { if (pulse > 0) while (true) withFrameNanos { time = it } }
    val view = LocalView.current
    Canvas(Modifier.fillMaxSize()) {
        val bw = width.dp.toPx()
        if (bw < 0.5f) return@Canvas
        val wave = if (pulse > 0) 0.5 + 0.5 * sin(time / 1e9 * pulse * 2 * PI) else 1.0
        val alpha = if (pulse > 0) (0.65 + 0.35 * wave).toFloat() else 1f
        val radius = if (Build.VERSION.SDK_INT >= 31)
            view.rootWindowInsets?.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)?.radius?.toFloat() ?: 40.dp.toPx()
        else 40.dp.toPx()
        drawRoundRect(animatedColor.copy(alpha = animatedColor.alpha * alpha), topLeft = Offset(bw / 2, bw / 2),
            size = Size(size.width - bw, size.height - bw), cornerRadius = CornerRadius(maxOf(0f, radius - bw / 2)),
            style = Stroke(bw))
    }
}

@Composable
fun RoundIcon(icon: ImageVector, label: String, modifier: Modifier = Modifier, dim: Boolean = false,
              enabled: Boolean = true, background: Float = if (dim) 0.06f else 0.09f, onClick: () -> Unit) {
    Box(
        modifier.size(44.dp).clip(CircleShape).clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(34.dp).background(Color.White.copy(alpha = background), CircleShape))
        Icon(icon, null, Modifier.size(18.dp).alpha(if (!enabled) 0.3f else 1f),
            tint = Color.White.copy(alpha = if (dim) 0.35f else 1f))
    }
}

/** A tiny, quiet speaker button: chirp volume, a test hi, and which Mac to link with. */
@Composable
private fun SoundButton(link: MacLink, live: LiveVoice) {
    var open by remember { mutableStateOf(false) }
    var volume by remember { mutableDoubleStateOf(live.volume) }
    var lastTouch by remember { mutableLongStateOf(0L) }
    LaunchedEffect(lastTouch, open) {
        // Tuck the panel away after a few quiet seconds so it never ends up in a shot.
        if (open) { delay(6000); open = false }
    }
    Box(Modifier.fillMaxSize().padding(top = 10.dp, end = 14.dp), contentAlignment = Alignment.TopEnd) {
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val icon = when {
                open -> Icons.Filled.Close
                volume < 0.01 -> Icons.Filled.VolumeOff
                volume < 0.5 -> Icons.Filled.VolumeDown
                else -> Icons.Filled.VolumeUp
            }
            RoundIcon(icon, if (open) "Close sound settings" else "Sound settings", dim = !open,
                background = if (open) 0.14f else 0.06f) { open = !open; lastTouch = SystemClock.uptimeMillis() }
            AnimatedVisibility(open, enter = fadeIn() + scaleIn(initialScale = 0.9f), exit = fadeOut() + scaleOut(targetScale = 0.9f)) {
                Column(
                    Modifier.width(260.dp).background(Palette.panel.copy(alpha = 0.95f), RoundedCornerShape(18.dp)).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("CHIRP VOLUME", style = plexMono(12.sp), color = Palette.inkSoft)
                        Spacer(Modifier.weight(1f))
                        Text("${Math.round(volume * 100)}%", style = plexMono(13.sp), color = Color.White)
                    }
                    Slider(volume.toFloat(), { volume = it.toDouble(); live.volume = volume; lastTouch = SystemClock.uptimeMillis() },
                        colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Palette.berry2))
                    GradientButton(if (live.state == LiveVoice.State.Asleep) "Wake him up" else "Say hi", enabled = link.connected) {
                        live.sayHi(); lastTouch = SystemClock.uptimeMillis()
                    }
                    if (link.macs.size > 1) {
                        Text("MAC", style = plexMono(12.sp), color = Palette.inkSoft)
                        MacChooser(link) { lastTouch = SystemClock.uptimeMillis() }
                    }
                    if (!link.connected) Text("Connect to your Mac to talk to him.", style = plexSans(12.sp), color = Palette.inkSoft)
                }
            }
        }
    }
}

@Composable
fun MacChooser(link: MacLink, touched: () -> Unit = {}) {
    for (name in link.macs) {
        Row(Modifier.fillMaxWidth().heightIn(min = 36.dp).clickable { link.choose(name); touched() },
            verticalAlignment = Alignment.CenterVertically) {
            Text(name, style = plexSans(14.sp), color = Color.White, maxLines = 1, modifier = Modifier.weight(1f))
            if (name == link.currentMac) {
                Icon(if (link.connected) Icons.Filled.Check else Icons.Filled.MoreHoriz, null, tint = Palette.berry2)
            }
        }
    }
}

@Composable
fun GradientButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier.fillMaxWidth().heightIn(min = 44.dp).alpha(if (enabled) 1f else 0.4f).clip(RoundedCornerShape(12.dp))
            .background(Blob.linear).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = plexSans(15.sp, FontWeight.SemiBold), color = Color.White) }
}

/** Shown until the phone finds the Mac: a sleepy blob and how to wake him up. */
@Composable
private fun PairingIntro(paired: Boolean, onPair: () -> Unit) {
    Row(
        Modifier.fillMaxSize().background(Color.Black)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            .padding(horizontal = 48.dp),
        horizontalArrangement = Arrangement.spacedBy(48.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SleepyBlob(Modifier.width(220.dp).height(190.dp))
        Column(verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.widthIn(max = 400.dp)) {
            Text("Your little blueberry helper", style = fredoka(32.sp), color = Color(0xFFF4F1FA))
            Text(
                if (paired) "Open Bluey on your Mac and keep this phone on the same Wi-Fi. We'll reconnect securely."
                else "Open Bluey's Mac menu > Pair Phone (QR). Scan once, then we'll reconnect securely on the same Wi-Fi.",
                style = plexSans(16.sp), color = Palette.inkSoft,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(top = 4.dp)) {
                CircularProgressIndicator(Modifier.size(16.dp), color = Palette.berry1, strokeWidth = 2.dp)
                Text("Looking for your Mac", style = plexMono(13.sp), color = Palette.inkSoft)
            }
            Box(
                Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(14.dp)).background(Palette.panel).clickable(onClick = onPair)
                    .padding(horizontal = 18.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(if (paired) "Pair again (scan QR)" else "Pair Mac (scan QR)", style = plexSans(15.sp, FontWeight.SemiBold),
                    color = Color(0xFFF4F1FA))
            }
        }
    }
}

@Composable
private fun SleepyBlob(modifier: Modifier) {
    val measurer = rememberTextMeasurer()
    var time by remember { mutableLongStateOf(System.nanoTime()) }
    LaunchedEffect(Unit) { while (true) withFrameNanos { time = it } }
    Canvas(modifier) {
        val t = time / 1e9
        val s = (1 + 0.02 * sin(t * 1.1)).toFloat()
        withTransform({ scale(s, s, Offset(size.width / 2, size.height)) }) {
            val body = Rect(Offset.Zero, size)
            val blob = Blob.path(body)
            drawPath(blob, Blob.fill(body), alpha = 0.9f)
            clipPath(blob) {
                drawPath(blob, Brush.radialGradient(listOf(Color.White.copy(alpha = 0.5f), Color.Transparent),
                    center = Offset(size.width * 0.3f, size.height * 0.22f), radius = 80.dp.toPx()), alpha = 0.9f)
            }
            val cx = size.width / 2
            val cy = size.height / 2
            val eyeW = 46.dp.toPx()
            val eyeH = 7.dp.toPx()
            val gap = 26.dp.toPx()
            for (side in listOf(-1, 1)) {
                val x = cx + side * (gap / 2 + eyeW / 2)
                drawRoundRect(Palette.ink, Offset(x - eyeW / 2, cy - 18.dp.toPx() - eyeH / 2), Size(eyeW, eyeH),
                    CornerRadius(eyeH / 2))
            }
            drawOval(Palette.nose, Offset(cx + 4.dp.toPx() - 9.dp.toPx(), cy + 21.dp.toPx() - 6.dp.toPx()),
                Size(18.dp.toPx(), 12.dp.toPx()))
            for ((i, z) in listOf(Triple(26, 110f, -95f), Triple(18, 128f, -122f)).withIndex()) {
                val layout = measurer.measure("z", TextStyle(fontFamily = BlueyFonts.fredoka, fontWeight = FontWeight.Bold,
                    fontSize = z.first.sp, color = if (i == 0) Palette.berry3 else Palette.berry4))
                val wobble = (4 * sin(t * 1.5 + i)).toFloat()
                drawText(layout, topLeft = Offset(cx + z.second.dp.toPx() - layout.size.width / 2f,
                    cy + (z.third - wobble).dp.toPx() - layout.size.height / 2f))
            }
        }
    }
}

/** Pairing: scan the Mac's QR code, or paste the code if scanning isn't available. */
@Composable
private fun PairSheet(ui: RootUiState, scanQr: () -> Unit, pairText: (String) -> Unit, close: () -> Unit) {
    var pasted by remember { mutableStateOf("") }
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.7f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { close() },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.widthIn(max = 560.dp).padding(16.dp).background(Palette.panel, RoundedCornerShape(22.dp))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .padding(22.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Pair your Mac", style = fredoka(24.sp), color = Color.White, modifier = Modifier.weight(1f))
                TextButton(onClick = close) { Text("Done") }
            }
            Text("Open Bluey's Mac menu > Pair Phone (QR), then scan the code. Keep the code private: it pairs your phone to that Mac. " +
                "Your phone and Mac must be on the same Wi-Fi.", style = plexSans(14.sp), color = Palette.inkSoft)
            GradientButton("Scan pairing code", onClick = scanQr)
            Text("Can't scan? Scan the QR with your camera app, copy the text it shows (it starts with bluey-pair:) and paste it here.",
                style = plexSans(12.sp), color = Palette.inkSoft)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(pasted, { pasted = it }, Modifier.weight(1f), singleLine = true,
                    placeholder = { Text("bluey-pair:…") }, textStyle = plexMono(12.sp))
                TextButton(onClick = { pairText(pasted) }, enabled = pasted.isNotBlank()) { Text("Pair") }
            }
            if (ui.pairError.isNotEmpty()) Text(ui.pairError, style = plexSans(13.sp), color = Color(0xFFFFA94D))
        }
    }
}
