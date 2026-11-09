/**
 * vivimusic Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.music.vivi.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import com.music.vivi.extensions.toMediaItem
import com.music.vivi.localmedia.LocalMusicRepository
import com.music.vivi.localmedia.LocalTrack
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class LocalMusicViewModel @Inject constructor(
    private val repository: LocalMusicRepository,
) : ViewModel() {
    private val _tracks = MutableStateFlow<List<LocalTrack>>(emptyList())
    val tracks: StateFlow<List<LocalTrack>> = _tracks.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    /** Carátulas en línea ya encontradas: id de la canción -> dirección de la imagen. */
    private val _artwork = MutableStateFlow<Map<String, String>>(emptyMap())
    val artwork: StateFlow<Map<String, String>> = _artwork.asStateFlow()

    /**
     * False hasta que se sabe de verdad qué hay: o se cargó la lista guardada, o terminó un
     * escaneo. Evita mostrar "No music found" en los primeros instantes, cuando la lista
     * todavía está vacía solo porque aún no se ha leído.
     */
    private val _isReady = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    private val requested = HashSet<String>()

    /**
     * Carga la lista guardada en cuanto se crea el ViewModel (no necesita permiso: es un archivo
     * propio de la app), sin esperar a que la pantalla termine de componerse.
     */
    private val cacheJob = viewModelScope.launch {
        repository.cachedTracks()?.let {
            publish(it)
            _isReady.value = true
        }
    }

    /**
     * Al entrar a la pantalla: usa la lista guardada. Solo escanea el teléfono si nunca
     * se ha escaneado (la primera vez). Después, solo se actualiza con el botón de refrescar.
     */
    fun loadIfNeeded() {
        if (_tracks.value.isNotEmpty() || _isLoading.value) return
        viewModelScope.launch {
            cacheJob.join()
            if (_tracks.value.isEmpty() && !_isLoading.value) scan()
        }
    }

    /** Botón de refrescar (manual): vuelve a leer las canciones del teléfono. */
    fun load() {
        if (_isLoading.value) return
        viewModelScope.launch { scan() }
    }

    private suspend fun scan() {
        _isLoading.value = true
        publish(runCatching { repository.loadTracks() }.getOrDefault(emptyList()))
        _isLoading.value = false
        _isReady.value = true
    }

    /** Muestra la lista con las carátulas ya guardadas desde el primer cuadro (sin parpadeo). */
    private suspend fun publish(list: List<LocalTrack>) {
        val saved = withContext(Dispatchers.Default) {
            buildMap { list.forEach { track -> repository.cachedArtwork(track)?.let { put(track.uri, it) } } }
        }
        _artwork.value = saved
        _tracks.value = list
    }

    /** Se llama desde cada fila visible: busca su carátula en línea una sola vez. */
    fun requestArtwork(track: LocalTrack) {
        if (!requested.add(track.uri)) return
        viewModelScope.launch {
            if (fetchArtwork(track) == null) requested.remove(track.uri)
        }
    }

    /** Arma la cola para el reproductor al instante: usa las carátulas ya guardadas, sin esperar internet. */
    suspend fun buildQueue(list: List<LocalTrack>, startIndex: Int): List<MediaItem> =
        withContext(Dispatchers.Default) {
            list.map { track ->
                val url = _artwork.value[track.uri] ?: repository.cachedArtwork(track)
                track.toMediaMetadata(url).toMediaItem()
            }
        }

    private suspend fun fetchArtwork(track: LocalTrack): String? {
        _artwork.value[track.uri]?.let { return it }
        val url = repository.cachedArtwork(track) ?: repository.onlineArtwork(track)
        if (url != null) _artwork.update { it + (track.uri to url) }
        return url
    }
}
