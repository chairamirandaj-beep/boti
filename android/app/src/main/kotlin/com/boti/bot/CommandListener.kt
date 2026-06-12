package com.boti.bot

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

object CommandListener {
    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val trigger = Channel<Unit>(Channel.CONFLATED)

    @Volatile var stopCurrent = false

    fun start() {
        // Conectar Realtime: cada INSERT en commands dispara el trigger inmediatamente
        val deviceId = DeviceId.get() ?: return
        RealtimeClient.onNewCommand = { trigger.trySend(Unit) }
        RealtimeClient.connect(deviceId)

        if (job?.isActive == true) return
        job = scope.launch {
            val id = DeviceId.get() ?: return@launch
            SupabaseClient.addLog(id, "info", "Bot iniciado (Realtime activo)")

            while (isActive) {
                // Espera trigger de Realtime O timeout de 10s (polling de respaldo)
                withTimeoutOrNull(10_000) { trigger.receive() }

                // Latido: avisa que el teléfono sigue vivo (cada ~10s o al recibir comando)
                runCatching { SupabaseClient.heartbeat(id) }

                // Si está pausado, no procesar comandos
                if (BotService.instance?.isListening != true) continue

                try {
                    val command = SupabaseClient.getPendingCommand(id)
                    if (command != null) {
                        val cmdId   = command.getString("id")
                        val action  = command.getString("action")
                        val payload = command.optString("payload", null)

                        if (action.uppercase() == "STOP") {
                            stopCurrent = true
                            SupabaseClient.cancelPendingCommands(id)
                            SupabaseClient.markCommand(cmdId, "done")
                            SupabaseClient.addLog(id, "info", "STOP: cola limpiada")
                            continue
                        }

                        stopCurrent = false
                        SupabaseClient.markCommand(cmdId, "executing")
                        SupabaseClient.addLog(id, "info", "Ejecutando: $action")

                        CommandExecutor.execute(action, payload)

                        SupabaseClient.markCommand(cmdId, "done")
                        SupabaseClient.addLog(id, "info", "Completado: $action")
                    }
                } catch (e: Exception) {
                    runCatching {
                        SupabaseClient.addLog(id, "error", "Error: ${e.message}")
                    }
                }
            }
        }
    }

    fun cancelCurrent() {
        stopCurrent = true
    }

    fun stop() {
        job?.cancel()
        job = null
        RealtimeClient.disconnect()
    }
}
