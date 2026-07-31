package com.example.alarmtracker.connector

import android.content.Context
import com.example.alarmtracker.data.Alarm
import com.example.alarmtracker.data.AlarmRepository
import com.example.alarmtracker.data.EventTrigger
import com.example.alarmtracker.scheduling.EventAlarmCoordinator
import java.util.Calendar
import org.json.JSONObject

/**
 * Reconciles a connector's freshly-polled items against the alarms it previously created.
 * Each item is an event-alarm (SCHEDULE_EVENT) backed by an [EventTrigger] whose fallback ETA is
 * the item's due time — so it rides the existing event engine (guaranteed fire + reschedule).
 *
 *   new item        → create alarm + trigger, arm it
 *   changed due date → move the alarm
 *   item gone/done   → cancel + delete the alarm
 *
 * A connector-owned trigger is identified by sourceType == CONNECTOR and a [configJson] carrying
 * the connector id + the item's external id. Returns how many alarms are currently set.
 */
object ConnectorAlarmSync {

    private const val CFG_CONNECTOR = "connectorId"
    private const val CFG_EXTERNAL = "externalId"
    private const val SKEW_MS = 60_000L

    suspend fun sync(context: Context, connector: Connector, items: List<ConnectorItem>): Int {
        val repo = AlarmRepository.get(context)
        val now = System.currentTimeMillis()
        val display = context.getString(connector.displayNameRes)

        val owned = repo.allEventTriggers().filter {
            it.sourceType == EventTrigger.SOURCE_CONNECTOR && connectorIdOf(it) == connector.id
        }
        val byExternal = owned.associateBy { externalIdOf(it) }
        val seen = HashSet<String>()
        var setCount = 0

        for (item in items) {
            if (item.dueAtMillis <= now + SKEW_MS) continue // in the past / too soon to schedule
            seen += item.externalId
            val existing = byExternal[item.externalId]
            if (existing == null) {
                val (h, m) = hourMinute(item.dueAtMillis)
                val alarmId = repo.save(
                    Alarm(
                        hour = h, minute = m, label = item.title,
                        scheduleType = Alarm.SCHEDULE_EVENT, daysOfWeek = 0, enabled = true
                    )
                )
                repo.saveEventTrigger(
                    EventTrigger(
                        alarmId = alarmId,
                        sourceType = EventTrigger.SOURCE_CONNECTOR,
                        enabled = true,
                        placeName = display,
                        fallbackEtaMillis = item.dueAtMillis,
                        configJson = buildConfig(connector.id, item.externalId)
                    )
                )
                EventAlarmCoordinator.onTriggerConfigured(context, alarmId)
                setCount++
            } else {
                val effective = existing.effectiveEtaMillis
                val moved = effective == null || kotlin.math.abs(effective - item.dueAtMillis) > SKEW_MS
                if (moved || !existing.enabled) {
                    repo.saveEventTrigger(
                        existing.copy(
                            enabled = true,
                            fallbackEtaMillis = item.dueAtMillis,
                            currentEtaMillis = null,
                            placeName = display,
                            configJson = buildConfig(connector.id, item.externalId)
                        )
                    )
                    repo.getAlarm(existing.alarmId)?.let { a ->
                        val (h, m) = hourMinute(item.dueAtMillis)
                        repo.save(a.copy(hour = h, minute = m, label = item.title, enabled = true))
                    }
                    EventAlarmCoordinator.onTriggerConfigured(context, existing.alarmId)
                }
                setCount++
            }
        }

        // Anything we created before that the source no longer returns → tear down.
        for (t in owned) {
            val ext = externalIdOf(t)
            if (ext != null && ext !in seen) {
                EventAlarmCoordinator.onTriggerDisabled(context, t.alarmId)
                repo.deleteEventTrigger(t.alarmId)
                repo.getAlarm(t.alarmId)?.let { repo.delete(it) }
            }
        }
        return setCount
    }

    private fun hourMinute(millis: Long): Pair<Int, Int> {
        val cal = Calendar.getInstance().apply { timeInMillis = millis }
        return cal.get(Calendar.HOUR_OF_DAY) to cal.get(Calendar.MINUTE)
    }

    private fun buildConfig(connectorId: String, externalId: String): String =
        JSONObject().put(CFG_CONNECTOR, connectorId).put(CFG_EXTERNAL, externalId).toString()

    private fun connectorIdOf(t: EventTrigger): String? = parse(t)?.optString(CFG_CONNECTOR)?.ifBlank { null }

    private fun externalIdOf(t: EventTrigger): String? = parse(t)?.optString(CFG_EXTERNAL)?.ifBlank { null }

    private fun parse(t: EventTrigger): JSONObject? =
        t.configJson?.let { try { JSONObject(it) } catch (_: Exception) { null } }
}
