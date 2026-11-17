/**
 * vivimusic Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.music.vivi.lyrics

import android.content.Context
import android.util.LruCache

import com.music.vivi.constants.LyricsPrioritizeSyncAccuracyKey
import com.music.vivi.constants.LyricsProviderOrderKey
import com.music.vivi.constants.PreferredLyricsProvider
import com.music.vivi.constants.PreferredLyricsProviderKey
import com.music.vivi.db.entities.LyricsEntity.Companion.LYRICS_NOT_FOUND
import com.music.vivi.localmedia.isLocalMediaId
import com.music.vivi.extensions.toEnum
import com.music.vivi.models.MediaMetadata
import com.music.vivi.utils.NetworkConnectivityObserver
import com.music.vivi.utils.dataStore
import com.music.vivi.utils.reportException
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LyricsHelper
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val networkConnectivity: NetworkConnectivityObserver,
) {
    /**
     * Resolves the ordered list of lyrics providers from the user's saved priority order.
     * Falls back to migrating the legacy [PreferredLyricsProvider] enum if the new order
     * preference has not been written yet, ensuring a smooth upgrade for existing users.
     */
    private suspend fun resolveLyricsProviders(): List<LyricsProvider> {
        val preferences = context.dataStore.data.first()
        val orderString = preferences[LyricsProviderOrderKey].orEmpty()

        if (orderString.isNotBlank()) {
            return LyricsProviderRegistry.getOrderedProviders(orderString)
        }

        // Migration path: place the old preferred provider first in the default order
        val preferredEnum = preferences[PreferredLyricsProviderKey]
            .toEnum(PreferredLyricsProvider.PAXSENIX)
        val preferredName = LyricsProviderRegistry.getProviderNameForEnum(preferredEnum)
        val defaultOrder = LyricsProviderRegistry.getDefaultProviderOrder()
        val migratedOrder = listOf(preferredName) + defaultOrder.filter { it != preferredName }
        return migratedOrder.mapNotNull { LyricsProviderRegistry.getProviderByName(it) }
    }



    private val cache = LruCache<String, List<LyricsResult>>(MAX_CACHE_SIZE)
    private var currentLyricsJob: Job? = null

    private val helperScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeFetches = mutableMapOf<String, Deferred<LyricsWithProvider>>()
    private val fetchesMutex = Mutex()

    suspend fun getLyrics(mediaMetadata: MediaMetadata): LyricsWithProvider {
        // No cancelamos currentLyricsJob aquí: ese trabajo es la búsqueda manual de letras,
        // y la precarga en segundo plano la cortaba a la mitad.
        val cached = cache.get(mediaMetadata.id)?.firstOrNull()
        if (cached != null) {
            return LyricsWithProvider(cached.lyrics, cached.providerName)
        }

        // Check network connectivity before making network requests
        // Use synchronous check as fallback if flow doesn't emit
        val isNetworkAvailable = try {
            networkConnectivity.isCurrentlyConnected()
        } catch (e: Exception) {
            // If network check fails, try to proceed anyway
            true
        }
        
        if (!isNetworkAvailable) {
            // Still proceed but return not found to avoid hanging
            return LyricsWithProvider(LYRICS_NOT_FOUND, "Unknown")
        }

        val cacheKey = mediaMetadata.id
        val deferred = fetchesMutex.withLock {
            activeFetches.getOrPut(cacheKey) {
                helperScope.async {
                    val preferences = context.dataStore.data.first()
                    val prioritizeSync = preferences[LyricsPrioritizeSyncAccuracyKey] ?: true
                    
                    val providers = resolveLyricsProviders()
                    
                    val channel = kotlinx.coroutines.channels.Channel<Triple<Int, String, String>>(kotlinx.coroutines.channels.Channel.UNLIMITED)
                    
                    val jobs = providers.mapIndexedNotNull { index, provider ->
                        // Los proveedores de YouTube buscan por id de video: no aplican a archivos locales.
                        val needsVideoId = provider === YouTubeLyricsProvider ||
                            provider === YouTubeSubtitleLyricsProvider
                        if (provider.isEnabled(context) && !(needsVideoId && mediaMetadata.id.isLocalMediaId())) {
                            launch {
                                try {
                                    // Si un proveedor tarda más de 3,5 s, se sigue sin él.
                                    withTimeoutOrNull(PROVIDER_FETCH_TIMEOUT_MS) {
                                        val result = provider.getLyrics(
                                            mediaMetadata.id,
                                            mediaMetadata.title,
                                            mediaMetadata.artists.joinToString { it.name },
                                            mediaMetadata.duration,
                                            mediaMetadata.album?.title,
                                        )
                                        result.onSuccess { lyrics ->
                                            if (lyrics.isNotBlank() && lyrics != LYRICS_NOT_FOUND) {
                                                channel.send(Triple(index, provider.name, lyrics))
                                            }
                                        }.onFailure {
                                            reportException(it)
                                        }
                                    }
                                } catch (e: Exception) {
                                    reportException(e)
                                }
                            }
                        } else null
                    }

                    launch {
                        kotlinx.coroutines.joinAll(*jobs.toTypedArray())
                        channel.close()
                    }

                    var bestLyrics = LYRICS_NOT_FOUND
                    var bestProvider = "Unknown"
                    var bestSyncType = LyricsUtils.SyncType.NONE
                    var bestIndex = Int.MAX_VALUE

                    try {
                        var deadline = System.currentTimeMillis() + 5000L

                        while (System.currentTimeMillis() < deadline) {
                            val remainingTime = (deadline - System.currentTimeMillis()).coerceAtLeast(1L)
                            val item = withTimeoutOrNull(remainingTime) {
                                channel.receiveCatching()
                            } ?: break

                            val triple = item.getOrNull() ?: break
                            val (index, providerName, lyrics) = triple

                            if (!prioritizeSync) {
                                if (index < bestIndex) {
                                    bestLyrics = lyrics
                                    bestProvider = providerName
                                    bestIndex = index
                                    if (index == 0) break
                                    deadline = minOf(deadline, System.currentTimeMillis() + 500L)
                                }
                            } else {
                                val syncType = try {
                                    LyricsUtils.getSyncType(lyrics)
                                } catch (e: Exception) {
                                    reportException(e)
                                    LyricsUtils.SyncType.NONE
                                }

                                if (syncType == LyricsUtils.SyncType.WORD) {
                                    if (bestSyncType != LyricsUtils.SyncType.WORD || index < bestIndex) {
                                        bestLyrics = lyrics
                                        bestProvider = providerName
                                        bestSyncType = syncType
                                        bestIndex = index
                                    }
                                    if (index == 0) break
                                    deadline = minOf(deadline, System.currentTimeMillis() + 400L)
                                } else if (syncType == LyricsUtils.SyncType.LINE) {
                                    val isBetter = bestSyncType == LyricsUtils.SyncType.NONE ||
                                        (bestSyncType == LyricsUtils.SyncType.LINE && index < bestIndex)
                                    if (isBetter) {
                                        bestLyrics = lyrics
                                        bestProvider = providerName
                                        bestSyncType = syncType
                                        bestIndex = index
                                    }
                                    if (bestSyncType == LyricsUtils.SyncType.LINE) {
                                        if (index == 0) {
                                            deadline = minOf(deadline, System.currentTimeMillis() + 500L)
                                        } else {
                                            deadline = minOf(deadline, System.currentTimeMillis() + 800L)
                                        }
                                    }
                                } else {
                                    if (bestLyrics == LYRICS_NOT_FOUND) {
                                        bestLyrics = lyrics
                                        bestProvider = providerName
                                        bestSyncType = syncType
                                        bestIndex = index
                                        deadline = minOf(deadline, System.currentTimeMillis() + 1200L)
                                    } else if (bestSyncType == LyricsUtils.SyncType.NONE && index < bestIndex) {
                                        bestLyrics = lyrics
                                        bestProvider = providerName
                                        bestIndex = index
                                    }
                                }
                            }
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        reportException(e)
                    } finally {
                        jobs.forEach { it.cancel() }
                        channel.close()
                    }

                    if (bestLyrics != LYRICS_NOT_FOUND) {
                        cache.put(cacheKey, listOf(LyricsResult(bestProvider, bestLyrics)))
                    }
                    LyricsWithProvider(bestLyrics, bestProvider)
                }
            }
        }

        return try {
            deferred.await()
        } finally {
            fetchesMutex.withLock {
                activeFetches.remove(cacheKey)
            }
        }
    }

    suspend fun getAllLyrics(
        mediaId: String,
        songTitle: String,
        songArtists: String,
        duration: Int,
        album: String? = null,
        callback: (LyricsResult) -> Unit,
    ) {
        currentLyricsJob?.cancel()

        val cacheKey = "$songArtists-$songTitle".replace(" ", "")
        cache.get(cacheKey)?.takeIf { it.isNotEmpty() }?.let { results ->
            results.forEach {
                callback(it)
            }
            return
        }

        // Check network connectivity before making network requests
        // Use synchronous check as fallback if flow doesn't emit
        val isNetworkAvailable = try {
            networkConnectivity.isCurrentlyConnected()
        } catch (e: Exception) {
            // If network check fails, try to proceed anyway
            true
        }
        
        if (!isNetworkAvailable) {
            // Still try to proceed in case of false negative
            return
        }

        val allResult = mutableListOf<LyricsResult>()
        val providers = resolveLyricsProviders()
        currentLyricsJob = CoroutineScope(SupervisorJob()).launch {
            // Todos los proveedores buscan a la vez, cada uno guarda sus resultados en su propio canal.
            val enabled = providers.filter { it.isEnabled(context) }
            val channels = enabled.map {
                kotlinx.coroutines.channels.Channel<LyricsResult>(kotlinx.coroutines.channels.Channel.UNLIMITED)
            }
            enabled.forEachIndexed { i, provider ->
                launch {
                    try {
                        // Si un proveedor tarda demasiado, se sigue con los de abajo.
                        withTimeoutOrNull(PROVIDER_TIMEOUT_MS) {
                            provider.getAllLyrics(mediaId, songTitle, songArtists, duration, album) { lyrics ->
                                channels[i].trySend(LyricsResult(provider.name, lyrics))
                            }
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // Catch network-related exceptions like UnresolvedAddressException
                        reportException(e)
                    } finally {
                        channels[i].close()
                    }
                }
            }
            // Se muestran en el orden de prioridad: los de un proveedor solo salen
            // cuando los de los proveedores de arriba ya terminaron de salir.
            for (channel in channels) {
                for (result in channel) {
                    allResult += result
                    callback(result)
                }
            }
            // Solo se guarda si la búsqueda terminó completa y encontró algo;
            // así una búsqueda cortada o vacía no se queda guardada.
            if (isActive && allResult.isNotEmpty()) cache.put(cacheKey, allResult)
        }

        currentLyricsJob?.join()
    }

    companion object {
        private const val MAX_CACHE_SIZE = 3
        private const val PROVIDER_TIMEOUT_MS = 15_000L
        private const val PROVIDER_FETCH_TIMEOUT_MS = 3_500L
    }
}

data class LyricsResult(
    val providerName: String,
    val lyrics: String,
)

data class LyricsWithProvider(
    val lyrics: String,
    val provider: String,
)