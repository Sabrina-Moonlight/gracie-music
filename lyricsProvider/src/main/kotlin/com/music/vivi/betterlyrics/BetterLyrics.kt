/**
 * vivimusic Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */
package com.music.vivi.betterlyrics

import com.music.vivi.betterlyrics.models.TTMLResponse
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.client.request.parameter
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

object BetterLyrics {
    private val client by lazy {
        HttpClient(CIO) {
            install(ContentNegotiation) {
                json(
                    Json {
                        isLenient = true
                        ignoreUnknownKeys = true
                    },
                )
            }

            install(HttpTimeout) {
                requestTimeoutMillis = 15000
                connectTimeoutMillis = 10000
                socketTimeoutMillis = 15000
            }

            defaultRequest {
                url("https://lyrics-api.boidu.dev")
            }

            expectSuccess = false
        }
    }

    // Igual que Metrolist: respuestas de más de 1 MB se descartan.
    private const val MAX_LYRICS_RESPONSE_CHARS = 1024 * 1024

    private suspend fun fetchTTML(
        artist: String,
        title: String,
        duration: Int = -1,
        album: String? = null,
    ): String? = runCatching {
        val response = client.get("/getLyrics") {
            parameter("s", title)
            parameter("a", artist)
            if (duration > 0) {
                parameter("d", duration)
            }
            if (!album.isNullOrBlank()) {
                parameter("al", album)
            }
            header("Accept", "application/json")
        }
        if (response.status == HttpStatusCode.OK) {
            val body = response.bodyAsText()
            if (body.length > MAX_LYRICS_RESPONSE_CHARS) {
                null
            } else {
                Json { ignoreUnknownKeys = true }.decodeFromString<TTMLResponse>(body).ttml.takeIf { it.isNotBlank() }
            }
        } else {
            null
        }
    }.getOrNull()

    /** Como Metrolist: primero con álbum; si no hay resultado, otra vez sin álbum. */
    private suspend fun fetchTTMLWithFallback(
        artist: String,
        title: String,
        duration: Int,
        album: String?,
    ): String? {
        val trimmedAlbum = album?.trim()?.takeIf { it.isNotEmpty() }
        fetchTTML(artist, title, duration, trimmedAlbum)?.let { return it }
        if (trimmedAlbum != null) return fetchTTML(artist, title, duration, null)
        return null
    }

    suspend fun getLyrics(
        title: String,
        artist: String,
        duration: Int,
        album: String? = null,
    ) = runCatching {
        // Use exact title and artist - no normalization to ensure correct sync
        // Normalizing can return wrong lyrics (e.g., radio edit vs original)
        val ttml = fetchTTMLWithFallback(artist, title, duration, album)
            ?: throw IllegalStateException("Lyrics unavailable")
        
        val parsedLines = TTMLParser.parseTTML(ttml)
        if (parsedLines.isEmpty()) {
            throw IllegalStateException("Failed to parse lyrics")
        }
        
        TTMLParser.toLRC(parsedLines)
    }

    suspend fun getAllLyrics(
        title: String,
        artist: String,
        duration: Int,
        album: String? = null,
        callback: (String) -> Unit,
    ) {
        getLyrics(title, artist, duration, album)
            .onSuccess { lrcString ->
                callback(lrcString)
            }
    }
}
