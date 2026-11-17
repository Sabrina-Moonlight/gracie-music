/**
 * vivimusic Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Búsqueda, puntuación, elección de la mejor letra y lectura de la respuesta
 * adaptadas del código de Metrolist (PaxsenixLyricsProvider.kt). La salida sigue
 * siendo el formato actual de la app (LRC + <palabra:ini:fin|...>).
 */
package com.music.paxsenix

import android.content.Context
import com.music.vivi.betterlyrics.TTMLParser
import com.music.paxsenix.models.AppleMusicSearchResponse
import com.music.paxsenix.models.LyricText
import com.music.paxsenix.models.LyricsContent
import com.music.paxsenix.models.LyricsResponse
import com.music.paxsenix.models.SearchResult
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import com.music.vivi.betterlyrics.TTMLParser.ParsedLine
import com.music.vivi.betterlyrics.TTMLParser.ParsedWord
import kotlinx.coroutines.delay
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.net.URLEncoder
import kotlin.math.abs

object Paxsenix {
    @Volatile
    private var client: HttpClient? = null
    private var appVersion: String = "Unknown"

    fun init(context: Context) {
        if (client != null) return // Already initialized
        
        synchronized(this) {
            if (client != null) return
            
            appVersion = try {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
                    ?: "Unknown"
            } catch (e: Exception) {
                Timber.e(e, "Failed to get app version")
                "Unknown"
            }
            
            Timber.d("Initializing Paxsenix with version: $appVersion")
            
            client = HttpClient(CIO) {
                install(HttpTimeout) {
                    requestTimeoutMillis = 15000
                    connectTimeoutMillis = 10000
                }
                install(ContentNegotiation) {
                    json(
                        Json {
                            isLenient = true
                            ignoreUnknownKeys = true
                        },
                    )
                }

                defaultRequest {
                    url("https://lyrics.paxsenix.org")
                    header("User-Agent", "ViviMusic/$appVersion")
                }

                expectSuccess = true
            }
            
            Timber.d("Paxsenix HTTP client initialized")
        }
    }

    private val httpClient: HttpClient
        get() = client ?: throw IllegalStateException("Paxsenix.init() must be called before using Paxsenix")

    private const val APPLE_MUSIC_API_BASE = "https://amp-api.music.apple.com/v1/catalog/us"
    private const val APPLE_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:95.0) Gecko/20100101 Firefox/95.0"
    private const val MAX_MATCH_INPUT_CHARS = 1_024
    private const val MAX_LYRICS_RESPONSE_CHARS = 1024 * 1024
    private const val MAX_APPLE_SCRIPT_CHARS = 8 * 1024 * 1024

    private val appleJson = Json { ignoreUnknownKeys = true; isLenient = true }
    @Volatile
    private var appleTokenManager: AppleTokenManager? = null
    private val tokenManager: AppleTokenManager
        get() = appleTokenManager ?: synchronized(this) {
            appleTokenManager ?: AppleTokenManager(httpClient).also { appleTokenManager = it }
        }

    private val mixedRegex = Regex("""\bmixed\b""")
    private val remixRegex = Regex("""\bremix\b""")
    private val bracketRegex = Regex("""\(.*?\)|\[.*?]|【.*?】""")
    private val featuredRegex = Regex("""\bfeat\.?.*$|\bft\.?.*$""")
    private val nonAlphanumericRegex = Regex("""[^a-z0-9]+""")
    private val whitespaceRegex = Regex("""\s+""")
    private val artistSplitRegex = Regex("""(?i),|&|\+|/|\bx\b|\bvs\.?\b|\band\b|\bfeat\.?\b|\bft\.?\b|\bfeaturing\b|\bwith\b""")

    private val titleCleanupPatterns = listOf(
        Regex("""\s*\(.*?(official|video|audio|lyrics|lyric|visualizer|hd|hq|4k|remaster|remix|live|acoustic|version|edit|extended|radio|clean|explicit).*?\)""", RegexOption.IGNORE_CASE),
        Regex("""\s*\[.*?(official|video|audio|lyrics|lyric|visualizer|hd|hq|4k|remaster|remix|live|acoustic|version|edit|extended|radio|clean|explicit).*?]""", RegexOption.IGNORE_CASE),
        Regex("""\s*【.*?】"""),
        Regex("""\s*\|.*$"""),
        Regex("""\s*-\s*(official|video|audio|lyrics|lyric|visualizer).*$""", RegexOption.IGNORE_CASE),
        Regex("""\s*\(feat\..*?\)""", RegexOption.IGNORE_CASE),
        Regex("""\s*\(ft\..*?\)""", RegexOption.IGNORE_CASE),
        Regex("""\s*feat\..*$""", RegexOption.IGNORE_CASE),
        Regex("""\s*ft\..*$""", RegexOption.IGNORE_CASE),
        Regex("""\s*\([^)]*\d{4}[^)]*\)""", RegexOption.IGNORE_CASE),
    )

    private val artistSeparators = listOf(" & ", " and ", ", ", " x ", " X ", " feat. ", " feat ", " ft. ", " ft ", " featuring ", " with ")

    private val variantTagRegex = Regex(
        """\((?:[^()]*\b(?:ver\.?|version|remix|acoustic|live|instrumental|extended|radio\s*edit|remaster(?:ed)?|clean|explicit|english|japanese|korean|chinese|mandarin|cantonese|spanish|thai|vietnamese|german|french|italian|portuguese|russian|hindi|indonesian|malay|arabic|filipino|tagalog)\b[^()]*)\)""" +
            """|\[(?:[^\[\]]*\b(?:ver\.?|version|remix|acoustic|live|instrumental|extended|radio\s*edit|remaster(?:ed)?|clean|explicit|english|japanese|korean|chinese|mandarin|cantonese|spanish|thai|vietnamese|german|french|italian|portuguese|russian|hindi|indonesian|malay|arabic|filipino|tagalog)\b[^\[\]]*)]""",
        RegexOption.IGNORE_CASE,
    )

    private fun cleanTitle(title: String): String {
        var cleaned = title.trim()
        for (pattern in titleCleanupPatterns) cleaned = cleaned.replace(pattern, "")
        return cleaned.trim()
    }

    private fun cleanArtist(artist: String): String {
        var cleaned = artist.trim()
        for (separator in artistSeparators) {
            if (cleaned.contains(separator, ignoreCase = true)) {
                cleaned = cleaned.split(separator, ignoreCase = true, limit = 2)[0]
                break
            }
        }
        return cleaned.trim()
    }

    // ---------------------------------------------------------------- búsqueda

    private suspend fun search(query: String): List<SearchResult> = runCatching {
        Timber.d("Searching Apple Music for: $query")
        searchWithToken(tokenManager.getToken(), query)
    }.getOrElse { e ->
        if (e is ClientRequestException && e.response.status.value == 401) {
            tokenManager.clearToken()
            return@getOrElse runCatching { searchWithToken(tokenManager.getToken(), query) }.getOrElse { e2 ->
                Timber.e(e2, "Search retry error: ${e2.message}")
                emptyList()
            }
        }
        Timber.e(e, "Search error: ${e.message}")
        emptyList()
    }

    private suspend fun searchWithToken(token: String, query: String): List<SearchResult> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val response = httpClient.get("$APPLE_MUSIC_API_BASE/search?term=$encodedQuery&types=songs&limit=25&l=en-US&platform=web&format[resources]=map&include[songs]=artists&extend=artistUrl") {
            header("Authorization", "Bearer $token")
            header("Origin", "https://music.apple.com")
            header("Referer", "https://music.apple.com/")
            header("User-Agent", APPLE_USER_AGENT)
            header("Accept", "application/json")
            header("Accept-Language", "en-US,en;q=0.5")
            header("x-apple-renewal", "true")
        }
        val text = response.bodyAsText()
        if (text.length > MAX_LYRICS_RESPONSE_CHARS) return emptyList()
        val body = try {
            appleJson.decodeFromString<AppleMusicSearchResponse>(text)
        } catch (e: Exception) {
            Timber.e(e, "Failed to parse Apple Music search response")
            return emptyList()
        }
        val songs = body.results.songs?.data ?: return emptyList()
        return songs.mapNotNull { songData ->
            val detail = body.resources?.songs?.get(songData.id) ?: return@mapNotNull null
            val attr = detail.attributes
            SearchResult(
                id = songData.id,
                trackName = attr.name,
                artistName = attr.artistName,
                albumName = attr.albumName,
                durationMs = attr.durationInMillis,
                artwork = attr.artwork?.url?.replace("{w}", "100")?.replace("{h}", "100")?.replace("{f}", "png"),
            )
        }
    }

    /** Busca con varias consultas, junta todos los resultados sin repetir y los ordena por puntuación. */
    private suspend fun searchScored(
        title: String,
        artist: String,
        durationMs: Long,
        album: String?,
    ): List<Pair<SearchResult, Double>> {
        val queries = buildList {
            val cleanTitle = cleanTitle(title)
            val cleanArtist = cleanArtist(artist)
            add("$cleanTitle $cleanArtist")
            add(cleanTitle)
            if (!album.isNullOrBlank()) add("$cleanTitle $cleanArtist $album")
        }
        val resultsById = LinkedHashMap<String, Pair<SearchResult, Double>>()
        for (query in queries) {
            for ((result, score) in scoreAndFilterResults(search(query), title, artist, durationMs, album)) {
                val existing = resultsById[result.id]
                if (existing == null || score > existing.second) resultsById[result.id] = result to score
            }
        }
        return resultsById.values.sortedByDescending { it.second }
    }

    suspend fun getLyrics(
        title: String,
        artist: String,
        duration: Int,
        album: String? = null,
    ): Result<String> = runCatching {
        val durationMs = if (duration > 0) duration * 1000L else 0L
        val scoredResults = searchScored(title, artist, durationMs, album)
        if (scoredResults.isEmpty()) throw IllegalStateException("No tracks found on Paxsenix")

        var bestLyrics: String? = null
        var bestQuality = 0
        var bestScore = Double.NEGATIVE_INFINITY
        val topScore = scoredResults.first().second

        for ((result, score) in scoredResults.take(10)) {
            if (topScore - score > 15.0 || (bestLyrics != null && score < bestScore)) break
            val lyrics = fetchLyricsForTrack(result.id).getOrNull()?.takeIf { it.isNotEmpty() } ?: continue
            val quality = getQuality(lyrics)
            if (bestLyrics == null || score > bestScore || (score == bestScore && quality > bestQuality)) {
                bestQuality = quality
                bestScore = score
                bestLyrics = lyrics
            }
            if (bestQuality == 3 && bestScore == topScore) break
        }
        bestLyrics ?: throw IllegalStateException("No lyrics available from Paxsenix")
    }

    suspend fun getAllLyrics(
        title: String,
        artist: String,
        duration: Int,
        album: String? = null,
        callback: (String) -> Unit,
    ) {
        val durationMs = if (duration > 0) duration * 1000L else 0L
        val scoredResults = searchScored(title, artist, durationMs, album)
        val collected = mutableListOf<Pair<String, Int>>()
        for ((result, _) in scoredResults.take(5)) {
            val lrc = fetchLyricsForTrack(result.id).getOrNull()?.takeIf { it.isNotEmpty() } ?: continue
            val quality = getQuality(lrc)
            collected.add(lrc to quality)
            if (quality == 3) break
        }
        collected.sortedByDescending { it.second }.forEach { (lrc, _) -> callback(lrc) }
    }

    private fun getQuality(lrc: String): Int {
        if (lrc.isBlank()) return 0
        val hasWordTimings = (lrc.contains("<") && lrc.contains(">") && (lrc.contains("|") || lrc.contains(":"))) ||
            lrc.contains(Regex("<\\d{1,2}:\\d{2}\\.\\d{2,3}>"))
        if (hasWordTimings) return 3
        val hasLineTimings = lrc.contains(Regex("\\[\\d\\d:\\d\\d\\.\\d{2,3}\\]")) ||
            lrc.contains(Regex("^\\[bg:.*\\]", RegexOption.MULTILINE))
        if (hasLineTimings) return 2
        return 1
    }

    // ------------------------------------------------------------ puntuación

    private fun scoreAndFilterResults(
        results: List<SearchResult>,
        title: String,
        artist: String,
        durationMs: Long,
        album: String?,
    ): List<Pair<SearchResult, Double>> {
        val targetTitle = normalizeForMatch(stripVariantTags(title))
        val targetVariantTags = extractVariantTags(title)
        val targetArtists = splitArtistTokens(artist)
        val targetAlbum = album?.takeIf { it.isNotBlank() }?.let { normalizeForMatch(it) }
        val targetIsMixed = mixedRegex.containsMatchIn(title.lowercase())
        val targetIsRemix = remixRegex.containsMatchIn(title.lowercase())

        return results
            .map { result ->
                var score = 0.0

                if (durationMs > 0 && result.durationMs != null) {
                    val delta = abs(result.durationMs - durationMs)
                    score += when {
                        delta <= 2_000 -> 30.0
                        delta <= 5_000 -> 20.0
                        delta <= 10_000 -> 5.0
                        else -> -30.0
                    }
                }

                val resultTitle = normalizeForMatch(stripVariantTags(result.displayName))
                val titleSim = similarity(targetTitle, resultTitle)
                score += when {
                    resultTitle == targetTitle -> 90.0
                    resultTitle.contains(targetTitle) || targetTitle.contains(resultTitle) -> 60.0 + titleSim * 20.0
                    titleSim >= 0.75 -> titleSim * 70.0
                    else -> titleSim * 30.0
                }

                val resultVariantTags = extractVariantTags(result.displayName)
                score += when {
                    targetVariantTags.isEmpty() && resultVariantTags.isEmpty() -> 0.0
                    targetVariantTags.isNotEmpty() && resultVariantTags.isNotEmpty() -> {
                        val overlap = targetVariantTags.any { t -> resultVariantTags.any { r -> tokensMatch(t, r) } }
                        if (overlap) 25.0 else -70.0
                    }
                    targetVariantTags.isNotEmpty() -> -50.0
                    else -> -35.0
                }

                val resultTitleLower = result.displayName.lowercase()
                if (mixedRegex.containsMatchIn(resultTitleLower) && !targetIsMixed) score -= 60.0
                if (remixRegex.containsMatchIn(resultTitleLower) && !targetIsRemix) score -= 40.0

                val resultArtists = splitArtistTokens(result.displayArtist)
                val artistOverlap = artistMatchScore(targetArtists, resultArtists)
                score += artistOverlap * 60.0
                if (artistOverlap == 0.0) score -= 40.0

                if (targetAlbum != null && !result.albumName.isNullOrBlank()) {
                    if (similarity(targetAlbum, normalizeForMatch(result.albumName)) >= 0.8) score += 15.0
                }

                result to score
            }.filter { it.second >= 40.0 }
            .sortedByDescending { it.second }
            .take(10)
    }

    private fun extractVariantTags(raw: String): Set<String> =
        variantTagRegex.findAll(raw)
            .map { normalizeForMatch(it.value.trim('(', ')', '[', ']')) }
            .filter { it.isNotEmpty() }
            .toSet()

    private fun stripVariantTags(raw: String): String = raw.replace(variantTagRegex, " ")

    private fun normalizeForMatch(input: String): String {
        var text = input.take(MAX_MATCH_INPUT_CHARS).lowercase()
        text = text.replace(bracketRegex, " ")
        text = text.replace(featuredRegex, " ")
        text = text.replace(nonAlphanumericRegex, " ")
        return text.trim().replace(whitespaceRegex, " ")
    }

    private fun splitArtistTokens(artist: String): List<String> =
        artist.split(artistSplitRegex).map { normalizeForMatch(it) }.filter { it.isNotEmpty() }

    private fun tokensMatch(a: String, b: String): Boolean =
        a == b || a.contains(b) || b.contains(a) || similarity(a, b) >= 0.82

    private fun artistMatchScore(target: List<String>, result: List<String>): Double {
        if (target.isEmpty() || result.isEmpty()) return 0.0
        val matchedTarget = target.count { t -> result.any { r -> tokensMatch(t, r) } }
        val matchedResult = result.count { r -> target.any { t -> tokensMatch(t, r) } }
        return maxOf(matchedTarget.toDouble() / target.size, matchedResult.toDouble() / result.size)
    }

    private fun similarity(a: String, b: String): Double {
        if (a.isEmpty() && b.isEmpty()) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0
        if (a == b) return 1.0
        return 1.0 - levenshtein(a, b).toDouble() / maxOf(a.length, b.length)
    }

    private fun levenshtein(a: String, b: String): Int {
        val (longer, shorter) = if (a.length >= b.length) a to b else b to a
        var previous = IntArray(shorter.length + 1) { it }
        var current = IntArray(shorter.length + 1)
        for (i in longer.indices) {
            current[0] = i + 1
            for (j in shorter.indices) {
                current[j + 1] = if (longer[i] == shorter[j]) {
                    previous[j]
                } else {
                    1 + minOf(previous[j], previous[j + 1], current[j])
                }
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[shorter.length]
    }

    // ---------------------------------------------------------------- letras

    /** Pide el texto con límite de tamaño; 404/204 = sin letra; 503 se reintenta un par de veces. */
    private suspend fun fetchText(path: String, id: String): String? {
        for (attempt in 0..2) {
            val response: HttpResponse = httpClient.get(path) {
                parameter("id", id)
                expectSuccess = false
            }
            if (response.status == HttpStatusCode.ServiceUnavailable && attempt < 2) {
                delay(500L shl attempt)
                continue
            }
            if (response.status == HttpStatusCode.NotFound || response.status == HttpStatusCode.NoContent) return null
            if (!response.status.isSuccess()) throw IllegalStateException("Paxsenix HTTP ${response.status.value}")
            val text = response.bodyAsText()
            return text.takeIf { it.length <= MAX_LYRICS_RESPONSE_CHARS }
        }
        return null
    }

    private suspend fun fetchLyricsForTrack(id: String): Result<String> = runCatching {
        val json = fetchText("/apple-music/lyrics", id) ?: throw IllegalStateException("No lyrics found")
        responseToAppFormat(appleJson.decodeFromString<LyricsResponse>(json))
            ?: throw IllegalStateException("No lyrics found")
    }

    /** Mismo orden de preferencia que Metrolist: TTML > ELRC varias voces > ELRC > texto plano > content. */
    private fun responseToAppFormat(response: LyricsResponse): String? {
        if (response.type.equals("None", ignoreCase = true)) {
            response.plain?.takeIf { it.isNotBlank() }?.let { return it }
        }
        response.ttmlContent?.takeIf { it.isNotBlank() }?.let { ttml ->
            convertTTMLToAppFormat(ttml).takeIf { it.isNotEmpty() }?.let { return it }
        }
        response.elrcMultiPerson?.takeIf { it.isNotBlank() }?.let { elrc ->
            (enhancedLrcToAppFormat(elrc) ?: elrc).let { return it }
        }
        response.elrc?.takeIf { it.isNotBlank() }?.let { return it }
        response.plain?.takeIf { it.isNotBlank() }?.let { return it }
        return contentToAppFormat(response.content)
    }

    private fun convertTTMLToAppFormat(ttml: String): String = try {
        TTMLParser.toLRC(TTMLParser.parseTTML(ttml))
    } catch (e: Exception) {
        Timber.e(e, "TTML conversion failed: ${e.message}")
        ""
    }

    private data class RawWord(val text: String, val startMs: Long, val endMs: Long, val hasSpace: Boolean)

    private fun List<LyricText>.toRawWords(): List<RawWord> = mapNotNull { word ->
        val raw = word.text.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val hasSpace = raw.endsWith(' ')
        RawWord(if (hasSpace) raw.dropLast(1) else raw, word.timestamp, word.endtime, hasSpace)
    }

    /** Une las sílabas sin espacio entre ellas en una palabra (la app trabaja con palabras). */
    private fun List<RawWord>.toParsedWords(): List<ParsedWord> {
        if (isEmpty()) return emptyList()
        val out = mutableListOf<ParsedWord>()
        var text = StringBuilder(first().text)
        var start = first().startMs
        var end = first().endMs
        for (i in 1 until size) {
            val prev = this[i - 1]
            val word = this[i]
            if (prev.hasSpace && !prev.text.endsWith('-')) {
                out.add(ParsedWord(text.toString().trim(), start / 1000.0, end / 1000.0))
                text = StringBuilder(word.text)
                start = word.startMs
            } else {
                text.append(word.text)
            }
            end = word.endMs
        }
        out.add(ParsedWord(text.toString().trim(), start / 1000.0, end / 1000.0))
        return out.filter { it.text.isNotEmpty() }
    }

    private fun bgLine(words: List<RawWord>): ParsedLine? {
        val parsed = words.toParsedWords()
        if (parsed.isEmpty()) return null
        return ParsedLine(parsed.joinToString(" ") { it.text }, parsed.first().startTime, parsed, null, true)
    }

    private fun contentToAppFormat(content: List<LyricsContent>): String? {
        if (content.isEmpty()) return null
        val lines = mutableListOf<ParsedLine>()
        var index = 0
        while (index < content.size) {
            val line = content[index]
            val next = content.getOrNull(index + 1)
            val words = line.text.toRawWords()
            val lineEnd = line.endtime.takeIf { it > line.timestamp }
                ?: (line.timestamp + line.duration).takeIf { it > line.timestamp }
                ?: line.timestamp
            val nextWords = next?.text?.toRawWords().orEmpty()
            val nextEnd = next?.let {
                it.endtime.takeIf { end -> end > it.timestamp }
                    ?: (it.timestamp + it.duration).takeIf { end -> end > it.timestamp }
                    ?: it.timestamp
            } ?: 0L
            // La línea siguiente es voz de fondo de esta si Apple la marca así o si se solapa con ella.
            val nextIsInlineBackground = next != null &&
                !line.background &&
                line.backgroundText.isEmpty() &&
                words.isNotEmpty() &&
                nextWords.isNotEmpty() &&
                (
                    next.background ||
                        (
                            next.backgroundText.isEmpty() &&
                                line.structure == next.structure &&
                                line.oppositeTurn == next.oppositeTurn &&
                                next.timestamp < lineEnd &&
                                nextEnd > lineEnd
                            )
                    )
            val bgWords = if (nextIsInlineBackground) nextWords else line.backgroundText.toRawWords()
            val parsed = words.toParsedWords()
            val text = parsed.joinToString(" ") { it.text }
            val agent = if (line.oppositeTurn) "v2" else "v1"
            if (text.isNotBlank()) {
                lines.add(
                    ParsedLine(
                        text = text,
                        startTime = line.timestamp / 1000.0,
                        words = parsed,
                        agent = agent,
                        backgroundLines = listOfNotNull(bgLine(bgWords)),
                    ),
                )
            }
            index += if (nextIsInlineBackground) 2 else 1
        }
        return lines.takeIf { it.isNotEmpty() }?.let { TTMLParser.toLRC(it) }
    }

    // ------------------------------------------------- ELRC (varias voces)

    private val elrcLineRegex = Regex("""^\[(\d{1,2}:\d{2}[.:]\d{2,3})](?:(v\d+):\s*)?(.*)$""")
    private val elrcBgRegex = Regex("""^\[bg:\s*(.*)]$""")
    private val elrcStampRegex = Regex("""<(\d{1,2}:\d{2}[.:]\d{2,3})>""")
    private val elrcPackedWordRegex = Regex("""<([^:|<>]+):([0-9.]+):([0-9.]+)>""")

    private fun tsToMs(ts: String): Long {
        val normalized = ts.trim()
        val colonIdx = normalized.indexOf(':')
        if (colonIdx < 0) return 0L
        val min = normalized.substring(0, colonIdx).toLongOrNull() ?: 0L
        val rest = normalized.substring(colonIdx + 1)
        val msSep = maxOf(rest.lastIndexOf('.'), rest.lastIndexOf(':'))
        val sec = if (msSep > 0) rest.substring(0, msSep).toLongOrNull() ?: 0L else rest.toLongOrNull() ?: 0L
        val msStr = if (msSep >= 0 && msSep + 1 < rest.length) rest.substring(msSep + 1) else "0"
        val ms = msStr.toLongOrNull() ?: 0L
        val msNorm = when (msStr.length) {
            1 -> ms * 100L
            2 -> ms * 10L
            else -> ms
        }
        return min * 60_000L + sec * 1_000L + msNorm
    }

    private fun parseElrcWords(content: String, nextStartMs: Long?): List<RawWord> {
        val stamps = elrcStampRegex.findAll(content).toList()
        if (stamps.isNotEmpty()) {
            return stamps.mapIndexedNotNull { index, match ->
                val start = tsToMs(match.groupValues[1])
                val segmentStart = match.range.last + 1
                val segmentEnd = stamps.getOrNull(index + 1)?.range?.first ?: content.length
                val raw = content.substring(segmentStart, segmentEnd)
                val hasSpace = raw.endsWith(' ')
                val text = if (hasSpace) raw.dropLast(1) else raw
                if (text.isEmpty() && !hasSpace) return@mapIndexedNotNull null
                val end = stamps.getOrNull(index + 1)?.groupValues?.get(1)?.let { tsToMs(it) }
                    ?: nextStartMs?.takeIf { it > start } ?: (start + 500L)
                RawWord(text, start, end, hasSpace)
            }
        }
        return elrcPackedWordRegex.findAll(content).mapNotNull { match ->
            val text = match.groupValues[1]
            val start = match.groupValues[2].toDoubleOrNull()?.times(1000)?.toLong() ?: return@mapNotNull null
            val end = match.groupValues[3].toDoubleOrNull()?.times(1000)?.toLong() ?: return@mapNotNull null
            val hasSpace = text.endsWith(' ')
            RawWord(if (hasSpace) text.dropLast(1) else text, start, end, hasSpace)
        }.toList()
    }

    private fun stripWordMarkers(content: String): String =
        content.replace(Regex("""<\d{1,2}:\d{2}[.:]\d{2,3}>"""), "").replace(Regex("""<[^:|<>]+:[0-9.]+:[0-9.]+>"""), "")

    /** Convierte ELRC con varias voces y [bg:] al formato de la app. Devuelve null si no entiende nada. */
    private fun enhancedLrcToAppFormat(elrc: String): String? {
        val raw = elrc.lines().filter { it.isNotBlank() }
        if (raw.isEmpty()) return null
        val lines = mutableListOf<ParsedLine>()
        var pendingLine: MatchResult? = null
        var pendingBackground: String? = null

        fun flush(nextStartMs: Long? = null) {
            val match = pendingLine ?: return
            val start = tsToMs(match.groupValues[1])
            val agent = match.groupValues[2].takeIf { it.isNotBlank() }
            val content = match.groupValues[3]
            val words = parseElrcWords(content, nextStartMs).toParsedWords()
            val bg = pendingBackground?.let { bgLine(parseElrcWords(it, nextStartMs)) }
            val text = stripWordMarkers(content).trim()
            if (words.isNotEmpty() || text.isNotBlank()) {
                lines.add(
                    ParsedLine(
                        text = if (words.isNotEmpty()) words.joinToString(" ") { it.text } else text,
                        startTime = start / 1000.0,
                        words = words,
                        agent = agent,
                        backgroundLines = listOfNotNull(bg),
                    ),
                )
            }
            pendingLine = null
            pendingBackground = null
        }

        raw.forEach { rawLine ->
            val trimmed = rawLine.trim()
            val bgMatch = elrcBgRegex.matchEntire(trimmed)
            if (bgMatch != null) {
                pendingBackground = bgMatch.groupValues[1]
            } else {
                val match = elrcLineRegex.matchEntire(trimmed) ?: return@forEach
                flush(tsToMs(match.groupValues[1]))
                pendingLine = match
            }
        }
        flush()
        return lines.takeIf { it.isNotEmpty() }?.let { TTMLParser.toLRC(it) }
    }

    // ----------------------------------------------------------------- token

    private class AppleTokenManager(private val httpClient: HttpClient) {
        private var cachedToken: String? = null
        private val mutex = Mutex()

        suspend fun getToken(): String = mutex.withLock {
            cachedToken?.let { return it }

            try {
                val mainPageBody = httpClient.get("https://beta.music.apple.com").bodyAsText()
                if (mainPageBody.length > MAX_LYRICS_RESPONSE_CHARS) throw Exception("Apple Music page is too large")

                val indexJsMatch = Regex("""/assets/index~[^/]+\.js""").find(mainPageBody)
                    ?: throw Exception("Could not find index JS URL")

                val indexJsBody = httpClient.get("https://beta.music.apple.com${indexJsMatch.value}").bodyAsText()
                if (indexJsBody.length > MAX_APPLE_SCRIPT_CHARS) throw Exception("Apple Music script is too large")

                val tokenMatch = Regex("""eyJ[A-Za-z0-9\-_=]+\.[A-Za-z0-9\-_=]+\.[A-Za-z0-9\-_=]+""").find(indexJsBody)
                    ?: throw Exception("Could not find token")

                val token = tokenMatch.value
                cachedToken = token
                Timber.d("Fetched new Apple Music token")
                return token
            } catch (e: Exception) {
                Timber.e(e, "Error fetching Apple Music token")
                throw Exception("Error fetching Apple Music token: ${e.message}", e)
            }
        }

        fun clearToken() {
            cachedToken = null
            Timber.d("Cleared cached Apple Music token")
        }
    }
}
