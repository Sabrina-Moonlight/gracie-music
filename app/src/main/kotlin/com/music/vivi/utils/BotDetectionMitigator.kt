/**
 * vivimusic Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.music.vivi.utils

import androidx.datastore.preferences.core.edit
import com.music.innertube.YouTube
import com.music.vivi.constants.VisitorDataKey
import com.music.vivi.App
import com.music.vivi.utils.PlaybackLogManager
import com.music.vivi.utils.PlaybackLogLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.concurrent.atomic.AtomicInteger

/**
 * Manages bot detection mitigation by tracking playback failures and
 * rotating guest identities (visitorData) when necessary.
 *
 * Key improvements:
 * - Locale preservation: snapshot region/language before rotation and restore it
 *   to ensure new visitorData is issued for the user's correct country.
 * - Immediate rotation: removed thresholds/cooldowns for faster playback recovery.
 * - Surgical rotation: only clears visitorData, not the entire session.
 */
object BotDetectionMitigator {
    private const val TAG = "BotDetectionMitigator"

    private val failureCount = AtomicInteger(0)

    /**
     * Call this when a track starts playing successfully.
     */
    fun notifyPlaybackSuccess() {
        failureCount.set(0)
    }

    /**
     * Rotates the guest session by obtaining a fresh visitorData token while preserving locale.
     */
    suspend fun rotateGuestSession() {
        Timber.tag(TAG).i("Rotating guest session to bypass bot detection...")
        PlaybackLogManager.log(
            PlaybackLogLevel.BOT, 
            "Rotating guest session", 
            "Bypassing bot detection by refreshing visitorData (locale preserved)"
        )
        
        withContext(Dispatchers.IO) {
            // Snapshot locale so the new token is issued for the user's actual region.
            val currentLocale = YouTube.locale

            // Clear only visitorData - minimal session change
            YouTube.visitorData = null
            
            YouTube.refreshVisitorData().onSuccess { newData ->
                Timber.tag(TAG).i("New visitorData obtained successfully for region ${currentLocale.gl}.")
                
                // Persist to DataStore
                App.context.dataStore.edit { settings ->
                    settings[VisitorDataKey] = newData
                }
            }.onFailure { e ->
                Timber.tag(TAG).e(e, "Failed to refresh visitorData during rotation")
                // Restore locale context if refresh failed
                YouTube.locale = currentLocale
            }
        }
        
        failureCount.set(0)
    }

    fun reset() {
        failureCount.set(0)
    }
}
