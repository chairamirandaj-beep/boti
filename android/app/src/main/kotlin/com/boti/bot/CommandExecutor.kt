package com.boti.bot

import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
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
            "TIKTOK_LIKE"           -> tiktokLike()
            "TIKTOK_SAVE"           -> tiktokSave()
            "TIKTOK_FOLLOW"         -> tiktokFollow()
            "TIKTOK_SWITCH_ACCOUNT" -> tiktokSwitchAccount(payload ?: return)
            "WHATSAPP_TAB"          -> whatsappTab(payload ?: "Novedades")
            "DEBUG_NODES"           -> debugNodes()
        }
    }

    // ── Gestures ─────────────────────────────────────────────────────────────

    private fun scroll() {
        val service = BotService.instance ?: return
        val metrics = service.resources.displayMetrics
        val cx = metrics.widthPixels / 2f
        val path = Path().apply {
            moveTo(cx, metrics.heightPixels * 0.75f)
            lineTo(cx, metrics.heightPixels * 0.25f)
        }
        service.dispatchGesture(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0L, 400L))
                .build(), null, null
        )
    }

    private fun tapAt(x: Float, y: Float) {
        val service = BotService.instance ?: return
        val path = Path().apply { moveTo(x, y) }
        service.dispatchGesture(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0L, 80L))
                .build(), null, null
        )
    }

    private fun tapNodeCenter(node: AccessibilityNodeInfo): Boolean {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (rect.isEmpty) return false
        tapAt(rect.centerX().toFloat(), rect.centerY().toFloat())
        return true
    }

    // ── Node search ───────────────────────────────────────────────────────────

    private fun openApp(packageName: String) {
        val service = BotService.instance ?: return
        val intent = service.packageManager.getLaunchIntentForPackage(packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP) ?: return
        service.startActivity(intent)
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

    // Igual que findNode pero solo retorna nodos con bounds válidos dentro de la pantalla.
    // Necesario en TikTok donde hay múltiples videos en el árbol con bounds fuera de pantalla.
    private fun findNodeOnScreen(node: AccessibilityNodeInfo, query: String, screenH: Int): AccessibilityNodeInfo? {
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        val text = node.text?.toString()?.lowercase() ?: ""
        if (desc.contains(query) || text.contains(query)) {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            if (rect.top >= 0 && rect.top < rect.bottom && rect.bottom <= screenH) {
                return node
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findNodeOnScreen(child, query, screenH)
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

    // Busca el nodo y lo toca — primero intenta ACTION_CLICK, si falla toca el centro por gesture
    private fun findAndClick(query: String): Boolean {
        val root = BotService.instance?.rootInActiveWindow ?: return false
        val node = findNode(root, query.lowercase())
        if (node == null) { root.recycle(); return false }

        val target = findClickableAncestor(node) ?: node
        val clicked = target.performAction(AccessibilityNodeInfo.ACTION_CLICK)

        if (!clicked) {
            // Fallback: gesture tap en el centro del nodo (funciona para nodos no-clickable como Favoritos)
            tapNodeCenter(node)
        }

        if (target !== node) target.recycle()
        if (node !== root) node.recycle()
        root.recycle()
        return true
    }

    // ── TikTok ────────────────────────────────────────────────────────────────

    private suspend fun tiktokOpen() {
        val deviceId = DeviceId.get() ?: return
        openApp("com.zhiliaoapp.musically")
        delay(3000)
        log(deviceId, "info", "TikTok abierto")
    }

    private suspend fun tiktokLike() {
        val deviceId = DeviceId.get() ?: return
        // TikTok expone: desc="Dar me gusta a un video. N me gusta"
        val found = findAndClick("dar me gusta")
        log(deviceId, if (found) "info" else "warn",
            if (found) "Like dado ✓" else "Botón like no encontrado — ¿estás en TikTok?")
    }

    private suspend fun tiktokSave() {
        val deviceId = DeviceId.get() ?: return
        val service = BotService.instance ?: return
        val root = service.rootInActiveWindow ?: run {
            log(deviceId, "error", "Guardar: sin ventana activa")
            return
        }
        val screenH = service.resources.displayMetrics.heightPixels

        // Buscar el nodo favoritos con bounds DENTRO de la pantalla
        // TikTok tiene múltiples videos en el árbol — filtramos por coordenadas válidas
        val node = findNodeOnScreen(root, "favoritos", screenH)
        root.recycle()

        if (node != null) {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            node.recycle()
            val x = rect.centerX().toFloat()
            val y = rect.centerY().toFloat()
            tapAt(x, y)
            log(deviceId, "info", "Guardado ✓ (tap en $x, $y)")
        } else {
            log(deviceId, "warn", "Guardar: nodo favoritos no encontrado en pantalla")
        }
    }

    private suspend fun tiktokFollow() {
        val deviceId = DeviceId.get() ?: return
        val service = BotService.instance ?: return
        val root = service.rootInActiveWindow ?: run {
            log(deviceId, "error", "Follow: sin ventana activa")
            return
        }
        val screenH = service.resources.displayMetrics.heightPixels

        // Buscar "Seguir a" con bounds dentro de pantalla (el video actual)
        val node = findNodeOnScreen(root, "seguir a", screenH)
        root.recycle()

        if (node != null) {
            val clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            if (!clicked) {
                val rect = Rect()
                node.getBoundsInScreen(rect)
                if (!rect.isEmpty) tapAt(rect.centerX().toFloat(), rect.centerY().toFloat())
            }
            node.recycle()
            log(deviceId, "info", "Seguido ✓")
        } else {
            log(deviceId, "warn", "Botón seguir no encontrado en pantalla")
        }
    }

    private suspend fun tiktokSwitchAccount(accountName: String) {
        val deviceId = DeviceId.get() ?: return
        log(deviceId, "info", "Cambiando a cuenta: $accountName")

        findAndClick("perfil") || findAndClick("profile")
        delay(1500)

        val found = findAndClick(accountName.lowercase())
        if (!found) {
            val service = BotService.instance ?: return
            val metrics = service.resources.displayMetrics
            tapAt(metrics.widthPixels * 0.5f, metrics.heightPixels * 0.15f)
            delay(1500)
            findAndClick(accountName.lowercase())
        }
        log(deviceId, if (found) "info" else "warn",
            if (found) "Cuenta '$accountName' seleccionada ✓" else "Cuenta '$accountName': no encontrada en selector")
    }

    // ── WhatsApp ──────────────────────────────────────────────────────────────

    private suspend fun whatsappTab(tabName: String) {
        val deviceId = DeviceId.get() ?: return
        log(deviceId, "info", "Abriendo WhatsApp...")
        openApp("com.whatsapp")
        delay(4000)

        val found = findAndClick(tabName.lowercase())
        log(deviceId, if (found) "info" else "warn",
            if (found) "Pestaña '$tabName' ✓" else "Pestaña '$tabName' no encontrada")
    }

    // ── Debug ─────────────────────────────────────────────────────────────────

    private fun debugNodes() {
        val deviceId = DeviceId.get() ?: return
        val root = BotService.instance?.rootInActiveWindow ?: run {
            log(deviceId, "error", "DEBUG: sin ventana activa")
            return
        }
        val found = mutableListOf<String>()
        collectNodes(root, found)
        root.recycle()

        log(deviceId, "info", "DEBUG: ${found.size} nodos encontrados")
        found.take(30).forEach { log(deviceId, "info", it) }
    }

    private fun collectNodes(node: AccessibilityNodeInfo, out: MutableList<String>) {
        val desc  = node.contentDescription?.toString()?.trim()
        val text  = node.text?.toString()?.trim()
        val cls   = node.className?.toString()?.substringAfterLast('.') ?: ""
        val click = if (node.isClickable) "✓" else " "

        if (!desc.isNullOrEmpty() || !text.isNullOrEmpty()) {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            val bounds = rect.toShortString()
            val label = when {
                !desc.isNullOrEmpty() && !text.isNullOrEmpty() -> "desc=\"$desc\" text=\"$text\""
                !desc.isNullOrEmpty() -> "desc=\"$desc\""
                else -> "text=\"$text\""
            }
            out.add("[$click][$cls] $bounds $label")
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectNodes(child, out)
            child.recycle()
        }
    }

    private fun log(deviceId: String, level: String, message: String) {
        try { SupabaseClient.addLog(deviceId, level, message) } catch (_: Exception) {}
    }
}
