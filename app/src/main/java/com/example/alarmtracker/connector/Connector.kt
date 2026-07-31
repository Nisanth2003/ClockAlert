package com.example.alarmtracker.connector

import android.content.Context

/**
 * A single actionable item pulled from an external service that should become an alarm.
 * [dueAtMillis] is the epoch-millis instant the alarm should ring (already lead-adjusted).
 */
data class ConnectorItem(
    val connectorId: String,   // e.g. "jira"
    val externalId: String,    // stable id in the source, e.g. the Jira issue key "PROJ-123"
    val title: String,         // becomes the alarm label
    val dueAtMillis: Long
)

/** Raised by a connector when a poll or credential check fails. */
class ConnectorException(message: String, val authError: Boolean = false) : Exception(message)

/**
 * A pluggable external source of alarms (v5). The framework treats every connector the same:
 * connect once, then a periodic background poll turns near-term items into event-alarms via the
 * existing [com.example.alarmtracker.scheduling.EventAlarmCoordinator] engine. Add a new service
 * (Google Calendar, GitHub, …) by implementing this and registering it in [ConnectorRegistry].
 */
interface Connector {
    /** Stable id stored in each trigger's config JSON. */
    val id: String

    /** Display name string resource (shown on the Connections screen). */
    val displayNameRes: Int

    /** One-line description string resource. */
    val descriptionRes: Int

    fun isConnected(context: Context): Boolean

    /** "Connected as X" detail once linked, else null. */
    fun accountLabel(context: Context): String?

    /** Forget stored credentials for this connector. */
    fun disconnect(context: Context)

    /**
     * Fetch near-term items (due in the next ~48h). Runs off the main thread already.
     * @throws ConnectorException on network/auth failure.
     */
    suspend fun poll(context: Context): List<ConnectorItem>
}
