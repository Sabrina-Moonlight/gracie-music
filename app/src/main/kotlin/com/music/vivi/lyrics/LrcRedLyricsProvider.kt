/**
 * vivimusic Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Búsqueda, elección del mejor resultado y descarga adaptadas del código de Metrolist
 * (LrcredLyricsProvider). La lectura del TTML usa el mismo lector que Better Lyrics.
 */

package com.music.vivi.lyrics

import android.content.Context
import com.music.vivi.constants.EnableLrcRedKey
import com.music.vivi.utils.dataStore
import com.music.vivi.utils.get
import io.ktor.client.HttpClient
import com.music.vivi.betterlyrics.TTMLParser
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.coroutines.delay
import kotlinx.serialization.SerialName
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.abs

object LrcRedLyricsProvider : LyricsProvider {
    override val name = "LrcRed"

    private val client by lazy {
        HttpClient(OkHttp) {
            install(ContentNegotiation) {
                json(
                    Json {
                        isLenient = true
                        ignoreUnknownKeys = true
                    }
                )
            }
            defaultRequest {
                url("https://lrc.red/")
            }
            expectSuccess = false
        }
    }

    override fun isEnabled(context: Context): Boolean = context.dataStore[EnableLrcRedKey] ?: true

    @Serializable
    data class LrcredSearchResponse(
        val results: List<LrcredResult> = emptyList(),
        val source: String? = null,
        val total: Int? = null,
    )

    @Serializable
    data class LrcredResult(
        val id: String? = null,
        val isrc: String? = null,
        @SerialName("track_name")
        val trackName: String? = null,
        @SerialName("artist_name")
        val artistName: String? = null,
        @SerialName("album_name")
        val albumName: String? = null,
        val duration: Double? = null,
        val lyricsUrl: String? = null,
        @SerialName("timing_type")
        val timingType: String? = null,
    )

    private const val MAX_LYRICS_RESPONSE_CHARS = 1024 * 1024
    private val lenientJson = Json { ignoreUnknownKeys = true; isLenient = true }

    private val NON_ALPHANUMERIC_OR_SPACE = Regex("""[^\p{L}\p{N}\s]""")
    private val MULTIPLE_SPACES = Regex("""\s+""")
    private val BRACKETED_FEAT =
        Regex("""[\(\[](?:feat\.?|ft\.?|with|prod\.?|bonus track|remaster(?:ed)?)[^\)\]]*[\)\]]""", RegexOption.IGNORE_CASE)
    private val TRAILING_FEAT = Regex("""\b(?:feat\.?|ft\.?|with)\s+.*$""", RegexOption.IGNORE_CASE)
    private val BRACKETED_MEDIA_TAGS =
        Regex(
            """[\(\[](?:official|music video|video|audio|lyrics?|visualizer|album version|explicit)[^\)\]]*[\)\]]""",
            RegexOption.IGNORE_CASE,
        )
    private val ARTIST_DELIMITER = Regex("""[,&/]|(?:\b(?:feat\.?|ft\.?|with|x)\b)""", RegexOption.IGNORE_CASE)
    private val VIDEO_TAG_REMOVAL =
        Regex(
            """\s*[\(\[](?:Official\s*(?:Music\s*)?Video|Music\s*Video|Official\s*Audio|Audio|Lyric\s*Video|Official\s*Lyric\s*Video)[\)\]]""",
            RegexOption.IGNORE_CASE,
        )

    private val MODIFIER_KEYWORDS = listOf(
        "slowed", "reverb", "remix", "mixed", "dj mix", "mashup", "cover", "tribute", "karaoke",
        "instrumental", "acoustic", "sped up", "speed up", "live", "edit", "versión", "version", "bpm",
        "guitar", "piano", "lofi", "lo-fi", "nightcore", "club mix", "extended mix", "radio edit",
    )

    /** Pide texto con límite de 1 MB; 404/204 = sin letra; 503 se reintenta un par de veces. */
    private suspend fun fetchText(path: String, query: String? = null): String? {
        for (attempt in 0..2) {
            val response: HttpResponse = client.get(path) {
                if (query != null) parameter("q", query)
            }
            if (response.status == HttpStatusCode.ServiceUnavailable && attempt < 2) {
                delay(500L shl attempt)
                continue
            }
            if (response.status == HttpStatusCode.NotFound || response.status == HttpStatusCode.NoContent) return null
            if (!response.status.isSuccess()) throw IllegalStateException("lrc.red HTTP ${response.status.value}")
            return response.bodyAsText().takeIf { it.length <= MAX_LYRICS_RESPONSE_CHARS }
        }
        return null
    }

    /** Forma antigua de lrc.red (la del código original): search.json + s/ISRC.ttml. */
    @Serializable
    private data class OldHit(
        val isrc: String? = null,
        val title: String? = null,
        val artist: String? = null,
        val duration: Double? = null,
    )

    @Serializable
    private data class OldResponse(val hits: List<OldHit>? = null)

    private suspend fun searchOld(query: String): List<LrcredResult> {
        val body = fetchText("search.json", query) ?: return emptyList()
        return (lenientJson.decodeFromString<OldResponse>(body).hits ?: emptyList())
            .filter { !it.isrc.isNullOrBlank() }
            .map {
                val isrc = it.isrc!!.trim().uppercase()
                LrcredResult(
                    isrc = isrc,
                    trackName = it.title,
                    artistName = it.artist,
                    duration = it.duration,
                    lyricsUrl = "s/$isrc.ttml",
                )
            }
    }

    private suspend fun searchNew(query: String): List<LrcredResult> {
        val body = fetchText("api/v1", query) ?: return emptyList()
        return lenientJson.decodeFromString<LrcredSearchResponse>(body).results
    }

    /** Primero la forma original (funcionaba); si falla o viene vacía, la nueva. */
    private suspend fun search(title: String, artist: String): List<LrcredResult> {
        val query = "$title $artist".trim()
        if (query.isEmpty()) return emptyList()
        runCatching { searchOld(query) }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }
        return runCatching { searchNew(query) }.getOrNull() ?: emptyList()
    }

    private suspend fun fetchTtml(match: LrcredResult): String? {
        val paths = listOfNotNull(
            match.lyricsUrl?.takeIf { it.isNotBlank() },
            match.isrc?.takeIf { it.isNotBlank() }?.let { "s/${it.trim().uppercase()}.ttml" },
        ).map { it.removePrefix("/") }.distinct()
        for (path in paths) {
            val ttml = runCatching { fetchText(path) }.getOrNull()
            if (!ttml.isNullOrBlank()) return ttml
        }
        return null
    }

    /** TTML -> formato de la app, con el lector de Metrolist; si no entiende nada, el lector antiguo. */
    private fun ttmlToLrc(ttml: String): String {
        val parsed = TTMLParser.parseTTML(ttml)
        if (parsed.isNotEmpty()) return TTMLParser.toLRC(parsed)
        return parseTtmlToLrc(ttml)
    }

    private suspend fun fetchFromApi(title: String, artist: String, durationSec: Int, album: String?): String? {
        val results = search(title, artist)
        if (results.isEmpty()) return null
        val match = findBestMatch(results, title, artist, durationSec.toLong(), album, isrc = null) ?: return null
        val ttml = fetchTtml(match) ?: return null
        return ttmlToLrc(ttml).takeIf { it.isNotBlank() }
    }

    private fun cleanVideoTitle(title: String): String = title.replace(VIDEO_TAG_REMOVAL, "").trim()

    override suspend fun getLyrics(
        id: String,
        title: String,
        artist: String,
        duration: Int,
        album: String?
    ): Result<String> = runCatching {
        fetchFromApi(title, artist, duration, album)?.let { return@runCatching it }

        val cleanTitle = cleanVideoTitle(TitleVersionTag.strip(title))
        if (cleanTitle != title || duration > 0) {
            fetchFromApi(cleanTitle, artist, 0, album)?.let { return@runCatching it }
        }
        throw IllegalStateException("No matching track found on lrc.red")
    }

    override suspend fun getAllLyrics(
        id: String,
        title: String,
        artist: String,
        duration: Int,
        album: String?,
        callback: (String) -> Unit
    ) {
        val results = runCatching { search(title, artist) }.getOrNull() ?: return

        var count = 0
        // Primero los que traen tiempo por palabra.
        for (hit in results.sortedBy { if (it.timingType.equals("word", ignoreCase = true)) 0 else 1 }) {
            if (count > 4) break
            currentCoroutineContext().ensureActive()

            val hitDurationS = hit.duration ?: 0.0
            val isDurationMatch = duration <= 0 || hitDurationS <= 0.0 || abs(hitDurationS - duration) <= 3.0
            if (!isDurationMatch) continue

            runCatching {
                val ttml = fetchTtml(hit) ?: return@runCatching
                val lrc = ttmlToLrc(ttml)
                if (lrc.isNotBlank()) {
                    callback(lrc)
                    count++
                }
            }
        }
    }

    private fun normalize(str: String?): String {
        if (str.isNullOrBlank()) return ""
        return str.lowercase().replace(NON_ALPHANUMERIC_OR_SPACE, " ").replace(MULTIPLE_SPACES, " ").trim()
    }

    private fun cleanBaseTitle(str: String?): String {
        if (str.isNullOrBlank()) return ""
        var s = TitleVersionTag.strip(str).lowercase()
        s = s.replace(BRACKETED_FEAT, "")
        s = s.replace(TRAILING_FEAT, "")
        s = s.replace(BRACKETED_MEDIA_TAGS, "")
        return normalize(s)
    }

    private fun extractArtists(str: String?): List<String> {
        if (str.isNullOrBlank()) return emptyList()
        return str.split(ARTIST_DELIMITER).map { normalize(it) }.filter { it.isNotEmpty() }
    }

    private fun findBestMatch(
        results: List<LrcredResult>,
        title: String,
        artist: String,
        durationSec: Long,
        album: String?,
        isrc: String?,
    ): LrcredResult? {
        if (results.isEmpty()) return null
        val targetDuration = durationSec.toDouble()

        if (!isrc.isNullOrBlank()) {
            val targetIsrc = isrc.trim().uppercase()
            results.firstOrNull { it.isrc?.trim()?.uppercase() == targetIsrc }?.let { return it }
        }

        val targetFullTitle = normalize(title)
        val targetBaseTitle = cleanBaseTitle(title)
        val targetArtist = normalize(artist)
        val targetAlbum = normalize(album)

        val targetTitleLower = title.lowercase()
        val targetAlbumLower = album?.lowercase().orEmpty()
        val targetModifiers = MODIFIER_KEYWORDS.filter { kw ->
            targetTitleLower.contains(kw) || targetAlbumLower.contains(kw)
        }

        var bestItem: LrcredResult? = null
        var maxScore = Double.NEGATIVE_INFINITY

        for (item in results) {
            val itemDuration = item.duration ?: 0.0
            if (targetDuration > 0 && itemDuration > 0) {
                if (abs(itemDuration - targetDuration) > 3.0) continue
            }

            val itemFullTitle = normalize(item.trackName)
            val itemBaseTitle = cleanBaseTitle(item.trackName)
            val itemArtist = normalize(item.artistName)
            val itemAlbum = normalize(item.albumName)

            var score = 0.0

            if (targetFullTitle.isNotEmpty() && itemFullTitle.isNotEmpty() && targetFullTitle == itemFullTitle) {
                score += 50.0
            } else if (targetBaseTitle.isNotEmpty() && itemBaseTitle.isNotEmpty()) {
                if (targetBaseTitle == itemBaseTitle) {
                    score += 35.0
                } else if (itemBaseTitle.contains(targetBaseTitle) || targetBaseTitle.contains(itemBaseTitle)) {
                    score += 15.0
                } else {
                    score -= 40.0
                }
            }

            if (targetArtist.isNotEmpty() && itemArtist.isNotEmpty()) {
                if (targetArtist == itemArtist) {
                    score += 30.0
                } else if (itemArtist.contains(targetArtist) || targetArtist.contains(itemArtist)) {
                    score += 15.0
                } else {
                    val targetArtists = extractArtists(artist)
                    val itemArtists = extractArtists(item.artistName)
                    val hasOverlap = targetArtists.any { ta ->
                        itemArtists.any { ia -> ia == ta || ia.contains(ta) || ta.contains(ia) }
                    }
                    if (hasOverlap) score += 10.0 else continue
                }
            }

            if (targetAlbum.isNotEmpty() && itemAlbum.isNotEmpty()) {
                if (targetAlbum == itemAlbum) {
                    score += 35.0
                } else if (itemAlbum.contains(targetAlbum) || targetAlbum.contains(itemAlbum)) {
                    score += 20.0
                }
                val targetIsSingle = targetAlbum.contains("single")
                val itemIsSingle = itemAlbum.contains("single")
                if (!targetIsSingle && itemIsSingle) {
                    score -= 15.0
                } else if (targetIsSingle && itemIsSingle) {
                    score += 10.0
                }
            }

            if (targetDuration > 0 && itemDuration > 0) {
                val diff = abs(itemDuration - targetDuration)
                if (diff == 0.0) {
                    score += 25.0
                } else if (diff <= 1.0) {
                    score += 20.0
                } else if (diff <= 2.0) {
                    score += 15.0
                }
            }

            when (item.timingType?.lowercase()) {
                "word" -> score += 15.0
                "line" -> score += 8.0
                else -> score -= 10.0
            }

            val itemTrackLower = item.trackName?.lowercase().orEmpty()
            val itemAlbumLower = item.albumName?.lowercase().orEmpty()
            val itemModifiers = MODIFIER_KEYWORDS.filter { kw ->
                itemTrackLower.contains(kw) || itemAlbumLower.contains(kw)
            }
            for (kw in itemModifiers) {
                if (kw !in targetModifiers) score -= 30.0
            }

            if (score > maxScore) {
                maxScore = score
                bestItem = item
            }
        }

        return if (bestItem != null && maxScore > 0.0) bestItem else null
    }

    /**
     * Parses Apple Music TTML into LRC format. 
     * Expects <p begin="MM:SS.mmm"> or <p begin="SS.mmm"> pattern.
     */
    internal fun parseTtmlToLrc(rawTtml: String): String {
        // Strip BOM and clean formatting
        val ttml = rawTtml.replace("\uFEFF", "")
        
        val pRegex = Regex("""<p([^>]*)>(.*?)</p>""", RegexOption.DOT_MATCHES_ALL)
        val tokenRegex = Regex("""(<[^>]+>)|([^<]+)""")
        val beginRegex = Regex("""begin="([^"]+)"""")
        val endRegex = Regex("""\bend="([^"]+)"""")
        val roleRegex = Regex("""(?:ttm:role|role)="([^"]+)"""")
        val agentRegex = Regex("""(?:ttm:agent|agent)="([^"]+)"""")
        
        val lines = buildMap<Long, MutableList<String>> {
            for (pMatch in pRegex.findAll(ttml)) {
                val pAttrs = pMatch.groupValues[1]
                val innerHtml = pMatch.groupValues[2]
                
                val pBeginStr = beginRegex.find(pAttrs)?.groupValues?.get(1) ?: continue
                val lineStartMs = timeToMs(pBeginStr) ?: continue
                val agent = agentRegex.find(pAttrs)?.groupValues?.get(1)
                val pEndMs = endRegex.find(pAttrs)?.groupValues?.get(1)?.let { timeToMs(it) }
                
                val fgPieces = mutableListOf<Pair<Long, String>>()
                val bgPieces = mutableListOf<Pair<Long, String>>()
                var currentSpanMs: Long = lineStartMs
                
                // Track nesting states
                val bgStack = mutableListOf<Boolean>()
                val skipStack = mutableListOf<Boolean>()
                val timeStack = mutableListOf<Long>()
                val endStack = mutableListOf<Long?>()
                var fgEndMs = 0L
                var bgEndMs = 0L
                
                for (tokenMatch in tokenRegex.findAll(innerHtml)) {
                    val tag = tokenMatch.groupValues[1]
                    val text = tokenMatch.groupValues[2]
                    
                    if (tag.isNotEmpty()) {
                        if (tag.startsWith("</")) {
                            if (bgStack.isNotEmpty()) bgStack.removeLast()
                            if (skipStack.isNotEmpty()) skipStack.removeLast()
                            if (endStack.isNotEmpty()) endStack.removeLast()
                            if (timeStack.isNotEmpty()) {
                                timeStack.removeLast()
                                currentSpanMs = timeStack.lastOrNull() ?: lineStartMs
                            }
                        } else if (tag.startsWith("<span") || tag.startsWith("<s ")) {
                            val role = roleRegex.find(tag)?.groupValues?.get(1)
                            val isSkip = role == "x-translation" || role == "x-roman"
                            val isBg = role == "x-bg"
                            
                            bgStack.add(isBg || (bgStack.lastOrNull() ?: false))
                            skipStack.add(isSkip || (skipStack.lastOrNull() ?: false))
                            
                            endStack.add(
                                endRegex.find(tag)?.groupValues?.get(1)?.let { timeToMs(it) }
                                    ?: endStack.lastOrNull()
                            )
                            val tBegin = beginRegex.find(tag)?.groupValues?.get(1)
                            if (tBegin != null) {
                                val tMs = timeToMs(tBegin)
                                if (tMs != null) {
                                    timeStack.add(tMs)
                                    currentSpanMs = tMs
                                    continue
                                }
                            }
                            timeStack.add(currentSpanMs)
                        }
                    } else if (text.isNotEmpty()) {
                        if (skipStack.lastOrNull() == true) continue
                        val isBackground = bgStack.lastOrNull() == true
                        val cleanText = text.replace(Regex("""\s+"""), " ")
                        if (cleanText.isNotEmpty()) {
                            val pieceEnd = endStack.lastOrNull() ?: 0L
                            if (isBackground) {
                                bgPieces.add(currentSpanMs to cleanText)
                                if (cleanText.isNotBlank()) bgEndMs = maxOf(bgEndMs, pieceEnd)
                            } else {
                                fgPieces.add(currentSpanMs to cleanText)
                                if (cleanText.isNotBlank()) fgEndMs = maxOf(fgEndMs, pieceEnd)
                            }
                        }
                    }
                }
                
                fun buildLine(pieces: List<Pair<Long, String>>, isBg: Boolean, endMs: Long) {
                    if (pieces.isEmpty()) return
                    val sb = java.lang.StringBuilder()
                    
                    if (isBg) sb.append("{bg}")
                    if (agent != null) sb.append("{agent:$agent}")
                    
                    // Si la línea no trae tiempos por palabra (una sola marca para varias
                    // palabras), se escribe como línea normal. Si no, la app creería que es
                    // letra por letra y rellenaría toda la línea de golpe.
                    val timed = pieces.filter { it.second.isNotBlank() }
                    if (timed.isEmpty()) return
                    val hasWordTimes = timed.map { it.first }.distinct().size > 1 ||
                        timed.joinToString("") { it.second }.trim().none { it.isWhitespace() }
                    if (!hasWordTimes) {
                        val plain = pieces.joinToString("") { it.second }.trim()
                        if (plain.isNotEmpty()) {
                            getOrPut(lineStartMs) { mutableListOf() }.add(sb.toString() + plain)
                        }
                        return
                    }
                    
                    var lastMs = -1L
                    for ((ms, text) in pieces) {
                        if (text.isBlank()) {
                            sb.append(text)
                            continue
                        }
                        if (ms != lastMs) {
                            sb.append("<${formatTime(ms)}>")
                            lastMs = ms
                        }
                        sb.append(text)
                    }
                    // Marca de fin de línea (como YouLyPlus): sin ella la app cree que la
                    // última palabra dura hasta la siguiente línea y no muestra el círculo de espera.
                    val lastStart = timed.last().first
                    val lineEnd = if (endMs > lastStart) endMs else (pEndMs ?: 0L)
                    var richText = sb.toString().trim()
                    if (richText.isNotEmpty() && lineEnd > lastStart) {
                        richText += "<${formatTime(lineEnd)}>"
                    }
                    if (richText.isNotEmpty()) {
                        getOrPut(lineStartMs) { mutableListOf() }.add(richText)
                    }
                }
                
                buildLine(fgPieces, false, fgEndMs)
                buildLine(bgPieces, true, bgEndMs)
            }
        }.entries.sortedBy { it.key }
        
        return lines.joinToString("\n") { (timeMs, list) ->
            list.joinToString("\n") { text ->
                "[${formatTime(timeMs)}]$text"
            }
        }
    }

    private fun formatTime(ms: Long): String {
        val minutes = (ms / 60000).toString().padStart(2, '0')
        val seconds = ((ms % 60000) / 1000).toString().padStart(2, '0')
        val millis = (ms % 1000).toString().padStart(3, '0')
        return "$minutes:$seconds.$millis"
    }

    private fun timeToMs(value: String?): Long? {
        val raw = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (raw.endsWith("ms")) return raw.dropLast(2).toDoubleOrNull()?.toLong()
        val stripped = raw.removeSuffix("s")
        val parts = stripped.split(':')
        val seconds = when (parts.size) {
            1 -> parts[0].toDoubleOrNull()
            2 -> parts[0].toDoubleOrNull()?.let { m -> parts[1].toDoubleOrNull()?.let { m * 60 + it } }
            3 -> parts[0].toDoubleOrNull()?.let { h ->
                parts[1].toDoubleOrNull()?.let { m ->
                    parts[2].toDoubleOrNull()?.let { h * 3600 + m * 60 + it }
                }
            }
            else -> null
        } ?: return null
        return (seconds * 1000).toLong()
    }
}
