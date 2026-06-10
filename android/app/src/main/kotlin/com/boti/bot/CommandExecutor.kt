package com.boti.bot

import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE
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
            "TIKTOK_COMMENT"        -> tiktokComment(payload ?: return)
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

    // Busca en TODAS las ventanas (para nav bar que está en ventana overlay)
    private fun findAndClickInAllWindows(query: String, excludeContaining: String = ""): Boolean {
        val service = BotService.instance ?: return false
        val windows = service.windows ?: return false
        for (window in windows) {
            val root = window.root ?: continue
            val node = findNodeExcluding(root, query.lowercase(), excludeContaining.lowercase())
            if (node != null) {
                val clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                if (!clicked) {
                    val rect = Rect()
                    node.getBoundsInScreen(rect)
                    if (!rect.isEmpty) tapAt(rect.centerX().toFloat(), rect.centerY().toFloat())
                }
                node.recycle()
                root.recycle()
                return true
            }
            root.recycle()
        }
        return false
    }

    private fun findNodeExcluding(node: AccessibilityNodeInfo, query: String, exclude: String): AccessibilityNodeInfo? {
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        val text = node.text?.toString()?.lowercase() ?: ""
        val matches = desc.contains(query) || text.contains(query)
        val excluded = exclude.isNotEmpty() && (desc.contains(exclude) || text.contains(exclude))
        if (matches && !excluded) return node

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findNodeExcluding(child, query, exclude)
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

    private suspend fun tiktokComment(text: String) {
        val deviceId = DeviceId.get() ?: return
        val service  = BotService.instance ?: return
        val m        = service.resources.displayMetrics
        val screenH  = m.heightPixels

        // 1. Abrir panel de comentarios
        log(deviceId, "info", "Comentar: abriendo comentarios...")
        val root1 = service.rootInActiveWindow ?: return
        val commentBtn = findNodeOnScreen(root1, "agregar comentarios", screenH)
        root1.recycle()

        if (commentBtn != null) {
            commentBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            commentBtn.recycle()
        } else {
            log(deviceId, "warn", "Comentar: botón no encontrado en pantalla")
            return
        }
        delay(2000)

        // 2. Activar el campo de texto tocando la barra inferior (siempre por coordenadas)
        // El campo de texto NO está en el árbol hasta que se toca y el teclado aparece
        log(deviceId, "info", "Comentar: activando campo de texto...")
        tapAt(m.widthPixels * 0.40f, m.heightPixels * 0.91f)
        delay(1800) // esperar que aparezca el teclado

        // Buscar el EditText ahora que el teclado está activo
        var inputNode: AccessibilityNodeInfo? = service.rootInActiveWindow?.let { root ->
            val n = findNode(root, "agregar un comentario")
                ?: findNode(root, "añadir un comentario")
                ?: findEditText(root)
            root.recycle()
            n
        }

        if (inputNode == null) {
            log(deviceId, "warn", "Comentar: campo de texto no encontrado tras activar teclado")
            return
        }

        log(deviceId, "info", "Comentar: escribiendo texto via portapapeles...")
        inputNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        delay(200)

        // Método preferido: portapapeles (más compatible con campos customizados de TikTok)
        val clipboard = service.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("boti_comment", text))
        delay(200)
        val pasted = inputNode.performAction(AccessibilityNodeInfo.ACTION_PASTE)

        // Fallback: ACTION_SET_TEXT
        if (!pasted) {
            val bundle = Bundle().apply { putCharSequence(ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
            val typed = inputNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
            if (!typed) {
                inputNode.recycle()
                log(deviceId, "warn", "Comentar: no pudo escribir el texto")
                return
            }
        }
        inputNode.recycle()
        delay(2000) // esperar que desaparezca la popup "Pegar texto copiado"

        delay(1200)
        log(deviceId, "info", "Comentar: publicando...")
        // Botón Publicar en x=1000, y=1505 (coordenadas confirmadas)
        tapAt(1000f, 1505f)
        delay(800)
        log(deviceId, "info", "Comentario publicado ✓: \"$text\"")
    }

    private fun findInAllWindowsByDesc(desc: String): AccessibilityNodeInfo? {
        val service = BotService.instance ?: return null
        val windows = service.windows ?: return null
        for (window in windows) {
            val root = window.root ?: continue
            val node = findNode(root, desc.lowercase())
            if (node != null) { root.recycle(); return node }
            root.recycle()
        }
        return null
    }

    // Busca cualquier nodo clickeable en el rango y de la barra de comentarios,
    // a la derecha de xMin, excluyendo el botón "Cerrar"
    private fun findClickableInBar(yMin: Int, yMax: Int, xMin: Int): AccessibilityNodeInfo? {
        val service = BotService.instance ?: return null
        val windows = service.windows ?: return null
        for (window in windows) {
            val root = window.root ?: continue
            val node = findClickableNodeInRegion(root, yMin, yMax, xMin)
            if (node != null) { root.recycle(); return node }
            root.recycle()
        }
        return null
    }

    private fun findClickableNodeInRegion(node: AccessibilityNodeInfo, yMin: Int, yMax: Int, xMin: Int): AccessibilityNodeInfo? {
        if (node.isClickable) {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            val cy = rect.centerY()
            val cx = rect.centerX()
            val nodeWidth = rect.right - rect.left
            // Excluir contenedores anchos (>750px = ~70% de pantalla) — son wrappers, no botones
            if (cy in yMin..yMax && cx >= xMin && nodeWidth < 750) {
                val desc = node.contentDescription?.toString()?.lowercase() ?: ""
                val text = node.text?.toString()?.lowercase() ?: ""
                if (!desc.contains("cerrar") && !text.contains("cerrar")
                    && !desc.contains("pegar") && !text.contains("pegar")) return node
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findClickableNodeInRegion(child, yMin, yMax, xMin)
            if (found != null) { if (found !== child) child.recycle(); return found }
            child.recycle()
        }
        return null
    }

    // Logea todos los nodos (clickeables o no) en el rango de la barra para diagnóstico
    private fun logBarNodes(yMin: Int, yMax: Int) {
        val deviceId = DeviceId.get() ?: return
        val service  = BotService.instance ?: return
        val windows  = service.windows ?: return
        var count = 0
        for (window in windows) {
            val root = window.root ?: continue
            collectBarNodes(root, yMin - 20, yMax + 20, deviceId)
            count++
            root.recycle()
        }
        if (count == 0) log(deviceId, "warn", "logBarNodes: sin ventanas")
    }

    private fun collectBarNodes(node: AccessibilityNodeInfo, yMin: Int, yMax: Int, deviceId: String) {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (rect.centerY() in yMin..yMax) {
            val desc  = node.contentDescription?.toString() ?: ""
            val text  = node.text?.toString() ?: ""
            val cls   = node.className?.toString()?.substringAfterLast('.') ?: ""
            val click = if (node.isClickable) "✓" else " "
            if (desc.isNotEmpty() || text.isNotEmpty()) {
                try { SupabaseClient.addLog(deviceId, "info", "BAR[$click][$cls] ${rect.toShortString()} desc='$desc' text='$text'") } catch (_: Exception) {}
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectBarNodes(child, yMin, yMax, deviceId)
            child.recycle()
        }
    }

    // Busca el primer EditText en el árbol (campo de texto genérico)
    private fun findEditText(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.className?.contains("EditText") == true) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findEditText(child)
            if (found != null) {
                if (found !== child) child.recycle()
                return found
            }
            child.recycle()
        }
        return null
    }

    private suspend fun tiktokSwitchAccount(accountName: String) {
        val deviceId = DeviceId.get() ?: return
        val service  = BotService.instance ?: return
        val m        = service.resources.displayMetrics
        val screenH  = m.heightPixels

        fun step(n: Int, msg: String) = log(deviceId, "info", "[$n/6] $msg")

        // 1. Ir al tab Perfil — la barra inferior no expone nodos de accesibilidad en TikTok
        step(1, "Ir a Perfil (barra inferior)...")
        val profileTabFound = findAndClickInAllWindows("perfil", excludeContaining = "perfil de")
        if (!profileTabFound) {
            // Barra inferior de TikTok: x=90% (último ícono), y dentro del rango 2190-2340
            // Probamos dos posiciones: 93% y 95%
            tapAt(m.widthPixels * 0.90f, m.heightPixels * 0.935f)
        }
        delay(1800)

        // Verificar si llegamos al perfil buscando "Editar perfil"
        val onProfile = service.rootInActiveWindow?.let { root ->
            val found = findNode(root, "editar perfil") != null || findNode(root, "edit profile") != null
            root.recycle(); found
        } ?: false

        if (!onProfile) {
            log(deviceId, "warn", "Perfil no detectado — intentando y=95%...")
            tapAt(m.widthPixels * 0.90f, m.heightPixels * 0.955f)
            delay(1800)
        } else {
            log(deviceId, "info", "En Perfil ✓")
        }

        // 2. Tocar las 3 barras horizontales (arriba a la derecha del perfil)
        // En la pantalla de perfil personal el ícono está en la esquina superior derecha
        step(2, "Abrir menú (3 barras)...")
        val menuFound = findAndClick("más opciones") || findAndClick("more options")
        if (!menuFound) {
            tapAt(m.widthPixels * 0.96f, m.heightPixels * 0.055f)
        }
        delay(1500)

        // 3. Tocar "Ajustes y privacidad"
        step(3, "Ajustes y privacidad...")
        val settingsFound = findAndClick("ajustes y privacidad") || findAndClick("ajustes")
        if (!settingsFound) {
            log(deviceId, "warn", "Ajustes no encontrado — revisa que el panel esté abierto")
            return
        }
        delay(2000)

        // 4. Scroll hasta encontrar "Cambiar cuenta" (está al fondo de Ajustes)
        step(4, "Buscando Cambiar cuenta...")
        var switchFound = false
        repeat(15) { i ->
            if (switchFound || CommandListener.stopCurrent) return@repeat

            // Log de nodos visibles para saber qué texto buscar
            val root = service.rootInActiveWindow
            if (root != null) {
                val visible = mutableListOf<String>()
                collectNodes(root, visible)
                root.recycle()
                val items = visible.filter { it.contains("cuenta") || it.contains("cerrar") || it.contains("salir") || it.contains("cambiar") }
                if (items.isNotEmpty()) log(deviceId, "info", "Scroll $i: ${items.take(3).joinToString(" | ")}")
            }

            switchFound = findAndClick("cambiar cuenta")
                || findAndClick("cambiar de cuenta")
                || findAndClick("switch account")
                || findAndClick("gestionar cuentas")

            if (!switchFound) {
                scroll()
                delay(1200)
            }
        }
        if (!switchFound) {
            log(deviceId, "warn", "Cambiar cuenta no encontrado — revisa el log para ver el texto exacto")
            return
        }
        delay(1500)

        // 5. Buscar y tocar la cuenta por nombre
        step(5, "Seleccionando cuenta: $accountName...")
        val screenNode = findNodeOnScreen(
            service.rootInActiveWindow ?: return,
            accountName.lowercase(),
            screenH
        )
        val accountFound = if (screenNode != null) {
            val clicked = screenNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            if (!clicked) {
                val rect = Rect()
                screenNode.getBoundsInScreen(rect)
                if (!rect.isEmpty) tapAt(rect.centerX().toFloat(), rect.centerY().toFloat())
            }
            screenNode.recycle()
            true
        } else {
            findAndClick(accountName.lowercase())
        }

        if (!accountFound) {
            log(deviceId, "warn", "Cuenta '$accountName' no encontrada en la lista")
            return
        }

        // 6. Esperar notificación de login
        step(6, "Esperando inicio de sesión...")
        delay(6000)
        log(deviceId, "info", "Cambio a '$accountName' completado ✓")
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
        val service = BotService.instance ?: run {
            log(deviceId, "error", "DEBUG: sin servicio")
            return
        }
        val found = mutableListOf<String>()

        // Buscar en TODAS las ventanas (nav bar de TikTok está en ventana separada)
        val windows = service.windows
        if (windows != null && windows.isNotEmpty()) {
            log(deviceId, "info", "DEBUG: ${windows.size} ventanas")
            windows.forEachIndexed { i, window ->
                val wBounds = android.graphics.Rect()
                window.getBoundsInScreen(wBounds)
                log(deviceId, "info", "Ventana $i tipo=${window.type} bounds=${wBounds.toShortString()}")
                val root = window.root ?: return@forEachIndexed
                collectNodes(root, found)
                root.recycle()
            }
        } else {
            val root = service.rootInActiveWindow ?: run {
                log(deviceId, "error", "DEBUG: sin ventana activa")
                return
            }
            collectNodes(root, found)
            root.recycle()
        }

        log(deviceId, "info", "DEBUG: ${found.size} nodos encontrados")
        found.take(40).forEach { log(deviceId, "info", it) }
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
