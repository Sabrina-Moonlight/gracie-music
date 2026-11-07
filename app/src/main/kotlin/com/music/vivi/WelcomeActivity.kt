/**
 * vivimusic Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */
package com.music.vivi

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.music.vivi.constants.AppLanguageKey
import com.music.vivi.constants.SYSTEM_DEFAULT
import com.music.vivi.utils.dataStore
import com.music.vivi.utils.get

/**
 * Launcher entry point. The first-run welcome/onboarding screen was removed in this fork:
 * this activity only sets the app language (Android < 13) and opens [MainActivity] right away.
 */
class WelcomeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            val appLang = dataStore.get(AppLanguageKey, SYSTEM_DEFAULT)
            val locale = appLang
                .takeUnless { it == SYSTEM_DEFAULT }
                ?.let { java.util.Locale.forLanguageTag(it) }
                ?: java.util.Locale.getDefault()
            com.music.vivi.utils.setAppLocale(this, locale)
        }

        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
