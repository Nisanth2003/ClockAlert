package com.example.alarmtracker.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * The event-source configuration + live estimate state attached to an event alarm.
 *
 * An event alarm is, under the hood, ONE [scheduling.AlarmScheduler.scheduleEventAlarm]
 * registration at the current best estimate, refined by sparse OS-delivered signals
 * (geofence transitions in Phase 1; parsed notifications in Phase 2) and always backed
 * by a guaranteed [fallbackEtaMillis] so it rings even with zero signals.
 *
 * One row per alarm (unique index on [alarmId]). Geofence config lives in typed
 * columns; [configJson] is reserved for source-specific config a future source needs
 * (e.g. Phase 2 notification match rules) so no further migration is required to add one.
 */
@Entity(
    tableName = "event_triggers",
    indices = [Index(value = ["alarmId"], unique = true)]
)
data class EventTrigger(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** The owning alarm's id (alarms.id). Unique — one trigger per alarm. */
    val alarmId: Long,
    /** "GEOFENCE" | "NOTIFICATION" | "COOLDOWN". */
    val sourceType: String = SOURCE_GEOFENCE,
    /** Whether this source is actively registered. Independent of the alarm's own enabled flag. */
    val enabled: Boolean = true,

    // ---- Geofence (destination) config ----
    /** Human place/address the user typed, kept for the list status line. */
    val placeName: String? = null,
    /** Resolved destination latitude (null until geocoded). */
    val destLat: Double? = null,
    /** Resolved destination longitude (null until geocoded). */
    val destLng: Double? = null,
    /** Arrival ring radius in metres (~150–500). ENTER of this ring fires the alarm. */
    val arrivalRadiusM: Int = 200,
    /** Outer "getting close" ring radius in metres (~2000). ENTER triggers a one-shot ETA refine. */
    val outerRadiusM: Int = 2000,
    /** Assumed travel speed (km/h) used to turn remaining distance into an ETA. */
    val assumedSpeedKmh: Int = 40,

    /** Reserved: source-specific config JSON (Phase 2 notification match rules, etc.). */
    val configJson: String? = null,

    // ---- Live estimate state ----
    /** Best refined ETA (epoch millis) from the latest signal; null until a signal refines it. */
    val currentEtaMillis: Long? = null,
    /** Guaranteed fallback ETA (epoch millis). Drives the alarm even with zero signals. */
    val fallbackEtaMillis: Long? = null,
    /** When the last refining signal was applied (epoch millis). */
    val lastSignalAt: Long? = null,
    /** Last known remaining distance to destination in metres — for the "≈X km" status. */
    val lastDistanceM: Int? = null,
    val createdAt: Long = System.currentTimeMillis()
) {
    /** The time the single event alarm is actually registered for: refined ETA, else fallback. */
    val effectiveEtaMillis: Long?
        get() = currentEtaMillis ?: fallbackEtaMillis

    val hasDestination: Boolean
        get() = destLat != null && destLng != null

    companion object {
        const val SOURCE_GEOFENCE = "GEOFENCE"
        const val SOURCE_NOTIFICATION = "NOTIFICATION"

        /**
         * Cooldown / usage-limit reset (v4). A one-shot event alarm whose guaranteed fallback is the
         * expected reset time (a reliable timer). A matching "you've hit your limit, resets at X"
         * notification is parsed for the exact reset time and only ever *refines* the alarm earlier
         * or later — it never rings on the limit-hit itself. Reuses the NOTIFICATION plumbing:
         * config (packages + reset cues) is a [NotificationMatchRule] with
         * [NotificationMatchRule.CONDITION_RESET] in [configJson]. Degrades to the pure timer if
         * notification access isn't granted.
         */
        const val SOURCE_COOLDOWN = "COOLDOWN"

        /**
         * External service connector (v5): Jira, Google Calendar, etc. A polled item's due
         * time is the guaranteed [fallbackEtaMillis]; a later re-poll refines it (item
         * rescheduled) or cancels the alarm (item done/removed). Config — the connector id and
         * the item's external id — is JSON in [configJson]. No geofence/notification plumbing:
         * a periodic WorkManager poll owns the lifecycle. [placeName] holds the connector's
         * display name for the list status line.
         */
        const val SOURCE_CONNECTOR = "CONNECTOR"

        // Signal source tags (for lastSignalAt provenance / logging).
        const val SIGNAL_OUTER_RING = "geofence_outer"
        const val SIGNAL_ARRIVAL = "geofence_arrival"
        const val SIGNAL_NOTIFICATION = "notification"
        const val SIGNAL_COOLDOWN = "cooldown_reset"
        const val SIGNAL_CONNECTOR = "connector"

        /** Sparse in-transit distance re-check (see [scheduling.LiveEtaTracker]). */
        const val SIGNAL_LIVE_ETA = "live_eta"
    }
}
