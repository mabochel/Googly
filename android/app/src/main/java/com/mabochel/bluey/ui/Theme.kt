package com.mabochel.bluey.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.mabochel.bluey.R

/** Blueberry colors from the design canvas (Shared/Palette.swift). */
object Palette {
    val berry1 = Color(0xFFA9BCFF) // light periwinkle
    val berry2 = Color(0xFF6C86F5)
    val berry3 = Color(0xFF4254D6)
    val berry4 = Color(0xFF2B2F8F) // deep indigo
    val nose = Color(0xFF1C1F66)
    val ink = Color(0xFF17151F)
    val inkSoft = Color(0xFFB9B2CC)
    val panel = Color(0xFF1E1B29)
    val green = Color(0xFF5BE49B)
    val peach = Color(0xFFEBAC73)
    val pink = Color(0xFFE5547A)
    val stops = arrayOf(0f to berry1, 0.34f to berry2, 0.68f to berry3, 1f to berry4)
}

@OptIn(ExperimentalTextApi::class)
object BlueyFonts {
    val fredoka = FontFamily(
        Font(R.font.fredoka, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700))),
        Font(R.font.fredoka, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
    )
    val plexSans = FontFamily(
        Font(R.font.plex_sans, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
        Font(R.font.plex_sans, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
        Font(R.font.plex_sans, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
    )
    val plexMono = FontFamily(
        Font(R.font.plex_mono, FontWeight.Normal),
        Font(R.font.plex_mono_medium, FontWeight.Medium),
    )
}

fun fredoka(size: TextUnit, weight: FontWeight = FontWeight.Bold) =
    TextStyle(fontFamily = BlueyFonts.fredoka, fontWeight = weight, fontSize = size)
fun plexSans(size: TextUnit, weight: FontWeight = FontWeight.Normal) =
    TextStyle(fontFamily = BlueyFonts.plexSans, fontWeight = weight, fontSize = size)
fun plexMono(size: TextUnit, weight: FontWeight = FontWeight.Normal) =
    TextStyle(fontFamily = BlueyFonts.plexMono, fontWeight = weight, fontSize = size)

@Composable
fun BlueyTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Palette.berry2,
            onPrimary = Color.White,
            secondary = Palette.berry1,
            background = Color.Black,
            surface = Palette.panel,
            onSurface = Color.White,
            surfaceVariant = Color(0xFF2A2638),
            onSurfaceVariant = Palette.inkSoft,
        ),
        typography = MaterialTheme.typography.copy(
            bodyLarge = plexSans(16.sp), bodyMedium = plexSans(14.sp), bodySmall = plexSans(12.sp),
            labelLarge = plexSans(14.sp, FontWeight.SemiBold), titleLarge = fredoka(22.sp),
        ),
    ) { Box { content() } }
}

/** The blob outline: CSS `border-radius: 52% 48% 46% 54% / 58% 56% 44% 42%` (BlobShape.swift). */
object Blob {
    fun path(r: Rect): Path {
        val w = r.width
        val h = r.height
        val tlw = 0.52f * w; val tlh = 0.58f * h
        val trw = 0.48f * w; val trh = 0.56f * h
        val brw = 0.46f * w; val brh = 0.44f * h
        val blw = 0.54f * w; val blh = 0.42f * h
        val k = 0.5523f // cubic approximation of a quarter ellipse
        return Path().apply {
            moveTo(r.left + tlw, r.top)
            lineTo(r.right - trw, r.top)
            cubicTo(r.right - trw * (1 - k), r.top, r.right, r.top + trh * (1 - k), r.right, r.top + trh)
            lineTo(r.right, r.bottom - brh)
            cubicTo(r.right, r.bottom - brh * (1 - k), r.right - brw * (1 - k), r.bottom, r.right - brw, r.bottom)
            lineTo(r.left + blw, r.bottom)
            cubicTo(r.left + blw * (1 - k), r.bottom, r.left, r.bottom - blh * (1 - k), r.left, r.bottom - blh)
            lineTo(r.left, r.top + tlh)
            cubicTo(r.left, r.top + tlh * (1 - k), r.left + tlw * (1 - k), r.top, r.left + tlw, r.top)
            close()
        }
    }

    /** The blueberry gradient at 150°, like the design. */
    fun fill(r: Rect): Brush = Brush.linearGradient(
        *Palette.stops,
        start = Offset(r.left + 0.25f * r.width, r.top + 0.067f * r.height),
        end = Offset(r.left + 0.75f * r.width, r.top + 0.933f * r.height),
    )

    /** The same gradient for any composable background. */
    val linear: Brush = object : androidx.compose.ui.graphics.ShaderBrush() {
        override fun createShader(size: androidx.compose.ui.geometry.Size) =
            androidx.compose.ui.graphics.LinearGradientShader(
                from = Offset(0.25f * size.width, 0.067f * size.height),
                to = Offset(0.75f * size.width, 0.933f * size.height),
                colors = Palette.stops.map { it.second },
                colorStops = Palette.stops.map { it.first },
            )
    }
}

/** The little blueberry crown on top of his head (60×40 design box). */
object Crown {
    private val points = listOf(30f to 4f, 36f to 16f, 52f to 12f, 40f to 24f, 46f to 36f, 30f to 28f,
        14f to 36f, 20f to 24f, 8f to 12f, 24f to 16f)

    fun path(r: Rect) = Path().apply {
        points.forEachIndexed { i, (x, y) ->
            val px = r.left + x / 60f * r.width
            val py = r.top + y / 40f * r.height
            if (i == 0) moveTo(px, py) else lineTo(px, py)
        }
        close()
    }
}
