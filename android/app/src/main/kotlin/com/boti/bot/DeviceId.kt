package com.boti.bot

import android.content.Context
import java.util.UUID

object DeviceId {
    private var id: String? = null

    fun init(context: Context) {
        if (id != null) return
        val prefs = context.getSharedPreferences("boti", Context.MODE_PRIVATE)
        id = prefs.getString("device_id", null) ?: run {
            val newId = UUID.randomUUID().toString()
            prefs.edit().putString("device_id", newId).apply()
            newId
        }
    }

    fun get(): String? = id
}
