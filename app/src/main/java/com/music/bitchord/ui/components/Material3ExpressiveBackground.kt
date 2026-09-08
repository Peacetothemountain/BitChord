package com.music.bitchord.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.data.settings.AppSettings
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Material 3 Expressive animated ambient background for BitChord.
 *
 * Directly driven by the device's dynamic Material 3 Expressive color scheme
 * (primary, tertiary, secondary, and rich dynamic tonal containers).
 *
 * Continuous harmonic orbital motion brings the background to life with
 * vibrant, breathing ambient color blobs, hardware-blurred and balanced with
 * a subtle contrast scrim to keep foreground text, shelves, cards, and navigation
 * 100% crisp and readable.
 */
@Composable
fun Material3ExpressiveBackground(
    modifier: Modifier = Modifier,
    driftMillis: Int = 14_000,
    continuous: Boolean = true,
    blurRadius: Dp = 72.dp,
    animated: Boolean = true,
) {
    val reduceAnimation by AppSettings.reduceAnimation.collectAsStateWithLifecycle()
    val scheme = MaterialTheme.colorScheme
    val isSystemDark = isSystemInDarkTheme()
    val isDark = isSystemDark || ColorUtils.calculateLuminance(scheme.background.toArgb()) < 0.5f

    // Direct Material 3 Expressive dynamic color tokens from the device
    val dynamicColors = remember(scheme) {
        listOf(
            scheme.primary,
            scheme.tertiary, // Signature M3 Expressive contrasting tone (e.g. coral/rose)
            scheme.secondary,
            scheme.tertiaryContainer,
            scheme.primaryContainer,
        )
    }

    val colorSpec: AnimationSpec<Color> = if (reduceAnimation || !animated) snap() else tween(1200)

    val animatedColors = dynamicColors.mapIndexed { index, color ->
        animateColorAsState(color, colorSpec, label = "m3Color$index").value
    }

    val baseColor by animateColorAsState(
        if (isDark) scheme.surfaceContainerLowest else scheme.surfaceContainerLowest,
        colorSpec,
        label = "m3BaseColor",
    )

    // Smooth, organic continuous motion with harmonic drift phases
    val infiniteTransition = rememberInfiniteTransition(label = "m3ExpressiveMotion")
    val rawDrift by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = driftMillis, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "driftPhase",
    )

    val secondaryDrift by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = (driftMillis * 1.45f).toInt(), easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "secondaryDriftPhase",
    )

    val drift = if (reduceAnimation || !animated || !continuous) 0f else rawDrift
    val secDrift = if (reduceAnimation || !animated || !continuous) 0f else secondaryDrift

    // Alpha calibration for glowing vibrancy without washing out contrast
    val blobAlpha = if (isDark) 0.48f else 0.26f
    val containerAlpha = if (isDark) 0.58f else 0.32f

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
            // Anchor coordinates distributed across screen quadrants
            val anchors = listOf(
                Offset(0.18f, 0.22f), // Primary (top-left)
                Offset(0.82f, 0.25f), // Tertiary (top-right expressive pop)
                Offset(0.25f, 0.78f), // Secondary (bottom-left)
                Offset(0.78f, 0.72f), // TertiaryContainer (bottom-right)
                Offset(0.50f, 0.48f), // PrimaryContainer (drifting center)
            )

            val orbitalSpeeds = listOf(1.0f, -0.85f, 0.90f, -1.15f, 0.75f)
            val orbitalRadiiX = listOf(0.22f, 0.20f, 0.24f, 0.18f, 0.26f)
            val orbitalRadiiY = listOf(0.18f, 0.24f, 0.18f, 0.22f, 0.20f)

            animatedColors.forEachIndexed { index, color ->
                val anchor = anchors.getOrElse(index) { Offset(0.5f, 0.5f) }
                val speed = orbitalSpeeds.getOrElse(index) { 1f }
                val rx = orbitalRadiiX.getOrElse(index) { 0.2f }
                val ry = orbitalRadiiY.getOrElse(index) { 0.2f }

                // Combine primary and secondary drift for non-repeating organic movement
                val currentAngle = drift * speed + secDrift * 0.35f + index * 1.4f
                val centerX = (anchor.x + rx * cos(currentAngle)) * size.width
                val centerY = (anchor.y + ry * sin(currentAngle * 0.92f)) * size.height
                val center = Offset(centerX, centerY)

                val radius = size.maxDimension * (if (index == 4) 0.65f else 0.76f)
                val targetAlpha = if (index >= 3) containerAlpha else blobAlpha

                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            color.copy(alpha = targetAlpha),
                            color.copy(alpha = targetAlpha * 0.45f),
                            color.copy(alpha = 0f),
                        ),
                        center = center,
                        radius = radius,
                    ),
                    radius = radius,
                    center = center,
                )
            }

            // Subtle vertical contrast scrim ensuring white text, tabs, and cards remain crystal clear
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color.Black.copy(alpha = if (isDark) 0.06f else 0.02f),
                        Color.Black.copy(alpha = if (isDark) 0.20f else 0.05f),
                    ),
                ),
            )
        }
    }
}
