/**
 * vivimusic Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.music.vivi.local

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import com.music.vivi.constants.LocalMediaIdPrefix
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Una canción del dispositivo. Su id de reproducción es "local:<id de MediaStore>". */
data class LocalSong(
    val mediaStoreId: Long,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
) {
    val id: String get() = LocalMediaIdPrefix + mediaStoreId
    val contentUri: Uri get() = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, mediaStoreId)
}

/**
 * Lista de música del dispositivo.
 * - La lista se guarda en un archivo y se carga al arrancar (warmUp), así la pantalla sale llena al instante.
 * - Se vuelve a escanear MediaStore con refresh().
 * - Las portadas se sacan bajo demanda (una vez) y se guardan en caché.
 * - No va al historial ni a la biblioteca: no se guarda nada en la base de datos.
 */
@Singleton
class LocalMusicRepository
@Inject
constructor(
    @ApplicationContext private val context: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val scanMutex = Mutex()
    private val artMutex = Mutex()

    private val _songs = MutableStateFlow<List<LocalSong>>(emptyList())
    val songs: StateFlow<List<LocalSong>> = _songs.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    @Volatile private var warmed = false

    private val listFile get() = File(context.filesDir, "local_music.json")
    private val artDir get() = File(context.cacheDir, "local_art").also { it.mkdirs() }

    /** Carga la lista guardada (rápido, sin permiso). Llamar desde App.onCreate. */
    fun warmUp() {
        if (warmed) return
        warmed = true
        scope.launch {
            val saved = loadSaved()
            if (_songs.value.isEmpty() && saved.isNotEmpty()) _songs.value = saved
        }
    }

    /** Vuelve a leer MediaStore. Necesita el permiso de audio ya concedido. */
    fun refresh() {
        scope.launch {
            scanMutex.withLock {
                _isScanning.value = true
                try {
                    val scanned = scan()
                    _songs.value = scanned
                    save(scanned)
                } catch (e: SecurityException) {
                    Timber.w(e, "LocalMusic: sin permiso")
                } catch (e: Exception) {
                    Timber.e(e, "LocalMusic: error al escanear")
                } finally {
                    _isScanning.value = false
                }
            }
        }
    }

    fun find(id: String): LocalSong? = _songs.value.firstOrNull { it.id == id }

    /** Ruta (uri file://) donde está o estará la portada de esta canción. Siempre la misma. */
    fun artworkUri(song: LocalSong): String = Uri.fromFile(File(artDir, "${song.mediaStoreId}.jpg")).toString()

    /** Genera la portada si no existe. Devuelve true si hay portada. */
    suspend fun ensureArtwork(song: LocalSong): Boolean = withContext(Dispatchers.IO) {
        val file = File(artDir, "${song.mediaStoreId}.jpg")
        if (file.exists() && file.length() > 0) return@withContext true
        artMutex.withLock {
            if (file.exists() && file.length() > 0) return@withLock true
            val bitmap = loadEmbedded(song) ?: loadThumbnail(song) ?: return@withLock false
            runCatching {
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            }.isSuccess
        }
    }

    private fun loadEmbedded(song: LocalSong): Bitmap? = runCatching {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, song.contentUri)
            retriever.embeddedPicture?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
        } finally {
            runCatching { retriever.release() }
        }
    }.getOrNull()

    private fun loadThumbnail(song: LocalSong): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return runCatching { context.contentResolver.loadThumbnail(song.contentUri, Size(800, 800), null) }.getOrNull()
    }

    private fun scan(): List<LocalSong> {
        val result = ArrayList<LocalSong>()
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
        )
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} >= 30000"
        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            null,
            "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC",
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val durCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            while (c.moveToNext()) {
                val artist = c.getString(artistCol)?.takeIf { it.isNotBlank() && it != "<unknown>" } ?: ""
                result += LocalSong(
                    mediaStoreId = c.getLong(idCol),
                    title = c.getString(titleCol)?.takeIf { it.isNotBlank() } ?: "",
                    artist = artist,
                    album = c.getString(albumCol)?.takeIf { it != "<unknown>" } ?: "",
                    durationMs = c.getLong(durCol),
                )
            }
        }
        return result
    }

    private fun save(list: List<LocalSong>) {
        runCatching {
            val arr = JSONArray()
            list.forEach {
                arr.put(
                    JSONObject().put("i", it.mediaStoreId).put("t", it.title)
                        .put("a", it.artist).put("b", it.album).put("d", it.durationMs),
                )
            }
            listFile.writeText(arr.toString())
        }
    }

    private fun loadSaved(): List<LocalSong> = runCatching {
        if (!listFile.exists()) return emptyList()
        val arr = JSONArray(listFile.readText())
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            LocalSong(o.getLong("i"), o.optString("t"), o.optString("a"), o.optString("b"), o.optLong("d"))
        }
    }.getOrDefault(emptyList())
}
