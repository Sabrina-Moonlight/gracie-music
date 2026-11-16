package com.music.vivi.viewmodels

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.SimpleCache
import com.music.vivi.constants.SongSortType
import com.music.vivi.db.MusicDatabase
import com.music.vivi.db.entities.Song
import com.music.vivi.di.PlayerCache
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import javax.inject.Inject

/** Canciones de la ruta `auto_playlist/{playlist}`: "liked" o "downloaded". */
@HiltViewModel
class AutoPlaylistViewModel @Inject constructor(
    database: MusicDatabase,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    val playlist = savedStateHandle.get<String>("playlist") ?: "liked"

    val songs: StateFlow<List<Song>?> =
        when (playlist) {
            "downloaded" -> database.downloadedSongs(SongSortType.CREATE_DATE, true)
            else -> database.likedSongs(SongSortType.CREATE_DATE, true)
        }.stateIn(viewModelScope, SharingStarted.Lazily, null)
}

/** Canciones de la ruta `top_playlist/{top}`: las N más reproducidas. */
@HiltViewModel
class TopPlaylistViewModel @Inject constructor(
    database: MusicDatabase,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    val top = savedStateHandle.get<String>("top")?.toIntOrNull() ?: 50

    val songs: StateFlow<List<Song>?> =
        database.mostPlayedSongs(fromTimeStamp = 0L, limit = top)
            .stateIn(viewModelScope, SharingStarted.Lazily, null)
}

/** Canciones guardadas en la caché de reproducción (ruta `cache_playlist/{playlist}`). */
@OptIn(UnstableApi::class)
@HiltViewModel
class CachePlaylistViewModel @Inject constructor(
    private val database: MusicDatabase,
    @PlayerCache private val playerCache: SimpleCache,
) : ViewModel() {
    val songs: StateFlow<List<Song>?> =
        flow {
            while (true) {
                emit(loadCached())
                delay(3000)
            }
        }.flowOn(Dispatchers.IO)
            .stateIn(viewModelScope, SharingStarted.Lazily, null)

    private suspend fun loadCached(): List<Song> {
        val ids = playerCache.keys.filter { id ->
            val length = playerCache.getContentMetadata(id).get(ContentMetadata.KEY_CONTENT_LENGTH, -1L)
            length > 0 && playerCache.isCached(id, 0, length)
        }
        if (ids.isEmpty()) return emptyList()
        return database.getSongsByIds(ids)
            .sortedBy { it.song.title.lowercase() }
    }
}
