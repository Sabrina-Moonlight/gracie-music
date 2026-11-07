/**
 * vivimusic Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.music.vivi.vivimusic.updater


import android.content.Context
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.URL
import java.util.regex.Pattern
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.aspectRatio
import coil3.compose.AsyncImage
import android.view.ViewGroup
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView




// Utility functions for SharedPreferences uses now view model
const val PREFS_NAME = "settings"
const val KEY_DOWNLOAD_NOTIFICATIONS = "download_notifications"

fun getDownloadNotificationsSetting(context: Context): Boolean {
    val sharedPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    return sharedPrefs.getBoolean(KEY_DOWNLOAD_NOTIFICATIONS, true)
}

// ──────────────────────────────────────────────────────────────────────────
// 9 PM daily gate for beta/nightly update checks
// ──────────────────────────────────────────────────────────────────────────
const val KEY_LAST_NIGHTLY_CHECK_DAY = "last_nightly_check_day"

/**
 * Returns true only if BOTH conditions are met:
 *  1. The current local time is 9:00 PM (21:00) or later.
 *  2. The nightly update check has NOT already run today.
 *
 * This prevents the nightly CI check from firing on every single app launch.
 * Once it returns true and the check runs, call [markNightlyCheckDone] so
 * subsequent launches today are silently skipped.
 */
fun shouldRunNightlyCheck(context: Context): Boolean {
    val now = java.time.LocalTime.now()
    val ninepm = java.time.LocalTime.of(21, 0)
    if (now.isBefore(ninepm)) return false          // before 9 PM — skip
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val todayEpochDay = java.time.LocalDate.now().toEpochDay()
    val lastCheckedDay = prefs.getLong(KEY_LAST_NIGHTLY_CHECK_DAY, -1L)
    return lastCheckedDay != todayEpochDay           // already ran today — skip
}

/**
 * Records that the nightly check ran today so [shouldRunNightlyCheck] returns
 * false for all remaining launches until midnight.
 */
fun markNightlyCheckDone(context: Context) {
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putLong(KEY_LAST_NIGHTLY_CHECK_DAY, java.time.LocalDate.now().toEpochDay())
        .apply()
}

fun String.extractUrls(): List<Pair<IntRange, String>> {
    val urlPattern = Pattern.compile(
        "(?:^|[\\s])((https?://|www\\.|pic\\.)[\\w-]+(\\.[\\w-]+)+([/?].*)?)"
    )
    val matcher = urlPattern.matcher(this)
    val urlList = mutableListOf<Pair<IntRange, String>>()

    while (matcher.find()) {
        val url = matcher.group(1)?.trim() ?: continue
        val range = IntRange(matcher.start(1), matcher.end(1) - 1)
        // Ensure URL has proper scheme
        val fullUrl = if (url.startsWith("http")) url else "https://$url"
        urlList.add(range to fullUrl)
    }

    return urlList
}

// JSON Block parser
fun parseSDUIBlock(json: JSONObject): SDUIBlock {
    val type = json.optString("type", "text")
    val url = json.optString("url").takeIf { it.isNotEmpty() }
    
    var modifierParams: SDUIModifier? = null
    if (json.has("modifier")) {
        val modJson = json.getJSONObject("modifier")
        modifierParams = SDUIModifier(
            weight = if (modJson.has("weight")) modJson.getDouble("weight").toFloat() else null,
            heightDp = if (modJson.has("height")) modJson.getInt("height") else null,
            fillMaxWidth = modJson.optBoolean("fillMaxWidth", false),
            paddingDp = if (modJson.has("padding")) modJson.getInt("padding") else null,
            aspectRatio = if (modJson.has("aspectRatio")) modJson.getDouble("aspectRatio").toFloat() else null
        )
    }
    
    var children: List<SDUIBlock>? = null
    if (json.has("children")) {
        val childrenArray = json.getJSONArray("children")
        val parsedList = mutableListOf<SDUIBlock>()
        for (i in 0 until childrenArray.length()) {
            parsedList.add(parseSDUIBlock(childrenArray.getJSONObject(i)))
        }
        children = parsedList
    }
    
    return SDUIBlock(type, url, modifierParams, children)
}

// Dynamic Jetpack Compose layout renderer
@Composable
fun RowScope.RowSDUIItem(child: SDUIBlock) {
    val w = child.modifierParams?.weight
    val childModifier = if (w != null) Modifier.weight(w) else Modifier
    RenderSDUIBlock(child, parentModifier = childModifier)
}

@Composable
fun RenderSDUIBlock(block: SDUIBlock, parentModifier: Modifier = Modifier) {
    var mod = parentModifier
    block.modifierParams?.let { params ->
        if (params.fillMaxWidth) mod = mod.fillMaxWidth()
        if (params.heightDp != null) mod = mod.height(params.heightDp.dp)
        if (params.paddingDp != null) mod = mod.padding(params.paddingDp.dp)
        if (params.aspectRatio != null) mod = mod.aspectRatio(params.aspectRatio)
    }

    when (block.type) {
        "row" -> {
            if (block.children.isNullOrEmpty()) return
            Row(modifier = mod.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                block.children?.forEach { child -> RowSDUIItem(child) }
            }
        }
        "image" -> {
           if (block.url.isNullOrBlank()) return
           AsyncImage(
                model = block.url,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = mod.clip(RoundedCornerShape(12.dp))
            )
        }
        "video" -> {
            if (block.url.isNullOrBlank()) return
            val context = LocalContext.current
            val exoPlayer = remember {
                ExoPlayer.Builder(context).build().apply {
                    val mediaItem = MediaItem.fromUri(block.url ?: "")
                    setMediaItem(mediaItem)
                    prepare()
                    playWhenReady = true
                    repeatMode = Player.REPEAT_MODE_ALL
                }
            }
            DisposableEffect(exoPlayer) {
                onDispose { exoPlayer.release() }
            }
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        player = exoPlayer
                        useController = false // looping aesthetics
                        setShowNextButton(false)
                        setShowPreviousButton(false)
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    }
                },
                modifier = mod.clip(RoundedCornerShape(12.dp))
            )
        }
    }
}
