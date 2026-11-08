/**
 * vivimusic Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.music.vivi.localmedia

import android.content.ContentUris
import android.content.Context
import android.media.MediaMetadataRetriever
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
    val album: String?,
    val year: Int,
    val trackNumber: Int,
    val durationMs: Long,
    val albumId: Long,
    val format: String,
    /** Artista del álbum (sin "feat."). Se usa para agrupar y ordenar la lista. */
    val albumArtist: String? = null,
    /** Artista bajo el que se agrupa la canción en la lista (por defecto, el suyo). */
    val groupArtist: String? = artist,
    /** Fecha de lanzamiento del álbum como aaaammdd (si solo hay año: aaaa0000). Sirve para ordenar. */
    val releaseDate: Int = 0,
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
    private val releasePrefs by lazy {
        context.getSharedPreferences("local_music_release_dates", Context.MODE_PRIVATE)
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
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.YEAR,
            MediaStore.Audio.Media.TRACK,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DISPLAY_NAME,
            *(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) arrayOf(MediaStore.Audio.Media.ALBUM_ARTIST) else emptyArray()),
        )
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} > 0"
        val tracks = mutableListOf<LocalTrack>()
        context.contentResolver.query(collection, projection, selection, null, null)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val yearCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.YEAR)
            val trackCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
            val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            val albumArtistCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM_ARTIST)
            } else {
                -1
            }
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
                    album = cursor.getString(albumCol)?.trim()
                        ?.takeIf { it.isNotEmpty() && it != MediaStore.UNKNOWN_STRING },
                    year = cursor.getInt(yearCol),
                    trackNumber = cursor.getInt(trackCol),
                    durationMs = cursor.getLong(durationCol),
                    albumId = cursor.getLong(albumIdCol),
                    format = displayName.substringAfterLast('.', "").uppercase(Locale.ROOT),
                    albumArtist = if (albumArtistCol >= 0) {
                        cursor.getString(albumArtistCol)?.trim()
                            ?.takeIf { it.isNotEmpty() && it != MediaStore.UNKNOWN_STRING }
                    } else {
                        null
                    },
                )
            }
        }
        val grouped = withReleaseDates(assignGroupArtists(tracks))

        // Por artista (A-Z); dentro de cada artista, álbumes del más reciente al más antiguo
        // (por fecha de lanzamiento completa si el archivo la trae; si empatan, la deluxe va antes);
        // dentro de cada álbum, en el orden del disco (número de pista).
        grouped.sortedWith(
            compareBy<LocalTrack> { it.groupArtist == null }
                .thenBy { it.groupArtist.orEmpty().lowercase(Locale.ROOT) }
                .thenByDescending { it.releaseDate }
                .thenBy { baseAlbumName(it.album) }
                .thenByDescending { isEdition(it.album) }
                .thenBy { it.album.orEmpty().lowercase(Locale.ROOT) }
                .thenBy { it.trackNumber }
                .thenBy { it.title.lowercase(Locale.ROOT) },
        )
    }

    private fun albumKey(track: LocalTrack) =
        track.groupArtist.orEmpty().lowercase(Locale.ROOT) + "|" + track.album.orEmpty().lowercase(Locale.ROOT)

    /**
     * Fecha de lanzamiento de cada álbum. Se lee de la etiqueta DATE del archivo (la base de datos
     * de Android solo guarda el año, y dos álbumes del mismo año quedarían empatados).
     * Si el archivo no trae fecha completa, se usa solo el año.
     */
    private fun withReleaseDates(tracks: List<LocalTrack>): List<LocalTrack> {
        val dates = HashMap<String, Int>()
        for ((key, list) in tracks.groupBy { albumKey(it) }) {
            val fromTag = list.sortedBy { it.trackNumber }.take(2).firstNotNullOfOrNull { readReleaseDate(it) }
            dates[key] = fromTag ?: (list.maxOf { it.year } * 10000)
        }
        return tracks.map { it.copy(releaseDate = dates[albumKey(it)] ?: 0) }
    }

    private fun readReleaseDate(track: LocalTrack): Int? {
        releasePrefs.getInt(track.uri, 0).takeIf { it > 0 }?.let { return it }
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, Uri.parse(track.uri))
            parseReleaseDate(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE))
                ?.also { releasePrefs.edit().putInt(track.uri, it).apply() }
        } catch (e: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    /** "2026-03-06", "20260306", "2026-03-06T00:00:00Z" -> 20260306. Solo el año -> null. */
    private fun parseReleaseDate(raw: String?): Int? {
        val match = Regex("(\\d{4})\\D?(\\d{2})\\D?(\\d{2})").find(raw.orEmpty()) ?: return null
        val (year, month, day) = match.destructured
        if (month.toInt() !in 1..12 || day.toInt() !in 1..31) return null
        return year.toInt() * 10000 + month.toInt() * 100 + day.toInt()
    }

    /** "Album (Deluxe)" -> "album": sirve para dejar juntas la edición normal y la deluxe. */
    private fun baseAlbumName(album: String?): String =
        album.orEmpty().lowercase(Locale.ROOT)
            .replace(Regex("\\(.*?\\)|\\[.*?\\]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    /** True si el álbum trae algo extra entre paréntesis/corchetes, como (Deluxe) o [Expanded]. */
    private fun isEdition(album: String?): Boolean =
        album != null && baseAlbumName(album) != album.lowercase(Locale.ROOT).trim()

    /**
     * Las colaboraciones ("Gracie Abrams Feat. Taylor Swift") se agrupan con el artista principal
     * del mismo álbum, para que aparezcan dentro de su álbum y no en una sección aparte.
     */
    private fun assignGroupArtists(tracks: List<LocalTrack>): List<LocalTrack> {
        val artistsByAlbum = tracks
            .groupBy { it.album.orEmpty().lowercase(Locale.ROOT) }
            .mapValues { entry -> entry.value.mapNotNull { it.artist }.distinct() }
        return tracks.map { track ->
            val artist = track.artist
            val main = track.albumArtist
                ?: artist?.let { full ->
                    artistsByAlbum[track.album.orEmpty().lowercase(Locale.ROOT)].orEmpty()
                        .filter { it.length < full.length && full.startsWith(it, ignoreCase = true) }
                        .filter { !full[it.length].isLetterOrDigit() }
                        .minByOrNull { it.length }
                }
            track.copy(groupArtist = main ?: artist)
        }
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
