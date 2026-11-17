package com.music.vivi.constants

/** Cuántas canciones siguientes de la cola se precargan (letras). Rango 1..10. */
const val DefaultLyricsPreloadCount = 3
const val MaxLyricsPreloadCount = 10

/** Prefijo antiguo de las canciones locales. Ahora el id es la dirección content:// o file:// del archivo. */
const val LocalMediaIdPrefix = "local:"

fun isLocalMediaId(id: String?): Boolean =
    id != null &&
        (id.startsWith(LocalMediaIdPrefix) || id.startsWith("content://", ignoreCase = true) || id.startsWith("file://", ignoreCase = true))
