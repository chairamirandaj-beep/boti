package com.boti.bot

import android.content.Context
import android.provider.Settings
import java.util.UUID

object DeviceId {
    private var id: String? = null

    fun init(context: Context) {
        if (id != null) return
        val prefs = context.getSharedPreferences("boti", Context.MODE_PRIVATE)

        // 1. Si ya hay uno guardado Y es un UUID válido, usarlo (compatibilidad).
        //    Si lo guardado NO es UUID (ej. "android-xxxx" de una versión anterior),
        //    se descarta y se regenera, porque la columna devices.id es de tipo uuid
        //    y un valor no-UUID hace fallar el registro en silencio.
        val saved = prefs.getString("device_id", null)
        if (saved != null && isUuid(saved)) { id = saved; return }

        // 2. UUID DETERMINÍSTICO derivado del ANDROID_ID: estable por dispositivo+firma,
        //    sobrevive reinstalaciones (mismo ANDROID_ID -> mismo UUID) y es un uuid válido.
        val androidId = try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        } catch (_: Exception) { null }

        val stable = if (!androidId.isNullOrBlank() && androidId != "9774d56d682e549c")
            UUID.nameUUIDFromBytes("boti-$androidId".toByteArray()).toString()
        else
            UUID.randomUUID().toString()  // fallback si ANDROID_ID no es confiable

        prefs.edit().putString("device_id", stable).apply()
        id = stable
    }

    private fun isUuid(s: String): Boolean =
        try { UUID.fromString(s); true } catch (_: Exception) { false }

    fun get(): String? = id
}
