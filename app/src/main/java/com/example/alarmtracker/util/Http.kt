package com.example.alarmtracker.util

import java.net.HttpURLConnection
import java.net.URL

/**
 * The one place a plain keyless HTTP GET is made (place search, reverse geocoding, routing).
 * Callers treat a null result as "not available" and degrade — never as a hard error, because
 * every endpoint behind this is a free public service that may be offline.
 */
object Http {

    private const val USER_AGENT = "AlarmTracker/1.0 (Android)"
    const val DEFAULT_TIMEOUT_MS = 8_000

    fun get(url: String, timeoutMs: Int = DEFAULT_TIMEOUT_MS): String? = try {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/json")
        }
        try {
            if (connection.responseCode in 200..299) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                null
            }
        } finally {
            connection.disconnect()
        }
    } catch (_: Exception) {
        // Offline, DNS failure, endpoint down — the caller degrades gracefully.
        null
    }
}
