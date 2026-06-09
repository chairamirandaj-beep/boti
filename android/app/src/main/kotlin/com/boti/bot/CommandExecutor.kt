package com.boti.bot

import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.view.accessibility.AccessibilityNodeInfo

object CommandExecutor {

    fun execute(action: String, payload: String?) {
        when (action.uppercase()) {
            "SCROLL"        -> scroll()
            "STOP"          -> CommandListener.stop()
            "OPEN_APP"      -> openApp(payload ?: return)
            "FIND_CLICK"    -> findAndClick(payload ?: return)
            "WAIT"          -> Thread.sleep(payload?.toLongOrNull() ?: 1000L)
            "TIKTOK_LIKE"   -> tiktokLikeLoop(payload?.toIntOrNull() ?: 2)
            "WHATSAPP_TAB"  -> whatsappTab(payload ?: "Novedades")
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
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) ?: return
        service.startActivity(intent)
    }

    private fun findAndClick(query: String): Boolean {
        val service = BotService.instance ?: return false
        val root = service.rootInActiveWindow ?: return false
        val node = findNode(root, query.lowercase()) ?: return false
        val target = findClickableAncestor(node) ?: node
        val clicked = target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
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
            if (found != null) return found
        }
        return null
    }

    private fun findClickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        repeat(5) {
            if (current?.isClickable == true) return current
            current = current?.parent
        }
        return null
    }

    private fun tiktokLikeLoop(times: Int) {
        val deviceId = DeviceId.get() ?: return

        openApp("com.zhiliaoapp.musically")
        log(deviceId, "info", "Abriendo TikTok...")
        Thread.sleep(4000)

        repeat(times) { i ->
            try {
                // 1. Intentar por accessibility tree (varias descripciones posibles)
                val liked = findAndClick("like")
                    || findAndClick("me gusta")
                    || findAndClick("gusta")
                    || findAndClick("coraz")
                    || tapTiktokLikeButton() // fallback por coordenadas

                log(deviceId, "info", "Video ${i + 1}/$times — like: ${if (liked) "OK" else "fallido"}")
                Thread.sleep(1000)

                scroll()
                Thread.sleep(3000)
            } catch (e: Exception) {
                log(deviceId, "error", "Error video ${i + 1}: ${e.message}")
            }
        }

        log(deviceId, "info", "Secuencia TikTok completada: $times videos")
    }

    // Toca donde TikTok siempre pone el botón de like (lado derecho, ~55% altura)
    private fun tapTiktokLikeButton(): Boolean {
        val service = BotService.instance ?: return false
        val metrics = service.resources.displayMetrics
        val x = metrics.widthPixels * 0.93f
        val y = metrics.heightPixels * 0.55f

        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 50L)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        service.dispatchGesture(gesture, null, null)
        return true
    }

    private fun whatsappTab(tabName: String) {
        val deviceId = DeviceId.get() ?: return

        log(deviceId, "info", "Paso 1: Abriendo WhatsApp...")
        openApp("com.whatsapp")
        Thread.sleep(3000)

        log(deviceId, "info", "Paso 2: Buscando pestaña '$tabName'...")
        val found = findAndClick(tabName)

        if (found) {
            log(deviceId, "info", "Paso 3: '$tabName' encontrado y clickeado ✓")
        } else {
            log(deviceId, "warn", "Paso 3: '$tabName' no encontrado en pantalla ✗")
        }
    }

    private fun log(deviceId: String, level: String, message: String) {
        try { SupabaseClient.addLog(deviceId, level, message) } catch (_: Exception) {}
    }
}
