package com.boti.bot

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.PowerManager
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.*

class BotService : AccessibilityService() {

    companion object {
        var instance: BotService? = null
    }

    @Volatile var isListening = true

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onServiceConnected() {
        instance = this
        DeviceId.init(applicationContext)

        // WakeLock parcial: mantiene CPU activa, evita que Samsung congele el proceso
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "boti:BotServiceLock").also {
            it.acquire()
        }

        scope.launch {
            runCatching {
                val id = DeviceId.get()!!
                // Upsert (no solo PATCH): garantiza que la fila exista aunque el registro
                // inicial en MainActivity haya fallado (p.ej. sin red al abrir la app).
                SupabaseClient.registerDevice(id, android.os.Build.MODEL)
                val m = resources.displayMetrics
                SupabaseClient.reportResolution(id, m.widthPixels, m.heightPixels)
                CoordProfile.refresh()
            }
        }

        CommandListener.start()
    }

    fun applyListening(value: Boolean) {
        isListening = value
        val status = if (value) "online" else "paused"
        scope.launch {
            runCatching {
                val id = DeviceId.get() ?: return@runCatching
                if (!value) SupabaseClient.cancelPendingCommands(id)
                SupabaseClient.updateDeviceStatus(id, status)
                SupabaseClient.addLog(id, "info", if (value) "Bot activado" else "Bot pausado")
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        CommandListener.stop()
        wakeLock?.release()
        scope.launch {
            runCatching { SupabaseClient.updateDeviceStatus(DeviceId.get()!!, "offline") }
        }
        instance = null
    }
}
