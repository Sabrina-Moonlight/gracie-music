package com.music.vivi.ui.screens.playlist

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import com.music.vivi.LocalPlayerAwareWindowInsets
import com.music.vivi.LocalPlayerConnection
import com.music.vivi.R
import com.music.vivi.db.entities.Song
import com.music.vivi.extensions.toMediaItem
import com.music.vivi.extensions.togglePlayPause
import com.music.vivi.playback.queues.ListQueue
import com.music.vivi.ui.component.IconButton
import com.music.vivi.ui.component.LocalMenuState
import com.music.vivi.ui.component.SongListItem
import com.music.vivi.ui.menu.SongMenu
import com.music.vivi.ui.utils.backToMain
import com.music.vivi.utils.listItemShape
import com.music.vivi.viewmodels.AutoPlaylistViewModel
import com.music.vivi.viewmodels.CachePlaylistViewModel
import com.music.vivi.viewmodels.TopPlaylistViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutoPlaylistScreen(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
    viewModel: AutoPlaylistViewModel = hiltViewModel(),
) {
    val songs by viewModel.songs.collectAsState()
    SongListScreen(
        title = stringResource(
            if (viewModel.playlist == "downloaded") R.string.downloaded_songs else R.string.liked_songs
        ),
        songs = songs,
        navController = navController,
        scrollBehavior = scrollBehavior,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TopPlaylistScreen(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
    viewModel: TopPlaylistViewModel = hiltViewModel(),
) {
    val songs by viewModel.songs.collectAsState()
    SongListScreen(
        title = stringResource(R.string.my_top) + " ${viewModel.top}",
        songs = songs,
        navController = navController,
        scrollBehavior = scrollBehavior,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CachePlaylistScreen(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
    viewModel: CachePlaylistViewModel = hiltViewModel(),
) {
    val songs by viewModel.songs.collectAsState()
    SongListScreen(
        title = stringResource(R.string.cached_playlist),
        songs = songs,
        navController = navController,
        scrollBehavior = scrollBehavior,
        isFromCache = true,
    )
}

/** Lista simple de canciones con Reproducir / Aleatorio, usada por las 3 pantallas. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SongListScreen(
    title: String,
    songs: List<Song>?,
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
    isFromCache: Boolean = false,
) {
    val playerConnection = LocalPlayerConnection.current ?: return
    val menuState = LocalMenuState.current
    val isPlaying by playerConnection.isEffectivelyPlaying.collectAsState()
    val mediaMetadata by playerConnection.mediaMetadata.collectAsState()

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(title) },
            navigationIcon = {
                IconButton(
                    onClick = navController::navigateUp,
                    onLongClick = navController::backToMain,
                ) {
                    Icon(painterResource(R.drawable.arrow_back), contentDescription = null)
                }
            },
            scrollBehavior = scrollBehavior,
        )
        Box(Modifier.weight(1f).fillMaxWidth()) {
        when {
            songs == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            songs.isEmpty() -> Text(
                text = stringResource(R.string.playlist_is_empty),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.align(Alignment.Center),
            )
            else -> LazyColumn(
                contentPadding = LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Bottom).asPaddingValues(),
                modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
            ) {
                item {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        FilledTonalButton(
                            onClick = {
                                playerConnection.playQueue(
                                    ListQueue(title = title, items = songs.map { it.toMediaItem() })
                                )
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(painterResource(R.drawable.play), contentDescription = null)
                            Text(stringResource(R.string.play), modifier = Modifier.padding(start = 8.dp))
                        }
                        FilledTonalButton(
                            onClick = {
                                playerConnection.playQueue(
                                    ListQueue(title = title, items = songs.shuffled().map { it.toMediaItem() })
                                )
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(painterResource(R.drawable.shuffle), contentDescription = null)
                            Text(stringResource(R.string.shuffle), modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
                itemsIndexed(items = songs, key = { _, song -> song.id }) { index, song ->
                    SongListItem(
                        song = song,
                        isActive = song.id == mediaMetadata?.id,
                        isPlaying = isPlaying,
                        showInLibraryIcon = true,
                        shape = listItemShape(index = index, count = songs.size),
                        trailingContent = {
                            IconButton(
                                onClick = {
                                    menuState.show {
                                        SongMenu(
                                            originalSong = song,
                                            navController = navController,
                                            onDismiss = menuState::dismiss,
                                            isFromCache = isFromCache,
                                        )
                                    }
                                },
                                onLongClick = {},
                            ) {
                                Icon(painterResource(R.drawable.more_vert), contentDescription = null)
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (song.id == mediaMetadata?.id) {
                                    playerConnection.player.togglePlayPause()
                                } else {
                                    playerConnection.playQueue(
                                        ListQueue(
                                            title = title,
                                            items = songs.map { it.toMediaItem() },
                                            startIndex = index,
                                        )
                                    )
                                }
                            },
                    )
                }
            }
        }
        }
    }
}
