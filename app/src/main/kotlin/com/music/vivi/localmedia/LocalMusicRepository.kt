/**
 * vivimusic Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.music.vivi.localmedia

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.music.innertube.YouTube
import com.music.innertube.models.SongItem
import com.music.vivi.models.MediaMetadata
import com.music.vivi.ui.utils.resize
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/** Un id de canción local es la dirección (content:// o file://) del archivo en el teléfono. */
fun String.isLocalMediaId(): Boolean =
    startsWith("content://", ignoreCase = true) || startsWith("file://", ignoreCase = true)

fun Uri.isLocalMediaUri(): Boolean =
    when (scheme?.lowercase(Locale.US)) {
        "content", "file" -> true
        else -> false
    }

data class LocalTrack(
    /** Dirección del archivo. Se usa también como id de la canción en el reproductor. */
    val uri: String,
    val title: String,
    val artist: String?,
    val durationMs: Long,
    val albumId: Long,
    val format: String,
) {
    val isLossless: Boolean
        get() = format in LosslessFormats

    /** Carátula guardada dentro del teléfono (se usa solo si no hay una en línea). */
    val deviceArtworkUri: String
        get() = "content://media/external/audio/albumart/$albumId"

    fun toMediaMetadata(thumbnailUrl: String?) = MediaMetadata(
        id = uri,
        title = title,
        artists = listOfNotNull(artist?.let { MediaMetadata.Artist(id = null, name = it) }),
        duration = (durationMs / 1000L).toInt(),
        thumbnailUrl = thumbnailUrl ?: deviceArtworkUri,
    )

    private companion object {
        val LosslessFormats = setOf("FLAC", "WAV", "AIF", "AIFF", "ALAC", "APE", "WV")
    }
}

@Singleton
class LocalMusicRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val artworkPrefs by lazy {
        context.getSharedPreferences("local_music_artwork", Context.MODE_PRIVATE)
    }
    private val artworkMemory = HashMap<String, String>()
    private val artworkGate = Semaphore(3)

    /** Lee todas las canciones de música del teléfono (no usa internet). */
    suspend fun loadTracks(): List<LocalTrack> = withContext(Dispatchers.IO) {
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DISPLAY_NAME,
        )
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} > 0"
        val tracks = mutableListOf<LocalTrack>()
        context.contentResolver.query(collection, projection, selection, null, null)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            while (cursor.moveToNext()) {
                val displayName = cursor.getString(nameCol).orEmpty()
                val artist = cursor.getString(artistCol)
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() && it != MediaStore.UNKNOWN_STRING }
                tracks += LocalTrack(
                    uri = ContentUris.withAppendedId(collection, cursor.getLong(idCol)).toString(),
                    title = cursor.getString(titleCol)?.trim()?.takeIf { it.isNotEmpty() }
                        ?: displayName.substringBeforeLast('.'),
                    artist = artist,
                    durationMs = cursor.getLong(durationCol),
                    albumId = cursor.getLong(albumIdCol),
                    format = displayName.substringAfterLast('.', "").uppercase(Locale.ROOT),
                )
            }
        }
        tracks.sortedBy { it.title.lowercase(Locale.ROOT) }
    }

    /** Carátula ya guardada de un intento anterior (no hace búsquedas). */
    fun cachedArtwork(track: LocalTrack): String? {
        val key = artworkKey(track)
        val value = synchronized(artworkMemory) { artworkMemory[key] } ?: artworkPrefs.getString(key, null)
        return value?.takeIf { it.isNotEmpty() }
    }

    /** Busca la carátula en línea (YouTube Music). Devuelve null si no hay coincidencia segura. */
    suspend fun onlineArtwork(track: LocalTrack): String? {
        val key = artworkKey(track)
        synchronized(artworkMemory) { artworkMemory[key] }?.let { return it.takeIf(String::isNotEmpty) }
        artworkPrefs.getString(key, null)?.let { stored ->
            synchronized(artworkMemory) { artworkMemory[key] = stored }
            return stored.takeIf(String::isNotEmpty)
        }

        return artworkGate.withPermit {
            withContext(Dispatchers.IO) {
                val query = listOfNotNull(track.title, track.artist).joinToString(" ")
                val result = YouTube.search(query, YouTube.SearchFilter.FILTER_SONG).getOrNull()
                    ?: return@withContext null // sin internet o error: no se guarda, se reintenta luego
                val match = result.items.filterIsInstance<SongItem>()
                    .filter { matches(track, it) }
                    .minByOrNull { candidate ->
                        candidate.duration?.let { abs(it - track.durationMs / 1000L) } ?: Long.MAX_VALUE
                    }
                val url = match?.thumbnail?.resize(544, 544).orEmpty()
                synchronized(artworkMemory) { artworkMemory[key] = url }
                artworkPrefs.edit().putString(key, url).apply()
                url.takeIf { it.isNotEmpty() }
            }
        }
    }

    private fun matches(track: LocalTrack, candidate: SongItem): Boolean {
        val wanted = normalize(track.title)
        val found = normalize(candidate.title)
        val titleOk = wanted.isNotEmpty() && found.isNotEmpty() &&
            (wanted == found || (wanted.length >= 3 && found.contains(wanted)) ||
                (found.length >= 3 && wanted.contains(found)))
        if (!titleOk) return false
        val artist = track.artist?.let(::normalize).orEmpty()
        if (artist.isEmpty()) return true
        return candidate.artists.any { name ->
            val other = normalize(name.name)
            other.isNotEmpty() && (artist.contains(other) || other.contains(artist))
        }
    }

    private fun artworkKey(track: LocalTrack) = "${normalize(track.title)}|${normalize(track.artist.orEmpty())}"

    private fun normalize(value: String): String =
        value.lowercase(Locale.ROOT)
            .replace(Regex("\\(.*?\\)|\\[.*?\\]"), " ")
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()
}
