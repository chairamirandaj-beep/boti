package com.boti.bot

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

object SupabaseClient {
    private const val BASE_URL = "https://zhezpmyrqpzmlmbzxdjx.supabase.co"
    private const val API_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6InpoZXpwbXlycXB6bWxtYnp4ZGp4Iiwicm9sZSI6ImFub24iLCJpYXQiOjE3ODEwMjA5MTEsImV4cCI6MjA5NjU5NjkxMX0.IRmeITfzCUxrxud-ekLFj1Ij7QNEmsL6n4CXx3Kx4uc"

    private val http = OkHttpClient()
    private val JSON_TYPE = "application/json".toMediaType()

    private fun Request.Builder.withAuth() = this
        .header("apikey", API_KEY)
        .header("Authorization", "Bearer $API_KEY")
        .header("Content-Type", "application/json")

    fun registerDevice(deviceId: String, name: String) {
        val body = JSONObject().apply {
            put("id", deviceId)
            put("name", name)
            put("status", "online")
            put("connected", true)
            put("last_seen", Instant.now().toString())
        }.toString()

        val req = Request.Builder()
            .url("$BASE_URL/rest/v1/devices?on_conflict=id")
            .header("Prefer", "resolution=merge-duplicates")
            .withAuth()
            .post(body.toRequestBody(JSON_TYPE))
            .build()
        http.newCall(req).execute().close()
    }

    fun updateDeviceStatus(deviceId: String, status: String) {
        val body = JSONObject().apply {
            put("status", status)
            put("connected", status == "online")
            put("last_seen", Instant.now().toString())
        }.toString()

        val req = Request.Builder()
            .url("$BASE_URL/rest/v1/devices?id=eq.$deviceId")
            .withAuth()
            .patch(body.toRequestBody(JSON_TYPE))
            .build()
        http.newCall(req).execute().close()
    }

    fun getPendingCommand(deviceId: String): JSONObject? {
        val req = Request.Builder()
            .url("$BASE_URL/rest/v1/commands?device_id=eq.$deviceId&status=eq.pending&order=created_at.asc&limit=1")
            .withAuth()
            .build()

        val response = http.newCall(req).execute()
        val body = response.body?.string() ?: return null
        val array = JSONArray(body)
        return if (array.length() > 0) array.getJSONObject(0) else null
    }

    fun markCommand(commandId: String, status: String) {
        val body = JSONObject().apply {
            put("status", status)
            if (status == "done" || status == "error") {
                put("executed_at", Instant.now().toString())
            }
        }.toString()

        val req = Request.Builder()
            .url("$BASE_URL/rest/v1/commands?id=eq.$commandId")
            .withAuth()
            .patch(body.toRequestBody(JSON_TYPE))
            .build()
        http.newCall(req).execute().close()
    }

    fun cancelPendingCommands(deviceId: String) {
        val body = JSONObject().apply { put("status", "cancelled") }.toString()
        val req = Request.Builder()
            .url("$BASE_URL/rest/v1/commands?device_id=eq.$deviceId&status=eq.pending")
            .withAuth()
            .patch(body.toRequestBody(JSON_TYPE))
            .build()
        http.newCall(req).execute().close()
    }

    // Latido: actualiza last_seen para que el panel sepa que el teléfono sigue vivo.
    fun heartbeat(deviceId: String) {
        val body = JSONObject().apply { put("last_seen", Instant.now().toString()) }.toString()
        val req = Request.Builder()
            .url("$BASE_URL/rest/v1/devices?id=eq.$deviceId")
            .withAuth()
            .patch(body.toRequestBody(JSON_TYPE))
            .build()
        http.newCall(req).execute().close()
    }

    fun updateTiktokAccounts(deviceId: String, accounts: List<String>) {
        val arr = JSONArray().also { accounts.forEach(it::put) }
        val body = JSONObject().apply { put("tiktok_accounts", arr) }.toString()
        val req = Request.Builder()
            .url("$BASE_URL/rest/v1/devices?id=eq.$deviceId")
            .withAuth()
            .patch(body.toRequestBody(JSON_TYPE))
            .build()
        http.newCall(req).execute().close()
    }

    fun addLog(deviceId: String, level: String, message: String) {
        val body = JSONObject().apply {
            put("device_id", deviceId)
            put("level", level)
            put("message", message)
        }.toString()

        val req = Request.Builder()
            .url("$BASE_URL/rest/v1/logs")
            .withAuth()
            .post(body.toRequestBody(JSON_TYPE))
            .build()
        http.newCall(req).execute().close()
    }
}
