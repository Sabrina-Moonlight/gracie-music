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
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
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
    // v2: las carátulas guardadas antes podían ser la del sencillo; se empieza de cero.
    private val artworkPrefs by lazy {
        runCatching { context.deleteSharedPreferences("local_music_artwork") }
        context.getSharedPreferences("local_music_artwork_v2", Context.MODE_PRIVATE)
    }
    private val releasePrefs by lazy {
        context.getSharedPreferences("local_music_release_dates", Context.MODE_PRIVATE)
    }
    private val artworkMemory = HashMap<String, String>()
    private val artworkGate = Semaphore(3)

    @Volatile
    private var memoryTracks: List<LocalTrack>? = null
    private val tracksFile by lazy { File(context.filesDir, "local_music_tracks.json") }

    /** Lista guardada la última vez que se escaneó (memoria o archivo). No lee el teléfono. */
    suspend fun cachedTracks(): List<LocalTrack>? = withContext(Dispatchers.IO) {
        memoryTracks ?: readTracksFile()?.also { memoryTracks = it }
    }

    private fun saveTracks(list: List<LocalTrack>) {
        memoryTracks = list.takeIf { it.isNotEmpty() }
        runCatching {
            if (list.isEmpty()) {
                tracksFile.delete()
            } else {
                val array = JSONArray()
                list.forEach { t ->
                    array.put(
                        JSONObject().apply {
                            put("uri", t.uri)
                            put("title", t.title)
                            put("artist", t.artist ?: JSONObject.NULL)
                            put("album", t.album ?: JSONObject.NULL)
                            put("year", t.year)
                            put("trackNumber", t.trackNumber)
                            put("durationMs", t.durationMs)
                            put("albumId", t.albumId)
                            put("format", t.format)
                            put("albumArtist", t.albumArtist ?: JSONObject.NULL)
                            put("groupArtist", t.groupArtist ?: JSONObject.NULL)
                            put("releaseDate", t.releaseDate)
                        },
                    )
                }
                val tmp = File(tracksFile.parentFile, tracksFile.name + ".tmp")
                tmp.writeText(array.toString())
                tmp.renameTo(tracksFile)
            }
        }
    }

    private fun readTracksFile(): List<LocalTrack>? = runCatching {
        if (!tracksFile.exists()) return@runCatching null
        val array = JSONArray(tracksFile.readText())
        List(array.length()) { i ->
            val o = array.getJSONObject(i)
            fun str(name: String) = if (o.isNull(name)) null else o.optString(name)
            LocalTrack(
                uri = o.getString("uri"),
                title = o.getString("title"),
                artist = str("artist"),
                album = str("album"),
                year = o.optInt("year"),
                trackNumber = o.optInt("trackNumber"),
                durationMs = o.optLong("durationMs"),
                albumId = o.optLong("albumId"),
                format = o.optString("format"),
                albumArtist = str("albumArtist"),
                groupArtist = str("groupArtist"),
                releaseDate = o.optInt("releaseDate"),
            )
        }.takeIf { it.isNotEmpty() }
    }.getOrNull()

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
        val sorted = grouped.sortedWith(
            compareBy<LocalTrack> { it.groupArtist == null }
                .thenBy { it.groupArtist.orEmpty().lowercase(Locale.ROOT) }
                .thenByDescending { it.releaseDate }
                .thenBy { baseAlbumName(it.album) }
                .thenByDescending { isEdition(it.album) }
                .thenBy { it.album.orEmpty().lowercase(Locale.ROOT) }
                .thenBy { it.trackNumber }
                .thenBy { it.title.lowercase(Locale.ROOT) },
        )
        saveTracks(sorted)
        sorted
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

    private fun stored(key: String): String? {
        synchronized(artworkMemory) { artworkMemory[key] }?.let { return it }
        return artworkPrefs.getString(key, null)?.also { synchronized(artworkMemory) { artworkMemory[key] = it } }
    }

    private fun store(key: String, url: String) {
        synchronized(artworkMemory) { artworkMemory[key] = url }
        artworkPrefs.edit().putString(key, url).apply()
    }

    /** Carátula ya guardada (de la canción o de su álbum). No hace búsquedas. */
    fun cachedArtwork(track: LocalTrack): String? {
        stored(artworkKey(track))?.takeIf { it.isNotEmpty() }?.let { return it }
        return albumArtworkKey(track)?.let { stored(it) }?.takeIf { it.isNotEmpty() }
    }

    /**
     * Busca la carátula del ÁLBUM de la canción en línea (YouTube Music). Si la canción es un
     * sencillo que también está en un álbum, se elige la versión que pertenece a ese álbum,
     * no la del sencillo. Devuelve null si no hay coincidencia segura (entonces se usa la del archivo).
     */
    suspend fun onlineArtwork(track: LocalTrack): String? {
        cachedArtwork(track)?.let { return it }
        if (stored(artworkKey(track)) != null) return null // ya se buscó y no hubo coincidencia

        return artworkGate.withPermit {
            // Mientras esperaba turno, otra canción del mismo álbum pudo encontrar la carátula.
            cachedArtwork(track)?.let { return@withPermit it }
            withContext(Dispatchers.IO) {
                val base = listOfNotNull(track.title, track.artist).joinToString(" ")
                val queries = if (track.album != null) listOf("$base ${track.album}", base) else listOf(base)
                var url = ""
                for (query in queries) {
                    val result = YouTube.search(query, YouTube.SearchFilter.FILTER_SONG).getOrNull()
                        ?: return@withContext null // sin internet o error: no se guarda, se reintenta luego
                    val candidates = result.items.filterIsInstance<SongItem>().filter { matches(track, it) }
                        .let { list -> if (track.album != null) list.filter { albumMatches(track, it) } else list }
                    val match = candidates.minByOrNull { candidate ->
                        candidate.duration?.let { abs(it - track.durationMs / 1000L) } ?: Long.MAX_VALUE
                    }
                    url = match?.thumbnail?.resize(544, 544).orEmpty()
                    if (url.isNotEmpty()) break
                }
                store(artworkKey(track), url)
                if (url.isNotEmpty()) albumArtworkKey(track)?.let { store(it, url) }
                url.takeIf { it.isNotEmpty() }
            }
        }
    }

    private fun albumMatches(track: LocalTrack, candidate: SongItem): Boolean {
        val wanted = normalize(track.album.orEmpty())
        val found = normalize(candidate.album?.name.orEmpty())
        return wanted.isNotEmpty() && wanted == found
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

    private fun artworkKey(track: LocalTrack) =
        "track|${normalize(track.title)}|${normalize(track.artist.orEmpty())}|${plain(track.album.orEmpty())}"

    /** Una carátula por álbum: todas las canciones del mismo álbum comparten la misma. */
    private fun albumArtworkKey(track: LocalTrack): String? {
        val album = plain(track.album.orEmpty()).takeIf { it.isNotEmpty() } ?: return null
        return "album|$album|${plain(track.groupArtist.orEmpty())}"
    }

    private fun plain(value: String): String =
        value.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    private fun normalize(value: String): String =
        value.lowercase(Locale.ROOT)
            .replace(Regex("\\(.*?\\)|\\[.*?\\]"), " ")
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()
}
