package com.boti.bot

import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.delay
import org.json.JSONObject

object CommandExecutor {

    suspend fun execute(action: String, payload: String?) {
        when (action.uppercase()) {
            "SCROLL"                -> scroll()
            "OPEN_APP"              -> openApp(payload ?: return)
            "FIND_CLICK"            -> findAndClick(payload ?: return)
            "WAIT"                  -> delay(payload?.toLongOrNull() ?: 1000L)
            "TIKTOK_OPEN"           -> tiktokOpen()
            "TIKTOK_OPEN_LIVE"      -> tiktokOpenLive(payload ?: return)
            "TIKTOK_LIKE"           -> tiktokLike()
            "TIKTOK_SAVE"           -> tiktokSave()
            "TIKTOK_COMMENT"        -> tiktokComment(payload ?: return)
            "TIKTOK_FOLLOW"         -> tiktokFollow()
            "TIKTOK_SWITCH_ACCOUNT" -> tiktokSwitchAccount(payload ?: return)
            "TIKTOK_LIVE_SWITCH_ACCOUNT" -> tiktokLiveSwitchAccount(payload ?: return)
            "TIKTOK_GET_ACCOUNTS"   -> tiktokGetAccounts()
            "TIKTOK_LIVE_COMMENT"   -> tiktokLiveComment(payload ?: return)
            "TIKTOK_LIVE_FOLLOW"    -> tiktokLiveFollow()
            "TIKTOK_LIVE_GIFT"      -> tiktokLiveGift(payload)
            "TIKTOK_AUTO_LIVE"      -> tiktokAutoLive(payload ?: return)
            "WHATSAPP_TAB"          -> whatsappTab(payload ?: "Novedades")
            "DEBUG_NODES"           -> debugNodes()
            "DEBUG_ALL"             -> debugAll()
            "DEBUG_COORDS"          -> debugCoords()
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

    // Scroll vertical confinado a una banda de la pantalla (p.ej. el panel de regalos),
    // para no mover el contenido de fondo (el live). Va de yFrom a yTo en la columna x.
    private fun scrollBand(x: Float, yFrom: Float, yTo: Float) {
        val service = BotService.instance ?: return
        val path = Path().apply { moveTo(x, yFrom); lineTo(x, yTo) }
        service.dispatchGesture(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0L, 350L))
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

    // ── Guardias de accesibilidad ──────────────────────────────────────────────
    // Verifican el estado de la pantalla ANTES de tocar coordenadas fijas.

    // ¿Existe en alguna ventana un nodo cuyo texto/desc contenga `query`?
    private fun screenHas(query: String): Boolean {
        val service = BotService.instance ?: return false
        val windows = service.windows ?: return false
        val q = query.lowercase()
        for (w in windows) {
            val root = w.root ?: continue
            val n = findNode(root, q)
            val hit = n != null
            if (n != null && n !== root) n.recycle()
            root.recycle()
            if (hit) return true
        }
        return false
    }

    // ¿Existe en alguna ventana un nodo cuyo resource id termine en `idSuffix`?
    private fun screenHasId(idSuffix: String): Boolean {
        val service = BotService.instance ?: return false
        val windows = service.windows ?: return false
        for (w in windows) {
            val root = w.root ?: continue
            val n = findNodeById(root, idSuffix)
            val hit = n != null
            if (n != null && n !== root) n.recycle()
            root.recycle()
            if (hit) return true
        }
        return false
    }

    // Toca una coordenada SOLO si el guardia confirma la pantalla correcta.
    // Devuelve true si tocó, false si el guardia falló (no toca a ciegas).
    private fun tapGuarded(x: Float, y: Float, guard: () -> Boolean): Boolean {
        if (!guard()) return false
        tapAt(x, y)
        return true
    }

    // Lee el saldo de Monedas del panel de regalos. El nodo de saldo tiene
    // desc "Tienes N Monedas. Pulsa dos veces para recargar..." y text="N".
    private fun readCoins(): Int? {
        val service = BotService.instance ?: return null
        val windows = service.windows ?: return null
        for (w in windows) {
            val root = w.root ?: continue
            val n = findNode(root, "recargar")   // distingue saldo de "Recompensas/canjear"
            if (n != null) {
                val t = n.text?.toString() ?: ""
                val d = n.contentDescription?.toString() ?: ""
                if (n !== root) n.recycle()
                root.recycle()
                val num = Regex("\\d+").find(t)?.value ?: Regex("\\d+").find(d)?.value
                return num?.toIntOrNull()
            }
            root.recycle()
        }
        return null
    }

    // Espera hasta `timeoutMs` a que el saldo baje respecto a `before` (el descuento
    // de Monedas no es instantáneo). Devuelve true si detectó el descuento.
    private suspend fun waitCoinsDrop(before: Int?, timeoutMs: Int): Boolean {
        if (before == null) return false
        var waited = 0
        while (waited < timeoutMs) {
            delay(250); waited += 250
            val now = readCoins()
            if (now != null && now < before) return true
        }
        return false
    }

    // Busca un regalo por nombre SOLO dentro de la zona del grid (evita coincidir con
    // el chat de arriba). El grid va aprox. de y=1400 a y=2170.
    private fun giftBounds(name: String): Rect? {
        val service = BotService.instance ?: return null
        val windows = service.windows ?: return null
        val q = name.lowercase()
        for (w in windows) {
            val root = w.root ?: continue
            val r = searchGift(root, q)
            root.recycle()
            if (r != null) return r
        }
        return null
    }

    private fun searchGift(node: AccessibilityNodeInfo, q: String): Rect? {
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        val text = node.text?.toString()?.lowercase() ?: ""
        if (desc.contains(q) || text.contains(q)) {
            val rect = Rect(); node.getBoundsInScreen(rect)
            if (!rect.isEmpty && rect.top in 1400..2170) return rect
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val r = searchGift(child, q)
            child.recycle()
            if (r != null) return r
        }
        return null
    }

    // Lista los nombres (desc) de regalos visibles en la zona del grid (y 1400-2170).
    private fun listGiftNames(): List<String> {
        val service = BotService.instance ?: return emptyList()
        val windows = service.windows ?: return emptyList()
        val out = mutableListOf<String>()
        for (w in windows) {
            val root = w.root ?: continue
            collectGiftNames(root, out)
            root.recycle()
        }
        return out.distinct()
    }

    private fun collectGiftNames(node: AccessibilityNodeInfo, out: MutableList<String>) {
        val desc = node.contentDescription?.toString()?.trim() ?: ""
        val cls  = node.className?.toString()?.substringAfterLast('.') ?: ""
        if (cls == "ImageView" && desc.isNotEmpty() && !desc.lowercase().contains("moneda")) {
            val rect = Rect(); node.getBoundsInScreen(rect)
            if (rect.top in 1400..2170) out.add(desc)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectGiftNames(child, out)
            child.recycle()
        }
    }

    // Busca el regalo y, si no está visible, hace scroll en el grid hacia abajo y reintenta.
    private suspend fun findGiftScrolling(deviceId: String, name: String, maxScrolls: Int): Rect? {
        var r = giftBounds(name)
        var n = 0
        while (r == null && n <= maxScrolls) {
            log(deviceId, "info", "Live regalo: visibles → ${listGiftNames().joinToString(", ")}")
            if (n == maxScrolls) break
            // Swipe hacia arriba dentro del grid → revela regalos de más abajo.
            scrollBand(540f, 2050f, 1550f)
            delay(800)
            r = giftBounds(name)
            n++
        }
        return r
    }

    // Devuelve los bounds en pantalla del primer nodo cuyo texto/desc contenga `query`.
    private fun boundsOf(query: String): Rect? {
        val service = BotService.instance ?: return null
        val windows = service.windows ?: return null
        val q = query.lowercase()
        for (w in windows) {
            val root = w.root ?: continue
            val n = findNode(root, q)
            if (n != null) {
                val rect = Rect()
                n.getBoundsInScreen(rect)
                if (n !== root) n.recycle()
                root.recycle()
                if (!rect.isEmpty && rect.top >= 0) return rect
            } else root.recycle()
        }
        return null
    }

    // ── Node search ───────────────────────────────────────────────────────────

    private fun openApp(packageName: String) {
        val deviceId = DeviceId.get() ?: return
        val service  = BotService.instance ?: return
        val pm       = service.packageManager

        // Intento 1: launch intent estándar
        val launch = pm.getLaunchIntentForPackage(packageName)
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            service.startActivity(launch)
            return
        }

        // Intento 2: ACTION_MAIN con CATEGORY_LAUNCHER directo
        try {
            val main = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                setPackage(packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            }
            val activities = pm.queryIntentActivities(main, 0)
            if (activities.isNotEmpty()) {
                main.component = android.content.ComponentName(
                    packageName, activities[0].activityInfo.name)
                service.startActivity(main)
                return
            }
        } catch (_: Exception) {}

        log(deviceId, "error", "openApp: no se pudo abrir '$packageName' — ¿está instalado?")
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

    // Abre un link de TikTok (p.ej. un live) forzando que lo maneje la app de TikTok.
    private suspend fun tiktokOpenLive(url: String) {
        val deviceId = DeviceId.get() ?: return
        val service  = BotService.instance ?: return
        val link = url.trim()
        if (link.isEmpty()) { log(deviceId, "warn", "Abrir live: link vacío"); return }

        log(deviceId, "info", "Abriendo live: $link")
        val uri = Uri.parse(link)

        // Intento 1: forzar la app de TikTok (global)
        try {
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                setPackage("com.zhiliaoapp.musically")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            service.startActivity(intent)
            delay(5000)
            log(deviceId, "info", "Live abierto ✓")
            return
        } catch (_: Exception) {}

        // Intento 2: variante regional del paquete
        try {
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                setPackage("com.ss.android.ugc.trill")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            service.startActivity(intent)
            delay(5000)
            log(deviceId, "info", "Live abierto ✓ (trill)")
            return
        } catch (_: Exception) {}

        // Intento 3: sin forzar paquete (deja que Android resuelva)
        try {
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            service.startActivity(intent)
            delay(5000)
            log(deviceId, "info", "Live abierto ✓ (sin forzar app)")
        } catch (e: Exception) {
            log(deviceId, "error", "Abrir live: no se pudo abrir el link — ${e.message}")
        }
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
            log(deviceId, "warn", "Comentar: botón comentarios no encontrado")
            return
        }
        delay(2000)

        // 2. Buscar campo de texto (sin activar teclado primero)
        log(deviceId, "info", "Comentar: buscando campo de texto...")
        var inputNode: AccessibilityNodeInfo? = service.rootInActiveWindow?.let { root ->
            val n = findNode(root, "agregar un comentario")
                ?: findNode(root, "añadir un comentario")
                ?: findEditText(root)
            root.recycle()
            n
        }
        // Si no encontrado, activar tocando la barra al fondo
        if (inputNode == null) {
            log(deviceId, "info", "Comentar: tocando barra de comentario...")
            tapAt(m.widthPixels * 0.40f, m.heightPixels * 0.95f)
            delay(1800)
            inputNode = service.rootInActiveWindow?.let { root ->
                val n = findEditText(root); root.recycle(); n
            }
        }
        if (inputNode == null) {
            log(deviceId, "warn", "Comentar: campo de texto no encontrado")
            return
        }

        // 3. Pegar texto
        log(deviceId, "info", "Comentar: pegando texto...")
        inputNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        delay(200)
        val clipboard = service.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("boti_comment", text))
        delay(200)
        val pasted = inputNode.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        if (!pasted) {
            val bundle = Bundle().apply { putCharSequence(ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
            val typed = inputNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
            if (!typed) { inputNode.recycle(); log(deviceId, "warn", "Comentar: no pudo escribir"); return }
        }
        inputNode.recycle()
        delay(1200)

        // 4. Publicar — posición según si el teclado está abierto o no (coords calibrables)
        log(deviceId, "info", "Comentar: publicando...")
        val keyboardOpen = service.windows?.any { it.type == 2 } ?: false
        if (keyboardOpen) {
            val (x, y) = CoordProfile.get("publish_kb", 1000f, 1505f)
            log(deviceId, "info", "Publicar con teclado: ($x,$y)")
            tapAt(x, y)
        } else {
            val (x, y) = CoordProfile.get("publish_nokb", 971f, 2237f)
            log(deviceId, "info", "Publicar sin teclado: ($x,$y)")
            tapAt(x, y)
        }
        delay(800)
        service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
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

    // Cambiar cuenta partiendo desde un live: un back para volver a "Para ti" y luego
    // el mismo proceso de cambiar cuenta.
    private suspend fun tiktokLiveSwitchAccount(accountName: String) {
        val deviceId = DeviceId.get() ?: return
        val service  = BotService.instance ?: return
        log(deviceId, "info", "Live: saliendo del live (atrás) para cambiar cuenta...")
        service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        delay(2000)
        tiktokSwitchAccount(accountName)
    }

    private suspend fun tiktokSwitchAccount(accountName: String) {
        val deviceId = DeviceId.get() ?: return
        val service  = BotService.instance ?: return
        val m        = service.resources.displayMetrics
        val screenH  = m.heightPixels

        fun step(n: Int, msg: String) = log(deviceId, "info", "[$n/5] $msg")

        // 1. Tab Perfil
        step(1, "Perfil...")
        val profileFound = findAndClickInAllWindows("perfil", excludeContaining = "perfil de")
        if (!profileFound) tapAt(m.widthPixels * 0.90f, m.heightPixels * 0.935f)
        delay(1200)

        // 2. Menú (3 barras arriba derecha)
        step(2, "Menú...")
        val menuFound = findAndClick("más opciones") || findAndClick("more options")
        if (!menuFound) tapAt(m.widthPixels * 0.96f, m.heightPixels * 0.055f)
        delay(900)

        // 3. Ajustes y privacidad
        step(3, "Ajustes...")
        val settingsFound = findAndClick("ajustes y privacidad") || findAndClick("ajustes")
        if (!settingsFound) { log(deviceId, "warn", "Ajustes no encontrado"); return }
        delay(1200)

        // 4. Scroll hasta "Cambiar cuenta"
        step(4, "Buscando Cambiar cuenta...")
        var switchFound = false
        repeat(12) {
            if (switchFound || CommandListener.stopCurrent) return@repeat
            switchFound = findAndClick("cambiar cuenta")
                || findAndClick("cambiar de cuenta")
                || findAndClick("switch account")
                || findAndClick("gestionar cuentas")
            if (!switchFound) { scroll(); delay(700) }
        }
        if (!switchFound) { log(deviceId, "warn", "Cambiar cuenta no encontrado"); return }
        delay(800)

        // 5. Seleccionar cuenta y esperar login
        step(5, "Cuenta: $accountName...")
        val root5 = service.rootInActiveWindow ?: return
        val node  = findNodeOnScreen(root5, accountName.lowercase(), screenH)
        root5.recycle()
        val found = if (node != null) {
            val clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            if (!clicked) { val r = Rect(); node.getBoundsInScreen(r); if (!r.isEmpty) tapAt(r.centerX().toFloat(), r.centerY().toFloat()) }
            node.recycle(); true
        } else findAndClick(accountName.lowercase())

        if (!found) { log(deviceId, "warn", "Cuenta '$accountName' no encontrada"); return }
        delay(4000)
        log(deviceId, "info", "Cambio a '$accountName' completado ✓")
    }

    private suspend fun tiktokGetAccounts() {
        val deviceId = DeviceId.get() ?: return
        val service  = BotService.instance ?: return
        val m        = service.resources.displayMetrics

        log(deviceId, "info", "Obteniendo lista de cuentas...")

        // Navegar a Perfil → Menú → Ajustes → Cambiar cuenta
        val profileFound = findAndClickInAllWindows("perfil", excludeContaining = "perfil de")
        if (!profileFound) tapAt(m.widthPixels * 0.90f, m.heightPixels * 0.935f)
        delay(1200)

        val menuFound = findAndClick("más opciones") || findAndClick("more options")
        if (!menuFound) tapAt(m.widthPixels * 0.96f, m.heightPixels * 0.055f)
        delay(900)

        val settingsFound = findAndClick("ajustes y privacidad") || findAndClick("ajustes")
        if (!settingsFound) { log(deviceId, "warn", "Ajustes no encontrado"); return }
        delay(1200)

        var switchFound = false
        repeat(12) {
            if (switchFound || CommandListener.stopCurrent) return@repeat
            switchFound = findAndClick("cambiar cuenta") || findAndClick("cambiar de cuenta") || findAndClick("switch account")
            if (!switchFound) { scroll(); delay(700) }
        }
        if (!switchFound) { log(deviceId, "warn", "Cambiar cuenta no encontrado"); return }
        delay(1000)

        // Leer cuentas de la pantalla
        val SKIP = setOf(
            "agregar cuenta", "añadir cuenta", "add account",
            "administrar cuentas", "manage accounts",
            "cambiar cuenta", "switch account", "cambiar de cuenta",
            "iniciar sesión", "log in", "inicio de sesión",
            "módulo inferior", "cerrar", "marca de verificación",
            "close", "verified", "bottom"
        )
        val accounts = mutableListOf<String>()
        val root = service.rootInActiveWindow
        if (root != null) {
            fun walk(node: AccessibilityNodeInfo?) {
                node ?: return
                val txt = (node.text?.toString() ?: node.contentDescription?.toString() ?: "").trim()
                if (txt.isNotEmpty() && !txt.all { it.isDigit() || it == ':' || it == ',' } && txt.length >= 3) {
                    val low = txt.lowercase()
                    if (SKIP.none { low.contains(it) }) accounts.add(txt)
                }
                for (i in 0 until node.childCount) walk(node.getChild(i))
            }
            walk(root)
            root.recycle()
        }
        val unique = accounts.distinct()

        if (unique.isEmpty()) {
            log(deviceId, "warn", "No se encontraron cuentas — usa DEBUG_NODES en esta pantalla")
        } else {
            SupabaseClient.updateTiktokAccounts(deviceId, unique)
            log(deviceId, "info", "Cuentas guardadas: ${unique.joinToString(", ")}")
        }

        // Volver al inicio (Cambiar cuenta → Ajustes → menú → Perfil)
        repeat(4) { service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK); delay(500) }
    }

    // ── TikTok Live ───────────────────────────────────────────────────────────

    private suspend fun tiktokLiveFollow() {
        val deviceId = DeviceId.get() ?: return
        val found = findAndClick("empezar a seguir")
        log(deviceId, if (found) "info" else "warn",
            if (found) "Live: seguido ✓" else "Live: botón follow no encontrado")
    }

    private suspend fun tiktokLiveComment(text: String) {
        val deviceId = DeviceId.get() ?: return
        val service  = BotService.instance ?: return

        // 1. Buscar input del chat — solo por placeholder exacto o EditText
        log(deviceId, "info", "Live comentar: buscando input...")
        var inputNode: AccessibilityNodeInfo? = service.rootInActiveWindow?.let { root ->
            val n = findNode(root, "deja un comentario")
                ?: findNode(root, "añadir comentario")
                ?: findEditText(root)
            root.recycle()
            n
        }
        // Si no encontrado, tocar la barra del chat
        // DEBUG_ALL confirmó: barra en [28,2174][640,2296], centro x=200, y=2235
        if (inputNode == null) {
            val (cbx, cby) = CoordProfile.get("live_chat", 200f, 2235f)
            log(deviceId, "info", "Live comentar: tocando barra de chat ($cbx,$cby)...")
            tapAt(cbx, cby)
            delay(1800)
            inputNode = service.rootInActiveWindow?.let { root ->
                val n = findEditText(root); root.recycle(); n
            }
        }
        if (inputNode == null) {
            log(deviceId, "warn", "Live comentar: campo no encontrado")
            return
        }

        // 2. Pegar texto
        log(deviceId, "info", "Live comentar: pegando texto...")
        inputNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        delay(200)
        val clipboard = service.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("boti_live", text))
        delay(200)
        val pasted = inputNode.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        if (!pasted) {
            val bundle = Bundle().apply { putCharSequence(ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
            val typed = inputNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
            if (!typed) { inputNode.recycle(); log(deviceId, "warn", "Live comentar: no pudo escribir"); return }
        }
        inputNode.recycle()
        delay(1200)

        // 3. Enviar
        log(deviceId, "info", "Live comentar: enviando...")
        val keyboardOpen = service.windows?.any { it.type == 2 } ?: false
        val sent = findAndClickInAllWindows("enviar") || findAndClickInAllWindows("send")
        if (!sent) {
            if (keyboardOpen) {
                // Con teclado: send arriba del teclado (calibrable, solo live)
                val (sx, sy) = CoordProfile.get("live_send_kb", 1000f, 1505f)
                tapAt(sx, sy)
            } else {
                // Sin teclado: ImageView send dentro de la barra [556,2193][640,2277]
                val (sx, sy) = CoordProfile.get("live_send", 598f, 2235f)
                tapAt(sx, sy)
            }
        }
        delay(600)
        // No salir del live — el usuario sigue viendo el stream
        log(deviceId, "info", "Live comentado ✓: \"$text\"")
    }

    // Enviar regalo en Live.
    //  - payload vacío           → abre el panel y lista los regalos disponibles (descubrimiento)
    //  - payload = nombre regalo  → abre panel, selecciona ese regalo por nombre y toca Enviar
    private suspend fun tiktokLiveGift(payload: String?) {
        val deviceId = DeviceId.get() ?: return
        val service  = BotService.instance ?: return

        // GUARDIA 1: estar en un live.
        val enLive = screenHasId("hsi") || screenHas("regalos") || screenHas("envíale") || screenHas("monedas")
        if (!enLive) {
            log(deviceId, "warn", "Live regalo: no estás en un live — no toco nada")
            return
        }

        // 1. Abrir el panel si no está abierto (el saldo "Monedas" solo existe en el panel).
        if (!screenHas("monedas")) {
            val (gix, giy) = CoordProfile.get("gift_icon", 900f, 2246f)
            log(deviceId, "info", "Live regalo: abriendo panel ($gix,$giy)...")
            tapAt(gix, giy)
            delay(2200)
        }
        // GUARDIA 2: confirmar panel abierto.
        if (!screenHas("monedas")) {
            log(deviceId, "warn", "Live regalo: el panel no abrió — revisa la coord del ícono")
            return
        }

        val name = payload?.trim()
        val esDescubrimiento = name.isNullOrBlank() || name == "null"

        // Modo descubrimiento: listar regalos visibles.
        if (esDescubrimiento) {
            val found = mutableListOf<String>()
            service.windows?.forEach { w ->
                val root = w.root ?: return@forEach
                collectNodes(root, found)
                root.recycle()
            }
            val regalos = found.filter { it.lowercase().contains("moneda") }
            log(deviceId, "info", "Live regalo: ${regalos.size} regalos visibles")
            regalos.take(40).forEach { log(deviceId, "info", "🎁 $it") }
            log(deviceId, "info", "Live regalo: panel listado ✓ (escribe un nombre para enviar)")
            return
        }

        // Modo envío: localizar el regalo por nombre, con scroll en el grid si hace falta.
        val rect = findGiftScrolling(deviceId, name!!, 6)
        if (rect == null) {
            log(deviceId, "warn", "Live regalo: '$name' no encontrado ni tras scroll — revisa el nombre")
            return
        }
        val cx = rect.centerX().toFloat()
        val cy = rect.centerY().toFloat()

        // En este panel no hay botón Enviar: tocar el regalo lo selecciona, y tocarlo
        // de nuevo (ya seleccionado) lo envía. PERO al abrir el panel el último regalo
        // enviado queda PRESELECCIONADO → ese primer tap ya envía. El descuento de
        // Monedas no es instantáneo, así que SONDEAMOS el saldo antes de tocar de nuevo.
        val coinsBefore = readCoins()
        if (coinsBefore == null) log(deviceId, "warn", "Live regalo: no pude leer el saldo")

        log(deviceId, "info", "Live regalo: tap 1 en '$name' ($cx,$cy)... (saldo=$coinsBefore)")
        tapAt(cx, cy)

        // ¿El primer tap ya envió? (regalo preseleccionado). Esperar el descuento.
        val enviadoEnTap1 = waitCoinsDrop(coinsBefore, 2500)
        if (enviadoEnTap1) {
            log(deviceId, "info", "Live regalo: estaba preseleccionado → enviado con 1 tap")
        } else {
            log(deviceId, "info", "Live regalo: solo seleccionó → tap 2 para enviar...")
            tapAt(cx, cy)
            waitCoinsDrop(coinsBefore, 2500)
        }

        val coinsAfter = readCoins()
        // 3. Volver atrás para cerrar el panel (sin salir del live).
        service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        delay(500)

        val ok = coinsBefore == null || coinsAfter == null || coinsAfter < coinsBefore
        log(deviceId, if (ok) "info" else "warn",
            if (ok) "Regalo '$name' enviado ✓ (saldo $coinsBefore→$coinsAfter)"
            else "Regalo '$name': saldo sin cambio ($coinsAfter) — quizá no se envió")
    }

    // Automatización encadenada desde un live:
    //   1) cambiar de cuenta (back al "Para ti" + proceso de cambio)
    //   2) abrir un enlace de live
    //   3) comentar en ese live
    // payload JSON: {"account":"...","link":"...","comment":"hola"}
    private suspend fun tiktokAutoLive(payload: String) {
        val deviceId = DeviceId.get() ?: return
        val json = try { JSONObject(payload) } catch (e: Exception) {
            log(deviceId, "error", "Automatización: payload inválido"); return
        }
        val account = json.optString("account").trim()
        val link    = json.optString("link").trim()
        val comment = json.optString("comment", "hola").ifBlank { "hola" }

        log(deviceId, "info", "▶ Automatización: cuenta='$account' → abrir live → comentar '$comment'")

        // 1. Cambiar cuenta partiendo del live (hace back y el proceso de cambio)
        if (account.isNotEmpty()) {
            log(deviceId, "info", "Auto 1/3: cambiando a '$account'...")
            tiktokLiveSwitchAccount(account)
            delay(2500)
        }

        // 2. Abrir el enlace del live (el intent ya espera ~5s internamente)
        if (link.isNotEmpty()) {
            log(deviceId, "info", "Auto 2/3: abriendo live...")
            tiktokOpenLive(link)
            delay(2000)
        } else {
            log(deviceId, "warn", "Auto: sin link de live — no puedo continuar"); return
        }

        // 3. Comentar en el live
        log(deviceId, "info", "Auto 3/3: comentando '$comment'...")
        tiktokLiveComment(comment)

        log(deviceId, "info", "✓ Automatización completada")
    }

    // Busca el primer nodo cuyo viewIdResourceName termine en `idSuffix` y lo clickea.
    private fun clickById(idSuffix: String): Boolean {
        val service = BotService.instance ?: return false
        val windows = service.windows ?: return false
        for (window in windows) {
            val root = window.root ?: continue
            val node = findNodeById(root, idSuffix)
            if (node != null) {
                val ok = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                if (!ok) tapNodeCenter(node)
                node.recycle()
                root.recycle()
                return true
            }
            root.recycle()
        }
        return false
    }

    private fun findNodeById(node: AccessibilityNodeInfo, idSuffix: String): AccessibilityNodeInfo? {
        val id = node.viewIdResourceName?.substringAfterLast('/') ?: ""
        if (id == idSuffix) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findNodeById(child, idSuffix)
            if (found != null) {
                if (found !== child) child.recycle()
                return found
            }
            child.recycle()
        }
        return null
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

    // Muestra TODOS los nodos clickeables (incluso sin texto/desc) — útil para Live
    private fun debugAll() {
        val deviceId = DeviceId.get() ?: return
        val service  = BotService.instance ?: return
        val windows  = service.windows
        if (windows == null || windows.isEmpty()) {
            val root = service.rootInActiveWindow ?: run { log(deviceId, "error", "DEBUG_ALL: sin ventana"); return }
            collectAllClickable(root, deviceId)
            root.recycle()
            return
        }
        log(deviceId, "info", "DEBUG_ALL: ${windows.size} ventanas")
        windows.forEachIndexed { i, window ->
            val root = window.root ?: return@forEachIndexed
            log(deviceId, "info", "=== Ventana $i tipo=${window.type} ===")
            collectAllClickable(root, deviceId)
            root.recycle()
        }
    }

    private fun collectAllClickable(node: AccessibilityNodeInfo, deviceId: String) {
        val rect  = Rect()
        node.getBoundsInScreen(rect)
        val desc  = node.contentDescription?.toString()?.trim() ?: ""
        val text  = node.text?.toString()?.trim() ?: ""
        val cls   = node.className?.toString()?.substringAfterLast('.') ?: ""
        val id    = node.viewIdResourceName?.substringAfterLast('/') ?: ""
        if (node.isClickable) {
            try {
                SupabaseClient.addLog(deviceId, "info",
                    "[✓][$cls#$id] ${rect.toShortString()} desc='$desc' text='$text'")
            } catch (_: Exception) {}
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectAllClickable(child, deviceId)
            child.recycle()
        }
    }

    // Muestra el perfil de coordenadas calibradas que el teléfono tiene cargado.
    private fun debugCoords() {
        val deviceId = DeviceId.get() ?: return
        val service  = BotService.instance
        val m = service?.resources?.displayMetrics
        log(deviceId, "info", "Resolución: ${m?.widthPixels}x${m?.heightPixels}")
        listOf("gift_icon", "publish_kb", "publish_nokb", "live_chat", "live_send").forEach { k ->
            val (x, y) = CoordProfile.get(k, -1f, -1f)
            val estado = if (x < 0) "sin calibrar (usa default)" else "calibrado"
            log(deviceId, "info", "coord $k = ($x,$y) — $estado")
        }
    }

    private fun log(deviceId: String, level: String, message: String) {
        try { SupabaseClient.addLog(deviceId, level, message) } catch (_: Exception) {}
    }
}
