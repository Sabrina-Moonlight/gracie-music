/**
 * vivimusic Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.music.vivi.ui.screens.library

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.music.vivi.LocalPlayerAwareWindowInsets
import com.music.vivi.LocalPlayerConnection
import com.music.vivi.R
import com.music.vivi.ui.component.ActiveBoxAlpha
import com.music.vivi.ui.component.PlayingIndicatorBox
import com.music.vivi.localmedia.LocalTrack
import com.music.vivi.playback.queues.ListQueue
import com.music.vivi.viewmodels.LocalMusicViewModel
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun LocalMusicScreen(
    navController: NavController,
    viewModel: LocalMusicViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val playerConnection = LocalPlayerConnection.current ?: return
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current

    val tracks by viewModel.tracks.collectAsState()
    val artwork by viewModel.artwork.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val queueTitle = stringResource(R.string.local_music_title)
    val currentMetadata by playerConnection.mediaMetadata.collectAsState()
    val isPlaying by playerConnection.isEffectivelyPlaying.collectAsState()

    val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }
    fun permissionGranted() =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    var hasPermission by remember { mutableStateOf(permissionGranted()) }
    var askedOnce by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        askedOnce = true
        hasPermission = granted
    }

    // Al volver a la pantalla (por ejemplo desde Ajustes) solo se revisa el permiso.
    // La lista se actualiza únicamente con el botón de refrescar.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasPermission = permissionGranted()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(hasPermission) {
        if (hasPermission) viewModel.loadIfNeeded()
    }

    if (!hasPermission) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(LocalPlayerAwareWindowInsets.current.asPaddingValues())
                .padding(32.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Icon(
                    painter = painterResource(R.drawable.download),
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = stringResource(R.string.local_music_permission_needed),
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                )
                Button(onClick = { permissionLauncher.launch(permission) }) {
                    Text(stringResource(R.string.local_music_grant))
                }
                if (askedOnce) {
                    OutlinedButton(
                        onClick = {
                            context.startActivity(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                    .setData(Uri.fromParts("package", context.packageName, null)),
                            )
                        },
                    ) {
                        Text(stringResource(R.string.local_music_open_settings))
                    }
                }
            }
        }
        return
    }

    var query by remember { mutableStateOf("") }
    val visibleTracks = remember(tracks, query) {
        val needle = query.trim().lowercase(Locale.ROOT)
        if (needle.isEmpty()) {
            tracks
        } else {
            tracks.filter {
                it.title.lowercase(Locale.ROOT).contains(needle) ||
                    it.artist.orEmpty().lowercase(Locale.ROOT).contains(needle)
            }
        }
    }

    // Filas: encabezado de artista, encabezado de álbum y canciones, en el mismo orden de la cola.
    val entries = remember(visibleTracks) {
        val artistCounts = visibleTracks.groupingBy { it.groupArtist }.eachCount()
        val albumYears = visibleTracks
            .groupBy { it.groupArtist to it.album }
            .mapValues { entry -> entry.value.maxOf { it.year } }
        buildList<ListEntry> {
            visibleTracks.forEachIndexed { index, track ->
                val previous = visibleTracks.getOrNull(index - 1)
                val newArtist = index == 0 || previous?.groupArtist != track.groupArtist
                if (newArtist) add(ListEntry.ArtistHeader(track.groupArtist, artistCounts[track.groupArtist] ?: 0))
                if (newArtist || previous?.album != track.album) {
                    add(ListEntry.AlbumHeader(track.groupArtist, track.album, albumYears[track.groupArtist to track.album] ?: 0))
                }
                add(ListEntry.Song(track, index))
            }
        }
    }

    fun play(list: List<LocalTrack>, startIndex: Int) {
        if (list.isEmpty()) return
        scope.launch {
            val items = viewModel.buildQueue(list, startIndex)
            playerConnection.playQueue(ListQueue(title = queueTitle, items = items, startIndex = startIndex))
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues(),
    ) {
        item(key = "header") {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.local_music_title),
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = pluralStringResource(R.plurals.local_music_count, tracks.size, tracks.size),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { viewModel.load() }) {
                        Icon(
                            painter = painterResource(R.drawable.refresh),
                            contentDescription = stringResource(R.string.local_music_refresh),
                        )
                    }
                }
                if (isLoading) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                }
                if (tracks.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Button(modifier = Modifier.weight(1f), onClick = { play(visibleTracks, 0) }) {
                            Icon(painterResource(R.drawable.play), contentDescription = null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.local_music_play))
                        }
                        FilledTonalButton(
                            modifier = Modifier.weight(1f),
                            onClick = { play(visibleTracks.shuffled(), 0) },
                        ) {
                            Icon(painterResource(R.drawable.shuffle), contentDescription = null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.local_music_shuffle))
                        }
                    }
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                        singleLine = true,
                        shape = RoundedCornerShape(28.dp),
                        placeholder = { Text(stringResource(R.string.local_music_search_hint)) },
                        leadingIcon = { Icon(painterResource(R.drawable.search), contentDescription = null) },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    )
                }
            }
        }

        if (tracks.isEmpty() && !isLoading) {
            item(key = "empty") {
                Text(
                    text = stringResource(R.string.local_music_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(32.dp),
                )
            }
        }

        items(items = entries, key = { it.key }) { entry ->
            when (entry) {
                is ListEntry.ArtistHeader -> Text(
                    text = entry.name ?: stringResource(R.string.unknown_artist),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp),
                )
                is ListEntry.AlbumHeader -> Text(
                    text = (entry.album ?: stringResource(R.string.local_music_unknown_album)) +
                        if (entry.year > 0) " · ${entry.year}" else "",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 2.dp),
                )
                is ListEntry.Song -> {
                    LaunchedEffect(entry.track.uri) { viewModel.requestArtwork(entry.track) }
                    LocalTrackRow(
                        track = entry.track,
                        artworkUrl = artwork[entry.track.uri],
                        isActive = entry.track.uri == currentMetadata?.id,
                        isPlaying = isPlaying,
                        onClick = { play(visibleTracks, entry.index) },
                    )
                }
            }
        }
    }
}

private sealed interface ListEntry {
    val key: String

    data class ArtistHeader(val name: String?, val count: Int) : ListEntry {
        override val key get() = "artist:$name"
    }

    data class AlbumHeader(val artist: String?, val album: String?, val year: Int) : ListEntry {
        override val key get() = "album:$artist:$album"
    }

    data class Song(val track: LocalTrack, val index: Int) : ListEntry {
        override val key get() = track.uri
    }
}

@Composable
private fun LocalTrackRow(
    track: LocalTrack,
    artworkUrl: String?,
    isActive: Boolean,
    isPlaying: Boolean,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (isActive) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(artworkUrl ?: track.deviceArtworkUri)
                    .crossfade(true)
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                error = painterResource(R.drawable.music_note),
                fallback = painterResource(R.drawable.music_note),
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
            PlayingIndicatorBox(
                isActive = isActive,
                playWhenReady = isPlaying,
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = ActiveBoxAlpha)),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = track.title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (isActive) MaterialTheme.colorScheme.primary else Color.Unspecified,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = track.artist ?: stringResource(R.string.unknown_artist),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (track.format.isNotEmpty()) {
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = if (track.isLossless) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
            ) {
                Text(
                    text = track.format,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
    }
}
