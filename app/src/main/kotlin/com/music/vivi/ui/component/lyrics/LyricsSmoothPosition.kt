/*
 * Metrolist Project (C) 2026 - adaptado para gracie-music
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.music.vivi.ui.component.lyrics

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.LongState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import com.music.vivi.ui.component.lyrics.words.LocalVisualWorkActive
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

@Composable
fun rememberSmoothLyricsPosition(
    currentPosition: Long,
    isPlaying: Boolean,
    songId: String? = null,
    playbackSpeed: Float = 1f,
    maxPosition: Long = Long.MAX_VALUE,
    isCasting: Boolean = false,
): Long =
    rememberSmoothLyricsPositionState(
        currentPosition,
        isPlaying,
        songId,
        playbackSpeed = playbackSpeed,
        maxPosition = maxPosition,
        isCasting = isCasting,
    ).longValue

@Composable
fun rememberSmoothLyricsPosition(
    positionFlow: StateFlow<Long>,
    isPlaying: Boolean,
    songId: String? = null,
    playbackSpeed: Float = 1f,
    maxPosition: Long = Long.MAX_VALUE,
    isCasting: Boolean = false,
): Long =
    rememberSmoothLyricsPositionState(
        positionFlow = positionFlow,
        isPlaying = isPlaying,
        songId = songId,
        playbackSpeed = playbackSpeed,
        maxPosition = maxPosition,
        isCasting = isCasting,
    ).longValue

@Composable
fun rememberSmoothLyricsPositionState(
    positionFlow: StateFlow<Long>,
    isPlaying: Boolean,
    songId: String? = null,
    frameInterval: Duration = 16.milliseconds,
    playbackSpeed: Float = 1f,
    maxPosition: Long = Long.MAX_VALUE,
    isCasting: Boolean = false,
    enabled: Boolean = true,
    positionReader: (() -> Long)? = null,
): LongState {
    val boundedMaxPosition = maxPosition.coerceAtLeast(0L)
    val visibility = LocalVisualWorkActive.current
    val smoothPosition =
        remember(songId, positionFlow, enabled) {
            val initial =
                if (enabled && visibility.value &&
                    !isCasting
                ) {
                    positionReader?.invoke() ?: positionFlow.value
                } else {
                    positionFlow.value
                }
            mutableLongStateOf(initial.coerceIn(0L, boundedMaxPosition))
        }
    val readPosition by rememberUpdatedState(positionReader)
    val hasLiveReader = positionReader != null
    LaunchedEffect(
        positionFlow,
        enabled,
        isPlaying,
        isCasting,
        songId,
        frameInterval,
        boundedMaxPosition,
        playbackSpeed,
        visibility,
        hasLiveReader,
    ) {
        smoothLyricsPositions(
            positionFlow = positionFlow,
            isPlaying = isPlaying,
            playbackSpeed = playbackSpeed,
            maxPosition = boundedMaxPosition,
            isCasting = isCasting,
            enabled = enabled,
            visibility = visibility,
            frameInterval = frameInterval,
            positionReader = if (hasLiveReader) ({ readPosition?.invoke() ?: positionFlow.value }) else null,
            songId = songId,
        ).collect { smoothPosition.longValue = it }
    }
    return smoothPosition
}

internal fun smoothLyricsPositions(
    positionFlow: StateFlow<Long>,
    isPlaying: Boolean,
    playbackSpeed: Float,
    visibility: StateFlow<Boolean>,
    maxPosition: Long = Long.MAX_VALUE,
    isCasting: Boolean = false,
    enabled: Boolean = true,
    frameInterval: Duration = 16.milliseconds,
    positionReader: (() -> Long)? = null,
    songId: String? = null,
    timeSource: TimeSource = TimeSource.Monotonic,
    tickOrigin: TimeMark = playbackAnimationOrigin,
): Flow<Long> =
    channelFlow {
        visibility.collectLatest { shown ->
            if (!enabled || !shown || !isPlaying || isCasting) {
                positionFlow.collect { sample ->
                    val position = if (enabled && shown && !isCasting) positionReader?.invoke() ?: sample else sample
                    send(position.coerceIn(0L, maxPosition))
                }
                return@collectLatest
            }
            val clock = LyricsPositionClock(playbackSpeed, maxPosition, timeSource)
            clock.sample(positionFlow.value, positionReader)
            send(clock.positionMs())
            coroutineScope {
                launch {
                    positionFlow.collect { sample ->
                        clock.sample(sample, positionReader)
                    }
                }
                launch {
                    lyricsPositionTicks(frameInterval, tickOrigin).collect {
                        val position = clock.positionMs(positionReader)
                        send(position)
                    }
                }
            }
        }
    }

internal class LyricsPositionClock(
    private val playbackSpeed: Float,
    private val maxPosition: Long = Long.MAX_VALUE,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    var anchorPositionMs = 0L
        private set
    private var anchorMark = timeSource.markNow()
    private var lastPositionMs = 0L
    private var initialized = false
    val anchorAgeMs: Long get() = anchorMark.elapsedNow().inWholeMilliseconds

    fun sample(
        positionMs: Long,
        positionReader: (() -> Long)? = null,
    ) {
        val bounded = (positionReader?.invoke() ?: positionMs).coerceIn(0L, maxPosition)
        if (!initialized || isPlaybackPositionDiscontinuity(anchorPositionMs, bounded, anchorAgeMs, playbackSpeed)) {
            lastPositionMs = bounded
        }
        anchorPositionMs = bounded
        anchorMark = timeSource.markNow()
        initialized = true
    }

    fun positionMs(positionReader: (() -> Long)? = null): Long {
        val position =
            positionReader?.invoke()?.coerceIn(0L, maxPosition)
                ?: advanceSmoothPlaybackPosition(anchorPositionMs.toDouble(), anchorAgeMs, playbackSpeed, maxPosition).toLong()
        lastPositionMs = maxOf(lastPositionMs, position)
        return lastPositionMs
    }
}

@Composable
fun rememberSmoothLyricsPositionState(
    currentPosition: Long,
    isPlaying: Boolean,
    songId: String? = null,
    frameInterval: Duration = 16.milliseconds,
    playbackSpeed: Float = 1f,
    maxPosition: Long = Long.MAX_VALUE,
    isCasting: Boolean = false,
    enabled: Boolean = true,
): LongState {
    val positionFlow = remember(songId) { MutableStateFlow(currentPosition) }
    positionFlow.value = currentPosition
    LaunchedEffect(currentPosition) {
        positionFlow.value = currentPosition
    }
    return rememberSmoothLyricsPositionState(
        positionFlow = positionFlow,
        isPlaying = isPlaying,
        songId = songId,
        frameInterval = frameInterval,
        playbackSpeed = playbackSpeed,
        maxPosition = maxPosition,
        isCasting = isCasting,
        enabled = enabled,
    )
}

internal fun lyricsPositionTicks(
    interval: Duration,
    origin: TimeMark = playbackAnimationOrigin,
): Flow<Long> =
    flow {
        val intervalMillis = interval.inWholeMilliseconds.coerceAtLeast(1L)
        while (true) {
            delay(playbackAnimationDelayMillis(origin.elapsedNow().inWholeMilliseconds, intervalMillis))
            emit(origin.elapsedNow().inWholeMilliseconds)
        }
    }

internal fun advanceSmoothPlaybackPosition(
    position: Double,
    elapsedMillis: Long,
    playbackSpeed: Float,
    maxPosition: Long,
): Double =
    (position + elapsedMillis.coerceAtLeast(0L) * playbackSpeed.coerceAtLeast(0.02f))
        .coerceIn(0.0, maxPosition.coerceAtLeast(0L).toDouble())

internal fun isPlaybackPositionDiscontinuity(
    previousPositionMs: Long,
    samplePositionMs: Long,
    elapsedSincePreviousSampleMs: Long,
    playbackSpeed: Float,
): Boolean {
    val expectedDeltaMs = elapsedSincePreviousSampleMs.coerceAtLeast(0L).toDouble() * playbackSpeed.coerceAtLeast(0.1f)
    val observedDeltaMs = (samplePositionMs - previousPositionMs).toDouble()
    return samplePositionMs < previousPositionMs || abs(observedDeltaMs - expectedDeltaMs) > 300.0
}

internal val playbackAnimationOrigin: TimeMark = TimeSource.Monotonic.markNow()

internal fun playbackAnimationDelayMillis(
    nowMillis: Long,
    intervalMillis: Long,
): Long = if (intervalMillis > 0L) intervalMillis - nowMillis.mod(intervalMillis) else 0L
