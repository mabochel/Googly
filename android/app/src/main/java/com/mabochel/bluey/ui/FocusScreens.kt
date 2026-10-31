package com.mabochel.bluey.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mabochel.bluey.focus.FocusActivity
import com.mabochel.bluey.focus.FocusCheck
import com.mabochel.bluey.focus.FocusClient
import com.mabochel.bluey.focus.FocusSession
import com.mabochel.bluey.focus.focusPercent
import com.mabochel.bluey.link.MacLink
import com.mabochel.bluey.sessions.BlueySession
import kotlin.math.min

private val secondary = Palette.inkSoft
private val cardShape = RoundedCornerShape(12.dp)

@Composable
fun ScreenHeader(title: String, action: String, onAction: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = fredoka(20.sp), color = Color.White, modifier = Modifier.weight(1f))
        TextButton(onClick = onAction) { Text(action) }
    }
}

@Composable
fun FocusDashboard(focus: FocusClient, link: MacLink, close: () -> Unit) {
    var task by remember { mutableStateOf("") }
    var minutes by remember { mutableDoubleStateOf(30.0) }
    var inspecting by remember { mutableStateOf<Pair<FocusSession, String>?>(null) }
    val canAct = link.connected && focus.fresh

    Column(Modifier.fillMaxSize().background(Color.Black).clickable(remember { MutableInteractionSource() }, null) {}) {
        ScreenHeader("bluey · focus friend", "Back to Bluey", close)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Developer Mode", style = plexSans(16.sp), color = Color.White, modifier = Modifier.weight(1f))
                    Switch(focus.developerMode, { focus.command("focusDeveloperMode", developerMode = it) }, enabled = canAct)
                }
                Text("4× clock and a screen check every 10 real seconds. Repeated off-task checks nudge after about 20–30 real " +
                    "seconds, plus request time. Applies immediately; test sessions are labelled in history.",
                    style = plexSans(12.sp), color = secondary)
                if (focus.developerMode) {
                    OutlinedButton(onClick = { focus.command("focusTestNudge") }, enabled = canAct) { Text("Test a nudge") }
                }
            }
            val current = focus.current
            if (current != null && current.isEnded) {
                Text("Lovely bit of focus.", style = fredoka(26.sp), color = Color.White)
                FocusSummary(current, Modifier.background(Color.White.copy(alpha = 0.05f), cardShape).padding(16.dp)) {
                    inspecting = current to it
                }
            }
            if (current != null && !current.isEnded) {
                FocusSummary(current) { inspecting = current to it }
                Text(if (focus.fresh) current.detail else "Connection lost. Reconnect before resuming.",
                    style = plexSans(13.sp), color = secondary)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = { focus.command(if (current.isRunning) "focusPause" else "focusResume") }, enabled = canAct) {
                        Text(if (current.isRunning) "Pause" else "Resume")
                    }
                    OutlinedButton(onClick = { focus.command("focusEnd") }, enabled = canAct) { Text("End focus") }
                }
            } else {
                Text("A little company. A little focus.", style = fredoka(26.sp), color = Color.White)
                Text("Hold Bluey and say what you're working on, or type it here.", style = plexSans(14.sp), color = Color.White)
                OutlinedTextField(task, { task = it }, Modifier.fillMaxWidth(), singleLine = true,
                    placeholder = { Text("What are we working on?") })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${minutes.toInt()} minutes", style = plexSans(16.sp), color = Color.White, modifier = Modifier.weight(1f))
                    IconButton(onClick = { minutes = maxOf(1.0, minutes - 5) }) { Icon(Icons.Filled.Remove, "Fewer minutes") }
                    IconButton(onClick = { minutes = min(240.0, minutes + 5) }) { Icon(Icons.Filled.Add, "More minutes") }
                }
                Button(onClick = { focus.command("focusStart", task = task, minutes = minutes) },
                    enabled = task.isNotBlank() && link.connected) { Text("Start focus") }
            }
            if (focus.message.isNotEmpty()) Text(focus.message, style = plexSans(13.sp), color = secondary)
            Text("While focusing, Bluey sends your main Mac display, task, elapsed time, idle duration and recent check results " +
                "to OpenAI every ${if (focus.developerMode) 10 else 30} real seconds. Brief detours are fine. Stillness isn't distraction.",
                style = plexSans(13.sp), color = secondary)
            Text("Screen Recording is required on Mac; microphone is needed for voice. Screenshots aren't saved by Bluey. Pause, " +
                "end, lock, sleep or disconnect stops capture. Resume is always manual. OpenAI processing follows your API " +
                "account's data controls.", style = plexSans(13.sp), color = secondary)
            HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
            Text("Your focus sessions", style = fredoka(23.sp), color = Color.White)
            if (focus.history.isEmpty()) Text("Your first timeline will appear here when you finish.", color = secondary)
            for (session in focus.history) {
                FocusSummary(session, Modifier.background(Color.White.copy(alpha = 0.05f), cardShape).padding(16.dp)) {
                    inspecting = session to it
                }
            }
        }
    }
    inspecting?.let { (session, category) ->
        BackHandler { inspecting = null }
        FocusCheckList(session, category) { inspecting = null }
    }
}

@Composable
private fun FocusSummary(session: FocusSession, modifier: Modifier = Modifier, inspect: (String) -> Unit) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(session.task, style = fredoka(22.sp), color = Color.White)
        if (session.usedDeveloperMode == true) {
            Text("Developer test · ${FocusSession.clock(session.realActiveElapsed)} real active time · timeline uses focus-clock time",
                style = plexMono(11.sp), color = secondary)
        }
        Text("${BlueySession.formatStart(session.startedMillis)} · ${FocusSession.clock(session.elapsed)} active",
            style = plexMono(11.sp), color = secondary)
        FocusTimeline(session, Modifier.fillMaxWidth().height(40.dp).clickable { inspect("all") }
            .semantics { contentDescription = "Inspect focus checks" })
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Legend("On task", Color(0xFF34C759)) { inspect(FocusActivity.ON_TASK) }
            Legend("Off task", Palette.peach) { inspect(FocusActivity.OFF_TASK) }
            Legend("Unknown", Color.Gray) { inspect(FocusActivity.UNCERTAIN) }
        }
        Text("Tap a category to see probabilities and confidence. Grey also includes unchecked time. Pauses are excluded.",
            style = plexSans(11.sp), color = secondary)
    }
}

@Composable
private fun Legend(name: String, color: Color, onClick: () -> Unit) {
    Row(Modifier.heightIn(min = 44.dp).clickable(onClick = onClick).semantics { contentDescription = "Inspect $name confidence" },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(Modifier.size(9.dp).background(color))
        Text(name, style = plexSans(11.sp), color = Color.White)
    }
}

/** Green on task, peach off task, grey unknown or unchecked. Paused time is excluded. */
@Composable
fun FocusTimeline(session: FocusSession, modifier: Modifier = Modifier) {
    Canvas(modifier.border(1.dp, Color.White.copy(alpha = 0.3f))) {
        drawRect(Color.Gray.copy(alpha = 0.35f))
        val total = maxOf(1.0, if (session.isEnded) session.elapsed else session.duration)
        for (check in session.checks) {
            if (check.activity != FocusActivity.ON_TASK && check.activity != FocusActivity.OFF_TASK) continue
            val span = check.sampleDuration ?: 30.0
            val start = maxOf(0.0, check.elapsed - span)
            drawRect(
                if (check.activity == FocusActivity.ON_TASK) Color(0xFF34C759) else Palette.peach,
                topLeft = Offset((start / total * size.width).toFloat(), 0f),
                size = Size((min(span, check.elapsed) / total * size.width).toFloat(), size.height),
            )
        }
    }
}

@Composable
private fun FocusCheckList(session: FocusSession, initial: String, close: () -> Unit) {
    var category by remember { mutableStateOf(initial) }
    var detail by remember { mutableStateOf<FocusCheck?>(null) }
    val checks = session.checks.reversed().filter {
        when {
            it.activity == FocusActivity.PAUSED -> false
            category == "all" -> true
            category == FocusActivity.UNCERTAIN -> it.activity == FocusActivity.UNCERTAIN || it.activity == FocusActivity.UNAVAILABLE
            else -> it.activity == category
        }
    }
    Column(Modifier.fillMaxSize().background(Color.Black).clickable(remember { MutableInteractionSource() }, null) {}) {
        ScreenHeader("Focus checks", "Done", close)
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for ((key, label) in listOf("all" to "All", FocusActivity.ON_TASK to "On task",
                        FocusActivity.OFF_TASK to "Off task", FocusActivity.UNCERTAIN to "Unknown")) {
                        FilterChip(category == key, { category = key }, label = { Text(label) })
                    }
                }
                Text("Each check is a periodic observation. Developer Mode checks every 10 real seconds with a 4× clock. Category " +
                    "probabilities and overall decision confidence are separate model estimates, not guarantees.",
                    style = plexSans(13.sp), color = secondary, modifier = Modifier.padding(vertical = 8.dp))
                Text("LATEST CHECKS FIRST", style = plexMono(11.sp), color = secondary)
            }
            if (checks.isEmpty()) item {
                Text("No checks recorded in this category yet. Unchecked time has no confidence estimate.", color = secondary)
            }
            items(checks, key = { it.id + it.elapsed }) { check ->
                Row(Modifier.fillMaxWidth().background(Palette.panel, cardShape).clickable { detail = check }.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Row {
                            Text(FocusActivity.name(check.activity), style = fredoka(20.sp), color = Color.White,
                                modifier = Modifier.weight(1f))
                            Text(FocusSession.clock(check.elapsed), style = plexMono(12.sp), color = Color.White)
                        }
                        val estimate = check.estimate(check.activity)
                        Text(when {
                            estimate != null -> "Category probability ${estimate.focusPercent()} · confidence ${check.confidence.focusPercent()}"
                            check.activity == FocusActivity.UNAVAILABLE -> "Check unavailable or stale · no valid estimate"
                            else -> "Category probability not saved · confidence ${check.confidence.focusPercent()}"
                        }, style = plexSans(12.sp), color = secondary)
                    }
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = secondary)
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
    detail?.let { check ->
        BackHandler { detail = null }
        FocusCheckDetail(check) { detail = null }
    }
}

@Composable
private fun FocusCheckDetail(check: FocusCheck, close: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Color.Black).clickable(remember { MutableInteractionSource() }, null) {}) {
        ScreenHeader("Check details", "Back", close)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).widthIn(max = 640.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Labeled("Observation", FocusActivity.name(check.activity))
            Labeled("Active session time", FocusSession.clock(check.elapsed))
            if (check.activity == FocusActivity.UNAVAILABLE) {
                Text("This check failed or arrived too late. It has no valid confidence estimate and does not count toward a nudge.",
                    color = Color.White)
            } else {
                Text("CATEGORY PROBABILITIES", style = plexMono(11.sp), color = secondary, modifier = Modifier.padding(top = 8.dp))
                for (category in listOf(FocusActivity.ON_TASK, FocusActivity.OFF_TASK, FocusActivity.UNCERTAIN)) {
                    Labeled(FocusActivity.name(category), check.estimate(category)?.focusPercent() ?: "Not saved")
                }
                Text("DECISION CONFIDENCE", style = plexMono(11.sp), color = secondary, modifier = Modifier.padding(top = 8.dp))
                Labeled("Overall confidence", check.confidence.focusPercent())
                Text("Confidence is reported separately by the API. It is not the probability of a particular category.",
                    style = plexSans(13.sp), color = secondary)
                if (check.probabilities == null) {
                    Text("This older check saved only the Off task probability and overall confidence. The other probabilities " +
                        "weren't saved.", color = secondary)
                }
                if (check.nudged) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.ChatBubbleOutline, null, tint = Palette.berry1)
                        Text("Bluey gave a gentle nudge after this check", color = Color.White)
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Labeled(label: String, value: String) {
    Row(Modifier.fillMaxWidth().background(Palette.panel, cardShape).padding(14.dp)) {
        Text(label, color = Color.White, modifier = Modifier.weight(1f))
        Text(value, color = secondary)
    }
}
