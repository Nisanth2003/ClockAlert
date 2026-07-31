package com.example.alarmtracker.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * Notification-source match rule for an event alarm. Persisted as JSON in
 * [EventTrigger.configJson] — the column already exists, so the NOTIFICATION source needs
 * NO schema migration (DB stays at version 5).
 *
 * JSON schema (configJson), version 1:
 * ```
 * {
 *   "v": 1,
 *   "packages": ["com.anthropic.claude"],       // source app package(s) to watch
 *   "condition": "onOngoingRemoved"             // one of the CONDITION_* values below
 *              | "textContainsKeyword"
 *              | "etaThreshold",
 *   "keywords": ["done","complete","arrived"],  // for textContainsKeyword; also a hard-fire
 *                                               //   safety net on the other conditions
 *   "etaThresholdMinutes": 1,                   // for etaThreshold: fire when parsed remaining <= N
 *   "parseEta": true,                           // parse Maps-style "12 km · 14 min" -> refine ETA
 *   "label": "Google Maps",                     // friendly name for the list status line
 *   "waitMinutes": 30                           // ring anyway this many minutes after saving
 * }
 * ```
 *
 * The fallback ETA itself is NOT stored here — it lives in [EventTrigger.fallbackEtaMillis] (and is
 * mirrored to the alarm's hour/minute), and drives the guaranteed `setAlarmClock` via
 * [scheduling.EventAlarmCoordinator.onTriggerConfigured]. [waitMinutes] only records HOW the user
 * expressed it, so re-opening the editor shows "30 min" again instead of a clock time they never chose.
 */
data class NotificationMatchRule(
    val packages: List<String>,
    val condition: String,
    val keywords: List<String> = emptyList(),
    val etaThresholdMinutes: Int? = null,
    val parseEta: Boolean = false,
    /**
     * Cooldown source: parse a reset clock-time / duration ("resets at 3:30 PM", "try again in 2h")
     * out of the notification and refine the alarm to it. Unlike [parseEta] (which counts a
     * remaining-time down to a fire), a parsed reset only reschedules the guaranteed timer — the
     * limit-hit notification itself never rings the alarm.
     */
    val parseReset: Boolean = false,
    val label: String? = null,
    /**
     * How long to wait for the notification before ringing anyway, in minutes from the moment the
     * alarm is saved — the notification source's form of the guaranteed fallback.
     *
     * A clock time is the wrong control for this source. "Ring at 9:15 pm" is right for a journey,
     * which takes as long as it takes; but "tell me when the build finishes / the food arrives / the
     * train gets in" is a short wait measured from now, and picking a wall-clock backstop for it means
     * doing the arithmetic yourself every single time. Null keeps the old behaviour (the alarm's set
     * time is the backstop) for anyone who genuinely wants a clock deadline, and for rows saved
     * before this field existed.
     */
    val waitMinutes: Int? = null
) {
    fun matchesPackage(pkg: String): Boolean = packages.any { it.equals(pkg, ignoreCase = true) }

    fun toJson(): String {
        val o = JSONObject()
        o.put("v", VERSION)
        o.put("packages", JSONArray(packages))
        o.put("condition", condition)
        if (keywords.isNotEmpty()) o.put("keywords", JSONArray(keywords))
        etaThresholdMinutes?.let { o.put("etaThresholdMinutes", it) }
        o.put("parseEta", parseEta)
        if (parseReset) o.put("parseReset", true)
        label?.let { o.put("label", it) }
        waitMinutes?.let { o.put("waitMinutes", it) }
        return o.toString()
    }

    companion object {
        const val VERSION = 1

        /** Fire when the notification's title/text contains one of [keywords]. */
        const val CONDITION_KEYWORD = "textContainsKeyword"

        /** Fire when a tracked ONGOING notification from the package is removed (task/download done). */
        const val CONDITION_REMOVED = "onOngoingRemoved"

        /** Refine the ETA from parsed nav text; fire when the remaining time drops to the threshold. */
        const val CONDITION_ETA = "etaThreshold"

        /**
         * Cooldown source: a matching notification carries a reset time we parse and refine to.
         * Never fires the alarm directly — the guaranteed timer does that at the reset moment.
         */
        const val CONDITION_RESET = "limitResetTime"

        fun fromJson(json: String?): NotificationMatchRule? {
            if (json.isNullOrBlank()) return null
            return try {
                val o = JSONObject(json)
                val pkgs = o.optJSONArray("packages")?.let { arr ->
                    (0 until arr.length()).map { arr.getString(it) }
                }.orEmpty()
                if (pkgs.isEmpty()) return null
                val kws = o.optJSONArray("keywords")?.let { arr ->
                    (0 until arr.length()).map { arr.getString(it) }
                }.orEmpty()
                NotificationMatchRule(
                    packages = pkgs,
                    condition = o.optString("condition", CONDITION_KEYWORD),
                    keywords = kws,
                    etaThresholdMinutes = if (o.has("etaThresholdMinutes")) o.getInt("etaThresholdMinutes") else null,
                    parseEta = o.optBoolean("parseEta", false),
                    parseReset = o.optBoolean("parseReset", false),
                    label = if (o.has("label") && !o.isNull("label")) o.optString("label") else null,
                    waitMinutes = o.optInt("waitMinutes", 0).takeIf { it > 0 }
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}
