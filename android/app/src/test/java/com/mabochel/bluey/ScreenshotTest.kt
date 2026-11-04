package com.mabochel.bluey

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.mabochel.bluey.link.FaceState
import com.mabochel.bluey.link.Mood
import com.mabochel.bluey.ui.AssistantHud
import com.mabochel.bluey.ui.BlueyAppScreen
import com.mabochel.bluey.ui.HudState
import com.mabochel.bluey.ui.TypeSheet
import com.mabochel.bluey.voice.LiveVoice
import com.mabochel.bluey.ui.BlueyTheme
import com.mabochel.bluey.ui.FaceAnimator
import com.mabochel.bluey.ui.FaceView
import com.mabochel.bluey.ui.FocusDashboard
import com.mabochel.bluey.ui.RootScreen
import com.mabochel.bluey.ui.RootUiState
import com.mabochel.bluey.ui.plexMono
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Renders the real screens to PNGs (opt-in: -Pscreenshots). A landscape phone, like the iPhone design. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w844dp-h390dp-land-xhdpi")
class ScreenshotTest {
    @get:Rule val compose = createComposeRule()

    @Before fun only() = assumeTrue(System.getProperty("bluey.screenshots") == "true")

    private val model get() = (ApplicationProvider.getApplicationContext<BlueyApplication>()).model

    /** Robolectric draws one frame, so run the springs forward first, like a second of real frames. */
    private fun FaceAnimator.settle(face: FaceState): FaceAnimator {
        receive(face, 2.0)
        for (i in 0..90) step(1.0 + i / 60.0)
        return this
    }

    private fun shot(name: String, millis: Long = 1600, content: @Composable () -> Unit) {
        compose.mainClock.autoAdvance = false
        compose.setContent { BlueyTheme { content() } }
        compose.mainClock.advanceTimeBy(millis)
        compose.onRoot().captureRoboImage("build/screenshots/$name.png")
    }

    private val focusJson = """
        {"current":{"id":"S1","task":"Edit the Codex launch video","duration":1800,"startedAt":783612000,"elapsed":745,
         "status":"running","detail":"Next check in 30 seconds","checks":[
           {"id":"a","elapsed":30,"activity":"on_task","probability":0.05,"confidence":0.92,"probabilities":{"on_task":0.93,"off_task":0.05,"uncertain":0.02},"sampleDuration":30},
           {"id":"b","elapsed":60,"activity":"on_task","probability":0.1,"confidence":0.9,"probabilities":{"on_task":0.88,"off_task":0.1,"uncertain":0.02},"sampleDuration":30},
           {"id":"c","elapsed":90,"activity":"off_task","probability":0.71,"confidence":0.6,"probabilities":{"on_task":0.2,"off_task":0.71,"uncertain":0.09},"sampleDuration":30},
           {"id":"d","elapsed":120,"activity":"off_task","probability":0.8,"confidence":0.7,"probabilities":{"on_task":0.1,"off_task":0.8,"uncertain":0.1},"sampleDuration":30,"nudged":true},
           {"id":"e","elapsed":150,"activity":"on_task","probability":0.04,"confidence":0.95,"probabilities":{"on_task":0.95,"off_task":0.04,"uncertain":0.01},"sampleDuration":30}],
         "developerMode":false,"realElapsed":745},
         "history":[{"id":"H1","task":"Write the pitch deck","duration":1500,"startedAt":783525600,"elapsed":1500,"status":"ended",
           "detail":"Time's up","checks":[{"id":"x","elapsed":300,"activity":"on_task","probability":0.1,"confidence":0.9,"sampleDuration":300},
           {"id":"y","elapsed":600,"activity":"off_task","probability":0.7,"confidence":0.8,"sampleDuration":300},
           {"id":"z","elapsed":1500,"activity":"on_task","probability":0.1,"confidence":0.9,"sampleDuration":900}]}],
         "revision":3,"sentAt":783612745,"developerMode":false}
    """.trimIndent()

    @Test fun faceListening() {
        val animator = FaceAnimator().settle(FaceState(-0.55, 0.35, "listening", 0.0))
        shot("01-face-listening") { FaceView(animator, Modifier.fillMaxSize()) }
    }

    @Test fun moods() {
        val moods = listOf(Mood.happy, Mood.thinking, Mood.talking, Mood.pointing, Mood.sleepy, Mood.resting)
        val animators = moods.map { mood ->
            FaceAnimator().apply { localMood = mood; awake = true }.settle(FaceState(0.4, -0.2, mood.name, 0.6))
        }
        shot("02-moods", millis = 1200) {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                for (row in animators.indices.chunked(3)) Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (i in row) Box(Modifier.width(278.dp).height(190.dp)) {
                        FaceView(animators[i], Modifier.fillMaxSize())
                        Text(moods[i].name, style = plexMono(11.sp), color = Color.White)
                    }
                }
            }
        }
    }

    @Test fun pairingIntro() {
        shot("03-pairing") { RootScreen(model, RootUiState(), scanQr = {}, pairText = {}) }
    }

    @Test fun hudWithFocusSession() {
        model.focus.receive(focusJson)
        model.animator.settle(FaceState(0.45, 0.6, "listening", 0.0))
        shot("04-hud") { RootScreen(model, RootUiState().apply { showPairingIntro = false }, scanQr = {}, pairText = {}) }
    }

    @Test fun pairSheet() {
        shot("05-pair-sheet") {
            RootScreen(model, RootUiState().apply { showPairingIntro = false; showPairSheet = true }, scanQr = {}, pairText = {})
        }
    }

    @Test fun focusDashboard() {
        model.focus.receive(focusJson)
        shot("06-focus") { FocusDashboard(model.focus, model.link) {} }
    }

    @Test fun appScreen() {
        shot("07-app") { BlueyAppScreen(model, {}, {}, {}) }
    }

    private fun assistant(name: String, state: HudState) {
        val animator = FaceAnimator().settle(FaceState(0.2, 0.1, "listening", 0.0))
        shot(name) {
            Box(Modifier.fillMaxSize()) {
                FaceView(animator, Modifier.fillMaxSize())
                AssistantHud(state, onType = {}, onFocus = {}, onPair = {}, onSuggestion = {}, onFocusCommand = {})
            }
        }
    }

    @Test fun assistantIdle() = assistant("08-assistant-idle", HudState(connected = true,
        headline = "Hold Bluey or type to ask anything", status = "Listening. Hold Bluey to ask",
        voice = LiveVoice.State.Listening))

    @Test fun assistantWorking() = assistant("09-assistant-working", HudState(connected = true,
        headline = "Open Notes and type hello from Bluey", status = "Typing…", voice = LiveVoice.State.Thinking))

    @Test fun assistantDone() = assistant("10-assistant-done", HudState(connected = true,
        headline = "Open Notes and type hello from Bluey", status = "Done",
        reply = "Done. Hello from Bluey is in Notes.", voice = LiveVoice.State.Listening))

    @Test fun typeSheet() {
        shot("11-type-sheet") { TypeSheet(send = {}, close = {}) }
    }
}
