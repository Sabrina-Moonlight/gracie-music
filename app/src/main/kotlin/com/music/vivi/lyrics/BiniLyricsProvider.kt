/**
 * vivimusic Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.music.vivi.lyrics

import android.content.Context
import com.music.vivi.constants.EnableBiniLyricsKey
import com.music.vivi.utils.dataStore
import com.music.vivi.utils.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Lyrics provider backed by the Binimum API.
 *
 * Endpoint: `GET https://lyrics-api.binimum.org/getLyrics?track=...&artist=...`
 * Returns a JSON with a TTML URL, which is then parsed into RichSync LRC format
 * (same TTML parser as [LrcRedLyricsProvider], since the files come from lrc.red).
 */
object BiniLyricsProvider : LyricsProvider {

    override val name = "Bini Lyrics"

    private const val BASE_URL = "https://lyrics-api.binimum.org"

    /** Máxima diferencia de duración (segundos) para dar un resultado por bueno. */
    private const val MAX_DURATION_DIFF_SECONDS = 3.0

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
    }

    override fun isEnabled(context: Context): Boolean =
        context.dataStore[EnableBiniLyricsKey] ?: true

    override suspend fun getLyrics(
        id: String,
        title: String,
        artist: String,
        duration: Int,
        album: String?,
    ): Result<String> {
        val first = fetchLyrics(title, artist, duration)
        if (first.isSuccess) return first
        // "(Taylor's Version)" y similares: se vuelve a buscar sin la etiqueta
        val stripped = TitleVersionTag.strip(title)
        return if (stripped.isNotBlank() && stripped != title) fetchLyrics(stripped, artist, duration) else first
    }

    private suspend fun fetchLyrics(
        title: String,
        artist: String,
        duration: Int,
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val url = "$BASE_URL/getLyrics".toHttpUrlOrNull()?.newBuilder()
                ?.addQueryParameter("track", title)
                ?.addQueryParameter("artist", artist)
                ?.build()
                ?.toString() ?: return@withContext Result.failure(Exception("Invalid URL"))

            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                response.close()
                return@withContext Result.failure(Exception("Bini Lyrics API returned ${response.code}"))
            }

            val body = response.body?.string() ?: run {
                response.close()
                return@withContext Result.failure(Exception("Empty API response"))
            }
            response.close()

            val json = JSONObject(body)
            val results = json.optJSONArray("results")
            if (results == null || results.length() == 0) {
                return@withContext Result.failure(Exception("Bini Lyrics: no results found"))
            }

            // Elige el resultado cuya duración más se parece a la de la canción que suena
            // (como hace LrcRed): evita coger una versión en vivo o un remix con otros tiempos.
            val candidates = (0 until results.length())
                .map { results.getJSONObject(it) }
                .filter { it.optString("lyricsUrl").isNotBlank() }
            if (candidates.isEmpty()) {
                return@withContext Result.failure(Exception("Bini Lyrics: no lyricsUrl in result"))
            }

            fun distance(result: JSONObject): Double {
                val resultDuration = result.optDouble("duration", 0.0)
                return if (duration > 0 && resultDuration > 0.0) abs(resultDuration - duration) else 0.0
            }

            val best = candidates
                .filter { distance(it) <= MAX_DURATION_DIFF_SECONDS }
                .minByOrNull { distance(it) }
                ?: return@withContext Result.failure(Exception("Bini Lyrics: no result matches the song duration"))
            val lyricsUrl = best.getString("lyricsUrl")

            // Fetch the TTML
            val ttmlReq = Request.Builder().url(lyricsUrl).build()
            val ttmlRes = client.newCall(ttmlReq).execute()
            if (!ttmlRes.isSuccessful) {
                ttmlRes.close()
                return@withContext Result.failure(Exception("Failed to fetch TTML"))
            }

            val ttmlBody = ttmlRes.body?.string() ?: run {
                ttmlRes.close()
                return@withContext Result.failure(Exception("Empty TTML response"))
            }
            ttmlRes.close()

            val lrc = ttmlToLrc(ttmlBody)
            if (lrc.isNotBlank()) {
                Result.success(lrc)
            } else {
                Result.failure(Exception("Failed to parse TTML or empty lyrics"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Parsing Logic
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Convierte el TTML (Apple Music) a LRC con tiempos por palabra.
     * Usa el mismo lector que LrcRed: junta bien las sílabas, separa las voces de fondo
     * en su propia línea {bg}, marca el final de cada línea y respeta los cantantes.
     * En las voces de fondo quita los paréntesis (como hace YouLyPlus).
     * Visible for testing.
     */
    internal fun ttmlToLrc(ttml: String): String =
        LrcRedLyricsProvider.parseTtmlToLrc(ttml)
            .lineSequence()
            .joinToString("\n") { line ->
                if (line.contains("]{bg}")) line.replace("(", "").replace(")", "") else line
            }
}
