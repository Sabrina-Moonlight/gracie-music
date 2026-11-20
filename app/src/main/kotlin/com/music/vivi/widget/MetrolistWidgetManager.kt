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
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.os.Bundle
import android.os.SystemClock
import android.widget.RemoteViews
import androidx.media3.common.Player
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.request.crossfade
import coil3.toBitmap
import com.music.vivi.MainActivity
import com.music.vivi.R
import com.music.vivi.db.MusicDatabase
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
    private var cachedNotificationBg: Bitmap? = null
    private var cachedNotificationBgSource: Bitmap? = null

    // Animación de la onda de la barra (widget «Estilo notificación»)
    private val waveScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var waveJob: Job? = null
    @Volatile private var waveDuration: Long = 0L
    @Volatile private var waveAccent: Int = 0xFFF8B4AE.toInt()
    @Volatile private var wavePosition: Long = 0L
    @Volatile private var waveStamp: Long = 0L

    suspend fun updateWidgets(
        title: String,
        artist: String,
        artworkUri: String?,
        isPlaying: Boolean,
        isLiked: Boolean,
        duration: Long = 0,
        currentPosition: Long = 0,
        repeatMode: Int = Player.REPEAT_MODE_OFF
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
                    currentPosition
                )
                appWidgetManager.updateAppWidget(widgetId, views)
            }
        }

        // Update notification-style widgets
        val notificationWidgetIds = appWidgetManager.getAppWidgetIds(
            ComponentName(context, NotificationWidgetReceiver::class.java)
        )
        if (notificationWidgetIds.isNotEmpty()) {
            val notificationViews = createNotificationRemoteViews(
                title, artist, albumArt, isPlaying, isLiked, repeatMode, duration, currentPosition
            )
            notificationWidgetIds.forEach { widgetId ->
                appWidgetManager.updateAppWidget(widgetId, notificationViews)
            }
            waveDuration = duration
            wavePosition = currentPosition
            waveStamp = SystemClock.elapsedRealtime()
            if (isPlaying && duration > 0) startWave() else stopWave()
        } else {
            stopWave()
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
        currentPosition: Long
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
                createRemoteViews(title, artist, albumArt, isPlaying, isLiked, duration, currentPosition)
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
        currentPosition: Long = 0
    ): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_music_player)

        // Set song info
        views.setTextViewText(R.id.widget_song_title, title)
        views.setTextViewText(R.id.widget_artist_name, artist)

        // Set album art with rounded corners
        if (albumArt != null) {
            val roundedAlbumArt = getRoundedCornerBitmap(albumArt, 48f)
            views.setImageViewBitmap(R.id.widget_album_art, roundedAlbumArt)
        } else {
            views.setImageViewBitmap(R.id.widget_album_art, getRoundedDefaultIcon(48f))
        }

        // Set play/pause icon
        val playPauseIcon = if (isPlaying) R.drawable.ic_widget_pause else R.drawable.ic_widget_play
        views.setImageViewResource(R.id.widget_play_pause, playPauseIcon)

        // Set like icon - using nav style (purple) for main widget
        val likeIcon = if (isLiked) R.drawable.ic_widget_heart_nav else R.drawable.ic_widget_heart_outline_nav
        views.setImageViewResource(R.id.widget_like_button, likeIcon)

        // Set Progress Level
        if (duration > 0) {
            val level = ((currentPosition.toDouble() / duration.toDouble()) * 10000).toInt()
            views.setInt(R.id.widget_progress_fill, "setImageLevel", level)
        } else {
            views.setInt(R.id.widget_progress_fill, "setImageLevel", 0)
        }

        // Set click intents
        views.setOnClickPendingIntent(R.id.widget_album_art, getOpenAppIntent())
        views.setOnClickPendingIntent(R.id.widget_play_pause_container, getPlayPauseIntent())
        views.setOnClickPendingIntent(R.id.widget_like_button, getLikeIntent())

        return views
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
    
    private fun createNotificationRemoteViews(
        title: String,
        artist: String,
        albumArt: Bitmap?,
        isPlaying: Boolean,
        isLiked: Boolean,
        repeatMode: Int,
        duration: Long,
        currentPosition: Long
    ): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_notification)

        views.setTextViewText(R.id.widget_n_song_title, title)
        views.setTextViewText(R.id.widget_n_artist_name, artist)
        views.setImageViewBitmap(R.id.widget_n_bg, getNotificationBackground(albumArt))
        waveAccent = accentFromArt(albumArt)

        // Barra con onda + tiempos
        val progress = if (duration > 0) {
            (currentPosition.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
        } else 0f
        views.setImageViewBitmap(R.id.widget_n_progress, createWavyProgressBitmap(progress, if (isPlaying) currentWavePhase() else 0f))
        views.setTextViewText(R.id.widget_n_time_current, formatWidgetTime(currentPosition))
        views.setTextViewText(R.id.widget_n_time_total, formatWidgetTime(duration))

        views.setImageViewResource(
            R.id.widget_n_play_pause,
            if (isPlaying) R.drawable.ic_widget_n_pause else R.drawable.ic_widget_n_play
        )
        views.setImageViewResource(
            R.id.widget_n_repeat,
            when (repeatMode) {
                Player.REPEAT_MODE_ONE -> R.drawable.ic_widget_n_repeat_one
                Player.REPEAT_MODE_ALL -> R.drawable.ic_widget_n_repeat_all
                else -> R.drawable.ic_widget_n_repeat_off
            }
        )
        views.setImageViewResource(
            R.id.widget_n_like,
            if (isLiked) R.drawable.ic_widget_n_heart_on else R.drawable.ic_widget_n_heart_off
        )

        views.setOnClickPendingIntent(R.id.widget_n_open_area, getOpenAppIntent())
        views.setOnClickPendingIntent(R.id.widget_n_play_container, getNotificationIntent(NotificationWidgetReceiver.ACTION_N_PLAY_PAUSE, 6))
        views.setOnClickPendingIntent(R.id.widget_n_prev, getNotificationIntent(NotificationWidgetReceiver.ACTION_N_PREVIOUS, 7))
        views.setOnClickPendingIntent(R.id.widget_n_next, getNotificationIntent(NotificationWidgetReceiver.ACTION_N_NEXT, 8))
        views.setOnClickPendingIntent(R.id.widget_n_repeat, getNotificationIntent(NotificationWidgetReceiver.ACTION_N_REPEAT, 9))
        views.setOnClickPendingIntent(R.id.widget_n_like, getNotificationIntent(NotificationWidgetReceiver.ACTION_N_LIKE, 10))

        return views
    }

    /** Portada bien difuminada (desenfoque real, sin pixeles) y oscurecida, con esquinas redondeadas. Se guarda para no repetir el trabajo. */
    private fun getNotificationBackground(albumArt: Bitmap?): Bitmap {
        val cached = cachedNotificationBg
        if (cached != null && cachedNotificationBgSource === albumArt) return cached

        val w = 960
        val h = 400
        val base = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(base)
        if (albumArt != null) {
            // Se recorta la portada con la proporción del widget y se dibuja nítida (como la notificación)
            val ratio = w.toFloat() / h.toFloat()
            var cropW = albumArt.width
            var cropH = (cropW / ratio).toInt()
            if (cropH > albumArt.height) { cropH = albumArt.height; cropW = (cropH * ratio).toInt() }
            cropW = cropW.coerceAtLeast(1); cropH = cropH.coerceAtLeast(1)
            val cx = ((albumArt.width - cropW) / 2).coerceAtLeast(0)
            val cy = ((albumArt.height - cropH) / 2).coerceAtLeast(0)
            val paint = Paint().apply { isFilterBitmap = true; isAntiAlias = true; isDither = true }
            canvas.drawBitmap(
                albumArt,
                android.graphics.Rect(cx, cy, cx + cropW, cy + cropH),
                android.graphics.Rect(0, 0, w, h),
                paint
            )
            // Capa oscura con el tono de la portada: más clara arriba, más oscura abajo (como la notificación)
            val hsvShade = FloatArray(3)
            android.graphics.Color.colorToHSV(accentFromArt(albumArt), hsvShade)
            fun shadeColor(alpha: Int, v: Float): Int {
                val c = floatArrayOf(hsvShade[0], 0.6f, v)
                return (alpha shl 24) or (android.graphics.Color.HSVToColor(c) and 0x00FFFFFF)
            }
            val shade = Paint().apply {
                isDither = true
                shader = android.graphics.LinearGradient(
                    0f, 0f, 0f, h.toFloat(),
                    intArrayOf(shadeColor(0x55, 0.25f), shadeColor(0x99, 0.19f), shadeColor(0xDD, 0.125f)),
                    floatArrayOf(0f, 0.55f, 1f),
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), shade)
        } else {
            canvas.drawColor(0xFF3B2B26.toInt())
        }

        // Esquinas redondeadas
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val paint = Paint().apply {
            isAntiAlias = true
            isFilterBitmap = true
            shader = BitmapShader(base, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        }
        Canvas(out).drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), 64f, 64f, paint)
        base.recycle()

        cachedNotificationBg = out
        cachedNotificationBgSource = albumArt
        return out
    }

    /** Desenfoque de caja (horizontal y vertical) sobre píxeles ARGB opacos. */
    private fun boxBlur(src: IntArray, w: Int, h: Int, r: Int): IntArray {
        val tmp = IntArray(src.size)
        val out = IntArray(src.size)
        for (y in 0 until h) blurLine(src, tmp, y * w, 1, w, r)
        for (x in 0 until w) blurLine(tmp, out, x, w, h, r)
        return out
    }

    private fun blurLine(inp: IntArray, out: IntArray, start: Int, step: Int, n: Int, r: Int) {
        val div = 2 * r + 1
        var sr = 0
        var sg = 0
        var sb = 0
        for (i in -r..r) {
            val p = inp[start + i.coerceIn(0, n - 1) * step]
            sr += (p shr 16) and 255; sg += (p shr 8) and 255; sb += p and 255
        }
        for (i in 0 until n) {
            out[start + i * step] = 0xFF000000.toInt() or ((sr / div) shl 16) or ((sg / div) shl 8) or (sb / div)
            val add = inp[start + (i + r + 1).coerceAtMost(n - 1) * step]
            val rem = inp[start + (i - r).coerceAtLeast(0) * step]
            sr += ((add shr 16) and 255) - ((rem shr 16) and 255)
            sg += ((add shr 8) and 255) - ((rem shr 8) and 255)
            sb += (add and 255) - (rem and 255)
        }
    }

    /** Color de acento sacado de la portada (tono pastel, como el de la notificación). */
    private fun accentFromArt(art: Bitmap?): Int {
        if (art == null) return 0xFFF8B4AE.toInt()
        return try {
            val small = Bitmap.createScaledBitmap(art, 24, 24, true)
            var r = 0.0; var g = 0.0; var bl = 0.0; var wSum = 0.0
            val hsv = FloatArray(3)
            for (y in 0 until 24) for (x in 0 until 24) {
                val c = small.getPixel(x, y)
                android.graphics.Color.colorToHSV(c, hsv)
                val wgt = 0.15 + hsv[1] * hsv[2]
                r += android.graphics.Color.red(c) * wgt
                g += android.graphics.Color.green(c) * wgt
                bl += android.graphics.Color.blue(c) * wgt
                wSum += wgt
            }
            if (small !== art) small.recycle()
            val avg = android.graphics.Color.rgb((r / wSum).toInt(), (g / wSum).toInt(), (bl / wSum).toInt())
            android.graphics.Color.colorToHSV(avg, hsv)
            hsv[1] = 0.34f
            hsv[2] = 0.96f
            android.graphics.Color.HSVToColor(hsv)
        } catch (e: Exception) {
            0xFFF8B4AE.toInt()
        }
    }

    /**
     * Barra de progreso como la de la notificación: barra gruesa del color de la portada, círculo chico
     * con borde fino, y encima de la parte recorrida 2 olas anchas y translúcidas del mismo color
     * (donde se cruzan se ven más claras). Van en sentidos opuestos y con largos distintos.
     */
    private fun createWavyProgressBitmap(progress: Float, phase: Float = 0f): Bitmap {
        // Proporción 12,2:1; el ImageView usa fitCenter, así que el círculo no se estira
        val w = 560
        val h = 46
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val thumbR = 13f
        val cy = 32f
        val left = thumbR
        val right = w - thumbR
        val x = left + (right - left) * progress
        val stroke = 13f
        val accent = waveAccent
        // Círculo: el color de la barra mezclado con blanco
        fun mix(c: Int, t: Float): Int {
            val r = android.graphics.Color.red(c); val g = android.graphics.Color.green(c); val b = android.graphics.Color.blue(c)
            return android.graphics.Color.rgb((r + (255 - r) * t).toInt(), (g + (255 - g) * t).toInt(), (b + (255 - b) * t).toInt())
        }

        val track = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeWidth = stroke
            color = 0x40FFFFFF
        }
        canvas.drawLine(left, cy, right, cy, track)

        if (x > left) {
            val twoPi = 2f * Math.PI.toFloat()
            // Cada ola: largo de onda, altura, vueltas por ciclo (negativo = al revés), desfase,
            // largo y desfase de la variación de altura (unas partes más altas, otras más bajas)
            val waves = arrayOf(
                floatArrayOf(105f, 15f, 1f, 0f, 230f, 0.5f),
                floatArrayOf(95f, 13f, -1f, 1f, 200f, 2.2f)
            )
            val waveColor = (accent and 0x00FFFFFF) or (0xA0 shl 24)
            val top = cy - stroke / 2f
            for (cfg in waves) {
                val wave = android.graphics.Path()
                wave.moveTo(left, cy)
                var px = left
                while (px < x) {
                    px = minOf(px + 2f, x)
                    // Sube suave al inicio y baja suave cerca del círculo
                    val edge = minOf(1f, (px - left) / 45f) * minOf(1f, (x - px) / 36f)
                    val env = 0.7f + 0.3f * (1f + kotlin.math.sin((px - left) / cfg[4] * twoPi + cfg[5])) / 2f
                    val bump = (1f + kotlin.math.sin((px - left) / cfg[0] * twoPi - cfg[3] - phase * cfg[2])) / 2f
                    wave.lineTo(px, top + 1f - cfg[1] * edge.coerceAtLeast(0f) * env * bump)
                }
                wave.lineTo(x, cy)
                wave.close()
                canvas.drawPath(wave, Paint().apply { isAntiAlias = true; style = Paint.Style.FILL; color = waveColor })
            }

            val fill = Paint().apply {
                isAntiAlias = true
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                strokeWidth = stroke
                color = accent
            }
            canvas.drawLine(left, cy, x, cy, fill)
        }

        canvas.drawCircle(x, cy, thumbR, Paint().apply { isAntiAlias = true; color = mix(accent, 0.55f) })
        canvas.drawCircle(x, cy, thumbR - 0.75f, Paint().apply {
            isAntiAlias = true; style = Paint.Style.STROKE; strokeWidth = 1.5f; color = 0x46000000
        })
        return bmp
    }

    /** Fase de la onda según el reloj: una vuelta completa cada 1,6 s, siempre en el mismo sentido. */
    private fun currentWavePhase(): Float {
        val t = SystemClock.uptimeMillis() % 1600L
        return t / 1600f * 2f * Math.PI.toFloat()
    }

    /** Redibuja solo la barra unas 16 veces por segundo mientras suena la música, para que la onda fluya. */
    private fun startWave() {
        if (waveJob?.isActive == true) return
        waveJob = waveScope.launch {
            val manager = AppWidgetManager.getInstance(context)
            val component = ComponentName(context, NotificationWidgetReceiver::class.java)
            while (isActive) {
                try {
                    val ids = manager.getAppWidgetIds(component)
                    if (ids.isEmpty()) break
                    val dur = waveDuration
                    val pos = (wavePosition + (SystemClock.elapsedRealtime() - waveStamp))
                        .coerceIn(0L, dur.coerceAtLeast(0L))
                    val progress = if (dur > 0) pos.toFloat() / dur.toFloat() else 0f
                    val partial = RemoteViews(context.packageName, R.layout.widget_notification)
                    partial.setImageViewBitmap(
                        R.id.widget_n_progress,
                        createWavyProgressBitmap(progress, currentWavePhase())
                    )
                    manager.partiallyUpdateAppWidget(ids, partial)
                } catch (e: Exception) {
                    // Widget quitado o error puntual: se intenta de nuevo en el siguiente ciclo
                }
                delay(60)
            }
        }
    }

    private fun stopWave() {
        waveJob?.cancel()
        waveJob = null
    }

    private fun formatWidgetTime(ms: Long): String {
        val total = (ms.coerceAtLeast(0L) / 1000L).toInt()
        return String.format(java.util.Locale.US, "%02d:%02d", total / 60, total % 60)
    }

    private fun getNotificationIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, NotificationWidgetReceiver::class.java).apply {
            this.action = action
        }
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
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
