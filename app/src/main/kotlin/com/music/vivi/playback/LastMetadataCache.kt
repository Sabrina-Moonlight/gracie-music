package com.music.vivi.playback

import android.content.Context
import com.music.vivi.models.MediaMetadata
import java.io.File
import java.io.ObjectInputStream
import java.io.ObjectOutputStream

/**
 * Guarda los datos de la última canción (título, artista, carátula, duración...) para que el
 * reproductor se abra ya cargado mientras el servicio todavía está restaurando la cola.
 */
object LastMetadataCache {
    private fun file(context: Context) = File(context.applicationContext.filesDir, "last_player_metadata.bin")

    fun load(context: Context): MediaMetadata? = runCatching {
        val f = file(context)
        if (!f.exists()) return@runCatching null
        ObjectInputStream(f.inputStream()).use { it.readObject() as MediaMetadata }
    }.getOrNull()

    fun save(context: Context, metadata: MediaMetadata) {
        runCatching {
            ObjectOutputStream(file(context).outputStream()).use { it.writeObject(metadata) }
        }
    }
}
