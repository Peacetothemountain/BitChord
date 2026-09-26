package com.music.bitchord.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin
import kotlinx.coroutines.delay

/**
 * Material 3 squiggly line slider for track progress and volume control.
 * Features an undulating sinusoidal wave while playing, seamlessly smoothing
 * flat on pause or drag.
 */
@Composable
fun ThinSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    onValueChangeFinished: (() -> Unit)? = null,
    /**
     * Sends a travelling sheen across the whole bar — played *and* unplayed,
     * the bar's full thickness — for as long as it is true.
     *
     * The wait a version switch spends fetching and measuring the other cut is
     * a wait with no measurable fraction to draw, and a stock indeterminate
     * line sat on the scrubber like a second, uglier bar beside the one the
     * listener is already watching. The sheen claims the bar itself instead:
     * no extra chrome, no slot of its own, nothing shifting under it on the
     * frame the eye lands — just motion along the bar, pointing the way the
     * music is going.
     */
    loading: Boolean = false,
    /**
     * Span of the track, as fractions of its duration, that the next Automix
     * transition is planned to occupy.
     */
    transitionWindow: ClosedFloatingPointRange<Float>? = null,
    idleHeight: Dp = 6.dp,
    activeHeight: Dp = 10.dp,
    activeColor: Color = Color.White.copy(alpha = 0.92f),
    inactiveColor: Color = Color.White.copy(alpha = 0.26f),
    markerColor: Color = Color.White.copy(alpha = 0.5f),
    /** Whether to render the signature Google Material 3 squiggly wave. */
    squiggly: Boolean = true,
    /** True when music is currently playing, driving the squiggly wave oscillation. */
    isPlaying: Boolean = true,
) {
    var dragging by remember { mutableStateOf(false) }
    val height by animateDpAsState(
        targetValue = if (dragging) activeHeight else idleHeight,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "sliderHeight",
    )
    // The sheen gets a minimum beat even when the switch resolves instantly —
    // a cached offset can land in a few hundred milliseconds — so its entry,
    // its sweep and its hand-over to the progress bar always play out as one
    // continuous morph, whenever the wait happened to start and stop. A flip
    // back to loading during the hold cancels it and the sheen simply stays,
    // so rapid toggling never blinks the bar out mid-morph.
    var shownLoading by remember { mutableStateOf(loading) }
    var sheenStart by remember { mutableStateOf(System.nanoTime()) }
    LaunchedEffect(loading) {
        if (loading) {
            sheenStart = System.nanoTime()
            shownLoading = true
        } else {
            val remaining = LOADING_MIN_MS - (System.nanoTime() - sheenStart) / 1_000_000L
            if (remaining > 0) delay(remaining)
            shownLoading = false
        }
    }
    // The played fill doesn't blink out when a switch starts, nor snap back
    // when it lands: it retracts to nothing as the sheen takes the bar over,
    // and slides back in when the wait is over — the loading bar *becoming*
    // the progress bar, rather than one vanishing and the other appearing on
    // the same frame. Keyed to the latched state and eased on the same curve
    // and length as the sheen's own transitions, so the two never drift apart.
    val fillFactor by animateFloatAsState(
        targetValue = if (shownLoading) 0f else 1f,
        animationSpec = tween(durationMillis = MORPH_MS, easing = FastOutSlowInEasing),
        label = "fillFactor",
    )

    // Undulating wave phase animation
    val infiniteTransition = rememberInfiniteTransition(label = "squigglyPhase")
    val wavePhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "wavePhase",
    )

    // Wave amplitude smooth transition: wavy while playing, straightens smoothly when paused or dragged
    val targetWaveAmplitude = if (squiggly && isPlaying && !dragging) 3.5.dp else 0.dp
    val waveAmplitude by animateDpAsState(
        targetValue = targetWaveAmplitude,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "waveAmp",
    )

    // Tactile thumb scale: expands dynamically when dragging/scrubbing
    val thumbScale by animateFloatAsState(
        targetValue = if (dragging) 1.28f else 1.0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "thumbScale",
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(activeHeight + 24.dp)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    dragging = true
                    onValueChange((down.position.x / size.width).coerceIn(0f, 1f))

                    while (true) {
                        val event = awaitPointerEvent()
                        val pointer = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!pointer.pressed) {
                            pointer.consume()
                            break
                        }
                        if (pointer.positionChanged()) {
                            onValueChange((pointer.position.x / size.width).coerceIn(0f, 1f))
                            pointer.consume()
                        }
                    }

                    dragging = false
                    onValueChangeFinished?.invoke()
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(height + 16.dp),
        ) {
            val trackStroke = height.toPx()
            val centerY = size.height / 2f
            val radius = CornerRadius(trackStroke / 2f)

            // Inactive background track
            drawRoundRect(
                color = inactiveColor,
                topLeft = Offset(0f, centerY - trackStroke / 2f),
                size = Size(size.width, trackStroke),
                cornerRadius = radius,
            )

            // Transition window marker
            transitionWindow?.let { window ->
                val from = size.width * window.start.coerceIn(0f, 1f)
                val to = size.width * window.endInclusive.coerceIn(0f, 1f)
                if (to > from) {
                    drawRoundRect(
                        color = markerColor,
                        topLeft = Offset(from, centerY - trackStroke / 2f),
                        size = Size(to - from, trackStroke),
                        cornerRadius = radius,
                    )
                }
            }
            val filled = size.width * value.coerceIn(0f, 1f) * fillFactor
            val ampPx = waveAmplitude.toPx() * fillFactor

            if (filled > 0f) {
                if (ampPx > 0.05f) {
                    // Draw squiggly sine wave along played portion with organic damping
                    val waveLengthPx = 24.dp.toPx()
                    val dampDistance = 16.dp.toPx()
                    val wavePath = Path()
                    wavePath.moveTo(0f, centerY)

                    val stepPx = 2.dp.toPx()
                    var x = 0f
                    while (x <= filled) {
                        val startDamp = (x / dampDistance).coerceIn(0f, 1f)
                        val endDamp = ((filled - x) / dampDistance).coerceIn(0f, 1f)
                        val damp = min(startDamp, endDamp)
                        val y = centerY + sin((x / waveLengthPx) * 2f * PI.toFloat() - wavePhase) * ampPx * damp
                        wavePath.lineTo(x, y)
                        x += stepPx
                    }
                    wavePath.lineTo(filled, centerY)

                    drawPath(
                        path = wavePath,
                        color = activeColor,
                        style = Stroke(
                            width = trackStroke,
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round,
                        ),
                    )
                } else {
                    // Smooth flat active track when paused or straight
                    drawRoundRect(
                        color = activeColor,
                        topLeft = Offset(0f, centerY - trackStroke / 2f),
                        size = Size(
                            filled.coerceAtLeast(size.height * fillFactor).coerceAtMost(size.width),
                            trackStroke,
                        ),
                        cornerRadius = radius,
                    )
                }

                // Material 3 Expressive tactile thumb capsule at playhead
                val thumbWidth = (trackStroke * 0.95f * thumbScale * fillFactor).coerceAtLeast(5.dp.toPx() * fillFactor)
                val thumbHeight = (trackStroke * 1.55f * thumbScale * fillFactor).coerceAtLeast(14.dp.toPx() * fillFactor)
                if (thumbWidth > 0f && thumbHeight > 0f) {
                    drawRoundRect(
                        color = activeColor,
                        topLeft = Offset(filled - thumbWidth / 2f, centerY - thumbHeight / 2f),
                        size = Size(thumbWidth, thumbHeight),
                        cornerRadius = CornerRadius(thumbWidth / 2f),
                    )
                }
            }
        }
        AnimatedVisibility(
            visible = shownLoading,
            // Grown out of the bar's own left end — where the progress fill
            // begins — instead of slid in from a third of its own width: the
            // capsule is full-bleed, so that slide started past the screen
            // edge and flew in from outside the display. Same duration and
            // curve as the fill's retraction, so the swap reads as one morph.
            enter = fadeIn(tween(durationMillis = MORPH_MS, easing = FastOutSlowInEasing)) +
                scaleIn(
                    animationSpec = tween(durationMillis = MORPH_MS, easing = FastOutSlowInEasing),
                    initialScale = 0f,
                    transformOrigin = TransformOrigin(0f, 0.5f),
                ),
            exit = fadeOut(tween(durationMillis = MORPH_MS, easing = FastOutSlowInEasing)),
        ) {
            MixSheen(height = height, color = activeColor)
        }
    }
}

private const val SHEEN_BAND_FRACTION = 0.34f

/** Length of one morph step — entry, fill retraction, fill return, exit — all on the same curve. */
private const val MORPH_MS = 450

/** Shortest time the sheen stays up, so even an instant switch still plays its morph. */
private const val LOADING_MIN_MS = 600L

/**
 * A highlight sweeping the bar's full thickness — played *and* unplayed
 * alike — about once a second, at the progress fill's own brightness.
 *
 * Loading has no measurable fraction to draw, so an indeterminate indicator
 * has to draw *something*: the usual choice is a thin line claiming a
 * sliver of the scrubber's height, which reads as a second, lesser bar
 * growing out of the first. This band instead takes the whole thickness the
 * scrubber already occupies and moves along it, so the wait looks like the
 * bar itself moving rather than an alien element parked on top — no gap, no
 * slot, no shifting of the controls below it.
 *
 * One pass a second rather than the old two: any faster and the band is a
 * strobe the eye tracks instead of a wait it can ignore. The pass reverses
 * at each edge rather than restarting from the far one.
 */
@Composable
private fun MixSheen(height: Dp, color: Color) {
    val transition = rememberInfiniteTransition(label = "mixSheen")
    val phase by transition.animateFloat(
        initialValue = -SHEEN_BAND_FRACTION,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1000, easing = LinearEasing),
            // Reverses rather than restarting: a restart teleports the band
            // back to the far edge every second, which is a hitch the eye
            // catches each time. Ping-pong has no edge to fall off.
            repeatMode = RepeatMode.Reverse,
        ),
        label = "mixSheenPhase",
    )
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(height),
    ) {
        val band = size.width * SHEEN_BAND_FRACTION
        val x = phase * size.width
        drawRoundRect(
            brush = Brush.horizontalGradient(
                colorStops = arrayOf(
                    0f to Color.Transparent,
                    0.5f to color,
                    1f to Color.Transparent,
                ),
                startX = x,
                endX = x + band,
            ),
            size = Size(size.width, size.height),
            // The band is clipped to the same capsule the track is drawn
            // with: a plain rect bared square corners wherever the sweep
            // crossed the rounded ends.
            cornerRadius = CornerRadius(size.height / 2f),
        )
    }
}
