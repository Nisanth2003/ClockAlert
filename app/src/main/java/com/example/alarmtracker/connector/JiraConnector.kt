package com.example.alarmtracker.connector

import android.content.Context
import android.util.Base64
import com.example.alarmtracker.R
import com.example.alarmtracker.util.Prefs
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Jira Cloud connector. Auth is a personal Atlassian API token (Basic auth: base64(email:token)),
 * so there is NO OAuth server and NO build-time secret — it works with any org's Jira Cloud.
 * Polls issues assigned to the current user with a due date in the near term and turns each into
 * an alarm. Uses HttpURLConnection + org.json (both in the Android platform) — no extra deps.
 *
 * SECURITY: the API token is sealed by [com.example.alarmtracker.util.SecretBox] (AES-GCM under a
 * hardware Keystore key) and kept in the separate `secrets` preferences file, which backup
 * excludes. The site URL is forced to https so the Basic auth header can never go out in clear.
 */
object JiraConnector : Connector {

    override val id = "jira"
    override val displayNameRes = R.string.connector_jira
    override val descriptionRes = R.string.connector_jira_desc

    private const val KEY_URL = "conn_jira_url"
    private const val KEY_EMAIL = "conn_jira_email"
    private const val KEY_TOKEN = "conn_jira_token"
    private const val KEY_DISPLAY = "conn_jira_display"

    /** Ring due issues at 9am local on their due date (Jira due dates are date-only). */
    private val RING_TIME: LocalTime = LocalTime.of(9, 0)
    private const val LOOKAHEAD_DAYS = 2

    override fun isConnected(context: Context): Boolean {
        val p = Prefs.get(context)
        return !p.getString(KEY_URL, null).isNullOrBlank() &&
            !p.getString(KEY_EMAIL, null).isNullOrBlank() &&
            !Prefs.readSecret(context, KEY_TOKEN).isNullOrBlank()
    }

    override fun accountLabel(context: Context): String? =
        Prefs.get(context).getString(KEY_DISPLAY, null)

    override fun disconnect(context: Context) {
        Prefs.get(context).edit()
            .remove(KEY_URL).remove(KEY_EMAIL).remove(KEY_TOKEN).remove(KEY_DISPLAY)
            .apply()
        Prefs.writeSecret(context, KEY_TOKEN, null)
    }

    /**
     * Validates the given credentials against GET /myself and, on success, stores them and returns
     * the account display name. @throws ConnectorException on failure (nothing is stored).
     */
    suspend fun connect(context: Context, siteUrl: String, email: String, token: String): String =
        withContext(Dispatchers.IO) {
            val base = normalizeBase(siteUrl)
            val body = httpGet("$base/rest/api/3/myself", email, token)
            val display = JSONObject(body).optString("displayName").ifBlank { email }
            Prefs.get(context).edit()
                .putString(KEY_URL, base)
                .putString(KEY_EMAIL, email)
                .putString(KEY_DISPLAY, display)
                .apply()
            Prefs.writeSecret(context, KEY_TOKEN, token)
            display
        }

    override suspend fun poll(context: Context): List<ConnectorItem> = withContext(Dispatchers.IO) {
        val p = Prefs.get(context)
        val base = p.getString(KEY_URL, null) ?: return@withContext emptyList()
        val email = p.getString(KEY_EMAIL, null) ?: return@withContext emptyList()
        // Sealed, and in the backup-excluded secrets store — see Prefs.readSecret.
        val token = Prefs.readSecret(context, KEY_TOKEN) ?: return@withContext emptyList()

        val jql = "assignee = currentUser() AND statusCategory != Done " +
            "AND duedate >= startOfDay() AND duedate <= endOfDay(\"+${LOOKAHEAD_DAYS}d\") " +
            "ORDER BY duedate ASC"
        val url = "$base/rest/api/3/search?jql=${enc(jql)}&fields=summary,duedate&maxResults=50"

        val root = JSONObject(httpGet(url, email, token))
        val issues = root.optJSONArray("issues") ?: return@withContext emptyList()
        val zone = ZoneId.systemDefault()
        val out = ArrayList<ConnectorItem>()
        for (i in 0 until issues.length()) {
            val issue = issues.getJSONObject(i)
            val key = issue.optString("key").ifBlank { continue }
            val fields = issue.optJSONObject("fields") ?: continue
            val due = fields.optString("duedate", "")
            if (due.isBlank() || due == "null") continue
            val dueAt = try {
                LocalDate.parse(due).atTime(RING_TIME).atZone(zone).toInstant().toEpochMilli()
            } catch (_: Exception) {
                continue
            }
            val summary = fields.optString("summary").ifBlank { key }
            out += ConnectorItem(id, key, "$key: $summary", dueAt)
        }
        out
    }

    // ---- HTTP ----

    private fun httpGet(urlStr: String, email: String, token: String): String {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "GET"
            conn.setRequestProperty("Authorization", basic(email, token))
            conn.setRequestProperty("Accept", "application/json")
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            val code = conn.responseCode
            if (code in 200..299) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else {
                val err = try { conn.errorStream?.bufferedReader()?.use { it.readText() } } catch (_: Exception) { null }
                throw ConnectorException(
                    "Jira HTTP $code: ${err?.take(200).orEmpty()}",
                    authError = code == 401 || code == 403
                )
            }
        } catch (e: ConnectorException) {
            throw e
        } catch (e: Exception) {
            throw ConnectorException("Network error: ${e.message}")
        } finally {
            conn.disconnect()
        }
    }

    private fun basic(email: String, token: String): String {
        val raw = "$email:$token".toByteArray(Charsets.UTF_8)
        return "Basic " + Base64.encodeToString(raw, Base64.NO_WRAP)
    }

    /**
     * Always https. Every request here carries the API token in a Basic auth header, so a typo'd
     * (or pasted) `http://` site URL would put a live credential on the wire in the clear.
     * Upgrading rather than failing is safe: Jira Cloud is https-only anyway.
     */
    private fun normalizeBase(url: String): String {
        val u = url.trim().trimEnd('/')
        return when {
            u.startsWith("https://", ignoreCase = true) -> u
            u.startsWith("http://", ignoreCase = true) -> "https://" + u.removeRange(0, 7)
            else -> "https://$u"
        }
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
}
