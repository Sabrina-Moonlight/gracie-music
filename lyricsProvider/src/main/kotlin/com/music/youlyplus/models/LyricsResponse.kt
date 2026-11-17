package com.music.youlyplus.models

import kotlinx.serialization.Serializable

/** Respuesta del servidor LyricsPlus (/v2/lyrics/get). Solo los campos que usamos. */
@Serializable
data class LyricsResponse(
    val syncedLyrics: String? = null,
    val plainLyrics: String? = null,
    val lyrics: List<LyricLine>? = null,
)

/** Una línea de la letra; si trae [syllabus], cada sílaba/palabra tiene su propio tiempo. */
@Serializable
data class LyricLine(
    val text: String? = null,
    val time: Long? = null,
    val duration: Long? = null,
    val syllabus: List<Syllable>? = null,
)

@Serializable
data class Syllable(
    val text: String? = null,
    val time: Long? = null,
    val duration: Long? = null,
    val isBackground: Boolean? = null,
)
