package com.music.vivi.constants

/** Cuántas canciones siguientes de la cola se precargan (letras). Rango 1..10. */
const val DefaultLyricsPreloadCount = 3
const val MaxLyricsPreloadCount = 10

/** Las canciones locales llevan este prefijo en su id; no se buscan letras online por id. */
const val LocalMediaIdPrefix = "local:"

fun isLocalMediaId(id: String?): Boolean = id != null && id.startsWith(LocalMediaIdPrefix)
