package com.boti.bot

import kotlinx.coroutines.*

object CommandListener {
    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            val deviceId = DeviceId.get() ?: return@launch
            SupabaseClient.addLog(deviceId, "info", "Bot iniciado, escuchando comandos")

            while (isActive) {
                try {
                    val command = SupabaseClient.getPendingCommand(deviceId)
                    if (command != null) {
                        val id      = command.getString("id")
                        val action  = command.getString("action")
                        val payload = command.optString("payload", null)

                        SupabaseClient.markCommand(id, "executing")
                        SupabaseClient.addLog(deviceId, "info", "Ejecutando: $action")

                        CommandExecutor.execute(action, payload)

                        SupabaseClient.markCommand(id, "done")
                        SupabaseClient.addLog(deviceId, "info", "Completado: $action")
                    }
                } catch (e: Exception) {
                    runCatching {
                        SupabaseClient.addLog(deviceId, "error", "Error: ${e.message}")
                    }
                }
                delay(2000)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}
