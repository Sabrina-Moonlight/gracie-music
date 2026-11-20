/*
 * Metrolist Project (C) 2026 - adaptado para gracie-music
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.music.vivi.ui.component.lyrics.words

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.runtime.remember
import androidx.compose.ui.text.style.TextAlign
import com.music.vivi.lyrics.WordTimestamp

@Composable
fun WordLevelLyrics(
    mainText: String,
    words: List<WordTimestamp>,
    isActiveLine: Boolean,
    positionProvider: () -> Long,
    adjustedEndTimeMs: Long?,
    lyricStyle: TextStyle,
    lineColor: () -> Color,
    expressiveAccent: Color,
    isBackground: Boolean,
    focusedAlpha: () -> Float,
    alignment: TextAlign,
    textMeasurer: TextMeasurer,
    isPlaying: Boolean = true,
    animationStyle: LyricsAnimationStyle = LyricsAnimationStyle.Default,
    romanizedWords: List<WordTimestamp>? = null,
    romanizedText: String? = null,
    showSyllableRomanization: Boolean = false,
    prewarmMasks: Boolean = isActiveLine,
    useFixedTone: Boolean = true,
) {
    // Después de una raya larga ("Satān—Welcome") la palabra siguiente empieza en la línea de abajo
    val lineText = remember(mainText) { mainText.replace(Regex("—(?=\\p{L})"), "—\n") }
    when (animationStyle) {
        LyricsAnimationStyle.APPLE -> {
            AppleWordAnimation(
                mainText = lineText,
                words = words,
                isActiveLine = isActiveLine,
                positionProvider = positionProvider,
                adjustedEndTimeMs = adjustedEndTimeMs,
                lyricStyle = lyricStyle,
                lineColor = lineColor,
                expressiveAccent = expressiveAccent,
                isBackground = isBackground,
                focusedAlpha = focusedAlpha,
                alignment = alignment,
                textMeasurer = textMeasurer,
                isPlaying = isPlaying,
                romanizedWords = romanizedWords,
                romanizedText = romanizedText,
                showSyllableRomanization = showSyllableRomanization,
                prewarmMasks = prewarmMasks,
            )
        }

        LyricsAnimationStyle.BRACCATO -> {
            BraccatoWordAnimation(
                mainText = lineText,
                words = words,
                isActiveLine = isActiveLine,
                positionProvider = positionProvider,
                adjustedEndTimeMs = adjustedEndTimeMs,
                lyricStyle = lyricStyle,
                lineColor = lineColor,
                expressiveAccent = expressiveAccent,
                isBackground = isBackground,
                focusedAlpha = focusedAlpha,
                alignment = alignment,
                textMeasurer = textMeasurer,
                isPlaying = isPlaying,
                romanizedWords = romanizedWords,
                romanizedText = romanizedText,
                showSyllableRomanization = showSyllableRomanization,
                prewarmMasks = prewarmMasks,
            )
        }

        LyricsAnimationStyle.EXPRESSIVE -> {
            ExpressiveWordAnimation(
                useFixedTone = useFixedTone,
                mainText = lineText,
                words = words,
                isActiveLine = isActiveLine,
                positionProvider = positionProvider,
                adjustedEndTimeMs = adjustedEndTimeMs,
                lyricStyle = lyricStyle,
                lineColor = lineColor,
                expressiveAccent = expressiveAccent,
                focusedAlpha = focusedAlpha,
                alignment = alignment,
                textMeasurer = textMeasurer,
                isPlaying = isPlaying,
                romanizedWords = romanizedWords,
                romanizedText = romanizedText,
                showSyllableRomanization = showSyllableRomanization,
                prewarmMasks = prewarmMasks,
            )
        }

        LyricsAnimationStyle.SIMPLE -> {
            SimpleWordAnimation(
                mainText = lineText,
                words = words,
                isActiveLine = isActiveLine,
                positionProvider = positionProvider,
                lyricStyle = lyricStyle,
                lineColor = lineColor,
                expressiveAccent = expressiveAccent,
                focusedAlpha = focusedAlpha,
                alignment = alignment,
                textMeasurer = textMeasurer,
                isPlaying = isPlaying,
                romanizedWords = romanizedWords,
                romanizedText = romanizedText,
                showSyllableRomanization = showSyllableRomanization,
            )
        }

        LyricsAnimationStyle.NONE -> {}
    }
}
