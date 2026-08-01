package com.example.alarmtracker.util

import android.content.Context
import android.content.SharedPreferences
import android.text.format.DateFormat
import androidx.appcompat.app.AppCompatDelegate
import androidx.preference.PreferenceManager

/** Central access to app settings stored in default SharedPreferences. */
object Prefs {
    const val KEY_TIME_FORMAT = "pref_time_format"       // system | 12 | 24
    const val KEY_DEFAULT_SNOOZE = "pref_default_snooze" // minutes as string
    const val KEY_VOLUME_RAMP = "pref_volume_ramp"       // boolean
    const val KEY_THEME = "pref_theme"                   // system | light | dark
    const val KEY_DYNAMIC_COLOR = "pref_dynamic_color"   // boolean
    const val KEY_SNOOZE_BUDGET = "pref_snooze_budget"   // snoozes/week as string; 0 = off
    const val KEY_MORNING_REPORT = "pref_morning_report" // boolean; post-dismiss report card
    const val KEY_PREFLIGHT = "pref_preflight"           // boolean; nightly pre-flight check
    const val KEY_VOLUME_SNOOZE = "pref_volume_snooze"   // boolean; volume buttons snooze a ringing alarm
    const val KEY_ONBOARDING_DONE = "pref_onboarding_done" // boolean; first-run guided setup completed
    const val KEY_FULLSCREEN_ALARM = "pref_fullscreen_alarm" // boolean; launch full-screen ring even when unlocked
    const val KEY_AUTO_OPEN_APP = "pref_auto_open_app" // boolean; open an alarm/timer's chosen app itself
    const val KEY_CONNECTOR_INTERVAL = "conn_poll_interval_h" // background poll period, hours as string
    const val KEY_COACH_DONE = "pref_coach_done"         // boolean; guided walkthrough shown once
    const val KEY_LIVE_ETA = "pref_live_eta"             // boolean; re-check distance to refine arrival alarms
    const val KEY_MEETING_AWARE = "pref_meeting_aware"   // boolean; don't ring out loud during a call
    const val KEY_ACCENT = "pref_accent"                 // "wallpaper" | a hex seed colour
    const val KEY_RELAY_URL = "friends_relay_url"        // friend-alert relay base URL
    const val KEY_RELAY_TOKEN = "friends_relay_token"    // optional bearer token (ntfy Pro / self-hosted)

    fun get(context: Context): SharedPreferences =
        PreferenceManager.getDefaultSharedPreferences(context)

    /**
     * A separate store for credentials, kept out of the main preferences file purely so backup can
     * exclude it by name (see res/xml/backup_rules.xml). Values in here are additionally sealed by
     * [SecretBox]; the file split is about what leaves the device, the sealing about what's
     * readable if the file is lifted off it.
     */
    fun secrets(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences("secrets", Context.MODE_PRIVATE)

    /**
     * Reads a secret, transparently moving it out of the old (backed-up) default preferences file
     * the first time it's seen there. Lets existing installs upgrade without re-entering anything.
     */
    fun readSecret(context: Context, key: String): String? {
        secrets(context).getString(key, null)?.let { return SecretBox.open(it) }
        val legacy = get(context).getString(key, null) ?: return null
        val plain = SecretBox.open(legacy)
        writeSecret(context, key, plain)
        get(context).edit().remove(key).apply()
        return plain
    }

    fun writeSecret(context: Context, key: String, value: String?) {
        secrets(context).edit().apply {
            if (value.isNullOrBlank()) remove(key) else putString(key, SecretBox.seal(value))
        }.apply()
    }

    /** How often connectors poll in the background, in hours (24 = morning only). Default 24. */
    fun connectorIntervalHours(context: Context): Long =
        get(context).getString(KEY_CONNECTOR_INTERVAL, "24")?.toLongOrNull() ?: 24L

    fun setConnectorIntervalHours(context: Context, hours: Long) {
        get(context).edit().putString(KEY_CONNECTOR_INTERVAL, hours.toString()).apply()
    }

    /** Post-dismiss Morning Report Card (feature 2). On by default. */
    fun morningReportEnabled(context: Context): Boolean =
        get(context).getBoolean(KEY_MORNING_REPORT, true)

    /** Nightly pre-flight alarm health check (feature 4). On by default. */
    fun preflightEnabled(context: Context): Boolean =
        get(context).getBoolean(KEY_PREFLIGHT, true)

    fun defaultSnoozeMinutes(context: Context): Int =
        get(context).getString(KEY_DEFAULT_SNOOZE, "10")?.toIntOrNull() ?: 10

    /**
     * Gradual volume: the ring fades in from near-silence instead of starting at full blast, and a
     * dismiss mission (puzzle/math/…) restarts that swell so you aren't shouted at while solving it.
     * ON by default — a wake-up should escalate, not detonate.
     */
    fun volumeRampEnabled(context: Context): Boolean =
        get(context).getBoolean(KEY_VOLUME_RAMP, true)

    /**
     * Keep an "arrive at a place" alarm's time honest by re-checking the remaining distance while
     * it's armed, so a wrong guessed time gets corrected. On by default; sampling is sparse and
     * only happens near the alarm (see [scheduling.LiveEtaTracker]).
     */
    fun liveEtaEnabled(context: Context): Boolean =
        get(context).getBoolean(KEY_LIVE_ETA, true)

    /**
     * Keep the ring off the loudspeaker while the user is in a call or meeting, so the alarm isn't
     * picked up by the mic and played to everyone else. It goes to a headset if one is connected,
     * otherwise the alarm vibrates and shows the ring screen instead. On by default — an alarm is
     * for its owner. See [MeetingDetector].
     */
    fun meetingAwareEnabled(context: Context): Boolean =
        get(context).getBoolean(KEY_MEETING_AWARE, true)

    /**
     * Where friend alerts are relayed. Defaults to ntfy's free public server, which needs no
     * account and no key. The user can point this at ntfy Pro or their own self-hosted instance
     * — the app never charges for it and takes no cut either way.
     */
    fun relayBaseUrl(context: Context): String =
        get(context).getString(KEY_RELAY_URL, null)?.takeIf { it.isNotBlank() }
            ?: "https://ntfy.sh"

    /** Bearer token for an authenticated relay. Null on the free public server. */
    fun relayToken(context: Context): String? = readSecret(context, KEY_RELAY_TOKEN)

    /**
     * Stores the relay endpoint. The URL is forced to https — friend payloads are already
     * encrypted, but a plaintext endpoint would still leak the topic name (which is the only thing
     * guarding the channel on a public server) to anyone on the network path.
     */
    fun setRelay(context: Context, baseUrl: String, token: String?) {
        get(context).edit().putString(KEY_RELAY_URL, forceHttps(baseUrl)).apply()
        writeSecret(context, KEY_RELAY_TOKEN, token)
    }

    /** True when [url] had to be upgraded or is unusable — lets the UI say so. */
    fun isPlainHttp(url: String): Boolean = url.trim().startsWith("http://", ignoreCase = true)

    private fun forceHttps(url: String): String {
        val trimmed = url.trim()
        return when {
            trimmed.isBlank() -> "https://ntfy.sh"
            trimmed.startsWith("http://", ignoreCase = true) ->
                "https://" + trimmed.removePrefix("http://").removePrefix("HTTP://")
            trimmed.startsWith("https://", ignoreCase = true) -> trimmed
            else -> "https://$trimmed"
        }
    }

    /** Pressing a volume button while an alarm rings snoozes it. On by default. */
    fun volumeSnoozeEnabled(context: Context): Boolean =
        get(context).getBoolean(KEY_VOLUME_SNOOZE, true)

    /**
     * Open an alarm's or timer's chosen app automatically instead of offering a button.
     *
     * OFF by default, and the difference is deliberate rather than arbitrary. For a TIMER, "on" means the
     * app opens the moment it finishes — that is the whole point of "three minutes, then open the camera".
     * For an ALARM, "on" means the app opens once you have DISMISSED it, never while it is ringing:
     * launching an app over a ringing alarm buries the dismiss button underneath it, which is how you end
     * up unable to stop your own alarm, and an alarm you can silence by opening an app is not an alarm.
     */
    fun autoOpenAppEnabled(context: Context): Boolean =
        get(context).getBoolean(KEY_AUTO_OPEN_APP, false)

    /** Launch the full-screen ring screen directly, so it appears even when unlocked. On by default. */
    fun fullScreenAlarmEnabled(context: Context): Boolean =
        get(context).getBoolean(KEY_FULLSCREEN_ALARM, true)

    /** Weekly snooze budget across all alarms; 0 = off. Only enforced for coaching-enabled alarms. */
    fun weeklySnoozeBudget(context: Context): Int =
        get(context).getString(KEY_SNOOZE_BUDGET, "0")?.toIntOrNull() ?: 0

    fun dynamicColorEnabled(context: Context): Boolean =
        get(context).getBoolean(KEY_DYNAMIC_COLOR, true)

    /**
     * The colour the whole app is generated from.
     *
     * null = "match my wallpaper" (Android's own dynamic colour). Otherwise a seed that Material
     * expands into a full, properly-contrasting light/dark scheme at runtime — so picking an accent
     * recolours every surface coherently instead of just tinting a couple of buttons.
     */
    fun accentSeed(context: Context): Int? {
        val raw = get(context).getString(KEY_ACCENT, ACCENT_BRAND) ?: ACCENT_BRAND
        if (raw == ACCENT_WALLPAPER) return null
        return runCatching { android.graphics.Color.parseColor(raw) }.getOrNull()
    }

    const val ACCENT_WALLPAPER = "wallpaper"

    /**
     * The brand seed, and the DEFAULT. It used to default to the wallpaper, which meant the app had no
     * identity of its own and looked washed out on any muted wallpaper — the single biggest reason it
     * read as bland. Wallpaper matching is still one tap away in Settings → Appearance.
     */
    const val ACCENT_BRAND = "#5B4EE8"

    /** Whether the first-run guided onboarding has been completed/skipped. */
    fun onboardingDone(context: Context): Boolean =
        get(context).getBoolean(KEY_ONBOARDING_DONE, false)

    fun setOnboardingDone(context: Context) {
        get(context).edit().putBoolean(KEY_ONBOARDING_DONE, true).apply()
    }

    /** Whether the guided coach-mark walkthrough has been shown/dismissed. */
    fun coachDone(context: Context): Boolean = get(context).getBoolean(KEY_COACH_DONE, false)

    fun setCoachDone(context: Context) {
        get(context).edit().putBoolean(KEY_COACH_DONE, true).apply()
    }

    /** Re-arm the walkthrough so it shows again on the Alarms tab ("Show tips again"). */
    fun resetCoach(context: Context) {
        get(context).edit().putBoolean(KEY_COACH_DONE, false).apply()
    }

    fun is24Hour(context: Context): Boolean =
        when (get(context).getString(KEY_TIME_FORMAT, "system")) {
            "12" -> false
            "24" -> true
            else -> DateFormat.is24HourFormat(context)
        }

    fun applyThemeFromPrefs(context: Context) {
        val mode = when (get(context).getString(KEY_THEME, "system")) {
            "light" -> AppCompatDelegate.MODE_NIGHT_NO
            "dark" -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        AppCompatDelegate.setDefaultNightMode(mode)
    }
}
