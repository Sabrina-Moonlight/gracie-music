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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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

    private val requested = HashSet<String>()

    fun load() {
        if (_isLoading.value) return
        viewModelScope.launch {
            _isLoading.value = true
            _tracks.value = runCatching { repository.loadTracks() }.getOrDefault(emptyList())
            _isLoading.value = false
        }
    }

    /** Se llama desde cada fila visible: busca su carátula en línea una sola vez. */
    fun requestArtwork(track: LocalTrack) {
        if (!requested.add(track.uri)) return
        viewModelScope.launch {
            if (fetchArtwork(track) == null) requested.remove(track.uri)
        }
    }

    /** Arma la cola para el reproductor, con carátula en línea para las primeras canciones. */
    suspend fun buildQueue(list: List<LocalTrack>, startIndex: Int): List<MediaItem> {
        val head = list.drop(startIndex).take(8)
        withTimeoutOrNull(2_500L) {
            coroutineScope { head.map { async { fetchArtwork(it) } }.awaitAll() }
        }
        return withContext(Dispatchers.Default) {
            list.map { track ->
                val url = _artwork.value[track.uri] ?: repository.cachedArtwork(track)
                track.toMediaMetadata(url).toMediaItem()
            }
        }
    }

    private suspend fun fetchArtwork(track: LocalTrack): String? {
        _artwork.value[track.uri]?.let { return it }
        val url = repository.cachedArtwork(track) ?: repository.onlineArtwork(track)
        if (url != null) _artwork.update { it + (track.uri to url) }
        return url
    }
}
