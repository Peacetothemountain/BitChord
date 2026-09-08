package com.music.bitchord.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin

/**
 * Material 3 Expressive dynamic audio visualizer equalizer bars.
 * Renders multiple rounded pill bars that smoothly bounce out-of-phase while audio
 * is playing, and seamlessly settle to a resting baseline when paused.
 */
@Composable
fun ExpressivePlayingEqualizer(
    modifier: Modifier = Modifier,
    isPlaying: Boolean = true,
    tint: Color = MaterialTheme.colorScheme.primary,
    size: Dp = 20.dp,
    barCount: Int = 4,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "eqRhythm")
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1100, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "eqPhase",
    )

    // Global active factor collapses bars when paused with spring dampening
    val activeFactor by animateFloatAsState(
        targetValue = if (isPlaying) 1f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "eqActiveFactor",
    )

    Canvas(
        modifier = modifier.size(size),
    ) {
        val width = this.size.width
        val height = this.size.height
        if (width <= 0f || height <= 0f) return@Canvas

        val barSpacing = width * 0.12f
        val totalSpacing = barSpacing * (barCount - 1)
        val barWidth = ((width - totalSpacing) / barCount).coerceAtLeast(2.dp.toPx())
        val cornerRadius = CornerRadius(barWidth / 2f)

        // Staggered frequency multipliers and phase shifts for organic visualizer rhythm
        val multipliers = floatArrayOf(1.8f, 2.7f, 1.4f, 2.3f)
        val phaseShifts = floatArrayOf(0f, 1.2f, 2.5f, 0.7f)
        val minFractions = floatArrayOf(0.28f, 0.40f, 0.22f, 0.35f)
        val maxFractions = floatArrayOf(0.88f, 1.00f, 0.75f, 0.94f)

        var currentX = 0f
        for (i in 0 until barCount) {
            val mult = multipliers.getOrElse(i) { 1.5f + i * 0.3f }
            val shift = phaseShifts.getOrElse(i) { i * 0.8f }
            val minFrac = minFractions.getOrElse(i) { 0.25f }
            val maxFrac = maxFractions.getOrElse(i) { 0.90f }

            // Harmonic wave oscillating between minFrac and maxFrac
            val oscillation = (sin(phase * mult + shift) + 1f) / 2f
            val dynamicFraction = minFrac + (maxFrac - minFrac) * oscillation

            // Resting fraction when paused is baseline pill
            val restingFraction = 0.20f
            val currentFraction = restingFraction + (dynamicFraction - restingFraction) * activeFactor

            val barHeight = (height * currentFraction).coerceIn(barWidth, height)
            val topY = height - barHeight

            drawRoundRect(
                color = tint,
                topLeft = Offset(currentX, topY),
                size = Size(barWidth, barHeight),
                cornerRadius = cornerRadius,
            )

            currentX += barWidth + barSpacing
        }
    }
}
