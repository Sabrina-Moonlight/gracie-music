package com.music.vivi.playback

import android.content.Context
import com.music.vivi.models.MediaMetadata
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Guarda en un archivo los datos básicos de la última canción para mostrarlos al abrir la app,
 * antes de que el servicio restaure la cola. Mientras se muestran estos datos "de caché"
 * (isShowingCachedMetadata en PlayerConnection) no se debe buscar ni guardar letra.
 */
object LastMetadataCache {
    private const val FILE_NAME = "last_metadata.json"

    fun load(context: Context): MediaMetadata? = runCatching {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return null
        val json = JSONObject(file.readText())
        val id = json.optString("id").takeIf { it.isNotBlank() } ?: return null
        val artistsJson = json.optJSONArray("artists") ?: JSONArray()
        val artists = (0 until artistsJson.length()).map {
            val a = artistsJson.getJSONObject(it)
            MediaMetadata.Artist(id = a.optString("id").takeIf { s -> s.isNotBlank() }, name = a.optString("name"))
        }
        val albumId = json.optString("albumId")
        MediaMetadata(
            id = id,
            title = json.optString("title"),
            artists = artists,
            duration = json.optInt("duration", -1),
            thumbnailUrl = json.optString("thumbnail").takeIf { it.isNotBlank() },
            album = if (albumId.isNotBlank()) MediaMetadata.Album(albumId, json.optString("albumTitle")) else null,
            explicit = json.optBoolean("explicit", false),
        )
    }.getOrNull()

    fun save(context: Context, metadata: MediaMetadata) {
        runCatching {
            val artists = JSONArray()
            metadata.artists.forEach {
                artists.put(JSONObject().put("id", it.id ?: "").put("name", it.name))
            }
            val json = JSONObject()
                .put("id", metadata.id)
                .put("title", metadata.title)
                .put("artists", artists)
                .put("duration", metadata.duration)
                .put("thumbnail", metadata.thumbnailUrl ?: "")
                .put("albumId", metadata.album?.id ?: "")
                .put("albumTitle", metadata.album?.title ?: "")
                .put("explicit", metadata.explicit)
            File(context.filesDir, FILE_NAME).writeText(json.toString())
        }
    }

    fun clear(context: Context) {
        runCatching { File(context.filesDir, FILE_NAME).delete() }
    }
}
