package com.music.vivi.lyrics

import android.content.Context
import com.music.vivi.db.entities.LyricsEntity
import org.json.JSONObject
import java.io.File

/**
 * Guarda en un archivo la letra de la última canción para que, al abrir la app,
 * salga al instante sin círculo de carga. Solo devuelve algo si el id coincide.
 * Nunca guarda "letra no encontrada" ni textos vacíos.
 */
object LastLyricsCache {
    private const val FILE_NAME = "last_lyrics.json"

    fun lyricsFor(context: Context, id: String?): LyricsEntity? {
        if (id == null) return null
        return runCatching {
            val file = File(context.filesDir, FILE_NAME)
            if (!file.exists()) return null
            val json = JSONObject(file.readText())
            if (json.optString("id") != id) return null
            val lyrics = json.optString("lyrics")
            if (lyrics.isBlank() || lyrics == LyricsEntity.LYRICS_NOT_FOUND) return null
            LyricsEntity(id = id, lyrics = lyrics, provider = json.optString("provider", "Unknown"))
        }.getOrNull()
    }

    fun thumbnailFor(context: Context, id: String?): String? {
        if (id == null) return null
        return runCatching {
            val file = File(context.filesDir, FILE_NAME)
            if (!file.exists()) return null
            val json = JSONObject(file.readText())
            if (json.optString("id") != id) return null
            json.optString("thumbnail").takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    fun save(context: Context, id: String, lyrics: String, provider: String, thumbnailUrl: String?) {
        if (lyrics.isBlank() || lyrics == LyricsEntity.LYRICS_NOT_FOUND) return
        runCatching {
            val json = JSONObject()
                .put("id", id)
                .put("lyrics", lyrics)
                .put("provider", provider)
                .put("thumbnail", thumbnailUrl ?: "")
            File(context.filesDir, FILE_NAME).writeText(json.toString())
        }
    }

    fun clear(context: Context) {
        runCatching { File(context.filesDir, FILE_NAME).delete() }
    }
}
