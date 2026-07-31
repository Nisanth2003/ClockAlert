package com.example.alarmtracker.notif

import android.app.Notification
import android.content.Context
import android.content.pm.PackageManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.example.alarmtracker.util.Dbg
import com.example.alarmtracker.data.AlarmRepository
import com.example.alarmtracker.data.EventTrigger
import com.example.alarmtracker.data.NotificationMatchRule
import com.example.alarmtracker.scheduling.EventAlarmCoordinator
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The NOTIFICATION event source (Phase 2). A push-only [NotificationListenerService] that reuses
 * the SAME engine as the geofence source: it never polls, never does heavy work on the callback
 * thread, and only reacts to notifications the OS delivers.
 *
 * For each enabled NOTIFICATION-source [EventTrigger] whose rule matches the posting package:
 *  - a "done/complete/arrived" keyword match, or the tracked ONGOING notification being REMOVED
 *    (a task/download finishing) -> [EventAlarmCoordinator.fireNow] (ring now, cancel the fallback);
 *  - a progress/nav notification -> parse a remaining time/distance and
 *    [EventAlarmCoordinator.applyEtaSignal] with the refined ETA (free — pushed by the OS on every
 *    update); an `etaThreshold` rule also fires when the remaining time drops to its threshold.
 *
 * If the user never grants (or later revokes) notification access, this service simply isn't bound
 * and the alarm degrades to the pure ETA fallback — it still rings at the estimate.
 *
 * BATTERY CONTRACT: no polling, no timers, no wakelocks. Notification updates are OS-pushed and
 * free; DB work is dispatched off the callback thread onto [scope].
 */
class AlarmNotificationListener : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Tracks the ONGOING notifications we are watching for removal, keyed by StatusBarNotification
     * key -> alarmId. Populated in [onNotificationPosted] and re-seeded in [onListenerConnected] so
     * that a "task finished" (notification removed) fires the right alarm — and ONLY for a
     * notification we were actually tracking, never an unrelated one from the same app.
     */
    private val trackedOngoing = ConcurrentHashMap<String, Long>()

    override fun onListenerConnected() {
        instance = this
        // Re-seed removal tracking from whatever is already on screen (covers a service restart /
        // reboot: the OS rebinds us and re-delivers active notifications, but not past removals).
        scope.launch { seedOngoingTracking() }
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        scope.cancel()
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val s = sbn ?: return
        val pkg = s.packageName ?: return
        // Proof-of-life for the diagnostics dialog. Two volatile writes per notification: the cheapest
        // possible way to answer "is this thing even receiving anything?", which is otherwise
        // unanswerable without logcat and a USB cable.
        postedCount++
        lastPostedAt = System.currentTimeMillis()
        val (title, text) = extractText(s)
        val key = s.key
        val ongoing = s.isOngoing
        scope.launch { handlePosted(pkg, title, text, key, ongoing) }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        val key = sbn?.key ?: return
        scope.launch { handleRemoved(key) }
    }

    // ---- Matching ----

    @Volatile
    private var cachedRules: List<Pair<EventTrigger, NotificationMatchRule>> = emptyList()

    @Volatile
    private var cachedRulesAt = 0L

    /**
     * Enabled notification/cooldown rules, cached briefly.
     *
     * This runs for EVERY notification posted anywhere on the device — hundreds an hour on a busy
     * phone — and each call was hitting the database and re-parsing JSON, even when the user has no
     * notification-source alarms at all. A short TTL keeps that cost near zero while still picking
     * up a newly-saved trigger within seconds, which is well inside human reaction time for this.
     */
    private suspend fun activeRules(): List<Pair<EventTrigger, NotificationMatchRule>> {
        val now = System.currentTimeMillis()
        if (now - cachedRulesAt < RULES_CACHE_MS) return cachedRules
        val repo = AlarmRepository.get(applicationContext)
        val rules = repo.getEnabledEventTriggers()
            .asSequence()
            .filter {
                it.sourceType == EventTrigger.SOURCE_NOTIFICATION ||
                    it.sourceType == EventTrigger.SOURCE_COOLDOWN
            }
            .mapNotNull { t -> NotificationMatchRule.fromJson(t.configJson)?.let { t to it } }
            .toList()
        cachedRules = rules
        cachedRulesAt = now
        return rules
    }

    private suspend fun handlePosted(
        pkg: String,
        title: String,
        text: String,
        key: String,
        ongoing: Boolean
    ) {
        val rules = activeRules()
        if (rules.isEmpty()) return
        val haystack = "$title\n$text"
        for ((trigger, rule) in rules) {
            if (!rule.matchesPackage(pkg)) continue

            // Cooldown source: a "you've hit your limit — resets at X" message only ever REFINES the
            // guaranteed timer to the parsed reset moment. It never rings the alarm on the limit-hit.
            if (rule.condition == NotificationMatchRule.CONDITION_RESET || rule.parseReset) {
                if (ResetTimeParser.looksLikeReset(haystack)) {
                    val reset = ResetTimeParser.parse(haystack)
                    if (reset != null && reset > System.currentTimeMillis()) {
                        Dbg.d(TAG) { "cooldown refine alarm=${trigger.alarmId} pkg=$pkg reset=$reset" }
                        EventAlarmCoordinator.applyEtaSignal(
                            applicationContext, trigger.alarmId, reset, null,
                            EventTrigger.SIGNAL_COOLDOWN
                        )
                    }
                }
                continue
            }

            // Watch this ongoing notification so its later removal can fire the alarm.
            if (rule.condition == NotificationMatchRule.CONDITION_REMOVED && ongoing) {
                trackedOngoing[key] = trigger.alarmId
            }

            // Hard "done" keyword — fires for the keyword condition, and acts as a safety net when a
            // removal/eta rule also lists done-words (e.g. Maps "You have arrived").
            if (rule.keywords.isNotEmpty() && matchesAnyKeyword(haystack, rule.keywords)) {
                Dbg.d(TAG) { "keyword fire alarm=${trigger.alarmId} pkg=$pkg" }
                trackedOngoing.remove(key)
                cachedRulesAt = 0L // this trigger is spent; re-read before matching anything else
                EventAlarmCoordinator.fireNow(
                    applicationContext, trigger.alarmId, EventTrigger.SIGNAL_NOTIFICATION
                )
                continue
            }

            // Progress / nav ETA refine (free — pushed on every update).
            if (rule.parseEta || rule.condition == NotificationMatchRule.CONDITION_ETA) {
                val parsed = MapsEtaParser.parse(haystack)
                if (parsed.remainingMinutes != null) {
                    val eta = System.currentTimeMillis() + parsed.remainingMinutes * 60_000L
                    EventAlarmCoordinator.applyEtaSignal(
                        applicationContext, trigger.alarmId, eta,
                        parsed.distanceMeters, EventTrigger.SIGNAL_NOTIFICATION
                    )
                    val threshold = rule.etaThresholdMinutes
                    if (rule.condition == NotificationMatchRule.CONDITION_ETA &&
                        threshold != null && parsed.remainingMinutes <= threshold
                    ) {
                        Dbg.d(TAG) { "eta-threshold fire alarm=${trigger.alarmId} min=${parsed.remainingMinutes}" }
                        trackedOngoing.remove(key)
                        cachedRulesAt = 0L
                        EventAlarmCoordinator.fireNow(
                            applicationContext, trigger.alarmId, EventTrigger.SIGNAL_NOTIFICATION
                        )
                    }
                }
            }
        }
    }

    private suspend fun handleRemoved(key: String) {
        val alarmId = trackedOngoing.remove(key) ?: return
        // Firing consumes the trigger, so don't let a stale cache match it a second time.
        cachedRulesAt = 0L
        val repo = AlarmRepository.get(applicationContext)
        val trigger = repo.getEventTrigger(alarmId) ?: return
        if (!trigger.enabled || trigger.sourceType != EventTrigger.SOURCE_NOTIFICATION) return
        Dbg.d(TAG) { "ongoing-removed fire alarm=$alarmId" }
        EventAlarmCoordinator.fireNow(
            applicationContext, alarmId, EventTrigger.SIGNAL_NOTIFICATION
        )
    }

    private suspend fun seedOngoingTracking() {
        val rules = activeRules().filter { it.second.condition == NotificationMatchRule.CONDITION_REMOVED }
        if (rules.isEmpty()) return
        val active = try {
            activeNotifications ?: return
        } catch (e: Exception) {
            Log.w(TAG, "seedOngoingTracking: activeNotifications unavailable", e)
            return
        }
        for (sbn in active) {
            val pkg = sbn.packageName ?: continue
            if (!sbn.isOngoing) continue
            rules.firstOrNull { it.second.matchesPackage(pkg) }?.let { (trigger, _) ->
                trackedOngoing[sbn.key] = trigger.alarmId
            }
        }
    }

    private fun matchesAnyKeyword(haystack: String, keywords: List<String>): Boolean {
        val lower = haystack.lowercase()
        return keywords.any { kw ->
            val k = kw.trim()
            k.isNotEmpty() && lower.contains(k.lowercase())
        }
    }

    /** A lightweight, UI-safe view of a live notification for the discovery screen. */
    data class ActiveNotif(
        val packageName: String,
        val appLabel: String,
        val title: String,
        val text: String,
        val isOngoing: Boolean,
        val key: String
    ) {
        val snippet: String
            get() = listOf(title, text).filter { it.isNotBlank() }.joinToString(" · ")
    }

    companion object {
        private const val TAG = "AlarmNotifListener"

        /** How long a rules snapshot stays good. Short enough that a new alarm arms almost at once. */
        private const val RULES_CACHE_MS = 10_000L

        /** The connected instance, used by the discovery UI to read active notifications. */
        @Volatile
        private var instance: AlarmNotificationListener? = null

        /** True when the OS has bound us (i.e. access is granted and the service is live). */
        fun isConnected(): Boolean = instance != null

        /** Notifications delivered to us since this process started. Diagnostics only. */
        @Volatile
        var postedCount: Long = 0
            private set

        /** When the last one arrived, or 0 if none has. Diagnostics only. */
        @Volatile
        var lastPostedAt: Long = 0
            private set

        /**
         * Does [rule] match anything on screen right now? Used by the editor's "Test tracking" check so
         * a rule can be sanity-checked against a real notification instead of waiting for a real
         * delivery to find out it never matched.
         *
         * Package match only — the keyword/removal/ETA conditions are about what the notification says
         * at the moment it fires, so a "no" here is not a failure, just "nothing from that app on
         * screen".
         */
        fun matchingActive(rule: NotificationMatchRule): List<ActiveNotif> {
            val svc = instance ?: return emptyList()
            val active = try {
                svc.activeNotifications ?: return emptyList()
            } catch (_: Exception) {
                return emptyList()
            }
            return active.mapNotNull { sbn ->
                val pkg = sbn.packageName ?: return@mapNotNull null
                if (!rule.matchesPackage(pkg)) return@mapNotNull null
                val (title, text) = extractText(sbn)
                ActiveNotif(pkg, pkg, title, text, sbn.isOngoing, sbn.key)
            }
        }

        /**
         * Snapshot of currently-active notifications worth offering as triggers. Returns empty when
         * access isn't granted / the service isn't connected — the UI then shows the grant prompt.
         * De-duplicates per app+title and skips our own notifications.
         */
        fun activeNotifications(context: Context): List<ActiveNotif> {
            val svc = instance ?: return emptyList()
            val active = try {
                svc.activeNotifications ?: return emptyList()
            } catch (_: Exception) {
                return emptyList()
            }
            val pm = context.packageManager
            val seen = HashSet<String>()
            val out = ArrayList<ActiveNotif>()
            for (sbn in active) {
                val pkg = sbn.packageName ?: continue
                if (pkg == context.packageName) continue
                val (title, text) = extractText(sbn)
                if (title.isBlank() && text.isBlank()) continue
                if (!seen.add("$pkg|$title")) continue
                out.add(ActiveNotif(pkg, appLabel(pm, pkg), title, text, sbn.isOngoing, sbn.key))
            }
            return out
        }

        private fun appLabel(pm: PackageManager, pkg: String): String = try {
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (_: Exception) {
            pkg
        }

        private fun extractText(sbn: StatusBarNotification): Pair<String, String> {
            val extras = sbn.notification?.extras ?: return "" to ""
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
            val text = (extras.getCharSequence(Notification.EXTRA_TEXT)
                ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
                ?: extras.getCharSequence(Notification.EXTRA_SUB_TEXT))?.toString().orEmpty()
            return title.trim() to text.trim()
        }
    }
}
