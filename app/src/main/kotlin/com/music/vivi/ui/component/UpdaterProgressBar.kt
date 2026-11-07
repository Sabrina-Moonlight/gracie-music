/**
 * vivimusic Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */
/*
 * Derivative work based on ImageToolbox's FancySlider (Apache License 2.0)
 * Original Copyright (c) T8RIN (Malik Mukhametzyanov)
 * Modifications for ViviMusic Updater include custom sizing, track logic, and UI integration.
 * Licensed under the Apache License, Version 2.0 (the "License")
 */

package com.music.vivi.ui.component

import androidx.compose.foundation.layout.height
import androidx.compose.runtime.getValue
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope

private fun DrawScope.drawFancyTrackSegment(
    startFraction: Float,
    endFraction: Float,
    color: Color,
    shape: Shape
) {
    if (endFraction <= 0f) return
    val width = size.width * endFraction.coerceIn(0f, 1f)

    val outline = shape.createOutline(
        size = Size(
            width = width,
            height = size.height
        ),
        layoutDirection = layoutDirection,
        density = this
    )

    when (outline) {
        is Outline.Rectangle -> drawRect(color = color, topLeft = outline.rect.topLeft, size = outline.rect.size)
        is Outline.Rounded -> drawPath(path = Path().apply { addRoundRect(outline.roundRect) }, color = color)
        is Outline.Generic -> drawPath(path = outline.path, color = color)
    }
}


