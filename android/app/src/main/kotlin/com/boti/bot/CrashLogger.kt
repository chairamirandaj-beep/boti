package com.boti.bot

import android.content.Context
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Captura excepciones no atrapadas (que matan el proceso) y guarda el stack trace
 * en SharedPreferences de forma SÍNCRONA. En el siguiente arranque se sube a los
 * logs de Supabase. Sirve para diagnosticar crashes específicos de dispositivo
 * (p.ej. Android 9 / Huawei) que reinician el servicio de accesibilidad.
 */
object CrashLogger {
    private const val PREF = "boti_crash"
    private const val KEY = "last_crash"

    fun install(context: Context) {
        val appCtx = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, ex ->
            runCatching {
                val sw = StringWriter()
                ex.printStackTrace(PrintWriter(sw))
                val text = "CRASH en ${thread.name}: ${sw.toString().take(3500)}"
                appCtx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                    .edit().putString(KEY, text).commit()  // commit() = síncrono
            }
            previous?.uncaughtException(thread, ex)
        }
    }

    /** Sube y limpia el último crash guardado (llamar al arrancar). */
    fun flush(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val crash = prefs.getString(KEY, null) ?: return
        prefs.edit().remove(KEY).apply()
        val id = DeviceId.get() ?: return
        runCatching { SupabaseClient.addLog(id, "error", crash) }
    }
}
