package com.example.alarmtracker.friends

import android.content.Context
import android.util.Log
import com.example.alarmtracker.util.Prefs
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * How encrypted messages get from one paired phone to the other.
 *
 * Kept behind an interface on purpose. Nothing above this line knows or cares which relay is in
 * use, so moving off the public ntfy server — to a self-hosted ntfy, or to something else entirely
 * — is one class, not a rewrite. The transport only ever handles opaque base64 blobs; see
 * [PairCrypto] for why it is treated as hostile.
 */
interface FriendsTransport {

    /** Sends one already-encrypted payload to [topic]. Returns false if it didn't get through. */
    suspend fun publish(topic: String, sealedPayload: String): Boolean

    /**
     * Fetches messages posted to [topic] since [sinceMillis], newest last. One-shot: no socket is
     * held open, which is what makes the idle cost of this feature genuinely zero.
     */
    suspend fun poll(topic: String, sinceMillis: Long): List<String>
}

/**
 * [ntfy](https://ntfy.sh) implementation — open source (Apache-2.0/GPLv2), no account, no API key,
 * and no SDK: publishing is an HTTP POST and reading is one GET. That is the entire integration,
 * which is also why self-hosting later changes nothing but a base URL.
 *
 * Reading uses `?poll=1` rather than holding a stream. A live socket would mean a permanent wake
 * cost for a feature that is idle almost all day; instead the sync worker polls only while a share
 * session is actually running, and the real-time half of the feature is carried by geofences on the
 * sharer's phone, which the OS wakes for free.
 */
class NtfyTransport(private val context: Context) : FriendsTransport {

    override suspend fun publish(topic: String, sealedPayload: String): Boolean = withContext(Dispatchers.IO) {
        val base = Prefs.relayBaseUrl(context).trimEnd('/')
        try {
            val connection = (URL("$base/$topic").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("User-Agent", USER_AGENT)
                // Nothing legible in the notification itself — the relay (and anyone watching the
                // topic) should learn nothing beyond "a message happened".
                setRequestProperty("Title", "AlarmTracker")
                setRequestProperty("Priority", "default")
                authToken()?.let { setRequestProperty("Authorization", "Bearer $it") }
            }
            try {
                connection.outputStream.use { it.write(sealedPayload.toByteArray(Charsets.UTF_8)) }
                val ok = connection.responseCode in 200..299
                if (!ok) Log.w(TAG, "publish failed: HTTP ${connection.responseCode}")
                ok
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            Log.w(TAG, "publish failed", e)
            false
        }
    }

    override suspend fun poll(topic: String, sinceMillis: Long): List<String> = withContext(Dispatchers.IO) {
        val base = Prefs.relayBaseUrl(context).trimEnd('/')
        // ntfy's `since` takes seconds, a duration, or "all".
        val since = if (sinceMillis <= 0) "12h" else "${(sinceMillis / 1000)}"
        val url = "$base/$topic/json?poll=1&since=${URLEncoder.encode(since, "UTF-8")}"
        try {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("User-Agent", USER_AGENT)
                authToken()?.let { setRequestProperty("Authorization", "Bearer $it") }
            }
            try {
                if (connection.responseCode in 200..299) {
                    // Newline-delimited JSON, one event per line.
                    connection.inputStream.bufferedReader().use(BufferedReader::readLines)
                        .mapNotNull { line -> line.takeIf { it.isNotBlank() }?.let(::messageBody) }
                } else {
                    Log.w(TAG, "poll failed: HTTP ${connection.responseCode}")
                    emptyList()
                }
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            Log.w(TAG, "poll failed", e)
            emptyList()
        }
    }

    /** Pulls the payload out of one ntfy event line, skipping keepalives and open events. */
    private fun messageBody(line: String): String? = try {
        val json = JSONObject(line)
        if (json.optString("event") == "message") {
            json.optString("message").takeIf { it.isNotBlank() }
        } else {
            null
        }
    } catch (_: Exception) {
        null
    }

    /** Set only when the user has pointed the app at ntfy Pro or their own authenticated server. */
    private fun authToken(): String? = Prefs.relayToken(context)?.takeIf { it.isNotBlank() }

    companion object {
        private const val TAG = "NtfyTransport"
        private const val TIMEOUT_MS = 15_000
        private const val USER_AGENT = "AlarmTracker/1.0 (Android)"

        /** ntfy's own free public relay. Editable in Settings; see [Prefs.relayBaseUrl]. */
        const val DEFAULT_BASE_URL = "https://ntfy.sh"
    }
}
