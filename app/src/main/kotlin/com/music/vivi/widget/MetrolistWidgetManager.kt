/**
 * vivimusic Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.music.vivi.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.os.Bundle
import android.widget.RemoteViews
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.request.crossfade
import coil3.toBitmap
import com.music.vivi.MainActivity
import com.music.vivi.R
import com.music.vivi.db.MusicDatabase
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class vivimusicWidgetManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: MusicDatabase
) {
    private val imageLoader by lazy {
        ImageLoader.Builder(context)
            .crossfade(false)
            .build()
    }

    // Cache for album art to avoid reloading
    private var cachedArtworkUri: String? = null
    private var cachedAlbumArt: Bitmap? = null
    private var cachedCircularAlbumArt: Bitmap? = null

    suspend fun updateWidgets(
        title: String,
        artist: String,
        artworkUri: String?,
        isPlaying: Boolean,
        isLiked: Boolean,
        duration: Long = 0,
        currentPosition: Long = 0,
        repeatMode: Int = 0
    ) {
        val appWidgetManager = AppWidgetManager.getInstance(context)

        // Use cached album art if URI hasn't changed, otherwise load new one
        val albumArt: Bitmap?
        val circularAlbumArt: Bitmap?
        
        if (artworkUri != null && artworkUri == cachedArtworkUri && cachedAlbumArt != null) {
            albumArt = cachedAlbumArt
            circularAlbumArt = cachedCircularAlbumArt
        } else {
            albumArt = artworkUri?.let { loadAlbumArt(it, 600) }
            circularAlbumArt = albumArt?.let { getCircularBitmap(it) }
            // Update cache
            cachedArtworkUri = artworkUri
            cachedAlbumArt = albumArt
            cachedCircularAlbumArt = circularAlbumArt
        }

        // Update main music player widgets
        val componentName = ComponentName(context, MusicWidgetReceiver::class.java)
        val widgetIds = appWidgetManager.getAppWidgetIds(componentName)
        if (widgetIds.isNotEmpty()) {
            widgetIds.forEach { widgetId ->
                val options = appWidgetManager.getAppWidgetOptions(widgetId)
                val views = createRemoteViewsForSize(
                    options,
                    title,
                    artist,
                    albumArt,
                    isPlaying,
                    isLiked,
                    duration,
                    currentPosition,
                    repeatMode
                )
                appWidgetManager.updateAppWidget(widgetId, views)
            }
        }

        // Update turntable widgets
        val turntableComponentName = ComponentName(context, TurntableWidgetReceiver::class.java)
        val turntableWidgetIds = appWidgetManager.getAppWidgetIds(turntableComponentName)
        if (turntableWidgetIds.isNotEmpty()) {
            val turntableViews = createTurntableRemoteViews(
                circularAlbumArt,
                isPlaying,
                isLiked
            )
            turntableWidgetIds.forEach { widgetId ->
                appWidgetManager.updateAppWidget(widgetId, turntableViews)
            }
        }
    }

    private fun createRemoteViewsForSize(
        options: Bundle,
        title: String,
        artist: String,
        albumArt: Bitmap?,
        isPlaying: Boolean,
        isLiked: Boolean,
        duration: Long,
        currentPosition: Long,
        repeatMode: Int
    ): RemoteViews {
        val minWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
        val minHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)

        // Determine widget size category
        // 2x2: approximately 110dp x 110dp (compact square)
        // 4x1: approximately 250dp x 40dp (wide single row)
        // Full: approximately 250dp x 110dp (default)
        return when {
            minWidth < 180 && minHeight < 100 -> {
                // 2x2 Compact - Only play button with album art
                createCompactSquareRemoteViews(albumArt, isPlaying)
            }
            minWidth >= 180 && minHeight < 100 -> {
                // 4x1 Wide - Single row with album art, song info, like and play buttons
                createCompactWideRemoteViews(title, artist, albumArt, isPlaying, isLiked)
            }
            else -> {
                // Full layout
                createRemoteViews(title, artist, albumArt, isPlaying, isLiked, duration, currentPosition, minWidth, minHeight, repeatMode)
            }
        }
    }

    private fun createRemoteViews(
        title: String,
        artist: String,
        albumArt: Bitmap?,
        isPlaying: Boolean,
        isLiked: Boolean,
        duration: Long = 0,
        currentPosition: Long = 0,
        widthDp: Int = 250,
        heightDp: Int = 110,
        repeatMode: Int = 0
    ): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_music_player)

        // Set song info
        views.setTextViewText(R.id.widget_song_title, title)
        views.setTextViewText(R.id.widget_artist_name, artist)

        // Portada de fondo (estilo notificación)
        views.setImageViewBitmap(
            R.id.widget_background_art,
            buildNotificationStyleBackground(albumArt, widthDp, heightDp)
        )

        // Barra de progreso con onda y círculo, color claro sacado de la carátula (como en la notificación)
        val accent = pickAccentColor(albumArt)
        val fraction = if (duration > 0) (currentPosition.toFloat() / duration.toFloat()).coerceIn(0f, 1f) else 0f
        // Android 12+: la ola la anima el propio launcher (fluida): 12 imágenes de la ola en fases distintas
        // que se mezclan con un fundido continuo. Cada imagen ya lleva el inicio y el final suaves.
        // En pausa, o antes de Android 12, la ola se dibuja por código dentro de la imagen de la barra.
        val trackDp = (widthDp - 36).coerceAtLeast(120)
        val waveWidthDp = (trackDp - 14).coerceAtLeast(1) * fraction
        val waveByLauncher = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S &&
            isPlaying && waveWidthDp >= 4f
        views.setImageViewBitmap(
            R.id.widget_progress_track,
            drawProgressBar(widthDp, fraction, accent, drawWave = !waveByLauncher)
        )
        if (waveByLauncher) {
            views.setViewLayoutWidth(
                R.id.widget_wave_clip,
                waveWidthDp,
                android.util.TypedValue.COMPLEX_UNIT_DIP
            )
            for (i in waveFrameIds.indices) {
                views.setImageViewBitmap(waveFrameIds[i], drawWaveFrame(waveWidthDp, accent, i))
            }
            views.setViewVisibility(R.id.widget_wave_clip, android.view.View.VISIBLE)
        } else {
            views.setViewVisibility(R.id.widget_wave_clip, android.view.View.GONE)
        }

        // Set play/pause icon
        val playPauseIcon = if (isPlaying) R.drawable.ic_widget_n_pause else R.drawable.ic_widget_n_play
        views.setImageViewResource(R.id.widget_play_pause, playPauseIcon)

        // Set like icon - using nav style (purple) for main widget
        val likeIcon = if (isLiked) R.drawable.ic_widget_n_heart_filled else R.drawable.ic_widget_n_heart_outline
        views.setImageViewResource(R.id.widget_like_button, likeIcon)

        // Repetir: apagado = tenue, todo = blanco, una = blanco con "1"
        val repeatOne = repeatMode == androidx.media3.common.Player.REPEAT_MODE_ONE
        views.setImageViewResource(R.id.widget_repeat_button, if (repeatOne) R.drawable.ic_widget_n_repeat_one else R.drawable.ic_widget_n_repeat)
        views.setInt(
            R.id.widget_repeat_button,
            "setImageAlpha",
            if (repeatMode == androidx.media3.common.Player.REPEAT_MODE_OFF) 130 else 255
        )
        views.setTextViewText(R.id.widget_time_current, formatTime(currentPosition))
        views.setTextViewText(R.id.widget_time_total, formatTime(duration))

        // Set click intents
        views.setOnClickPendingIntent(R.id.widget_background_art, getOpenAppIntent())
        views.setOnClickPendingIntent(R.id.widget_play_pause_container, getPlayPauseIntent())
        views.setOnClickPendingIntent(R.id.widget_like_button, getLikeIntent())
        views.setOnClickPendingIntent(R.id.widget_prev_button, getPreviousIntent())
        views.setOnClickPendingIntent(R.id.widget_next_button, getNextIntent())
        views.setOnClickPendingIntent(R.id.widget_repeat_button, getRepeatIntent())

        return views
    }

    private val waveFrameIds = intArrayOf(
        R.id.widget_wave_f0, R.id.widget_wave_f1, R.id.widget_wave_f2, R.id.widget_wave_f3,
        R.id.widget_wave_f4, R.id.widget_wave_f5, R.id.widget_wave_f6, R.id.widget_wave_f7,
        R.id.widget_wave_f8, R.id.widget_wave_f9, R.id.widget_wave_f10, R.id.widget_wave_f11
    )

    /**
     * Una imagen de la ola (dos capas translúcidas) en el instante `index` de un ciclo de 6 s dividido en
     * 12 pasos. El launcher las va mezclando en orden y en bucle, y el ciclo cierra sin saltos.
     * Mide `spanDp` de ancho y 24 dp de alto, y se dibuja a media resolución (la ola es suave y se estira bien).
     */
    private fun drawWaveFrame(spanDp: Float, color: Int, index: Int): Bitmap {
        val s = context.resources.displayMetrics.density * 0.5f
        val w = (spanDp * s).toInt().coerceAtLeast(2)
        val h = (24f * s).toInt()
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val cy = h - 8f * s
        val base = cy - 2.5f * s
        val spanPx = w.toFloat()
        val t = index * 6.0 / 12.0
        val grow = (spanDp / 24f).coerceAtMost(1f)
        val taper = (28f * s).coerceAtMost(spanPx / 2f)
        val waves = arrayOf(
            // longitud (dp), vueltas laterales por ciclo, vueltas de subida/bajada por ciclo, fase, altura (dp), alpha
            doubleArrayOf(70.0, 1.0, 1.0, 0.0, 12.0, 110.0),
            doubleArrayOf(46.0, -1.0, 1.0, 2.1, 9.5, 90.0)
        )
        for (wv in waves) {
            val lambda = wv[0] * s
            val shift = 2.0 * Math.PI * wv[1] * t / 6.0
            val breathe = 0.55 + 0.45 * kotlin.math.sin(2.0 * Math.PI * wv[2] * t / 6.0 + wv[3])
            val amp = (wv[4] * s * grow * breathe).toFloat()
            val path = Path().apply {
                moveTo(0f, cy)
                var x = 0f
                while (x < spanPx) {
                    x = minOf(x + 1f, spanPx)
                    val edge = (minOf(x, spanPx - x) / taper).coerceIn(0f, 1f)
                    val envelope = edge * edge * (3f - 2f * edge)
                    val crest = 0.5f + 0.5f * kotlin.math.sin((x / lambda) * 2.0 * Math.PI - shift).toFloat()
                    lineTo(x, base - amp * envelope * crest)
                }
                lineTo(spanPx, cy)
                close()
            }
            val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                this.color = ColorUtils.setAlphaComponent(color, wv[5].toInt())
            }
            c.drawPath(path, fill)
        }
        return out
    }

    /**
     * Barra de progreso como la de la notificación: barra recta (lo reproducido en color, el resto tenue),
     * con una ola suave que se levanta encima de lo reproducido y un círculo al final.
     */
    private fun drawProgressBar(widthDp: Int, fraction: Float, color: Int, drawWave: Boolean = true): Bitmap {
        val d = context.resources.displayMetrics.density
        val w = ((widthDp - 36).coerceAtLeast(120) * d).toInt()
        val h = (24 * d).toInt()
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val cy = h - 8f * d
        val stroke = 5f * d
        val thumbR = 7f * d
        val x0 = thumbR
        val x1 = w - thumbR
        val xe = x0 + fraction * (x1 - x0)

        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeWidth = stroke
        }
        line.color = ColorUtils.setAlphaComponent(Color.WHITE, 80)
        c.drawLine(x0, cy, x1, cy, line)

        line.color = color
        if (xe > x0 + 1f) {
            c.drawLine(x0, cy, xe, cy, line)

            if (drawWave) {
            // Dos olas translúcidas que suben y bajan, desfasadas entre sí. Se animan con el reloj
            // (el widget se redibuja unas 5 veces por segundo mientras suena la música).
            val span = xe - x0
            val now = (System.currentTimeMillis() % 600_000L) / 1000.0
            val grow = (span / (24f * d)).coerceAtMost(1f)
            val taper = (28f * d).coerceAtMost(span / 2f)
            val waves = arrayOf(
                // longitud (dp), velocidad lateral, velocidad de subida/bajada, fase, altura (dp), alpha
                doubleArrayOf(70.0, 1.3, 1.1, 0.0, 12.0, 110.0),
                doubleArrayOf(46.0, -1.8, 0.8, 2.1, 9.5, 90.0)
            )
            for (wv in waves) {
                val lambda = wv[0] * d
                val breathe = 0.55 + 0.45 * kotlin.math.sin(now * wv[2] + wv[3])
                val amp = (wv[4] * d * grow * breathe).toFloat()
                val path = Path().apply {
                    moveTo(x0, cy)
                    var x = x0
                    while (x < xe) {
                        x = minOf(x + 2f, xe)
                        // Sube suave desde la barra en el inicio y baja suave hasta la barra en el final
                        val edge = (minOf(x - x0, xe - x) / taper).coerceIn(0f, 1f)
                        val envelope = edge * edge * (3f - 2f * edge)
                        val crest = 0.5f + 0.5f * kotlin.math.sin(
                            ((x - x0) / lambda) * 2.0 * Math.PI - now * wv[1]
                        ).toFloat()
                        lineTo(x, cy - stroke / 2f - amp * envelope * crest)
                    }
                    lineTo(xe, cy)
                    close()
                }
                val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.FILL
                    this.color = ColorUtils.setAlphaComponent(color, wv[5].toInt())
                }
                c.drawPath(path, fill)
            }
            }
        }

        val thumb = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            this.color = color
        }
        c.drawCircle(xe, cy, thumbR, thumb)
        return out
    }

    /**
     * Fondo del widget estilo notificación: la carátula recortada al tamaño del widget, suavizada,
     * con esquinas redondeadas y un oscurecido leve para que el texto blanco se lea.
     */
    private var bgCacheArt: Bitmap? = null
    private var bgCacheSize: Pair<Int, Int>? = null
    private var bgCacheBitmap: Bitmap? = null

    private fun buildNotificationStyleBackground(art: Bitmap?, widthDp: Int, heightDp: Int): Bitmap {
        // El widget se actualiza seguido (progreso): el fondo solo se vuelve a dibujar si cambia la carátula o el tamaño.
        val cached = bgCacheBitmap
        if (cached != null && art === bgCacheArt && bgCacheSize == (widthDp to heightDp)) return cached
        val built = drawNotificationStyleBackground(art, widthDp, heightDp)
        bgCacheArt = art
        bgCacheSize = widthDp to heightDp
        bgCacheBitmap = built
        return built
    }

    private fun drawNotificationStyleBackground(art: Bitmap?, widthDp: Int, heightDp: Int): Bitmap {
        val density = context.resources.displayMetrics.density
        var w = (widthDp.coerceAtLeast(180) * density).toInt()
        var h = (heightDp.coerceAtLeast(110) * density).toInt()
        val k = minOf(1f, 640f / maxOf(w, h))
        w = (w * k).toInt().coerceAtLeast(1)
        h = (h * k).toInt().coerceAtLeast(1)

        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val radius = 28f * density * k
        val clip = Path().apply {
            addRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), radius, radius, Path.Direction.CW)
        }
        canvas.clipPath(clip)

        if (art != null) {
            // Recorte central de la carátula con la misma proporción que el widget
            val aspect = w.toFloat() / h
            val side = minOf(art.width, art.height)
            val srcW = if (aspect >= 1f) side else (side * aspect).toInt().coerceAtLeast(1)
            val srcH = if (aspect >= 1f) (side / aspect).toInt().coerceAtLeast(1) else side
            val left = (art.width - srcW) / 2
            val top = (art.height - srcH) / 2
            val src = Rect(left, top, left + srcW, top + srcH)
            // Carátula nítida (sin agrandar una versión diminuta, que salía en bloques)
            canvas.drawBitmap(art, src, RectF(0f, 0f, w.toFloat(), h.toFloat()), Paint(Paint.FILTER_BITMAP_FLAG))
            // Tinte con el color dominante de la carátula, como en la notificación
            val dominant = Palette.from(art).maximumColorCount(8).generate().getDominantColor(0xFF2B2B2B.toInt())
            canvas.drawColor(ColorUtils.setAlphaComponent(dominant, 0x66))
        } else {
            canvas.drawColor(0xFF2B2B2B.toInt())
        }

        val shade = Paint().apply {
            shader = LinearGradient(
                0f, 0f, 0f, h.toFloat(),
                0x26000000, 0x66000000,
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), shade)
        return out
    }

    private var accentSourceArt: Bitmap? = null
    private var accentColor: Int = Color.WHITE

    /** Color claro de la carátula para la barra de progreso (blanco si no hay carátula). */
    private fun pickAccentColor(art: Bitmap?): Int {
        if (art == null) return Color.WHITE
        if (art === accentSourceArt) return accentColor
        val palette = Palette.from(art).maximumColorCount(12).generate()
        val base = palette.getLightVibrantColor(
            palette.getVibrantColor(palette.getDominantColor(Color.WHITE))
        )
        accentColor = ColorUtils.blendARGB(base, Color.WHITE, 0.45f)
        accentSourceArt = art
        return accentColor
    }

    private fun formatTime(ms: Long): String {
        val s = (ms.coerceAtLeast(0L) / 1000)
        return "${(s / 60).toString().padStart(2, '0')}:${(s % 60).toString().padStart(2, '0')}"
    }

    private suspend fun loadAlbumArt(artworkUri: String, size: Int = 200): Bitmap? {
        return withContext(Dispatchers.IO) {
            try {
                val request = ImageRequest.Builder(context)
                    .data(artworkUri)
                    .size(size, size)
                    .allowHardware(false)
                    .crossfade(300)
                    .build()
                val result = imageLoader.execute(request)
                result.image?.toBitmap()
            } catch (e: Exception) {
                null
            }
        }
    }

    private fun getRoundedCornerBitmap(bitmap: Bitmap, cornerRadius: Float): Bitmap {
        // Ensure the bitmap is square for thumbnails
        val size = minOf(bitmap.width, bitmap.height)
        val xOffset = (bitmap.width - size) / 2
        val yOffset = (bitmap.height - size) / 2
        val squareBitmap = Bitmap.createBitmap(bitmap, xOffset, yOffset, size, size)

        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint().apply {
            isAntiAlias = true
            isFilterBitmap = true
            shader = BitmapShader(squareBitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        }
        val rect = RectF(0f, 0f, size.toFloat(), size.toFloat())
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, paint)
        
        if (squareBitmap != bitmap) {
            squareBitmap.recycle()
        }
        
        return output
    }

    private fun getCircularBitmap(bitmap: Bitmap): Bitmap {
        val size = minOf(bitmap.width, bitmap.height)
        
        // First crop to square
        val xOffset = (bitmap.width - size) / 2
        val yOffset = (bitmap.height - size) / 2
        val squareBitmap = Bitmap.createBitmap(bitmap, xOffset, yOffset, size, size)
        
        // Create circular output
        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint().apply {
            isAntiAlias = true
            isFilterBitmap = true
            shader = BitmapShader(squareBitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        }
        val radius = size / 2f
        canvas.drawCircle(radius, radius, radius, paint)
        
        if (squareBitmap != bitmap) {
            squareBitmap.recycle()
        }
        return output
    }

    private fun createCompactSquareRemoteViews(
        albumArt: Bitmap?,
        isPlaying: Boolean
    ): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_compact_square)

        // Set album art with rounded corners
        if (albumArt != null) {
            val roundedAlbumArt = getRoundedCornerBitmap(albumArt, 48f)
            views.setImageViewBitmap(R.id.widget_compact_album_art, roundedAlbumArt)
        } else {
            views.setImageViewBitmap(R.id.widget_compact_album_art, getRoundedDefaultIcon(48f))
        }

        // Set play/pause icon - using low style icons
        val playPauseIcon = if (isPlaying) R.drawable.ic_widget_pause_low else R.drawable.ic_widget_play_low
        views.setImageViewResource(R.id.widget_compact_play_pause, playPauseIcon)

        // Set click intents
        views.setOnClickPendingIntent(R.id.widget_compact_album_art, getOpenAppIntent())
        views.setOnClickPendingIntent(R.id.widget_compact_play_container, getPlayPauseIntent())

        return views
    }

    private fun createCompactWideRemoteViews(
        title: String,
        artist: String,
        albumArt: Bitmap?,
        isPlaying: Boolean,
        isLiked: Boolean
    ): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_compact_wide)

        // Set song info
        views.setTextViewText(R.id.widget_wide_song_title, title)
        views.setTextViewText(R.id.widget_wide_artist_name, artist)

        // Set album art with rounded corners (48f to match 12dp at ~4x density for 48dp view)
        if (albumArt != null) {
            val roundedAlbumArt = getRoundedCornerBitmap(albumArt, 48f)
            views.setImageViewBitmap(R.id.widget_wide_album_art, roundedAlbumArt)
        } else {
            // Create rounded default icon
            views.setImageViewBitmap(R.id.widget_wide_album_art, getRoundedDefaultIcon(48f))
        }

        // Set play/pause icon - using low style icons
        val playPauseIcon = if (isPlaying) R.drawable.ic_widget_pause_low else R.drawable.ic_widget_play_low
        views.setImageViewResource(R.id.widget_wide_play_pause, playPauseIcon)

        // Set like icon - using navigation style (purple)
        val likeIcon = if (isLiked) R.drawable.ic_widget_heart_nav else R.drawable.ic_widget_heart_outline_nav
        views.setImageViewResource(R.id.widget_wide_like_button, likeIcon)

        // Set click intents
        views.setOnClickPendingIntent(R.id.widget_wide_album_art, getOpenAppIntent())
        views.setOnClickPendingIntent(R.id.widget_wide_play_container, getPlayPauseIntent())
        views.setOnClickPendingIntent(R.id.widget_wide_like_button, getLikeIntent())

        return views
    }

    private fun createTurntableRemoteViews(
        circularAlbumArt: Bitmap?,
        isPlaying: Boolean,
        isLiked: Boolean
    ): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_turntable)

        // Set circular album art - create circular default icon if no album art
        if (circularAlbumArt != null) {
            views.setImageViewBitmap(R.id.widget_turntable_album_art, circularAlbumArt)
        } else {
            // Load and make the default icon circular
            views.setImageViewBitmap(R.id.widget_turntable_album_art, getCircularDefaultIcon())
        }

        // Set play/pause icon - using secondary color icons for turntable
        val playPauseIcon = if (isPlaying) R.drawable.ic_widget_pause_secondary else R.drawable.ic_widget_play_secondary
        views.setImageViewResource(R.id.widget_turntable_play_pause, playPauseIcon)

        // Set click intents
        views.setOnClickPendingIntent(R.id.widget_turntable_album_art, getOpenAppIntent())
        views.setOnClickPendingIntent(R.id.widget_turntable_play_container, getTurntablePlayPauseIntent())
        views.setOnClickPendingIntent(R.id.widget_turntable_prev_button, getTurntablePreviousIntent())
        views.setOnClickPendingIntent(R.id.widget_turntable_next_button, getTurntableNextIntent())

        return views
    }
    
    private fun getCircularDefaultIcon(): Bitmap {
        // Get the launcher icon and make it circular
        val drawable = context.packageManager.getApplicationIcon(context.packageName)
        val size = 300
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, size, size)
        drawable.draw(canvas)
        return getCircularBitmap(bitmap)
    }
    
    private fun getRoundedDefaultIcon(cornerRadius: Float): Bitmap {
        // Get the launcher icon and make it rounded
        val drawable = context.packageManager.getApplicationIcon(context.packageName)
        val size = 300
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, size, size)
        drawable.draw(canvas)
        return getRoundedCornerBitmap(bitmap, cornerRadius)
    }

    private fun getOpenAppIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun getPlayPauseIntent(): PendingIntent {
        val intent = Intent(context, MusicWidgetReceiver::class.java).apply {
            action = MusicWidgetReceiver.ACTION_PLAY_PAUSE
        }
        return PendingIntent.getBroadcast(
            context,
            1,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun getLikeIntent(): PendingIntent {
        val intent = Intent(context, MusicWidgetReceiver::class.java).apply {
            action = MusicWidgetReceiver.ACTION_LIKE
        }
        return PendingIntent.getBroadcast(
            context,
            2,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun getNextIntent(): PendingIntent {
        val intent = Intent(context, MusicWidgetReceiver::class.java).apply {
            action = MusicWidgetReceiver.ACTION_NEXT
        }
        return PendingIntent.getBroadcast(
            context,
            6,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun getRepeatIntent(): PendingIntent {
        val intent = Intent(context, MusicWidgetReceiver::class.java).apply {
            action = MusicWidgetReceiver.ACTION_REPEAT
        }
        return PendingIntent.getBroadcast(
            context,
            8,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun getPreviousIntent(): PendingIntent {
        val intent = Intent(context, MusicWidgetReceiver::class.java).apply {
            action = MusicWidgetReceiver.ACTION_PREVIOUS
        }
        return PendingIntent.getBroadcast(
            context,
            7,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun getTurntablePlayPauseIntent(): PendingIntent {
        val intent = Intent(context, TurntableWidgetReceiver::class.java).apply {
            action = TurntableWidgetReceiver.ACTION_TURNTABLE_PLAY_PAUSE
        }
        return PendingIntent.getBroadcast(
            context,
            3,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun getTurntableNextIntent(): PendingIntent {
        val intent = Intent(context, TurntableWidgetReceiver::class.java).apply {
            action = TurntableWidgetReceiver.ACTION_TURNTABLE_NEXT
        }
        return PendingIntent.getBroadcast(
            context,
            4,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun getTurntablePreviousIntent(): PendingIntent {
        val intent = Intent(context, TurntableWidgetReceiver::class.java).apply {
            action = TurntableWidgetReceiver.ACTION_TURNTABLE_PREVIOUS
        }
        return PendingIntent.getBroadcast(
            context,
            5,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
