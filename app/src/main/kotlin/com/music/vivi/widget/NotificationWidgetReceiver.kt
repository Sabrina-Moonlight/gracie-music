/**
 * vivimusic Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.music.vivi.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import com.music.vivi.playback.MusicService

/** Widget «estilo notificación»: portada, texto y botones anterior / play / siguiente / repetir / me gusta. */
class NotificationWidgetReceiver : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        if (MusicService.isRunning) {
            val intent = Intent(context, MusicService::class.java).apply {
                action = MusicWidgetReceiver.ACTION_UPDATE_WIDGET
            }
            try {
                context.startService(intent)
            } catch (e: Exception) {
                // El servicio puede estar restringido en segundo plano
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)

        val serviceAction = when (intent.action) {
            ACTION_N_PLAY_PAUSE -> MusicWidgetReceiver.ACTION_PLAY_PAUSE
            ACTION_N_NEXT -> MusicWidgetReceiver.ACTION_NEXT
            ACTION_N_PREVIOUS -> MusicWidgetReceiver.ACTION_PREVIOUS
            ACTION_N_REPEAT -> MusicWidgetReceiver.ACTION_REPEAT
            ACTION_N_LIKE -> MusicWidgetReceiver.ACTION_LIKE
            else -> return
        }
        val serviceIntent = Intent(context, MusicService::class.java).apply {
            action = serviceAction
        }
        try {
            context.startService(serviceIntent)
        } catch (e: Exception) {
            // El servicio puede estar restringido en segundo plano
        }
    }

    companion object {
        const val ACTION_N_PLAY_PAUSE = "com.music.vivi.widget.N_PLAY_PAUSE"
        const val ACTION_N_NEXT = "com.music.vivi.widget.N_NEXT"
        const val ACTION_N_PREVIOUS = "com.music.vivi.widget.N_PREVIOUS"
        const val ACTION_N_REPEAT = "com.music.vivi.widget.N_REPEAT"
        const val ACTION_N_LIKE = "com.music.vivi.widget.N_LIKE"
    }
}
