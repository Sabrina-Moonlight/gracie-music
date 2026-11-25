/*
 * Metrolist Project (C) 2026 - adaptado para gracie-music
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.music.vivi.ui.component.lyrics

internal fun nextLyricsDeadlineIndex(
    deadlines: List<Long>,
    positionMs: Long,
): Int {
    val index = deadlines.binarySearch(positionMs)
    return if (index >= 0) index + 1 else -index - 1
}
