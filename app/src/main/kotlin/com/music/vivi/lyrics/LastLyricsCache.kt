package com.music.vivi.lyrics

import android.content.Context
import com.music.vivi.db.entities.LyricsEntity

/**
 * Guarda la última canción que se vio en el reproductor (letra, proveedor y carátula) para que,
 * al abrir la app, las letras y el fondo aparezcan al instante sin esperar a que cargue todo.
 */
object LastLyricsCache {
    private const val PREFS = "last_lyrics_cache"
    private const val KEY_ID = "id"
    private const val KEY_LYRICS = "lyrics"
    private const val KEY_PROVIDER = "provider"
    private const val KEY_THUMB = "thumb"

    // Copia en memoria para no leer el archivo cada vez.
    @Volatile private var cachedThumb: String? = null
    @Volatile private var thumbLoaded = false

    fun save(context: Context, id: String, lyrics: String, provider: String, thumbnailUrl: String?) {
        if (lyrics.isBlank() || lyrics == LyricsEntity.LYRICS_NOT_FOUND) return
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString(KEY_ID, null) == id &&
            prefs.getString(KEY_LYRICS, null) == lyrics &&
            prefs.getString(KEY_THUMB, null) == thumbnailUrl
        ) return
        prefs.edit()
            .putString(KEY_ID, id)
            .putString(KEY_LYRICS, lyrics)
            .putString(KEY_PROVIDER, provider)
            .putString(KEY_THUMB, thumbnailUrl)
            .apply()
        cachedThumb = thumbnailUrl
        thumbLoaded = true
    }

    /** Letra guardada, solo si es de la canción [id]. */
    fun lyricsFor(context: Context, id: String?): LyricsEntity? {
        if (id == null) return null
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString(KEY_ID, null) != id) return null
        val lyrics = prefs.getString(KEY_LYRICS, null) ?: return null
        return LyricsEntity(id, lyrics, prefs.getString(KEY_PROVIDER, null) ?: "Unknown")
    }

    /** Carátula de la última canción vista, para el fondo mientras carga la canción real. */
    fun thumbnail(context: Context): String? {
        if (!thumbLoaded) {
            cachedThumb = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_THUMB, null)
            thumbLoaded = true
        }
        return cachedThumb
    }
}
