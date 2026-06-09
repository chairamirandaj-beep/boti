package com.boti.bot

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.*

class MainActivity : AppCompatActivity() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        DeviceId.init(this)

        scope.launch {
            runCatching {
                SupabaseClient.registerDevice(DeviceId.get()!!, android.os.Build.MODEL)
            }
        }

        findViewById<Button>(R.id.btnEnable).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    override fun onResume() {
        super.onResume()
        val active = BotService.instance != null
        findViewById<TextView>(R.id.tvStatus).text = if (active) "ACTIVO" else "INACTIVO"
        findViewById<TextView>(R.id.tvStatus).setTextColor(
            if (active) 0xFF22C55E.toInt() else 0xFF888888.toInt()
        )
        findViewById<TextView>(R.id.tvDeviceId).text = "ID: ${DeviceId.get()?.take(8)}..."
        findViewById<Button>(R.id.btnEnable).text = if (active) "Configuración Accesibilidad" else "Activar Bot"
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
