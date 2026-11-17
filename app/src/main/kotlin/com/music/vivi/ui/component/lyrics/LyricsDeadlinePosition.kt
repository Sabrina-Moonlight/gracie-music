/*
 * Metrolist Project (C) 2026 - adaptado para gracie-music
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.music.vivi.ui.component.lyrics

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import com.music.vivi.ui.component.lyrics.words.LocalVisualWorkActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.ceil
import kotlin.time.TimeSource

internal data class LyricsDeadlinePosition(
    val positionMs: Long,
    val seekGeneration: Int = 0,
)

internal class LyricsPlaybackAnchor(
    var positionMs: Long,
    var isPlaying: Boolean,
    var playbackSpeed: Float,
    timeSource: TimeSource,
) {
    var mark = timeSource.markNow()
    var needsReseed = false
}

internal fun nextLyricsDeadlineIndex(
    deadlines: List<Long>,
    positionMs: Long,
): Int {
    val index = deadlines.binarySearch(positionMs)
    return if (index >= 0) index + 1 else -index - 1
}

internal fun nextLyricsDeadlineDelay(
    deadlines: List<Long>,
    positionMs: Long,
    playbackSpeed: Float,
): Long? {
    if (!playbackSpeed.isFinite() || playbackSpeed <= 0f) return null
    val next = deadlines.getOrNull(nextLyricsDeadlineIndex(deadlines, positionMs)) ?: return null
    return ceil((next.toDouble() - positionMs.toDouble()) / playbackSpeed).toLong().coerceAtLeast(1L)
}

internal fun deadlineLyricsPositions(
    positionFlow: StateFlow<Long>,
    deadlines: List<Long>,
    initial: LyricsDeadlinePosition,
    isPlaying: Boolean,
    isCasting: Boolean,
    playbackSpeed: Float,
    timingOffsetMs: Int,
    visibility: StateFlow<Boolean>,
    timeSource: TimeSource = TimeSource.Monotonic,
    anchor: LyricsPlaybackAnchor = LyricsPlaybackAnchor(initial.positionMs, isPlaying, playbackSpeed, timeSource),
    positionReader: (() -> Long)? = null,
): Flow<LyricsDeadlinePosition> =
    channelFlow {
        var published = initial
        visibility.collectLatest { shown ->
            if (!shown) {
                anchor.needsReseed = true
                positionFlow.collect { rawSample ->
                    val sample = rawSample.coerceAtLeast(0L)
                    anchor.positionMs = sample
                    anchor.mark = timeSource.markNow()
                    published = LyricsDeadlinePosition(sample, published.seekGeneration)
                    send(published)
                }
                return@collectLatest
            }
            var firstSample = true
            positionFlow.collectLatest sample@{ rawSample ->
                val sample = (if (isCasting) rawSample else positionReader?.invoke() ?: rawSample).coerceAtLeast(0L)
                val mark = timeSource.markNow()
                val isSeek =
                    anchor.needsReseed || sample < anchor.positionMs - 80L ||
                        isPlaybackPositionDiscontinuity(
                            anchor.positionMs,
                            sample,
                            if (anchor.isPlaying) anchor.mark.elapsedNow().inWholeMilliseconds else 0L,
                            anchor.playbackSpeed,
                        )
                anchor.needsReseed = false
                anchor.positionMs = sample
                anchor.mark = mark
                anchor.isPlaying = isPlaying
                anchor.playbackSpeed = playbackSpeed
                val effectiveSample =
                    if (!isSeek && !firstSample && isPlaying && published.positionMs - sample in 1L..250L) {
                        published.positionMs
                    } else {
                        sample
                    }
                val generation = published.seekGeneration + if (isSeek) 1 else 0
                if (firstSample || isSeek || !isPlaying || isCasting ||
                    nextLyricsDeadlineIndex(deadlines, effectiveSample + timingOffsetMs) !=
                    nextLyricsDeadlineIndex(deadlines, published.positionMs + timingOffsetMs)
                ) {
                    published = LyricsDeadlinePosition(effectiveSample, generation)
                    send(published)
                }
                firstSample = false
                if (!isPlaying || isCasting || !playbackSpeed.isFinite() || playbackSpeed <= 0f) return@sample

                while (true) {
                    val position =
                        maxOf(
                            effectiveSample,
                            published.positionMs,
                            positionReader?.invoke()
                                ?: (sample + (mark.elapsedNow().inWholeMilliseconds * playbackSpeed.toDouble()).toLong()),
                        )
                    if (nextLyricsDeadlineIndex(deadlines, position + timingOffsetMs) !=
                        nextLyricsDeadlineIndex(deadlines, published.positionMs + timingOffsetMs)
                    ) {
                        published = LyricsDeadlinePosition(position, generation)
                        send(published)
                    }
                    val waitMs = nextLyricsDeadlineDelay(deadlines, position + timingOffsetMs, playbackSpeed) ?: break
                    delay(if (positionReader != null) minOf(waitMs, 16L) else waitMs)
                }
            }
        }
    }

@Composable
internal fun rememberDeadlineLyricsPositionState(
    positionFlow: StateFlow<Long>,
    deadlines: List<Long>,
    isPlaying: Boolean,
    songId: String?,
    timingOffsetMs: Int,
    isCasting: Boolean,
    enabled: Boolean,
    playbackSpeed: Float = 1f,
    positionReader: (() -> Long)? = null,
): State<LyricsDeadlinePosition> {
    val visibility = LocalVisualWorkActive.current
    val position =
        remember(songId, positionFlow, enabled) {
            val initial =
                if (enabled && visibility.value &&
                    !isCasting
                ) {
                    positionReader?.invoke() ?: positionFlow.value
                } else {
                    positionFlow.value
                }
            mutableStateOf(LyricsDeadlinePosition(initial.coerceAtLeast(0L)))
        }
    val anchor =
        remember(songId) { LyricsPlaybackAnchor(positionFlow.value.coerceAtLeast(0L), isPlaying, playbackSpeed, TimeSource.Monotonic) }
    val readPosition by rememberUpdatedState(positionReader)
    val hasLiveReader = positionReader != null
    LaunchedEffect(
        positionFlow,
        deadlines,
        enabled,
        isPlaying,
        songId,
        timingOffsetMs,
        isCasting,
        playbackSpeed,
        visibility,
        hasLiveReader,
    ) {
        if (!enabled) {
            anchor.needsReseed = true
            positionFlow.collect { rawSample ->
                val sample = rawSample.coerceAtLeast(0L)
                anchor.positionMs = sample
                position.value = LyricsDeadlinePosition(sample, position.value.seekGeneration)
            }
            return@LaunchedEffect
        }
        deadlineLyricsPositions(
            positionFlow,
            deadlines,
            position.value,
            isPlaying,
            isCasting,
            playbackSpeed,
            timingOffsetMs,
            visibility,
            anchor = anchor,
            positionReader = if (hasLiveReader) ({ readPosition?.invoke() ?: positionFlow.value }) else null,
        ).collect { position.value = it }
    }
    return position
}
