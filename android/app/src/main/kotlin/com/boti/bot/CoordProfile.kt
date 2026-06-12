package com.boti.bot

import org.json.JSONObject

// Perfil de coordenadas calibradas por teléfono. Se refresca desde Supabase antes de
// ejecutar cada comando. Cada acción que usa coordenada consulta aquí; si no hay valor
// calibrado, usa el default que le pasa quien llama.
object CoordProfile {
    @Volatile private var coords: JSONObject = JSONObject()

    fun refresh() {
        val id = DeviceId.get() ?: return
        runCatching { SupabaseClient.getCoords(id) }.getOrNull()?.let { coords = it }
    }

    // Devuelve [x,y] calibrado para `key`, o el default si no está calibrado.
    fun get(key: String, defX: Float, defY: Float): Pair<Float, Float> {
        val arr = coords.optJSONArray(key) ?: return defX to defY
        if (arr.length() < 2) return defX to defY
        val x = arr.optDouble(0, defX.toDouble()).toFloat()
        val y = arr.optDouble(1, defY.toDouble()).toFloat()
        return x to y
    }
}
