package com.boti.bot

import android.accessibilityservice.GestureDescription
import android.graphics.Path

object CommandExecutor {

    fun execute(action: String, payload: String?) {
        when (action.uppercase()) {
            "SCROLL" -> scroll()
            "STOP"   -> CommandListener.stop()
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
}
