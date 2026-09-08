package com.music.bitchord.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Material 3 Expressive shape-morphing indeterminate loading indicator.
 * Smoothly morphs between an organic squircle, 4-petal flower, and circle
 * while rotating with dynamic angular velocity.
 */
@Composable
fun ExpressiveShapeSpinner(
    modifier: Modifier = Modifier,
    size: Dp = 38.dp,
    strokeWidth: Dp = 3.dp,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "shapeMorph")

    // Rotation angle
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "spinnerRotation",
    )

    // Morph amplitude between circle (0f) and expressive 4-lobed squircle (0.24f)
    val morphAmplitude by infiniteTransition.animateFloat(
        initialValue = -0.22f,
        targetValue = 0.22f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "morphAmplitude",
    )

    // Sweep length (gives the open indeterminate spinner look)
    val sweepFraction by infiniteTransition.animateFloat(
        initialValue = 0.65f,
        targetValue = 0.88f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1100, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "sweepFraction",
    )

    Canvas(modifier = modifier.size(size)) {
        val diameter = this.size.minDimension
        if (diameter <= 0f) return@Canvas

        val strokePx = strokeWidth.toPx()
        val baseRadius = (diameter - strokePx * 2.2f) / 2f
        val center = Offset(this.size.width / 2f, this.size.height / 2f)

        val path = Path()
        val steps = 60
        val totalAngle = 2f * PI.toFloat() * sweepFraction
        val rotRad = Math.toRadians(rotation.toDouble()).toFloat()

        for (i in 0..steps) {
            val progress = i.toFloat() / steps
            val theta = progress * totalAngle
            // Polar harmonic curve: r(theta) = R * (1 + A * cos(4 * theta))
            val r = baseRadius * (1f + morphAmplitude * cos(4f * theta))
            val angle = theta + rotRad

            val x = center.x + r * cos(angle)
            val y = center.y + r * sin(angle)

            if (i == 0) {
                path.moveTo(x, y)
            } else {
                path.lineTo(x, y)
            }
        }

        drawPath(
            path = path,
            color = color,
            style = Stroke(
                width = strokePx,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            ),
        )
    }
}
