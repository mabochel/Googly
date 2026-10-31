package com.mabochel.bluey.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.FindInPage
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mabochel.bluey.BlueyModel
import com.mabochel.bluey.sessions.BlueySession
import com.mabochel.bluey.sessions.TranscriptEntry
import com.mabochel.bluey.voice.LiveVoice
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** Bluey's own app screen: start or stop listening, past voice sessions and settings. */
@Composable
fun BlueyAppScreen(model: BlueyModel, openFocus: () -> Unit, pairMac: () -> Unit, close: () -> Unit) {
    val link = model.link
    val live = model.live
    val store = model.store
    var volume by remember { mutableDoubleStateOf(live.volume) }
    var openSession by remember { mutableStateOf<String?>(null) }

    Row(Modifier.fillMaxSize().background(Color.Black).clickable(remember { MutableInteractionSource() }, null) {}) {
        Column(
            Modifier.width(300.dp).fillMaxHeight().background(Palette.panel.copy(alpha = 0.6f))
                .verticalScroll(rememberScrollState()).padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                MiniBluey(Modifier.width(54.dp).height(48.dp))
                Column {
                    Text("Bluey", style = fredoka(28.sp), color = Color.White)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(7.dp).background(if (link.connected) Palette.green else Palette.inkSoft.copy(alpha = 0.5f), CircleShape))
                        Text(if (link.connected) link.macName ?: link.currentMac ?: "Mac" else "Looking for your Mac",
                            style = plexSans(12.sp), color = Palette.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Button(onClick = openFocus, modifier = Modifier.fillMaxWidth()) { Text("Focus sessions") }
            OutlinedButton(onClick = pairMac, modifier = Modifier.fillMaxWidth()) { Text("Pair Mac (QR)") }

            val asleep = live.state == LiveVoice.State.Asleep
            val canStart = link.connected || !asleep
            Row(
                Modifier.fillMaxWidth().heightIn(min = 50.dp).alpha(if (canStart) 1f else 0.5f).clip(RoundedCornerShape(16.dp))
                    .then(if (asleep) Modifier.background(Blob.linear) else Modifier.background(Palette.pink))
                    .clickable(enabled = canStart) { live.toggle(); if (asleep) close() }, // back to his face when starting
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(if (asleep) Icons.Filled.PlayArrow else Icons.Filled.Stop, null, tint = Color.White)
                Text(if (asleep) "Start listening" else "Stop listening", style = fredoka(18.sp), color = Color.White)
            }
            Text(if (asleep) "He listens the whole session. Hold his face to ask something, let go and he answers."
                 else "Listening. Hold his face to ask; use Focus sessions for the task and timer.",
                style = plexSans(12.sp), color = Palette.inkSoft)
            HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("CHIRP VOLUME", style = plexMono(11.sp), color = Palette.inkSoft)
                Slider(volume.toFloat(), { volume = it.toDouble(); live.volume = volume })
            }
            if (link.macs.size > 1) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("MAC", style = plexMono(11.sp), color = Palette.inkSoft)
                    MacChooser(link)
                }
            }
            Row(
                Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(14.dp)).background(Color.White.copy(alpha = 0.08f))
                    .clickable(onClick = close),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Face, null, tint = Color.White)
                Text("Back to his face", style = plexSans(15.sp, FontWeight.SemiBold), color = Color.White)
            }
        }

        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, top = 20.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("Voice history", style = fredoka(24.sp), color = Color.White, modifier = Modifier.weight(1f))
                Text("${store.sessions.size}", style = plexMono(13.sp), color = Palette.inkSoft)
            }
            if (store.sessions.isEmpty()) {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.ChatBubbleOutline, null, Modifier.size(34.dp), tint = Palette.berry2)
                    Text("No sessions yet", style = fredoka(18.sp), color = Color.White)
                    Text("Start one and your transcript and his replies show up here.", style = plexSans(13.sp), color = Palette.inkSoft)
                }
            } else {
                LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(store.sessions, key = { it.id }) { session ->
                        SessionRow(session, live = session.id == store.currentID, open = { openSession = session.id },
                            delete = { store.delete(session.id) })
                    }
                    item { Spacer(Modifier.height(16.dp)) }
                }
            }
        }
    }
    openSession?.let { id ->
        BackHandler { openSession = null }
        SessionDetail(model, id) { openSession = null }
    }
}

private fun durationText(seconds: Double): String {
    val s = seconds.toInt()
    return if (s >= 3600) String.format(Locale.US, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
    else String.format(Locale.US, "%d:%02d", s / 60, s % 60)
}

@Composable
private fun SessionRow(session: BlueySession, live: Boolean, open: () -> Unit, delete: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(Palette.panel, RoundedCornerShape(12.dp)).clickable(onClick = open)
        .padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(BlueySession.formatStart(session.started), style = plexSans(15.sp, FontWeight.SemiBold), color = Color.White)
                if (live) {
                    Text("LIVE", style = plexMono(10.sp, FontWeight.Medium), color = Color.Black,
                        modifier = Modifier.background(Palette.green, CircleShape).padding(horizontal = 6.dp, vertical = 2.dp))
                }
            }
            Text(session.summary, style = plexSans(13.sp), color = Palette.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(durationText(session.duration()), style = plexMono(12.sp), color = Palette.inkSoft)
            Text("${session.questionCount} asked", style = plexMono(11.sp), color = Palette.berry1)
        }
        if (!live) {
            Icon(Icons.Filled.Delete, "Delete session", Modifier.size(40.dp).clip(CircleShape).clickable(onClick = delete).padding(10.dp),
                tint = Palette.inkSoft.copy(alpha = 0.6f))
        }
    }
}

/** One session as a chat: what you said in grey, your questions in blue, his replies in white bubbles. */
@Composable
private fun SessionDetail(model: BlueyModel, id: String, close: () -> Unit) {
    val context = LocalContext.current
    val session = model.store.sessions.firstOrNull { it.id == id }
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(2000); copied = false } }
    val entries = session?.displayEntries ?: emptyList()
    val list = rememberLazyListState()
    LaunchedEffect(entries.size) { if (entries.isNotEmpty()) list.animateScrollToItem(entries.size - 1) }

    Column(Modifier.fillMaxSize().background(Color.Black).clickable(remember { MutableInteractionSource() }, null) {}) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            CircleButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", close)
            Column(Modifier.weight(1f)) {
                Text(session?.let { BlueySession.formatStart(it.started) } ?: "Session", style = fredoka(20.sp), color = Color.White)
                if (session != null) {
                    Text("${durationText(session.duration())} · ${session.questionCount} asked", style = plexMono(11.sp), color = Palette.inkSoft)
                }
            }
            if (session != null) {
                Row(Modifier.height(40.dp).clip(CircleShape).background(Palette.berry3)
                    .clickable { copyPrompt(context, session); copied = true }.padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(if (copied) Icons.Filled.Check else Icons.Filled.ContentCopy, null, Modifier.size(16.dp), tint = Color.White)
                    Text(if (copied) "Copied" else "Copy prompt for agent", style = plexSans(13.sp, FontWeight.SemiBold), color = Color.White)
                }
                CircleButton(Icons.Filled.Share, "Share") {
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, session.exportText)
                    context.startActivity(Intent.createChooser(send, "Share session"))
                }
            }
        }
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 22.dp), state = list, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(entries, key = { it.id }) { EntryView(it) }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun CircleButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Box(Modifier.size(40.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.08f)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center) { Icon(icon, label, Modifier.size(18.dp), tint = Color.White) }
}

/** A prompt for an agent on the Mac that points it at this session's notes files. */
private fun copyPrompt(context: Context, session: BlueySession) {
    val root = "~/Documents/Bluey Notes"
    val path = session.notesPath?.let { p ->
        val at = p.indexOf("/Documents/Bluey Notes")
        if (at >= 0) "~" + p.substring(at) else p
    } ?: "$root/ (the folder starting ${java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(session.started))})"
    val when_ = DateFormat.getDateTimeInstance(DateFormat.FULL, DateFormat.SHORT).format(Date(session.started))
    val prompt = """
        I recorded a session with my Bluey app on $when_. The notes are on my Mac in $path

        - notes.md: the transcript with timestamps, plus my questions to Bluey, its replies and research reports. Start here.
        - session.json: the same data, structured.

        All my sessions are in $root/, one folder per session (see README.md there). Read the notes, then help me with:
    """.trimIndent()
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    clipboard.setPrimaryClip(ClipData.newPlainText("Bluey prompt", prompt))
}

private val speakerColors = listOf(0xFF8FB3FF, 0xFF5BE49B, 0xFFFFB86B, 0xFFFF9AD0, 0xFFC79BFF, 0xFF6BE3E0)

@Composable
private fun EntryView(entry: TranscriptEntry) {
    val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(entry.time))
    when (entry.kind) {
        TranscriptEntry.TRANSCRIPT -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val first = entry.speaker?.firstOrNull()
            val color = if (first == null || entry.speaker == "?") Palette.inkSoft else Color(speakerColors[first.code % speakerColors.size])
            Text(entry.speaker?.let { "Speaker $it" } ?: "Speaker", style = plexMono(11.sp, FontWeight.Medium), color = color,
                modifier = Modifier.width(78.dp).padding(top = 2.dp))
            Text(entry.text, style = plexSans(15.sp), color = Color.White.copy(alpha = 0.92f), modifier = Modifier.weight(1f))
            Text(time, style = plexMono(10.sp), color = Palette.inkSoft.copy(alpha = 0.7f), modifier = Modifier.padding(top = 3.dp))
        }
        TranscriptEntry.HEARD -> Text(entry.text, style = plexSans(14.sp), color = Palette.inkSoft, modifier = Modifier.padding(start = 4.dp))
        TranscriptEntry.ASKED -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Column(Modifier.padding(start = 120.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("You asked", style = plexMono(10.sp), color = Palette.berry1)
                Text(entry.text, style = plexSans(15.sp, FontWeight.Medium), color = Color.White,
                    modifier = Modifier.clip(RoundedCornerShape(18.dp)).background(Blob.linear).padding(horizontal = 14.dp, vertical = 10.dp))
            }
        }
        TranscriptEntry.REPLY -> Row(Modifier.padding(end = 120.dp), verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MiniBluey(Modifier.width(28.dp).height(25.dp))
            Text(entry.text, style = fredoka(16.sp, FontWeight.SemiBold), color = Palette.ink,
                modifier = Modifier.background(Color.White, RoundedCornerShape(18.dp)).padding(horizontal = 14.dp, vertical = 10.dp))
        }
        TranscriptEntry.CODEX -> Row(Modifier.padding(start = 36.dp, end = 40.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Filled.Code, null, Modifier.size(14.dp).padding(top = 2.dp), tint = Palette.berry2)
            Text(entry.text, style = plexMono(11.sp), color = Palette.inkSoft, maxLines = 4, overflow = TextOverflow.Ellipsis)
        }
        else -> ReportCard(entry.text)
    }
}

@Composable
private fun ReportCard(text: String) {
    var open by remember { mutableStateOf(false) }
    val parts = text.split("\n\n")
    Column(
        Modifier.padding(start = 36.dp, end = 80.dp).fillMaxWidth().background(Color(0xFFEEF0FF), RoundedCornerShape(18.dp))
            .border(2.dp, Palette.berry2, RoundedCornerShape(18.dp)).padding(14.dp).animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.fillMaxWidth().clickable { open = !open }, verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Filled.FindInPage, null, tint = Palette.berry2)
            Text(parts.firstOrNull() ?: "Research", style = fredoka(16.sp, FontWeight.SemiBold), color = Palette.ink, modifier = Modifier.weight(1f))
            Box(Modifier.size(24.dp).background(Palette.berry2, CircleShape), contentAlignment = Alignment.Center) {
                Icon(if (open) Icons.Filled.Remove else Icons.Filled.Add, null, Modifier.size(14.dp), tint = Color.White)
            }
        }
        Text(parts.drop(1).joinToString("\n\n"), style = plexSans(14.sp), color = Palette.ink,
            maxLines = if (open) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis)
    }
}

/** A tiny Bluey: the blob with two googly eyes. */
@Composable
fun MiniBluey(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val body = Rect(Offset.Zero, size)
        drawPath(Blob.path(body), Blob.fill(body))
        val eye = w * 0.26f
        val gap = w * 0.1f
        val cy = size.height / 2 - w * 0.04f
        for (side in listOf(-1, 1)) {
            val cx = size.width / 2 + side * (gap / 2 + eye / 2)
            drawCircle(Color.White, eye / 2, Offset(cx, cy))
            drawCircle(Palette.ink, w * 0.06f, Offset(cx + w * 0.02f, cy + w * 0.03f))
        }
    }
}

