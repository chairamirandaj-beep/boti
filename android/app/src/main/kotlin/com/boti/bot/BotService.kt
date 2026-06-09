package com.boti.bot

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.*

class BotService : AccessibilityService() {

    companion object {
        var instance: BotService? = null
    }

    @Volatile var isListening = true

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onServiceConnected() {
        instance = this
        DeviceId.init(applicationContext)

        scope.launch {
            runCatching { SupabaseClient.updateDeviceStatus(DeviceId.get()!!, "online") }
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
        scope.launch {
            runCatching { SupabaseClient.updateDeviceStatus(DeviceId.get()!!, "offline") }
        }
        instance = null
    }
}
