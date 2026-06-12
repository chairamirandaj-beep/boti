package com.boti.bot

import android.content.Context
import android.provider.Settings
import java.util.UUID

object DeviceId {
    private var id: String? = null

    fun init(context: Context) {
        if (id != null) return
        val prefs = context.getSharedPreferences("boti", Context.MODE_PRIVATE)

        // 1. Si ya hay uno guardado, usarlo (compatibilidad con instalaciones previas)
        prefs.getString("device_id", null)?.let { id = it; return }

        // 2. ANDROID_ID: estable por dispositivo+firma, sobrevive reinstalaciones.
        //    Evita crear un teléfono nuevo en cada reinstalación.
        val androidId = try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        } catch (_: Exception) { null }

        val stable = if (!androidId.isNullOrBlank() && androidId != "9774d56d682e549c")
            "android-$androidId"
        else
            UUID.randomUUID().toString()  // fallback si ANDROID_ID no es confiable

        prefs.edit().putString("device_id", stable).apply()
        id = stable
    }

    fun get(): String? = id
}
