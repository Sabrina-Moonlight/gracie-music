/**
 * vivimusic Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.music.vivi.utils.cipher

import android.content.Context
import timber.log.Timber

/**
 * Holds the app context for the cipher package.
 */
object CipherDeobfuscator {
    private const val TAG = "vivimusic_CipherDeobfusc"

    lateinit var appContext: Context
        private set

    fun initialize(context: Context) {
        Timber.tag(TAG).d("CipherDeobfuscator initializing...")
        appContext = context.applicationContext
        Timber.tag(TAG).d("CipherDeobfuscator initialized")
    }
}
