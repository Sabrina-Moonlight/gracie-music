/**
 * vivimusic Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */
package com.music.youlyplus

import com.music.youlyplus.models.LyricsResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.selects.select
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicReference

/**
 * YouLyPlus / LyricsPlus KPoe API client.
 *
 * This replicates the multi-server fetch strategy from the YouLyPlus browser
 * extension (ibratabian17/YouLyPlus), querying community-hosted instances of
 * the open-source LyricsPlus backend (ibratabian17/lyricsplus).
 *
 * API endpoint: GET {server}/v2/lyrics/get?title=...&artist=...&duration=...
 */
object YouLyPlus {

    /** Mirror of YouLyPlus extension's KPOE_SERVERS constant. */
    private val BASE_SERVERS = listOf(
        "https://lyricsplus.prjktla.my.id",       // youly's server
        "https://lyricsplus.atomix.one",          // meow's mirror
        "https://lyricsplus.binimum.org",         // binimum's server
        "https://lyricsplus.prjktla.workers.dev", // ibra's cf worker
        "https://lyricsplus-seven.vercel.app",    // jigen's mirror
        "https://lyrics-plus-backend.vercel.app", // ibra's vercel
    )

    /**
     * Remembers the last server that returned a valid result so it is tried
     * first on the next call, giving a fast path on repeated fetches.
     */
    private val lastWorkingServer = AtomicReference<String?>(null)

    /** Returns the server list with the last-working server promoted to front. */
    private val servers: List<String>
        get() {
            val lws = lastWorkingServer.get() ?: return BASE_SERVERS
            return listOf(lws) + BASE_SERVERS.filter { it != lws }
        }

    private val client by lazy {
        HttpClient(OkHttp) {
            install(HttpTimeout) {
                connectTimeoutMillis = 3_000
                requestTimeoutMillis = 8_000
            }
            install(ContentNegotiation) {
                json(
                    Json {
                        isLenient = true
                        ignoreUnknownKeys = true
                    }
                )
            }
            expectSuccess = true
        }
    }

    /**
     * Fetch lyrics by racing all servers in parallel.
     * Returns the first non-blank result; records the winning server so future
     * calls skip the slow ones.
     */
    suspend fun getLyrics(
        title: String,
        artist: String,
        duration: Int,
        album: String? = null,
        id: String? = null,
        isrc: String? = null,
    ): Result<String> = runCatching {
        val scope = CoroutineScope(Dispatchers.IO)
        val jobs = servers.map { server ->
            server to scope.async {
                fetchFromServer(server, title, artist, duration, album, id, isrc)
            }
        }

        try {
            // Poll until one server returns a usable result
            val remaining = jobs.toMutableList()
            while (remaining.isNotEmpty()) {
                val (winServer, winLyrics) = select {
                    remaining.forEach { (srv, deferred) ->
                        deferred.onAwait { response -> srv to response }
                    }
                }
                remaining.removeAll { it.first == winServer }

                val lrc = winLyrics?.toLrc()
                if (!lrc.isNullOrBlank()) {
                    lastWorkingServer.set(winServer)
                    return@runCatching lrc
                }
            }
            throw IllegalStateException("No lyrics found from any YouLyPlus server")
        } finally {
            scope.coroutineContext.cancelChildren()
        }
    }

    /**
     * Collect all lyrics options across servers; invokes [callback] for each
     * distinct non-blank result. Each server is queried in parallel; callbacks
     * are delivered as results arrive.
     */
    suspend fun getAllLyrics(
        title: String,
        artist: String,
        duration: Int,
        album: String? = null,
        id: String? = null,
        isrc: String? = null,
        callback: (String) -> Unit,
    ) {
        val scope = CoroutineScope(Dispatchers.IO)
        val jobs = servers.map { server ->
            scope.async {
                runCatching {
                    val response = fetchFromServer(server, title, artist, duration, album, id, isrc)
                    response?.toLrc()
                }.getOrNull()
            }
        }
        try {
            val remaining = jobs.toMutableList()
            while (remaining.isNotEmpty()) {
                val lrc = select {
                    remaining.forEach { deferred -> deferred.onAwait { it } }
                }
                remaining.removeAll { it.isCompleted }
                if (!lrc.isNullOrBlank()) callback(lrc)
            }
        } finally {
            scope.coroutineContext.cancelChildren()
        }
    }


    /** Respuesta del servidor -> texto LRC (con tiempos por palabra si los hay). */
    private fun LyricsResponse.toLrc(): String? {
        val lrc = syncedLyrics?.takeIf { it.isNotBlank() }
        if (lrc != null) return lrc

        val kpoe = lyrics
        if (!kpoe.isNullOrEmpty()) {
            return kpoe.convertToLrc()?.takeIf { it.isNotBlank() }
                ?: plainLyrics?.takeIf { it.isNotBlank() }
        }
        return plainLyrics?.takeIf { it.isNotBlank() }
    }

    /**
     * Converts a list of LyricsItem (with millisecond 'time') to a standard 
     * [mm:ss.xxx]LRC string. Supports word-by-word rich sync if syllables are present.
     */
    private fun List<com.music.youlyplus.models.LyricsItem>.convertToLrc(): String? {
        if (isEmpty()) return null
        return flatMap { item ->
            val lineTime = item.time ?: 0L
            val syllabus = item.syllabus
            if (syllabus.isNullOrEmpty()) {
                return@flatMap listOf(formatTime(lineTime) + (item.text ?: ""))
            }

            // Separa la voz principal de la voz de fondo (como hace BetterLyrics):
            // la principal va en su línea y cada tramo de fondo en una línea {bg} aparte.
            val main = syllabus.filter { it.isBackground != true }
            val bgRuns = mutableListOf<MutableList<com.music.youlyplus.models.Syllable>>()
            var prevWasBg = false
            syllabus.forEach { syl ->
                val bg = syl.isBackground == true
                if (bg) {
                    if (!prevWasBg) bgRuns.add(mutableListOf())
                    bgRuns.last().add(syl)
                }
                prevWasBg = bg
            }

            val out = mutableListOf<String>()
            if (main.isNotEmpty()) {
                val start = if (syllabus.first().isBackground != true) lineTime else (main.first().time ?: lineTime)
                buildSyllableLine(start, main, isBg = false, fallbackEnd = lineTime + (item.duration ?: 0L))
                    ?.let { out.add(it) }
            }
            bgRuns.forEach { run ->
                buildSyllableLine(run.first().time ?: lineTime, run, isBg = true, fallbackEnd = 0L)
                    ?.let { out.add(it) }
            }
            out
        }.joinToString("\n").takeIf { it.isNotBlank() }
    }

    /** Construye una línea con sílabas; en voces de fondo quita los paréntesis del texto. */
    private fun buildSyllableLine(
        lineTime: Long,
        syllables: List<com.music.youlyplus.models.Syllable>,
        isBg: Boolean,
        fallbackEnd: Long,
    ): String? {
        val texts = syllables.map {
            val t = it.text ?: ""
            if (isBg) t.replace("(", "").replace(")", "") else t
        }
        if (texts.all { it.isBlank() }) return null

        val sb = StringBuilder(formatTime(lineTime))
        if (isBg) sb.append("{bg}")
        syllables.forEachIndexed { i, syl ->
            sb.append(formatTime(syl.time ?: 0L, isSyllable = true))
            // Confía en el espaciado de la API: no añade espacios extra.
            sb.append(texts[i])
        }
        // Marca de fin de la línea: sin ella la app cree que la última palabra
        // dura hasta la siguiente línea y nunca muestra el círculo de espera.
        val lastStart = syllables.last().time ?: lineTime
        val sylEnd = syllables.maxOf { (it.time ?: 0L) + (it.duration ?: 0L) }
        val lineEnd = if (sylEnd > lastStart) sylEnd else fallbackEnd
        if (lineEnd > lastStart) sb.append(formatTime(lineEnd, isSyllable = true))
        return sb.toString().trimEnd()
    }


    private fun formatTime(timeMs: Long, isSyllable: Boolean = false): String {
        val minutes = (timeMs / 1000) / 60
        val seconds = (timeMs / 1000) % 60
        val millis = timeMs % 1000
        val prefix = if (isSyllable) "<" else "["
        val suffix = if (isSyllable) ">" else "]"
        return "%s%02d:%02d.%03d%s".format(prefix, minutes, seconds, millis, suffix)
    }


    // Fuentes en orden de preferencia: Apple primero (letra limpia, sin créditos ni traducciones).
    private const val SOURCE_ORDER = "apple,lyricsplus,musixmatch,spotify,musixmatch-word"

    private suspend fun fetchFromServer(
        baseUrl: String,
        title: String,
        artist: String,
        duration: Int,
        album: String? = null,
        id: String? = null,
        isrc: String? = null,
    ): LyricsResponse? =
        // Si el servidor no acepta el parámetro de fuente, se reintenta sin él.
        requestLyrics(baseUrl, title, artist, duration, album, id, isrc, SOURCE_ORDER)
            ?: requestLyrics(baseUrl, title, artist, duration, album, id, isrc, null)

    private suspend fun requestLyrics(
        baseUrl: String,
        title: String,
        artist: String,
        duration: Int,
        album: String?,
        id: String?,
        isrc: String?,
        source: String?,
    ): LyricsResponse? = runCatching {
        client.get(baseUrl.let { if (it.endsWith("/")) it else "$it/" } + "v2/lyrics/get") {
            parameter("title", title)
            parameter("artist", artist)
            parameter("duration", duration)
            if (album != null) parameter("album", album)
            if (id != null) parameter("id", id)
            if (isrc != null) parameter("isrc", isrc)
            if (source != null) parameter("source", source)
        }.body<LyricsResponse>()
    }.getOrNull()
}
