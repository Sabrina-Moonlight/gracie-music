/**
 * Metrolist Project (C) 2026 - adaptado para gracie-music
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.music.vivi.ui.component.lyrics.words

/**
 * Estilos nuevos de letras por palabra (motor de Metrolist).
 * Se guarda en DataStore con [name]. [fromString] acepta tambien los nombres
 * de los estilos viejos para migrarlos sin romper nada.
 */
enum class LyricsAnimationStyle {
    APPLE,
    BRACCATO,
    EXPRESSIVE,
    SIMPLE,
    NONE,
    ;

    companion object {
        val Default = APPLE

        fun fromString(value: String?): LyricsAnimationStyle {
            if (value == null) return Default
            return when (value.uppercase()) {
                "APPLE", "APPLE_V2", "LYRICS_V2" -> APPLE
                "BRACCATO", "METRO_LYRICS" -> BRACCATO
                "EXPRESSIVE", "GLOW", "SLIDE", "VIVIMUSIC_1" -> EXPRESSIVE
                "SIMPLE", "FADE", "KARAOKE" -> SIMPLE
                "NONE" -> NONE
                else -> Default
            }
        }
    }
}
