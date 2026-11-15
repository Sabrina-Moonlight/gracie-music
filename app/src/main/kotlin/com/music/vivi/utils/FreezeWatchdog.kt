package com.music.vivi.utils

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Herramienta de diagnóstico: si la app se queda congelada más de 4 segundos, guarda qué estaba
 * haciendo (el "rastro" del hilo principal). Al abrir la app otra vez copia ese informe al
 * portapapeles para poder pegarlo en el chat.
 */
object FreezeWatchdog {
    private const val FILE_NAME = "freeze_report.txt"
    private const val FREEZE_MS = 4000L

    @Volatile private var started = false

    fun start(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        val mainHandler = Handler(Looper.getMainLooper())

        mainHandler.postDelayed({ copyPreviousReport(app) }, 3000L)

        val thread = Thread {
            val file = File(app.filesDir, FILE_NAME)
            while (true) {
                val answered = AtomicBoolean(false)
                mainHandler.post { answered.set(true) }
                val start = System.currentTimeMillis()
                try {
                    Thread.sleep(FREEZE_MS)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                if (!answered.get()) {
                    runCatching {
                        val trace = Looper.getMainLooper().thread.stackTrace
                        val text = buildString {
                            append("Congelada ${System.currentTimeMillis() - start} ms\n")
                            trace.forEach { append("  at ").append(it).append('\n') }
                        }
                        // Solo se guarda el primer informe, hasta que se copie.
                        if (!file.exists()) file.writeText(text)
                    }
                }
            }
        }
        thread.isDaemon = true
        thread.name = "FreezeWatchdog"
        thread.start()
    }

    private fun copyPreviousReport(context: Context) {
        runCatching {
            val file = File(context.filesDir, FILE_NAME)
            if (!file.exists()) return
            val text = file.readText()
            file.delete()
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("freeze_report", text))
            Toast.makeText(context, "Informe de congelamiento copiado: pégalo en el chat", Toast.LENGTH_LONG).show()
        }
    }
}
