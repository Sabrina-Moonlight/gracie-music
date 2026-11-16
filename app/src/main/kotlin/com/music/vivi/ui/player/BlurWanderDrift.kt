/**
 * vivimusic Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.music.vivi.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** Margen extra (+2 %) para que el borde del fondo difuminado nunca asome. */
internal const val BlurBackdropCoverSafety = 1.02f

private const val MinSegmentSeconds = 7f
private const val MaxSegmentSeconds = 13f
private const val ReachFraction = 0.85f

/**
 * Ancla que deriva entre destinos aleatorios dentro de un disco de radio [maxDriftDp].
 * Cada tramo usa easing coseno, así la velocidad es cero en cada punto de paso.
 * El desplazamiento actual (en dp) se lee en [offsetXDp] / [offsetYDp].
 */
internal class BlurWanderDrift(
    maxDriftDp: Float,
    private val random: Random = Random.Default,
) {
    var maxDriftDp: Float = maxDriftDp.coerceAtLeast(0f)

    var offsetXDp by mutableFloatStateOf(0f)
        private set
    var offsetYDp by mutableFloatStateOf(0f)
        private set

    private var fromX = 0f
    private var fromY = 0f
    private var toX = 0f
    private var toY = 0f
    private var elapsed = 0f
    private var duration = 0f

    private fun pickNextTarget() {
        fromX = offsetXDp
        fromY = offsetYDp
        val radius = maxDriftDp * sqrt(random.nextFloat()) // uniforme en el disco
        val angle = random.nextFloat() * 2f * PI.toFloat()
        toX = radius * cos(angle)
        toY = radius * sin(angle)
        duration = MinSegmentSeconds + random.nextFloat() * (MaxSegmentSeconds - MinSegmentSeconds)
        elapsed = 0f
    }

    fun advance(deltaNanos: Long) {
        if (maxDriftDp <= 0f) {
            offsetXDp = 0f
            offsetYDp = 0f
            return
        }
        if (duration <= 0f) pickNextTarget()
        elapsed += deltaNanos / 1_000_000_000f
        if (elapsed >= duration) {
            offsetXDp = toX
            offsetYDp = toY
            pickNextTarget()
            return
        }
        val t = elapsed / duration
        val eased = (1f - cos(PI.toFloat() * t)) / 2f
        offsetXDp = fromX + (toX - fromX) * eased
        offsetYDp = fromY + (toY - fromY) * eased
    }
}

/** Deriva máxima: ≈85 % de la semidiagonal del área. */
internal fun movingBlurWanderMaxDriftDp(width: Dp, height: Dp): Float =
    ReachFraction * 0.5f * hypot(width.value, height.value)

/**
 * Tamaño del fondo para cubrir siempre el área aunque derive hasta [maxDriftDp].
 * La escala por eje queda entre [restScale] y [driftScale], más el margen de seguridad.
 */
internal fun blurBackdropFootprint(
    width: Dp,
    height: Dp,
    restScale: Float,
    driftScale: Float,
    maxDriftDp: Float,
): DpSize {
    fun axis(size: Dp): Dp {
        val needed = if (size.value > 0f) 1f + 2f * maxDriftDp / size.value else restScale
        val scale = needed.coerceIn(restScale, maxOf(restScale, driftScale))
        return size * scale * BlurBackdropCoverSafety
    }
    return DpSize(axis(width), axis(height))
}

/**
 * Recuerda un [BlurWanderDrift]. Avanza con el reloj de frames solo mientras [active].
 * Cambiar [resetKey] crea uno nuevo, en reposo (0, 0).
 */
@Composable
internal fun rememberBlurWanderDrift(
    active: Boolean,
    maxDriftDp: Float,
    resetKey: Any? = null,
): BlurWanderDrift {
    val drift = remember(resetKey) { BlurWanderDrift(maxDriftDp) }
    drift.maxDriftDp = maxDriftDp.coerceAtLeast(0f)
    LaunchedEffect(active, drift) {
        if (!active) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            drift.advance(now - last)
            last = now
        }
    }
    return drift
}
