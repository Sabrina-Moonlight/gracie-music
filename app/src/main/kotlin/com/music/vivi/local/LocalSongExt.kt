/**
 * vivimusic Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.music.vivi.local

import com.music.vivi.models.MediaMetadata

/** Metadatos de reproducción de una canción local. Sin ids de artista/álbum: no se navega a YouTube. */
fun LocalSong.toMediaMetadata(artworkUri: String?): MediaMetadata =
    MediaMetadata(
        id = id,
        title = title,
        artists = if (artist.isNotBlank()) listOf(MediaMetadata.Artist(id = null, name = artist)) else emptyList(),
        duration = (durationMs / 1000L).toInt(),
        thumbnailUrl = artworkUri,
        album = null,
    )
