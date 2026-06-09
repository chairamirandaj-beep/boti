package com.boti.bot

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
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

        findViewById<Button>(R.id.btnToggleListen).setOnClickListener {
            val service = BotService.instance ?: return@setOnClickListener
            service.setListening(!service.isListening)
            updateUI()
        }
    }

    override fun onResume() {
        super.onResume()
        updateUI()
    }

    private fun updateUI() {
        val service = BotService.instance
        val tvStatus   = findViewById<TextView>(R.id.tvStatus)
        val tvDeviceId = findViewById<TextView>(R.id.tvDeviceId)
        val btnEnable  = findViewById<Button>(R.id.btnEnable)
        val btnToggle  = findViewById<Button>(R.id.btnToggleListen)

        tvDeviceId.text = "ID: ${DeviceId.get()?.take(8)}..."

        if (service == null) {
            tvStatus.text = "INACTIVO"
            tvStatus.setTextColor(0xFF888888.toInt())
            btnEnable.text = "Activar Servicio"
            btnToggle.visibility = View.GONE
        } else if (service.isListening) {
            tvStatus.text = "ACTIVO"
            tvStatus.setTextColor(0xFF22C55E.toInt())
            btnEnable.text = "Configuración Accesibilidad"
            btnToggle.text = "PAUSAR"
            btnToggle.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFFf59e0b.toInt())
            btnToggle.visibility = View.VISIBLE
        } else {
            tvStatus.text = "PAUSADO"
            tvStatus.setTextColor(0xFFf59e0b.toInt())
            btnEnable.text = "Configuración Accesibilidad"
            btnToggle.text = "ACTIVAR"
            btnToggle.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF22C55E.toInt())
            btnToggle.visibility = View.VISIBLE
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
