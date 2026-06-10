package com.boti.bot

import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.delay

object CommandExecutor {

    suspend fun execute(action: String, payload: String?) {
        when (action.uppercase()) {
            "SCROLL"                -> scroll()
            "OPEN_APP"              -> openApp(payload ?: return)
            "FIND_CLICK"            -> findAndClick(payload ?: return)
            "WAIT"                  -> delay(payload?.toLongOrNull() ?: 1000L)
            "TIKTOK_OPEN"           -> tiktokOpen()
            "TIKTOK_LIKE"           -> tiktokLikeLoop(payload?.toIntOrNull() ?: 1)
            "TIKTOK_FOLLOW"         -> tiktokFollow()
            "TIKTOK_SWITCH_ACCOUNT" -> tiktokSwitchAccount(payload ?: return)
            "SCREENSHOT"            -> takeScreenshot()
            "WHATSAPP_TAB"          -> whatsappTab(payload ?: "Novedades")
            "DEBUG_NODES"           -> debugNodes()
        }
    }

    private fun scroll() {
        val service = BotService.instance ?: return
        val metrics = service.resources.displayMetrics
        val centerX = metrics.widthPixels / 2f
        val startY  = metrics.heightPixels * 0.75f
        val endY    = metrics.heightPixels * 0.25f

        val path = Path().apply {
            moveTo(centerX, startY)
            lineTo(centerX, endY)
        }
        val stroke  = GestureDescription.StrokeDescription(path, 0L, 400L)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        service.dispatchGesture(gesture, null, null)
    }

    private fun openApp(packageName: String) {
        val service = BotService.instance ?: return
        val intent = service.packageManager.getLaunchIntentForPackage(packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP) ?: return
        service.startActivity(intent)
    }

    private fun findAndClick(query: String): Boolean {
        val service = BotService.instance ?: return false
        val root = service.rootInActiveWindow ?: return false
        val node = findNode(root, query.lowercase())
        if (node == null) {
            root.recycle()
            return false
        }
        val target = findClickableAncestor(node) ?: node
        val clicked = target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        if (target !== node) target.recycle()
        if (node !== root) node.recycle()
        root.recycle()
        return clicked
    }

    private fun findNode(node: AccessibilityNodeInfo, query: String): AccessibilityNodeInfo? {
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        val text = node.text?.toString()?.lowercase() ?: ""
        if (desc.contains(query) || text.contains(query)) return node

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findNode(child, query)
            if (found != null) {
                if (found !== child) child.recycle()
                return found
            }
            child.recycle()
        }
        return null
    }

    private fun findClickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isClickable) return node
        var current: AccessibilityNodeInfo? = node.parent
        repeat(5) {
            val c = current ?: return null
            if (c.isClickable) return c
            val parent = c.parent
            c.recycle()
            current = parent
        }
        current?.recycle()
        return null
    }

    private suspend fun tiktokLikeLoop(times: Int) {
        val deviceId = DeviceId.get() ?: return

        openApp("com.zhiliaoapp.musically")
        log(deviceId, "info", "Abriendo TikTok...")
        delay(5000)

        repeat(times) { i ->
            if (CommandListener.stopCurrent) {
                log(deviceId, "info", "Secuencia detenida por STOP")
                return
            }
            try {
                val liked = findAndClick("me gusta")
                    || findAndClick("me gustó")
                    || findAndClick("dar me gusta")
                    || findAndClick("like")
                    || findAndClick("liked")
                    || findAndClick("coraz")

                if (liked) {
                    log(deviceId, "info", "Video ${i + 1}/$times — like: accesibilidad OK")
                } else {
                    val tapped = tapTiktokLikeButton()
                    log(deviceId, "warn", "Video ${i + 1}/$times — like: fallback coordenadas (${if (tapped) "disparado" else "fallo servicio"})")
                }

                delay(1500)

                if (!CommandListener.stopCurrent) {
                    scroll()
                    delay(3500)
                }
            } catch (e: Exception) {
                log(deviceId, "error", "Error video ${i + 1}: ${e.message}")
            }
        }

        log(deviceId, "info", "Secuencia TikTok completada: $times videos")
    }

    // Like button en Galaxy A55 (1080x2340): x≈93%, y≈45%
    // Si el log dice "fallback coordenadas" significa que la accesibilidad no lo encontró
    private fun tapTiktokLikeButton(): Boolean {
        val service = BotService.instance ?: return false
        val metrics = service.resources.displayMetrics
        val x = metrics.widthPixels * 0.93f
        val y = metrics.heightPixels * 0.45f

        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 80L)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        service.dispatchGesture(gesture, null, null)
        return true
    }

    private suspend fun whatsappTab(tabName: String) {
        val deviceId = DeviceId.get() ?: return

        log(deviceId, "info", "Abriendo WhatsApp en pantalla principal...")
        openApp("com.whatsapp")
        delay(4000)

        log(deviceId, "info", "Buscando pestaña '$tabName'...")
        val found = findAndClick(tabName.lowercase()) || findAndClick(tabName)

        if (found) {
            log(deviceId, "info", "Pestaña '$tabName' encontrada ✓")
        } else {
            log(deviceId, "warn", "Pestaña '$tabName' no encontrada — WhatsApp debe estar en español y en la pantalla de chats")
        }
    }

    private suspend fun tiktokOpen() {
        val deviceId = DeviceId.get() ?: return
        openApp("com.zhiliaoapp.musically")
        log(deviceId, "info", "TikTok abierto")
        delay(3000)
    }

    private suspend fun tiktokFollow() {
        val deviceId = DeviceId.get() ?: return
        log(deviceId, "info", "Buscando botón Seguir/Follow...")

        val found = findAndClick("seguir")
            || findAndClick("follow")

        if (found) {
            log(deviceId, "info", "Seguido ✓")
        } else {
            // Fallback: coordenadas del botón Follow en TikTok (encima del avatar, lado derecho del video)
            val service = BotService.instance ?: return
            val metrics = service.resources.displayMetrics
            val x = metrics.widthPixels * 0.93f
            val y = metrics.heightPixels * 0.55f
            val path = Path().apply { moveTo(x, y) }
            val stroke = GestureDescription.StrokeDescription(path, 0L, 80L)
            service.dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
            log(deviceId, "warn", "Follow: fallback coordenadas")
        }
    }

    private suspend fun tiktokSwitchAccount(accountName: String) {
        val deviceId = DeviceId.get() ?: return
        log(deviceId, "info", "Cambiando a cuenta: $accountName")

        // 1. Ir al tab Perfil
        val profileFound = findAndClick("perfil") || findAndClick("profile") || findAndClick("yo")
        delay(1500)

        // 2. Tocar el nombre de usuario (abre el selector de cuentas)
        val nameFound = findAndClick(accountName.lowercase())
        if (!nameFound) {
            // Tocar la zona del nombre de usuario en la parte superior del perfil (~centro, y=15%)
            val service = BotService.instance ?: return
            val metrics = service.resources.displayMetrics
            val x = metrics.widthPixels * 0.5f
            val y = metrics.heightPixels * 0.15f
            val path = Path().apply { moveTo(x, y) }
            val stroke = GestureDescription.StrokeDescription(path, 0L, 80L)
            service.dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
            delay(1500)
            // Intentar seleccionar la cuenta por nombre
            findAndClick(accountName.lowercase())
        }
        log(deviceId, if (nameFound) "info" else "warn",
            if (nameFound) "Cuenta '$accountName' seleccionada ✓" else "Cuenta '$accountName': buscar manual en selector")
    }

    private fun debugNodes() {
        val deviceId = DeviceId.get() ?: return
        val root = BotService.instance?.rootInActiveWindow ?: run {
            log(deviceId, "error", "DEBUG: sin ventana activa")
            return
        }
        val found = mutableListOf<String>()
        collectNodes(root, found, depth = 0)
        root.recycle()

        if (found.isEmpty()) {
            log(deviceId, "warn", "DEBUG: árbol vacío — TikTok no expone nodos")
        } else {
            log(deviceId, "info", "DEBUG: ${found.size} nodos encontrados")
            found.take(30).forEach { log(deviceId, "info", it) }
        }
    }

    private fun collectNodes(node: AccessibilityNodeInfo, out: MutableList<String>, depth: Int) {
        val desc = node.contentDescription?.toString()?.trim()
        val text = node.text?.toString()?.trim()
        val cls  = node.className?.toString()?.substringAfterLast('.') ?: ""
        val click = if (node.isClickable) "✓" else " "

        if (!desc.isNullOrEmpty() || !text.isNullOrEmpty()) {
            val label = when {
                !desc.isNullOrEmpty() && !text.isNullOrEmpty() -> "desc=\"$desc\" text=\"$text\""
                !desc.isNullOrEmpty() -> "desc=\"$desc\""
                else -> "text=\"$text\""
            }
            out.add("[$click][$cls] $label")
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectNodes(child, out, depth + 1)
            child.recycle()
        }
    }

    private fun takeScreenshot() {
        val deviceId = DeviceId.get() ?: return
        // AccessibilityService.takeScreenshot() requiere Android 11+ y callback
        // Por ahora logueamos que el comando fue recibido
        log(deviceId, "info", "Screenshot: usa el agente Python con ADB para capturas")
    }

    private fun log(deviceId: String, level: String, message: String) {
        try { SupabaseClient.addLog(deviceId, level, message) } catch (_: Exception) {}
    }
}
