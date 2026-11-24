package com.music.youlyplus

import com.music.youlyplus.models.LyricLine
import com.music.youlyplus.models.LyricsResponse
import com.music.youlyplus.models.Syllable
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicReference

/**
 * Cliente de LyricsPlus (el servidor que usa la extensión YouLyPlus).
 * Pregunta a varios servidores a la vez y se queda con el primero que responde bien.
 */
object YouLyPlus {

    private val SERVERS = listOf(
        "https://lyricsplus.prjktla.my.id",
        "https://lyricsplus.atomix.one",
        "https://lyricsplus.binimum.org",
        "https://lyricsplus.prjktla.workers.dev",
        "https://lyricsplus-seven.vercel.app",
        "https://lyrics-plus-backend.vercel.app",
    )

    /** Fuentes en orden de preferencia (Apple primero: letra limpia). */
    private const val SOURCES = "apple,lyricsplus,spotify"

    private val lastGoodServer = AtomicReference<String?>(null)

    private fun serversInOrder(): List<String> {
        val good = lastGoodServer.get() ?: return SERVERS
        return listOf(good) + SERVERS.filterNot { it == good }
    }

    private val client by lazy {
        HttpClient(OkHttp) {
            install(HttpTimeout) {
                connectTimeoutMillis = 3_000
                requestTimeoutMillis = 5_000
            }
            install(ContentNegotiation) {
                json(Json { isLenient = true; ignoreUnknownKeys = true })
            }
            expectSuccess = true
        }
    }

    private class Query(
        val title: String,
        val artist: String,
        val duration: Int,
        val album: String?,
        val id: String?,
        val isrc: String?,
    )

    /** Primera letra válida de cualquier servidor. */
    suspend fun getLyrics(
        title: String,
        artist: String,
        duration: Int,
        album: String? = null,
        id: String? = null,
        isrc: String? = null,
    ): Result<String> = runCatching {
        val query = Query(title, artist, duration, album, id, isrc)
        val answers = Channel<Pair<String, String?>>(Channel.UNLIMITED)
        val found = coroutineScope<String?> {
            val jobs = serversInOrder().map { server ->
                launch(Dispatchers.IO) { answers.send(server to ask(server, query)) }
            }
            repeat(jobs.size) {
                val (server, lrc) = answers.receive()
                // Una letra con todos los tiempos en 00:00 no sirve: se sigue con los demás servidores.
                if (!lrc.isNullOrBlank() && lrc.hasRealTiming()) {
                    lastGoodServer.set(server)
                    jobs.forEach { it.cancel() }
                    return@coroutineScope lrc
                }
            }
            null
        }
        found ?: throw IllegalStateException("YouLyPlus: ningún servidor tiene esta letra sincronizada")
    }

    /** Entrega cada letra distinta según va llegando de los servidores. */
    suspend fun getAllLyrics(
        title: String,
        artist: String,
        duration: Int,
        album: String? = null,
        id: String? = null,
        isrc: String? = null,
        callback: (String) -> Unit,
    ) {
        val query = Query(title, artist, duration, album, id, isrc)
        val answers = Channel<String?>(Channel.UNLIMITED)
        val seen = HashSet<String>()
        coroutineScope {
            val servers = serversInOrder()
            servers.forEach { server ->
                launch(Dispatchers.IO) { answers.send(ask(server, query)) }
            }
            repeat(servers.size) {
                val lrc = answers.receive()
                if (!lrc.isNullOrBlank() && seen.add(lrc)) callback(lrc)
            }
        }
    }

    /** Pide la letra a un servidor (con las fuentes preferidas; si no las acepta, sin ellas). */
    private suspend fun ask(server: String, q: Query): String? = coroutineScope {
        // Las dos peticiones (con y sin fuentes preferidas) salen a la vez: antes la segunda
        // esperaba a que la primera fallara y el límite de tiempo la cortaba antes de empezar.
        val preferred = async(Dispatchers.IO) { request(server, q, SOURCES) }
        val plain = async(Dispatchers.IO) { request(server, q, null) }
        val first = preferred.await()?.toLrc()
        if (first != null && first.hasRealTiming()) {
            plain.cancel()
            first
        } else {
            val second = plain.await()?.toLrc()
            if (second != null && second.hasRealTiming()) second else first ?: second
        }
    }

    /** True si la letra tiene al menos dos tiempos de línea distintos (no todo en 00:00). */
    private fun String.hasRealTiming(): Boolean {
        val starts = LINE_START.findAll(this).map {
            val (m, sec, frac) = it.destructured
            m.toLong() * 60_000L + sec.toLong() * 1000L + (frac.padEnd(3, '0').take(3).toLongOrNull() ?: 0L)
        }.toSet()
        return starts.size >= 2
    }

    private val LINE_START = Regex("""^\[(\d+):(\d{2})(?:[.:](\d+))?]""", RegexOption.MULTILINE)

    private suspend fun request(server: String, q: Query, sources: String?): LyricsResponse? =
        runCatching {
            client.get(server.trimEnd('/') + "/v2/lyrics/get") {
                parameter("title", q.title)
                parameter("artist", q.artist)
                parameter("duration", q.duration)
                q.album?.let { parameter("album", it) }
                q.id?.let { parameter("id", it) }
                q.isrc?.let { parameter("isrc", it) }
                sources?.let { parameter("source", it) }
            }.body<LyricsResponse>()
        }.getOrNull()

    // ---- Conversión a LRC (con tiempo por sílaba si el servidor lo trae) ----

    private fun LyricsResponse.toLrc(): String? {
        syncedLyrics?.takeIf { it.isNotBlank() }?.let { return it }
        val fromLines = lyrics?.takeIf { it.isNotEmpty() }?.let { linesToLrc(it) }
        return fromLines ?: plainLyrics?.takeIf { it.isNotBlank() }
    }

    private fun linesToLrc(lines: List<LyricLine>): String? {
        val out = ArrayList<String>()
        for (line in lines) {
            val start = line.time ?: 0L
            val syllables = line.syllabus.orEmpty()
            if (syllables.isEmpty()) {
                out.add(stamp(start, '[', ']') + (line.text ?: ""))
                continue
            }
            val main = syllables.filter { it.isBackground != true }
            if (main.isNotEmpty()) {
                val lineStart = if (syllables.first().isBackground != true) start else (main.first().time ?: start)
                syllableLine(lineStart, main, background = false, fallbackEnd = start + (line.duration ?: 0L))
                    ?.let { out.add(it) }
            }
            // Cada tramo seguido de voz de fondo va en su propia línea {bg}.
            var run = ArrayList<Syllable>()
            for (s in syllables) {
                if (s.isBackground == true) {
                    run.add(s)
                } else if (run.isNotEmpty()) {
                    syllableLine(run.first().time ?: start, run, true, 0L)?.let { out.add(it) }
                    run = ArrayList()
                }
            }
            if (run.isNotEmpty()) syllableLine(run.first().time ?: start, run, true, 0L)?.let { out.add(it) }
        }
        return out.joinToString("\n").takeIf { it.isNotBlank() }
    }

    private fun syllableLine(start: Long, parts: List<Syllable>, background: Boolean, fallbackEnd: Long): String? {
        val texts = parts.map { p ->
            val t = p.text.orEmpty()
            if (background) t.replace("(", "").replace(")", "") else t
        }
        if (texts.all { it.isBlank() }) return null

        val sb = StringBuilder(stamp(start, '[', ']'))
        if (background) sb.append("{bg}")
        parts.forEachIndexed { i, p -> sb.append(stamp(p.time ?: 0L, '<', '>')).append(texts[i]) }

        // Marca final: sin ella la última palabra dura hasta la línea siguiente.
        val lastStart = parts.last().time ?: start
        val syllableEnd = parts.maxOf { (it.time ?: 0L) + (it.duration ?: 0L) }
        val end = if (syllableEnd > lastStart) syllableEnd else fallbackEnd
        if (end > lastStart) sb.append(stamp(end, '<', '>'))
        return sb.toString().trimEnd()
    }

    private fun stamp(ms: Long, open: Char, close: Char): String {
        val total = ms / 1000
        return "%c%02d:%02d.%03d%c".format(open, total / 60, total % 60, ms % 1000, close)
    }
}
