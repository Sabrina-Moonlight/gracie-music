/**
 * vivimusic Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Linea de letra: usa el motor de estilos por palabra de Metrolist
 * (ver ui/component/lyrics/words/).
 */
package com.music.vivi.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import com.music.vivi.LocalPlayerConnection
import com.music.vivi.constants.LyricsAnimationStyleKey
import com.music.vivi.constants.LyricsRomanizeAsMainKey
import com.music.vivi.lyrics.LyricsEntry
import com.music.vivi.lyrics.WordTimestamp
import com.music.vivi.ui.component.lyrics.words.LyricsAnimationStyle
import com.music.vivi.ui.component.lyrics.words.WordLevelLyrics
import com.music.vivi.ui.screens.settings.LyricsPosition
import com.music.vivi.utils.rememberPreference
import kotlinx.coroutines.flow.MutableStateFlow

/** Posicion del reproductor que avanza suave entre lecturas (se lee cuando el motor la pide). */
private class LyricsPlayerClock(private val player: Player) {
    private var lastPos = player.currentPosition
    private var lastTime = android.os.SystemClock.elapsedRealtime()

    fun now(): Long {
        val t = android.os.SystemClock.elapsedRealtime()
        val pos = player.currentPosition
        val playing = player.isPlaying
        if (pos != lastPos || !playing) {
            lastPos = pos
            lastTime = t
        }
        return lastPos + if (playing) t - lastTime else 0L
    }
}

/** Parte "lo-lo-love" en "lo-", "lo-", "love" (sin espacio entre ellas) repartiendo el tiempo. */
private fun splitHyphenWords(words: List<WordTimestamp>): List<WordTimestamp> =
    words.flatMap { word ->
        val text = word.text
        val firstHyphen = text.indexOf('-')
        if (text.length < 2 || firstHyphen == -1 || firstHyphen == text.lastIndex) return@flatMap listOf(word)
        val segments = mutableListOf<String>()
        var start = 0
        for (i in text.indices) {
            if (text[i] == '-') {
                segments.add(text.substring(start, i + 1))
                start = i + 1
            }
        }
        if (start < text.length) segments.add(text.substring(start))
        if (segments.size < 2) return@flatMap listOf(word)
        val segmentDuration = (word.endTime - word.startTime) / segments.size
        segments.mapIndexed { index, segment ->
            word.copy(
                text = segment,
                startTime = word.startTime + index * segmentDuration,
                endTime = word.startTime + (index + 1) * segmentDuration,
                hasTrailingSpace = if (index == segments.lastIndex) word.hasTrailingSpace else false
            )
        }
    }

/**
 * La voz de fondo llega como "(palabra" ... "palabra)". El texto que se muestra ya no lleva los
 * paréntesis, así que se quitan también de las palabras con tiempo; si no, la primera y la última
 * no coinciden con el texto y nunca se iluminan.
 */
private fun stripBackgroundParens(words: List<WordTimestamp>): List<WordTimestamp> =
    words.mapIndexedNotNull { i, w ->
        var t = w.text
        if (i == 0) t = t.removePrefix("(")
        if (i == words.lastIndex) t = t.removeSuffix(")")
        when {
            t.isEmpty() -> null
            t == w.text -> w
            else -> w.copy(text = t)
        }
    }

@Composable
fun MetroLyricsLine(
    entry: LyricsEntry,
    nextEntryTime: Long?,
    effectivePlaybackPosition: Long,
    lyricsOffset: Long = 0L,
    isSynced: Boolean,
    isActive: Boolean,
    distanceFromCurrent: Int,
    lyricsTextPosition: LyricsPosition,
    textColor: Color,
    showRomanized: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    isSelected: Boolean,
    isSelectionModeActive: Boolean,
    isAutoScrollActive: Boolean,
    expressiveAccent: Color,
    bgVisible: Boolean = true,
    bgInstantCollapse: Boolean = false,
    isPlusLighter: Boolean = false,
    modifier: Modifier = Modifier
) {
    val (romanizeAsMain) = rememberPreference(LyricsRomanizeAsMainKey, false)
    val animationStyleName by rememberPreference(LyricsAnimationStyleKey, LyricsAnimationStyle.Default.name)
    val animationStyle = remember(animationStyleName) { LyricsAnimationStyle.fromString(animationStyleName) }
    val textMeasurer = rememberTextMeasurer()
    val playerConnection = LocalPlayerConnection.current
    val isPlaying by (playerConnection?.isPlaying ?: remember { MutableStateFlow(true) }).collectAsState()
    val latestPosition by rememberUpdatedState(effectivePlaybackPosition)
    val clock = remember(playerConnection) { playerConnection?.let { LyricsPlayerClock(it.player) } }
    // Se lee latestPosition (estado de Compose) para que Apple/Simple recalculen la palabra activa
    // en cada cuadro: sin eso, el reloj directo del reproductor no avisa de cambios y la sílaba
    // activa nunca se actualizaba. El reloj sigue dando la posición suave.
    val positionProvider: () -> Long = {
        val stateBased = latestPosition
        clock?.now()?.plus(lyricsOffset) ?: stateBased
    }
    
    val romanizedTextState by entry.romanizedTextFlow.collectAsState()
    val isRomanizedAvailable = romanizedTextState != null
    
    val mainTextRaw = if (showRomanized && romanizeAsMain && isRomanizedAvailable) romanizedTextState else entry.text
    val subTextRaw = if (showRomanized && romanizeAsMain && isRomanizedAvailable) entry.text else if (showRomanized) romanizedTextState else null
    
    val mainText = if (entry.isBackground) mainTextRaw?.removePrefix("(")?.removeSuffix(")") ?: "" else mainTextRaw ?: ""
    val subText = if (entry.isBackground) subTextRaw?.removePrefix("(")?.removeSuffix(")") else subTextRaw

    // Plus lighter: las líneas apagadas son más tenues porque la mezcla las aclara.
    val unlitAlpha = if (isPlusLighter) 0.2f else 0.3f
    val focusedAlpha = if (entry.isBackground) 0.5f else unlitAlpha
    val activeAlpha = 1f
    
    val targetAlpha = when {
        !isSynced || (isSelectionModeActive && isSelected) -> 1f
        entry.isBackground || isActive -> activeAlpha
        isAutoScrollActive && distanceFromCurrent >= 0 -> {
            when (distanceFromCurrent) {
                0 -> focusedAlpha
                1, 2 -> 0.2f
                3 -> 0.15f
                4 -> 0.1f
                else -> 0.08f
            }
        }
        else -> 0.2f
    }

    val animatedAlpha by animateFloatAsState(
        targetValue = targetAlpha,
        animationSpec = tween(durationMillis = 300),
        label = "lineAlpha"
    )

    val lyricsTextSize = 36f
    val lyricsLineSpacing = 1.3f

    val itemModifier = modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(16.dp))
        .combinedClickable(
            enabled = true,
            onClick = onClick,
            onLongClick = onLongClick
        )
        .background(
            if (isSelected && isSelectionModeActive)
                MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
            else Color.Transparent
        )
        .padding(
            start = 24.dp,
            end = 24.dp,
            top = if (entry.isBackground) 0.dp else (8 * lyricsLineSpacing).dp,
            bottom = if (entry.isBackground) 2.dp else (8 * lyricsLineSpacing).dp
        )

    val agentAlignment = when {
        entry.isBackground -> Alignment.CenterHorizontally
        entry.agent == "v1" -> Alignment.Start
        entry.agent == "v2" -> Alignment.End
        entry.agent == "v1000" -> Alignment.CenterHorizontally
        else -> when (lyricsTextPosition) {
            LyricsPosition.LEFT -> Alignment.Start
            LyricsPosition.CENTER -> Alignment.CenterHorizontally
            LyricsPosition.RIGHT -> Alignment.End
        }
    }

    val agentTextAlign = when {
        entry.isBackground -> TextAlign.Center
        entry.agent == "v1" -> TextAlign.Left
        entry.agent == "v2" -> TextAlign.Right
        entry.agent == "v1000" -> TextAlign.Center
        else -> when (lyricsTextPosition) {
            LyricsPosition.LEFT -> TextAlign.Left
            LyricsPosition.CENTER -> TextAlign.Center
            LyricsPosition.RIGHT -> TextAlign.Right
        }
    }

    val lyricStyle = TextStyle(
        fontSize = if (entry.isBackground) (lyricsTextSize * 0.7f).sp else lyricsTextSize.sp,
        fontWeight = FontWeight.Bold,
        fontStyle = if (entry.isBackground) FontStyle.Italic else FontStyle.Normal,
        lineHeight = if (entry.isBackground) (lyricsTextSize * 0.7f * lyricsLineSpacing).sp else (lyricsTextSize * lyricsLineSpacing).sp,
        letterSpacing = (-0.5).sp,
        textAlign = agentTextAlign,
        platformStyle = PlatformTextStyle(includeFontPadding = false),
        lineHeightStyle = LineHeightStyle(
            alignment = LineHeightStyle.Alignment.Center,
            trim = LineHeightStyle.Trim.Both
        )
    )

    @Composable
    fun LyricContent() {
        Column(
            modifier = itemModifier,
            horizontalAlignment = agentAlignment
        ) {
            val wordList = entry.words
            val baseWords: List<WordTimestamp> = if (wordList != null && wordList.isNotEmpty() && (!showRomanized || !romanizeAsMain || !isRomanizedAvailable || mainText == entry.text)) {
                wordList.mapIndexed { idx, word ->
                    WordTimestamp(
                        text = word.text,
                        startTime = word.startTime,
                        endTime = word.endTime,
                        hasTrailingSpace = word.hasTrailingSpace,
                        isBackground = entry.isBackground
                    )
                }
            } else {
                remember(mainText, entry.time) {
                    val words = mainText.split(Regex("\\s+")).filter { it.isNotBlank() }
                    val wordDurationSec = 0.18
                    val wordStaggerSec = 0.03
                    val startTimeSec = entry.time / 1000.0
                    words.mapIndexed { idx, wordText ->
                        WordTimestamp(
                            text = wordText,
                            startTime = startTimeSec + (idx * wordStaggerSec),
                            endTime = startTimeSec + (idx * wordStaggerSec) + wordDurationSec,
                            hasTrailingSpace = idx < words.size - 1,
                            isBackground = entry.isBackground
                        )
                    }
                }
            }
            // Palabras con guion ("lo-lo-love") se parten en silabas para el rebote de Expressive.
            val effectiveWords = remember(baseWords) {
                splitHyphenWords(if (entry.isBackground) stripBackgroundParens(baseWords) else baseWords)
            }
            val lineEndMs = remember(effectiveWords) {
                effectiveWords.maxOfOrNull { (it.endTime * 1000).toLong() }
            }

            val baseLineColor = expressiveAccent.copy(alpha = if (entry.isBackground) focusedAlpha else animatedAlpha)
            
            val lineColorValue = if (isActive) expressiveAccent.copy(alpha = 1f) else baseLineColor
            if (isSynced && animationStyle != LyricsAnimationStyle.NONE && effectiveWords.isNotEmpty() &&
                (isActive || distanceFromCurrent <= 3) && mainText.isNotEmpty()
            ) {
                WordLevelLyrics(
                    mainText = mainText,
                    words = effectiveWords,
                    isActiveLine = isActive,
                    positionProvider = positionProvider,
                    adjustedEndTimeMs = lineEndMs,
                    lyricStyle = lyricStyle,
                    lineColor = { lineColorValue },
                    expressiveAccent = expressiveAccent,
                    isBackground = entry.isBackground,
                    focusedAlpha = { focusedAlpha },
                    alignment = agentTextAlign,
                    textMeasurer = textMeasurer,
                    isPlaying = isPlaying,
                    animationStyle = animationStyle
                )
            } else {
                Text(
                    text = mainText,
                    style = lyricStyle.copy(color = if (isActive) expressiveAccent else baseLineColor),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            if (subText != null) {
                Text(
                    text = subText,
                    fontSize = 18.sp,
                    color = baseLineColor.copy(alpha = 0.6f),
                    textAlign = agentTextAlign,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 2.dp).fillMaxWidth(),
                    lineHeight = (18 * lyricsLineSpacing.coerceAtMost(1.3f)).sp
                )
            }

        }
    }

    if (entry.isBackground) {
        AnimatedVisibility(
            visible = bgVisible,
            enter = fadeIn(tween(durationMillis = 250, delayMillis = 100)),
            exit = if (bgInstantCollapse) ExitTransition.None else fadeOut(tween(250))
        ) {
            LyricContent()
        }
    } else {
        LyricContent()
    }
}
