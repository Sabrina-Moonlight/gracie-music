/**
 * Gracie Music — desenfoque progresivo de las letras (adaptado de Metrolist).
 * Desenfoca las líneas cerca del borde de arriba y de abajo y deja nítida la zona del centro.
 * Solo funciona en Android 13+ (en versiones más viejas no hace nada).
 */

package com.music.vivi.ui.component.lyrics

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp

@Composable
fun Modifier.lyricsProgressiveBlur(
    topEndFraction: Float,
    bottomStartFraction: Float,
    maxRadius: Dp,
    enabled: Boolean,
): Modifier {
    if (!enabled || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return this
    return lyricsProgressiveBlurApi33(topEndFraction, bottomStartFraction, maxRadius)
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun Modifier.lyricsProgressiveBlurApi33(
    topEndFraction: Float,
    bottomStartFraction: Float,
    maxRadius: Dp,
): Modifier {
    val density = LocalDensity.current
    val shaderH = remember { runCatching { RuntimeShader(PROGRESSIVE_BLUR_H_SKSL) }.getOrNull() }
    val shaderV = remember { runCatching { RuntimeShader(PROGRESSIVE_BLUR_V_SKSL) }.getOrNull() }
    if (shaderH == null || shaderV == null) return this

    return this.graphicsLayer {
        val w = size.width
        val h = size.height
        if (w > 0f && h > 0f) {
            val radiusPx = with(density) { maxRadius.toPx() }
            for (shader in arrayOf(shaderH, shaderV)) {
                shader.setFloatUniform("blurRadius", radiusPx)
                shader.setFloatUniform("crop", 0f, 0f, w, h)
                shader.setFloatUniform("topStartY", 0f)
                shader.setFloatUniform("topEndY", h * topEndFraction)
                shader.setFloatUniform("bottomStartY", h * bottomStartFraction)
                shader.setFloatUniform("bottomEndY", h)
            }
            val effectH = RenderEffect.createRuntimeShaderEffect(shaderH, "content")
            val effectV = RenderEffect.createRuntimeShaderEffect(shaderV, "content")
            renderEffect = RenderEffect.createChainEffect(effectV, effectH).asComposeRenderEffect()
        }
    }
}

private const val PROGRESSIVE_BLUR_H_SKSL = """
uniform shader content;
uniform float blurRadius;
uniform float4 crop;
uniform float topStartY;
uniform float topEndY;
uniform float bottomStartY;
uniform float bottomEndY;

float getIntensity(float y) {
    float top = 0.0;
    if (topEndY > topStartY) {
        float f = clamp((topEndY - y) / max(topEndY - topStartY, 1.0), 0.0, 1.0);
        top = f * f * (3.0 - 2.0 * f);
    }
    float bottom = 0.0;
    if (bottomEndY > bottomStartY) {
        float f = clamp((y - bottomStartY) / max(bottomEndY - bottomStartY, 1.0), 0.0, 1.0);
        bottom = f * f * (3.0 - 2.0 * f);
    }
    return max(top, bottom);
}

float4 blurH(float2 coord, float radius) {
    if (radius <= 0.001) {
        return content.eval(coord);
    }

    float4 result = content.eval(coord);
    float stepDist = radius / 10.0;

    for (float k = 1.0; k <= 10.0; k += 1.0) {
        float weight = exp(-k * k * 0.03462604);
        float2 offset = float2(k * stepDist, 0.0);
        float2 coord1 = clamp(coord - offset, crop.xy, crop.zw);
        float2 coord2 = clamp(coord + offset, crop.xy, crop.zw);
        result += (content.eval(coord1) + content.eval(coord2)) * weight;
    }

    return result / 9.47198;
}

half4 main(float2 coord) {
    float radius = blurRadius * getIntensity(coord.y);
    return half4(blurH(coord, radius));
}
"""

private const val PROGRESSIVE_BLUR_V_SKSL = """
uniform shader content;
uniform float blurRadius;
uniform float4 crop;
uniform float topStartY;
uniform float topEndY;
uniform float bottomStartY;
uniform float bottomEndY;

float getIntensity(float y) {
    float top = 0.0;
    if (topEndY > topStartY) {
        float f = clamp((topEndY - y) / max(topEndY - topStartY, 1.0), 0.0, 1.0);
        top = f * f * (3.0 - 2.0 * f);
    }
    float bottom = 0.0;
    if (bottomEndY > bottomStartY) {
        float f = clamp((y - bottomStartY) / max(bottomEndY - bottomStartY, 1.0), 0.0, 1.0);
        bottom = f * f * (3.0 - 2.0 * f);
    }
    return max(top, bottom);
}

float4 blurV(float2 coord, float radius) {
    if (radius <= 0.001) {
        return content.eval(coord);
    }

    float4 result = content.eval(coord);
    float stepDist = radius / 10.0;

    for (float k = 1.0; k <= 10.0; k += 1.0) {
        float weight = exp(-k * k * 0.03462604);
        float2 offset = float2(0.0, k * stepDist);
        float2 coord1 = clamp(coord - offset, crop.xy, crop.zw);
        float2 coord2 = clamp(coord + offset, crop.xy, crop.zw);
        result += (content.eval(coord1) + content.eval(coord2)) * weight;
    }

    return result / 9.47198;
}

half4 main(float2 coord) {
    float radius = blurRadius * getIntensity(coord.y);
    return half4(blurV(coord, radius));
}
"""
