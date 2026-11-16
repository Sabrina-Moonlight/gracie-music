/**
 * vivimusic Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.music.vivi.ui.screens.library

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.music.vivi.LocalPlayerAwareWindowInsets
import com.music.vivi.LocalPlayerConnection
import com.music.vivi.R
import com.music.vivi.constants.ListThumbnailSize
import com.music.vivi.constants.ThumbnailCornerRadius
import com.music.vivi.extensions.toMediaItem
import com.music.vivi.local.LocalSong
import com.music.vivi.local.toMediaMetadata
import com.music.vivi.playback.queues.ListQueue
import com.music.vivi.utils.makeTimeString
import com.music.vivi.viewmodels.LocalMusicViewModel

private fun audioPermission() =
    if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
    else Manifest.permission.READ_EXTERNAL_STORAGE

@Composable
fun LocalMusicScreen(
    navController: NavController,
    viewModel: LocalMusicViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val playerConnection = LocalPlayerConnection.current
    val songs by viewModel.songs.collectAsState()
    val total by viewModel.totalCount.collectAsState()
    val scanning by viewModel.isScanning.collectAsState()
    val query by viewModel.query.collectAsState()

    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, audioPermission()) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
        if (it) viewModel.refresh()
    }

    // Al entrar se actualiza la lista (la guardada ya se ve mientras tanto).
    LaunchedEffect(granted) { if (granted) viewModel.refresh() }

    val insets = LocalPlayerAwareWindowInsets.current

    Column(Modifier.fillMaxSize().windowInsetsPadding(insets)) {
        if (!granted) {
            Column(
                Modifier.fillMaxSize().padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(stringResource(R.string.local_music_permission), style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(16.dp))
                Button(onClick = { launcher.launch(audioPermission()) }) {
                    Text(stringResource(R.string.local_music_grant))
                }
            }
            return@Column
        }

        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = query,
                onValueChange = { viewModel.query.value = it },
                singleLine = true,
                placeholder = { Text(stringResource(R.string.local_music_search)) },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(24.dp),
            )
            IconButton(onClick = { viewModel.refresh() }) {
                if (scanning) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else Icon(painterResource(R.drawable.refresh), contentDescription = stringResource(R.string.local_music_refresh))
            }
        }

        if (songs.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (scanning) CircularProgressIndicator()
                else Text(
                    stringResource(if (total == 0) R.string.local_music_empty else R.string.local_music_no_results),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            return@Column
        }

        LazyColumn(Modifier.fillMaxSize()) {
            itemsIndexed(songs, key = { _, s -> s.mediaStoreId }) { index, song ->
                LocalSongRow(
                    song = song,
                    artwork = viewModel.repository.artworkUri(song),
                    onClick = {
                        viewModel.prepareArtwork(songs, index)
                        val items = songs.map { it.toMediaMetadata(viewModel.repository.artworkUri(it)).toMediaItem() }
                        playerConnection?.playQueue(
                            ListQueue(title = context.getString(R.string.local_music), items = items, startIndex = index),
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun LocalSongRow(song: LocalSong, artwork: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(ListThumbnailSize).clip(RoundedCornerShape(ThumbnailCornerRadius)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(R.drawable.music_note), contentDescription = null, modifier = Modifier.size(24.dp))
            AsyncImage(
                model = artwork,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(song.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (song.artist.isNotBlank()) {
                Text(
                    song.artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(makeTimeString(song.durationMs), style = MaterialTheme.typography.bodySmall)
    }
}
