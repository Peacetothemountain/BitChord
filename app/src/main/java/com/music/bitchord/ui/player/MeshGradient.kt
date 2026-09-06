package com.music.bitchord.ui.player

import android.graphics.Bitmap
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.palette.graphics.Palette
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.music.bitchord.data.settings.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

private val MaterialYouGrayishFallbacks = listOf(
    Color(0xFF282C34),
    Color(0xFF23272F),
    Color(0xFF2A2D36),
    Color(0xFF1F2228),
)

/** The four mesh colours, wrapped so the backdrop can skip recomposition. */
@Immutable
data class MeshPalette(val colors: List<Color>)

/**
 * YouTube Music inspired Material You grayish ambient backdrop: four moody, desaturated
 * color blobs sampled from the album art and blended with the system Monet palette,
 * drawn as soft radial gradients and blurred into a gentle, continuous drifting mesh.
 * Colour changes on track skip crossfade over ~1.4s instead of snapping.
 */
@Composable
fun MeshGradientBackground(
    palette: MeshPalette,
    modifier: Modifier = Modifier,
    trackKey: Any? = null,
    driftMillis: Int = 12_000,
    /**
     * Keep the blobs orbiting continuously with subtle ambient motion like YouTube Music.
     */
    continuous: Boolean = true,
    /**
     * How far the blobs are smeared.
     */
    blurRadius: Dp = 56.dp,
    animated: Boolean = true,
) {
    val reduceAnimation by AppSettings.reduceAnimation.collectAsStateWithLifecycle()
    val scheme = MaterialTheme.colorScheme

    val dynamicFallbacks = remember(scheme) {
        listOf(
            scheme.surfaceContainerHigh,
            scheme.surfaceContainerHighest,
            scheme.surfaceVariant,
            scheme.surfaceContainer,
        )
    }
    val fallbackMonet = scheme.surfaceContainerHigh

    val inputColors = (palette.colors.ifEmpty { dynamicFallbacks } + dynamicFallbacks)
    val tuned = inputColors
        .take(4)
        .map { it.toMaterialYouGrayish(fallbackMonet) }

    // Each colour slot crossfades independently when the track (palette) changes,
    // unless "reduce animation" is on, in which case colours snap straight to target.
    val colorSpec: AnimationSpec<Color> = if (reduceAnimation || !animated) snap() else tween(1400)
    val animatedColors = tuned.mapIndexed { index, color ->
        animateColorAsState(color, colorSpec, label = "meshColor$index").value
    }
    val baseColor by animateColorAsState(scheme.surfaceContainerLowest, colorSpec, label = "meshBase")

    // Smooth, reliable continuous ambient drift driven by rememberInfiniteTransition
    val infiniteTransition = rememberInfiniteTransition(label = "meshDrift")
    val rawDrift by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = driftMillis, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "driftPhase",
    )
    val drift = if (reduceAnimation || !animated || !continuous) 0f else rawDrift

    Box(modifier = modifier.clipToBounds()) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = 1.35f
                    scaleY = 1.35f
                }
                .background(baseColor)
                .blur(blurRadius),
        ) {
            val anchors = listOf(
                Offset(0.20f, 0.25f),
                Offset(0.80f, 0.22f),
                Offset(0.75f, 0.78f),
                Offset(0.22f, 0.75f),
            )
            val speeds = listOf(1f, -0.75f, 0.85f, -1.1f)

        animatedColors.forEachIndexed { index, color ->
            val anchor = anchors[index]
            val center = Offset(
                x = (anchor.x + 0.20f * cos(drift * speeds[index] + index * 1.7f)) * size.width,
                y = (anchor.y + 0.20f * sin(drift * speeds[index] * 0.9f + index * 2.3f)) * size.height,
            )
            val radius = size.maxDimension * 0.70f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(color.copy(alpha = 0.92f), color.copy(alpha = 0f)),
                    center = center,
                    radius = radius,
                ),
                radius = radius,
                center = center,
            )
        }

        // Gentle scrim so white text and controls stay crisp and legible
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(
                    Color.Black.copy(alpha = 0.04f),
                    Color.Black.copy(alpha = 0.18f),
                ),
            ),
        )
    }
}
}

/**
 * Loads the artwork with Coil (software bitmap, thumbnail-sized) and pulls a
 * 4-colour palette out of it. Recomputes when [imageUrl] changes.
 *
 * A track's motion artwork is frequently lit nothing like its still sleeve —
 * a different shot, a different grade. [canvasFrame], a frame captured off
 * the playing clip once one is available, is quantised the same way and
 * takes over from there, crossfading in exactly like a track skip.
 */
@Composable
fun rememberArtworkColors(imageUrl: String?, canvasFrame: Bitmap? = null): MeshPalette {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val dynamicFallbacks = remember(scheme) {
        listOf(
            scheme.surfaceContainerHigh,
            scheme.surfaceContainerHighest,
            scheme.surfaceVariant,
            scheme.surfaceContainer,
        )
    }
    var palette by remember(imageUrl) { mutableStateOf(MeshPalette(dynamicFallbacks)) }

    LaunchedEffect(imageUrl) {
        if (imageUrl == null) return@LaunchedEffect
        val request = ImageRequest.Builder(context)
            .data(imageUrl)
            .size(128) // palette quality is fine at thumbnail size, and it's fast
            .allowHardware(false) // Palette needs pixel access
            .build()
        val result = SingletonImageLoader.get(context).execute(request)
        val bitmap = (result as? SuccessResult)?.image?.toBitmap() ?: return@LaunchedEffect
        palette = MeshPalette(paletteOf(bitmap))
    }

    LaunchedEffect(canvasFrame) {
        val frame = canvasFrame ?: return@LaunchedEffect
        val colors = withContext(Dispatchers.Default) { paletteOf(frame) }
        palette = MeshPalette(colors)
    }
    return palette
}

/**
 * How far the blobs travel in one settle. A shade under half a turn: enough
 * that the backdrop visibly reacts to a track change, short of a full orbit
 * that would land the blobs back where they started.
 */
private const val DRIFT_RADIANS = (PI * 0.45f).toFloat()

/**
 * Four mesh colours drawn from the artwork.
 *
 * The named swatches — vibrant, muted and friends — are a convenience over the
 * full set, and on dark or desaturated sleeves every vibrant slot comes back
 * null: Karan Aujla's marble interior fills two of the five. Topping the rest
 * up from [MaterialYouGrayishFallbacks] is what left those covers sitting under the stock
 * purple. So the whole swatch list is read instead, and any shortfall is
 * derived from the art's own colours rather than borrowed.
 */
private fun paletteOf(bitmap: Bitmap): List<Color> {
    fun swatchesOf(builder: Palette.Builder): List<Color> =
        builder.maximumColorCount(24).generate().swatches
            .sortedByDescending { it.population }
            .map { Color(it.rgb) }

    val found = swatchesOf(Palette.from(bitmap)).ifEmpty {
        // The default filter discards near-black and near-white, which on a
        // monochrome sleeve can be everything there is.
        swatchesOf(Palette.from(bitmap).clearFilters())
    }

    val distinct = found.distinctEnough()
    return when {
        distinct.isEmpty() -> MaterialYouGrayishFallbacks
        distinct.size >= 4 -> distinct.take(4)
        else -> distinct.expandedToFour()
    }
}

/** Drop near-duplicates, so the four blobs don't collapse into one wash. */
private fun List<Color>.distinctEnough(): List<Color> {
    val kept = mutableListOf<Color>()
    forEach { color -> if (kept.none { it.isCloseTo(color) }) kept += color }
    return kept
}

private fun Color.isCloseTo(other: Color): Boolean {
    val a = hsl()
    val b = other.hsl()
    val hueGap = abs(a[0] - b[0]).let { min(it, 360f - it) }
    return hueGap < 15f && abs(a[2] - b[2]) < 0.12f
}

/** Fill the empty slots off the art itself, fanning hue and lightness out. */
private fun List<Color>.expandedToFour(): List<Color> {
    val out = toMutableList()
    var step = 1
    while (out.size < 4) {
        out += this[(out.size - size) % size].shifted(24f * step, 0.12f * step)
        step++
    }
    return out
}

private fun Color.shifted(hue: Float, lightness: Float): Color {
    val hsl = hsl()
    hsl[0] = (hsl[0] + hue) % 360f
    hsl[2] = (hsl[2] + lightness).coerceIn(0.2f, 0.7f)
    return Color(ColorUtils.HSLToColor(hsl))
}

private fun Color.hsl(): FloatArray =
    FloatArray(3).also { ColorUtils.colorToHSL(toArgb(), it) }

/**
 * Tunes the sampled artwork color into a moody, dark, grayish ambient tone
 * reminiscent of YouTube Music's ambient player mode and Material You Monet palette.
 * Keeps saturation in a subtle grayish-slate band (12% - 30% saturation),
 * sets a soft luminous ambient lightness (26% - 38% lightness), and blends
 * 35% with the active Material You Monet surface color.
 */
private fun Color.toMaterialYouGrayish(monetSurface: Color): Color {
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(toArgb(), hsl)
    val isMonetDark = ColorUtils.calculateLuminance(monetSurface.toArgb()) < 0.5f
    // Gentle grayish ambient tone with subtle color character (14% - 30% saturation)
    hsl[1] = (hsl[1] * 0.38f).coerceIn(0.14f, 0.30f)
    if (isMonetDark) {
        // Soft luminous ambient lightness against dark base (28% - 40% lightness)
        hsl[2] = hsl[2].coerceIn(0.28f, 0.40f)
    } else {
        // In light theme, keep it soft and subtle (82% - 94% lightness)
        hsl[2] = hsl[2].coerceIn(0.82f, 0.94f)
    }
    val desaturated = Color(ColorUtils.HSLToColor(hsl))
    // Blend with the system Monet surface container
    val blended = ColorUtils.blendARGB(desaturated.toArgb(), monetSurface.toArgb(), 0.30f)
    return Color(blended)
}
