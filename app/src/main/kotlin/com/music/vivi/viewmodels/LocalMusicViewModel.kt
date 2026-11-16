/**
 * vivimusic Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.music.vivi.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.music.vivi.local.LocalMusicRepository
import com.music.vivi.local.LocalSong
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.Normalizer
import javax.inject.Inject

@HiltViewModel
class LocalMusicViewModel
@Inject
constructor(
    val repository: LocalMusicRepository,
) : ViewModel() {
    val query = MutableStateFlow("")
    val isScanning: StateFlow<Boolean> = repository.isScanning

    val songs: StateFlow<List<LocalSong>> =
        combine(repository.songs, query) { all, q ->
            val needle = q.normalized()
            if (needle.isEmpty()) all
            else all.filter {
                it.title.normalized().contains(needle) ||
                    it.artist.normalized().contains(needle) ||
                    it.album.normalized().contains(needle)
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val totalCount: StateFlow<Int> =
        combine(repository.songs, query) { all, _ -> all.size }
            .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    fun refresh() = repository.refresh()

    /** Genera en segundo plano las portadas de la cola, empezando por la canción tocada. */
    fun prepareArtwork(queue: List<LocalSong>, startIndex: Int) {
        viewModelScope.launch {
            val ordered = queue.drop(startIndex) + queue.take(startIndex)
            ordered.take(40).forEach { repository.ensureArtwork(it) }
        }
    }

    private fun String.normalized(): String =
        Normalizer.normalize(lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").trim()
}
