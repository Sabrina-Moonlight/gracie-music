package com.music.vivi.constants

import com.music.vivi.db.entities.Song

/** Adelanto base de las letras (ms). Se suma al valor guardado por canción. */
const val DefaultLyricsOffsetMs = 100

/**
 * Desfase real de la letra: lo guardado en la canción (diferencia) + la base.
 * Usar SIEMPRE esta función al leer; así la pantalla y la sincronía coinciden.
 */
fun Song?.effectiveLyricsOffsetMs(): Int = (this?.song?.lyricsOffset ?: 0) + DefaultLyricsOffsetMs
