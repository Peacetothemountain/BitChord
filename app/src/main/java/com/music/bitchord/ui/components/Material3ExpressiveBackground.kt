package com.music.bitchord.ui.components

import android.os.Build
import androidx.annotation.RequiresApi
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
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.data.settings.AppSettings
import org.intellij.lang.annotations.Language
import kotlin.math.cos
import kotlin.math.sin

/**
 * Material 3 Smokey Animated Ambient Background for BitChord.
 *
 * Grounded in the app's authentic Material theme background color, this creates
 * an organic, volumetric smoky atmosphere featuring gentle rising plumes, curling
 * fluid vortices, and subtle billowing mist tinted with the device's dynamic
 * Material You tonal palette.
 *
 * Hardware-accelerated with AGSL RuntimeShader on Android 13+ (API 33+) with full
 * domain-warped Fractal Brownian Motion (FBM) for realistic fluid dynamics at 120 FPS,
 * seamlessly falling back to a multi-harmonic blurred procedural Canvas on older devices.
 */
@Composable
fun Material3ExpressiveBackground(
    modifier: Modifier = Modifier,
    driftMillis: Int = 18_000,
    continuous: Boolean = true,
    blurRadius: Dp = 64.dp,
    animated: Boolean = true,
) {
    val reduceAnimation by AppSettings.reduceAnimation.collectAsStateWithLifecycle()
    val scheme = MaterialTheme.colorScheme
    val isSystemDark = isSystemInDarkTheme()
    val isDark = isSystemDark || ColorUtils.calculateLuminance(scheme.background.toArgb()) < 0.5f

    val colorSpec: AnimationSpec<Color> = if (reduceAnimation || !animated) snap() else tween(1400)

    // Base color is the app's clean, authentic Material background
    val baseColor by animateColorAsState(
        targetValue = scheme.background,
        animationSpec = colorSpec,
        label = "smokeBaseColor",
    )

    // Subtle, harmoniously tinted smoke colors derived from the Material theme
    val rawSmokeColors = remember(scheme, isDark) {
        if (isDark) {
            listOf(
                // Primary smoke plume: subtle elevated charcoal/slate tone
                scheme.surfaceContainerHigh.copy(alpha = 0.82f),
                // Secondary curling wisp: soft tonal accent carrying dynamic primary warmth
                Color(ColorUtils.blendARGB(scheme.surfaceVariant.toArgb(), scheme.primary.toArgb(), 0.16f)),
                // Tertiary wisp: ethereal dynamic tertiary accent (e.g. delicate lavender/cyan/coral)
                Color(ColorUtils.blendARGB(scheme.surfaceContainerHighest.toArgb(), scheme.tertiary.toArgb(), 0.20f)),
                // Delicate luminous wisp highlight
                scheme.onSurface.copy(alpha = 0.32f),
            )
        } else {
            listOf(
                scheme.surfaceContainerHigh.copy(alpha = 0.70f),
                Color(ColorUtils.blendARGB(scheme.surfaceVariant.toArgb(), scheme.primary.toArgb(), 0.14f)),
                Color(ColorUtils.blendARGB(scheme.surfaceContainerLow.toArgb(), scheme.tertiary.toArgb(), 0.16f)),
                scheme.onSurface.copy(alpha = 0.22f),
            )
        }
    }

    val animatedSmokeColors = rawSmokeColors.mapIndexed { index, color ->
        animateColorAsState(color, colorSpec, label = "smokeColor$index").value
    }

    // Continuous time accumulator for fluid smoke motion
    val infiniteTransition = rememberInfiniteTransition(label = "smokeFluidMotion")
    val rawTimeSeconds by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1000_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "smokeTimeSeconds",
    )

    val timeSeconds = if (reduceAnimation || !animated || !continuous) 0f else rawTimeSeconds

    // Hardware AGSL RuntimeShader on Android 13+ (API 33+)
    val agslShader = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching { android.graphics.RuntimeShader(SmokeShaderString) }.getOrNull()
        } else {
            null
        }
    }

    Box(modifier = modifier.fillMaxSize().clipToBounds()) {
        if (agslShader != null) {
            AgslSmokeCanvas(
                shader = agslShader,
                baseColor = baseColor,
                smokeColors = animatedSmokeColors,
                isDark = isDark,
                timeSeconds = timeSeconds,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            ProceduralSmokeCanvas(
                baseColor = baseColor,
                smokeColors = animatedSmokeColors,
                isDark = isDark,
                timeSeconds = timeSeconds,
                blurRadius = blurRadius,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun AgslSmokeCanvas(
    shader: android.graphics.RuntimeShader,
    baseColor: Color,
    smokeColors: List<Color>,
    isDark: Boolean,
    timeSeconds: Float,
    modifier: Modifier = Modifier,
) {
    val shaderBrush = remember(shader) { ShaderBrush(shader) }

    Canvas(modifier = modifier) {
        shader.setFloatUniform("uResolution", size.width, size.height)
        shader.setFloatUniform("uTime", timeSeconds)
        shader.setFloatUniform("uIsDark", if (isDark) 1f else 0f)
        shader.setColorUniform("uBaseColor", baseColor.toArgb())
        shader.setColorUniform("uSmoke1", smokeColors.getOrElse(0) { baseColor }.toArgb())
        shader.setColorUniform("uSmoke2", smokeColors.getOrElse(1) { baseColor }.toArgb())
        shader.setColorUniform("uSmoke3", smokeColors.getOrElse(2) { baseColor }.toArgb())
        shader.setColorUniform("uSmoke4", smokeColors.getOrElse(3) { baseColor }.toArgb())

        drawRect(brush = shaderBrush)
    }
}

@Composable
private fun ProceduralSmokeCanvas(
    baseColor: Color,
    smokeColors: List<Color>,
    isDark: Boolean,
    timeSeconds: Float,
    blurRadius: Dp,
    modifier: Modifier = Modifier,
) {
    val maxAlpha = if (isDark) 0.32f else 0.18f

    Box(modifier = modifier.clipToBounds()) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = 1.30f
                    scaleY = 1.30f
                }
                .background(baseColor)
                .blur(blurRadius),
        ) {
            val anchors = listOf(
                Offset(0.20f, 0.28f),
                Offset(0.80f, 0.24f),
                Offset(0.48f, 0.55f),
                Offset(0.22f, 0.78f),
                Offset(0.76f, 0.74f),
                Offset(0.50f, 0.20f),
            )
            val speeds = listOf(0.45f, -0.40f, 0.50f, -0.55f, 0.35f, -0.48f)
            val radiiX = listOf(0.24f, 0.22f, 0.26f, 0.20f, 0.28f, 0.22f)
            val radiiY = listOf(0.20f, 0.26f, 0.18f, 0.24f, 0.20f, 0.25f)
            val phases = listOf(0f, 1.2f, 2.4f, 3.6f, 4.8f, 5.5f)

            anchors.forEachIndexed { i, anchor ->
                val color = smokeColors[i % smokeColors.size]
                val sp = speeds[i]
                val rx = radiiX[i]
                val ry = radiiY[i]
                val ph = phases[i]

                val angle = (timeSeconds * sp * 0.18f + ph)
                val rise = ((timeSeconds * 0.04f + i * 0.16f) % 1f)
                val cx = (anchor.x + rx * cos(angle) + 0.06f * sin(angle * 1.8f)) * size.width
                val cy = ((anchor.y + ry * sin(angle * 0.9f) - rise * 0.25f).mod(1.2f) - 0.1f) * size.height
                val center = Offset(cx, cy)
                val radius = size.maxDimension * (if (i % 2 == 0) 0.68f else 0.82f)

                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            color.copy(alpha = maxAlpha),
                            color.copy(alpha = maxAlpha * 0.50f),
                            color.copy(alpha = maxAlpha * 0.15f),
                            Color.Transparent,
                        ),
                        center = center,
                        radius = radius,
                    ),
                    radius = radius,
                    center = center,
                )
            }
        }
    }
}

/**
 * Domain-Warped Fractal Brownian Motion (FBM) Smoke AGSL Shader.
 *
 * Simulates fluid convective vortices and curling smoke plumes, softly feathered
 * and blended directly into the authentic Material background.
 */
@Language("AGSL")
private const val SmokeShaderString = """
uniform float2 uResolution;
uniform float uTime;
layout(color) uniform half4 uBaseColor;
layout(color) uniform half4 uSmoke1;
layout(color) uniform half4 uSmoke2;
layout(color) uniform half4 uSmoke3;
layout(color) uniform half4 uSmoke4;
uniform float uIsDark;

float hash(float2 p) {
    float3 p3 = fract(float3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

float noise(float2 p) {
    float2 i = floor(p);
    float2 f = fract(p);
    float2 u = f * f * (3.0 - 2.0 * f);
    float a = hash(i);
    float b = hash(i + float2(1.0, 0.0));
    float c = hash(i + float2(0.0, 1.0));
    float d = hash(i + float2(1.0, 1.0));
    return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}

float2 rot(float2 p, float a) {
    float c = cos(a);
    float s = sin(a);
    return float2(c * p.x - s * p.y, s * p.x + c * p.y);
}

float fbm(float2 p) {
    float v = 0.0;
    float a = 0.5;
    for (int i = 0; i < 4; ++i) {
        v += a * noise(p);
        p = rot(p, 0.5) * 2.02 + float2(100.0, 100.0);
        a *= 0.5;
    }
    return v;
}

half4 main(float2 fragCoord) {
    float2 uv = fragCoord / uResolution;
    float aspect = uResolution.x / uResolution.y;
    float2 p = float2(uv.x * aspect, uv.y) * 2.2;
    
    // Slow, serene atmospheric drift
    float t = uTime * 0.06;
    float2 motion = float2(sin(t * 0.6) * 0.15, -t * 0.35);
    
    // Domain Warping pass 1: macro velocity flow
    float2 q = float2(
        fbm(p + motion),
        fbm(p + motion * 0.85 + float2(5.2, 1.3))
    );
    
    // Domain Warping pass 2: micro turbulent eddies and curls
    float2 r = float2(
        fbm(p + 2.0 * q + float2(1.7, 9.2) + float2(t * 0.35, -t * 0.2)),
        fbm(p + 2.0 * q + float2(8.3, 2.8) + float2(-t * 0.25, t * 0.4))
    );
    
    // Smoke body density
    float smoke = fbm(p + 2.2 * r + motion * 0.3);
    
    // Delicate curling wisps
    float wisps = fbm(p * 2.5 + 1.6 * q - motion * 0.5);
    
    // Smooth feathering curves for billowing clouds of smoke
    float d1 = smoothstep(0.18, 0.76, smoke);
    float d2 = smoothstep(0.32, 0.84, r.x * r.y * 3.6);
    float d3 = smoothstep(0.38, 0.88, wisps);
    
    // Layering smoke colors
    half4 plume = mix(uSmoke1, uSmoke2, clamp(q.x * 1.3, 0.0, 1.0));
    plume = mix(plume, uSmoke3, clamp(d2, 0.0, 1.0));
    plume = mix(plume, uSmoke4, clamp(d3 * 0.55, 0.0, 1.0));
    
    // Smoke opacity: ethereal and moody, preserving the deep Material background
    float maxAlpha = (uIsDark > 0.5) ? 0.38 : 0.22;
    float alpha = (d1 * 0.55 + d2 * 0.30 + d3 * 0.15) * maxAlpha;
    
    half3 finalRgb = mix(uBaseColor.rgb, plume.rgb, alpha);
    return half4(finalRgb, 1.0);
}
"""
