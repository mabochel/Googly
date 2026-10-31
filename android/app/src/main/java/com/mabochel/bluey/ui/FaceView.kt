package com.mabochel.bluey.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import com.mabochel.bluey.link.FaceState
import com.mabochel.bluey.link.Mood
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** A tiny damped spring, so every part of the face moves with a little life. */
private class Spring(var value: Double, private val stiffness: Double = 200.0, private val damping: Double = 0.7) {
    var velocity = 0.0
    fun step(target: Double, dt: Double) {
        val c = 2 * sqrt(stiffness) * damping
        velocity += (stiffness * (target - value) - c * velocity) * dt
        value += velocity * dt
    }
}

private data class P(val x: Double, val y: Double)

private fun rnd(from: Double, to: Double) = Random.nextDouble(from, to)

/** Smooths what the Mac asks for into lifelike motion: darting eyes, brows, blinks, leaning, hops. */
class FaceAnimator {
    data class Frame(
        val gazeX: Double, val gazeY: Double, val mood: Mood, val talk: Double,
        val closed: Double, val breathe: Double, val time: Double, val pupil: Double,
        val browLift: Double, val browTilt: Double, val lean: Double, val hop: Double,
        val blush: Double, val squint: Double,
    )

    private var target = FaceState()
    private var lastPacket = -100.0
    private val gazeX = Spring(0.0, 260.0, 0.82)
    private val gazeY = Spring(0.0, 260.0, 0.82)
    private val pupil = Spring(1.0, 160.0, 0.5)
    private val browLift = Spring(0.0, 180.0, 0.55)
    private val browTilt = Spring(0.0, 150.0, 0.6)
    private val lean = Spring(0.0, 60.0, 0.8)
    private val hop = Spring(0.0, 260.0, 0.35)
    private val blush = Spring(0.25, 40.0, 1.0)
    private val squint = Spring(0.0, 150.0, 0.7)
    private var talk = 0.0
    private var mood = Mood.listening
    private var blinkStart = -1.0
    private var doubleBlink = false
    private var nextBlink = 2.0
    private var lastTime: Double? = null
    private var nextSaccade = 0.0
    private var wanderTarget = P(0.0, -0.3)
    private var jitter = P(0.0, 0.0)
    private var nextJitter = 0.0
    private var nextHop = 6.0

    /** Mood set on the phone itself (waking up, talking). */
    @Volatile var localMood: Mood? = null
    /** Loudness of the chirp playing on this phone, 0…1. */
    var localTalk: () -> Double = { 0.0 }
    /** True while he's awake and talking with you. Asleep (follow mode) his eyes stay locked on your mouse. */
    @Volatile var awake = false

    @Synchronized
    fun receive(face: FaceState, time: Double) {
        target = face
        lastPacket = time
    }

    @Synchronized
    fun step(now: Double): Frame {
        val dt = min(now - (lastTime ?: now), 1.0 / 20).coerceAtLeast(0.0)
        lastTime = now

        val live = now - lastPacket < 2.5
        val wantedMood = localMood ?: if (live) Mood.of(target.mood) else Mood.listening
        if (wantedMood != mood) {
            mood = wantedMood
            blinkStart = now // blink through every mood change
            if (mood != Mood.sleepy && mood != Mood.resting) {
                hop.velocity += 260 // and a little bounce of surprise
                pupil.velocity += 3
            }
        }

        // Where to look, plus tiny darting movements so the eyes never sit dead still.
        var want = if (live) P(target.gazeX, target.gazeY) else {
            if (now > nextSaccade) {
                wanderTarget = P(rnd(-0.9, 0.9), rnd(-0.9, 0.4))
                nextSaccade = now + rnd(0.6, 2.2)
            }
            wanderTarget
        }
        if (mood == Mood.thinking) want = P(0.6 + 0.08 * sin(now * 1.3), -0.85)
        if (now > nextJitter) {
            val amount = if (!awake) 0.015 else when (mood) { Mood.talking -> 0.07; Mood.thinking -> 0.05; else -> 0.02 }
            jitter = P(rnd(-amount, amount), rnd(-amount, amount))
            nextJitter = now + rnd(0.5, 1.4)
        }
        gazeX.step(want.x + jitter.x, dt)
        gazeY.step(want.y + jitter.y, dt)

        var wantTalk = if (live) target.talk else if (localMood == Mood.talking) 0.5 + 0.5 * sin(now * 19) * sin(now * 7.3) else 0.0
        wantTalk = max(wantTalk, localTalk())
        talk += (wantTalk - talk) * min(1.0, dt * 25)

        var wantPupil = 1.0; var wantLift = 0.0; var wantTilt = 0.0; var wantBlush = 0.25; var wantSquint = 0.0
        when (mood) {
            Mood.listening -> { wantPupil = 1.12; wantLift = 10.0 }
            Mood.talking -> { wantPupil = 1.05; wantLift = 6 + talk * 18; wantTilt = 0.05 * sin(now * 2.3) }
            Mood.pointing -> { wantPupil = 0.95; wantLift = -4.0; wantTilt = -0.14 }
            Mood.thinking -> { wantPupil = 0.9; wantLift = 4.0; wantTilt = 0.22 }
            Mood.happy -> { wantPupil = 1.2; wantLift = 16.0; wantBlush = 0.85; wantSquint = 1.0 }
            Mood.resting -> { wantPupil = 0.9; wantLift = -8.0; wantBlush = 0.15 }
            Mood.sleepy -> { wantPupil = 0.88; wantLift = -10.0; wantTilt = 0.12; wantBlush = 0.15 }
        }
        pupil.step(wantPupil, dt)
        browLift.step(wantLift, dt)
        browTilt.step(wantTilt, dt)
        blush.step(wantBlush, dt)
        squint.step(wantSquint, dt)
        lean.step(-gazeX.value * 0.045, dt)

        // An occasional happy little hop when nothing much is going on.
        if (now > nextHop) {
            if (talk < 0.05 && mood != Mood.sleepy && mood != Mood.resting) hop.velocity += rnd(180.0, 320.0)
            nextHop = now + rnd(7.0, 14.0)
        }
        hop.step(0.0, dt)

        // Blinks, sometimes doubled. When he's drowsy they're slow and heavy.
        val drowsy = mood == Mood.sleepy
        if (now > nextBlink) {
            blinkStart = now
            doubleBlink = !drowsy && Random.nextDouble() < 0.25
            nextBlink = now + if (drowsy) rnd(1.6, 3.0) else rnd(2.0, 5.0)
        }
        val p = (now - blinkStart) / (if (drowsy) 0.7 else 0.15)
        var closed = if (p in 0.0..1.0) sin(PI * p) else 0.0
        if (doubleBlink && p in 1.3..2.3) closed = sin(PI * (p - 1.3))

        val breathe = sin(now * if (mood == Mood.resting) 1.1 else 1.8)
        return Frame(gazeX.value, gazeY.value, mood, talk, closed, breathe, now, pupil.value, browLift.value,
            browTilt.value, lean.value, hop.value, blush.value, squint.value.coerceIn(0.0, 1.0))
    }
}

/** The landscape face on pure black: he fills the screen and peeks up from the bottom edge. */
@Composable
fun FaceView(animator: FaceAnimator, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    var frameNanos by remember { mutableLongStateOf(System.nanoTime()) }
    LaunchedEffect(Unit) { while (true) withFrameNanos { frameNanos = it } }
    Canvas(modifier) {
        val frame = animator.step(frameNanos / 1e9)
        drawFace(frame, measurer)
    }
}

private const val DESIGN_W = 844f
private const val DESIGN_H = 390f
private val pinkCheek = Color(0xFFF3A6D8)
private val tongueColor = Color(0xFFE58BC4)

private fun oval(x: Float, y: Float, w: Float, h: Float) = Path().apply { addOval(Rect(x, y, x + w, y + h)) }
private fun rect(x: Float, y: Float, w: Float, h: Float) = Path().apply { addRect(Rect(x, y, x + w, y + h)) }

private fun shine(body: Rect) = Brush.radialGradient(
    listOf(Color.White.copy(alpha = 0.5f), Color.White.copy(alpha = 0f)),
    center = Offset(body.left + 246, body.top + 126), radius = 243f,
)

/** Fills a shape with his skin (gradient plus the same soft shine), for eyelids. */
private fun DrawScope.fillSkin(path: Path, body: Rect) {
    drawPath(path, Blob.fill(body))
    drawPath(path, shine(body))
}

fun DrawScope.drawFace(f: FaceAnimator.Frame, measurer: TextMeasurer) {
    drawRect(Color.Black)
    val scale = max(size.width / DESIGN_W, size.height / DESIGN_H)
    withTransform({
        translate((size.width - DESIGN_W * scale) / 2, size.height - DESIGN_H * scale)
        scale(scale, scale, pivot = Offset.Zero)
    }) {
        // Whole-body motion: breathing, talking bounce, idle hops, and leaning toward what he looks at.
        val bounce = (-f.talk * 16 + f.breathe * 3 - f.hop).toFloat()
        val body = Rect(12f, 44f + bounce, 12f + 820f, 44f + bounce + 700f)
        val pivot = Offset(body.center.x, body.bottom)
        val stretch = (1 + (f.hop / 900).coerceIn(-0.03, 0.03)).toFloat()
        withTransform({
            rotate((f.lean * 180 / PI).toFloat(), pivot)
            scale(2 - stretch, stretch, pivot)
        }) { drawBody(f, body, measurer) }
    }
}

private fun DrawScope.drawBody(f: FaceAnimator.Frame, body: Rect, measurer: TextMeasurer) {
    val blob = Blob.path(body)
    drawPath(blob, Palette.berry3)
    drawPath(blob, Blob.fill(body))
    clipPath(blob) {
        drawPath(blob, shine(body))
        // A little glossy sparkle on his head.
        drawPath(oval(body.left + 190, body.top + 40, 46f, 22f), Color.White.copy(alpha = 0.35f))
    }

    val navy = Palette.nose
    val ink = Palette.ink
    val lift = f.browLift.toFloat()
    drawPath(Crown.path(Rect(body.left + 368, body.top - 20 - lift * 0.3f, body.left + 368 + 84, body.top - 20 - lift * 0.3f + 56)), navy)

    val eyeY = body.top + 157
    val eyes = listOf(Offset(body.left + 280, eyeY), Offset(body.left + 540, eyeY))
    val squash = (1 - f.talk * 0.12).toFloat()

    // Blushy cheeks, rosier when he's happy (a soft radial fill stands in for the iPhone's blur).
    eyes.forEachIndexed { i, c ->
        val side = if (i == 0) -1f else 1f
        val cheek = Rect(c.x - 45 + side * 62, c.y + 96, c.x + 45 + side * 62, c.y + 138)
        val color = pinkCheek.copy(alpha = (0.25 + 0.45 * f.blush).toFloat().coerceIn(0f, 1f))
        val radius = cheek.width / 2 + 12
        withTransform({ scale(1f, (cheek.height / 2 + 12) / radius, cheek.center) }) {
            drawCircle(Brush.radialGradient(0f to color, 0.6f to color.copy(alpha = color.alpha * 0.8f), 1f to color.copy(alpha = 0f),
                center = cheek.center, radius = radius), radius, cheek.center)
        }
    }

    eyes.forEachIndexed { i, c ->
        val side = if (i == 0) -1f else 1f

        // Little arched eyebrows do a lot of the acting.
        val browY = c.y - 112 - lift.coerceIn(-10f, 18f) * 0.7f
        val tilt = f.browTilt * side * -1 + if (f.mood == Mood.thinking && i == 1) -0.25 else 0.0
        withTransform({
            translate(c.x + side * 6, browY)
            rotate((tilt * 180 / PI).toFloat(), Offset.Zero)
        }) {
            val arch = Path().apply { moveTo(-38f, 6f); quadraticTo(0f, -12f, 38f, 6f) }
            drawPath(arch, navy, style = Stroke(14f, cap = StrokeCap.Round))
        }

        when {
            f.mood == Mood.resting -> {
                // Peacefully closed: a soft downward curve.
                val shut = Path().apply { moveTo(c.x - 62, c.y + 6); quadraticTo(c.x, c.y + 52, c.x + 62, c.y + 6) }
                drawPath(shut, ink, style = Stroke(18f, cap = StrokeCap.Round))
            }
            f.mood == Mood.happy && f.squint > 0.6 -> {
                val arc = Path().apply { moveTo(c.x - 60, c.y + 34); quadraticTo(c.x, c.y - 54, c.x + 60, c.y + 34) }
                drawPath(arc, ink, style = Stroke(24f, cap = StrokeCap.Round))
            }
            else -> drawOpenEye(f, c, side, squash, body)
        }
    }

    if (f.mood == Mood.resting) {
        // Little z's floating up while he naps.
        for (i in 0 until 3) {
            val phase = ((f.time * 0.35 + i / 3.0) % 1.0)
            val px = 26f + i * 8f
            val point = Offset(body.left + 660 + phase.toFloat() * 70 + sin(phase * 6).toFloat() * 8,
                body.top + 90 - phase.toFloat() * 110)
            val style = TextStyle(fontFamily = BlueyFonts.fredoka, fontWeight = FontWeight.Bold, fontSize = px.toSp(),
                color = Color.White.copy(alpha = (0.9 * sin(PI * phase)).toFloat().coerceIn(0f, 1f)))
            val layout = measurer.measure("z", style)
            drawText(layout, topLeft = Offset(point.x - layout.size.width / 2f, point.y - layout.size.height / 2f))
        }
    }

    if (f.mood == Mood.thinking) {
        for (i in 0 until 3) {
            val wobble = (sin(f.time * 3 + i) * 4).toFloat()
            drawPath(oval(body.left + 690 + i * 26, body.top + 40 - i * 12 + wobble, 16f, 16f),
                Color.White.copy(alpha = 1f - i * 0.3f))
        }
    }

    // His little mouth-nose: opens as he talks, curls into a smile when he's happy.
    val talk = f.talk.toFloat()
    val noseW = 60 - talk * 8
    val noseH = 38 + talk * 34
    val nose = Rect(body.left + 410 - noseW / 2, body.top + 272 - talk * 6, body.left + 410 + noseW / 2, body.top + 272 - talk * 6 + noseH)
    if (f.mood == Mood.happy && f.talk < 0.1) {
        val smile = Path().apply {
            moveTo(nose.left - 8, nose.top + 8)
            quadraticTo(nose.center.x, nose.top + 58, nose.right + 8, nose.top + 8)
            close()
        }
        drawPath(smile, navy)
    } else {
        drawOval(navy, nose.topLeft, nose.size)
        if (f.talk > 0.25) {
            drawOval(tongueColor.copy(alpha = 0.9f), Offset(nose.center.x - noseW * 0.28f, nose.bottom - noseH * 0.38f),
                Size(noseW * 0.56f, noseH * 0.3f))
        }
    }
}

private fun DrawScope.drawOpenEye(f: FaceAnimator.Frame, c: Offset, side: Float, squash: Float, body: Rect) {
    val open = max(0.06, 1 - f.closed).toFloat() * squash
    val white = Rect(c.x - 95, c.y - 95 * open, c.x + 95, c.y + 95 * open)
    val whitePath = Path().apply { addOval(white) }
    drawPath(whitePath, Color.White)
    if (open <= 0.2f) return

    var gx = f.gazeX
    var gy = f.gazeY
    val len = hypot(gx, gy)
    if (len > 1) { gx /= len; gy /= len }
    val reach = 50f
    val r = (46 * f.pupil).toFloat()
    var px = c.x + gx.toFloat() * reach - side * 3
    var py = c.y + gy.toFloat() * reach * open
    if (f.mood == Mood.sleepy) py = max(py, c.y) + 28 // eyes sink under heavy lids
    clipPath(whitePath) {
        drawCircle(Palette.ink, r, Offset(px, py))
        // Two catchlights make them shine.
        drawPath(oval(px - r * 0.58f, py - r * 0.72f, r * 0.56f, r * 0.56f), Color.White)
        drawPath(oval(px + r * 0.28f, py + r * 0.22f, r * 0.24f, r * 0.24f), Color.White.copy(alpha = 0.85f))
        // Happy cheeks push up from below as he smiles.
        if (f.squint > 0.02) {
            fillSkin(oval(c.x - 110, c.y + 95 * open - 70 * f.squint.toFloat(), 220f, 160f), body)
        }
        // Heavy, droopy lids when he's getting sleepy.
        if (f.mood == Mood.sleepy) {
            val droop = (0.5 + 0.06 * sin(f.time * 1.3)).toFloat()
            fillSkin(rect(c.x - 100, white.top - 10, 200f, white.height * droop + 10), body)
            val y = white.top + white.height * droop
            drawLine(Palette.nose.copy(alpha = 0.7f), Offset(c.x - 92, y), Offset(c.x + 92, y), 8f, StrokeCap.Round)
        }
        // A soft upper lid when he's focused on pointing.
        if (f.mood == Mood.pointing) fillSkin(rect(c.x - 100, white.top - 20, 200f, 40f), body)
    }
}
