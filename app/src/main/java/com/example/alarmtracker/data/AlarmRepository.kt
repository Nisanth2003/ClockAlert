package com.example.alarmtracker.data

import android.content.Context
import android.os.SystemClock
import com.example.alarmtracker.util.NextTrigger
import kotlinx.coroutines.flow.Flow

class AlarmRepository private constructor(
    private val db: AppDatabase,
    private val appContext: Context
) {

    private val alarmDao = db.alarmDao()
    private val eventDao = db.alarmEventDao()
    private val sleepSignalDao = db.sleepSignalDao()
    private val eventTriggerDao = db.eventTriggerDao()

    // ---- Alarms ----

    fun observeAlarms(): Flow<List<Alarm>> = alarmDao.observeAll()

    fun observeNextEnabled(): Flow<Alarm?> = alarmDao.observeNextEnabled()

    suspend fun getAlarm(id: Long): Alarm? = alarmDao.getById(id)

    suspend fun getNextEnabled(): Alarm? = alarmDao.getNextEnabled()

    suspend fun getEnabled(): List<Alarm> = alarmDao.getEnabled()

    /** Saves the alarm, materializing nextTriggerAt from its recurrence rule. Returns the row id. */
    suspend fun save(alarm: Alarm): Long {
        val withTrigger = alarm.copy(
            nextTriggerAt = if (alarm.enabled) NextTrigger.compute(appContext, alarm) else null
        )
        val id = alarmDao.upsert(withTrigger)
        return if (withTrigger.id != 0L) withTrigger.id else id
    }

    suspend fun setEnabled(alarm: Alarm, enabled: Boolean) {
        save(alarm.copy(enabled = enabled))
    }

    /** Sets (or clears) the pause window and re-materializes the trigger. */
    suspend fun setPause(alarm: Alarm, from: Long?, until: Long?) {
        save(alarm.copy(pausedFrom = from, pausedUntil = until))
    }

    /** Advances a recurring alarm past [after]; disables a one-shot / event alarm. */
    suspend fun advanceAfterRing(alarmId: Long, after: Long) {
        val alarm = alarmDao.getById(alarmId) ?: return
        // Deliberately leaves snoozedUntil alone: this also runs from the stale-trigger heal in
        // AlarmScheduler.rescheduleNext, which must not wipe a snooze that is still pending.
        // The real fire path clears it in AlarmReceiver before calling this.
        // An event alarm is a one-shot arrival: once it rings there is nothing to advance.
        if (alarm.scheduleType == Alarm.SCHEDULE_ONCE || alarm.scheduleType == Alarm.SCHEDULE_EVENT) {
            alarmDao.update(alarm.copy(enabled = false, nextTriggerAt = null))
        } else {
            alarmDao.update(alarm.copy(nextTriggerAt = NextTrigger.compute(appContext, alarm, after)))
        }
    }

    /**
     * Records (or clears) the instant a pending snooze will ring at. Written straight through the
     * DAO so it never re-materializes nextTriggerAt — the snooze has its own registration.
     */
    suspend fun setSnoozedUntil(alarmId: Long, until: Long) {
        val alarm = alarmDao.getById(alarmId) ?: return
        if (alarm.snoozedUntil == until) return
        alarmDao.update(alarm.copy(snoozedUntil = until))
    }

    /** Soft-delete to the recycle bin: stops it ringing but keeps the row for restore. */
    suspend fun delete(alarm: Alarm) {
        alarmDao.update(alarm.copy(deletedAt = System.currentTimeMillis(), nextTriggerAt = null))
    }

    /** Restore from the recycle bin (also the swipe UNDO): clears the deleted flag + reschedules. */
    suspend fun restore(alarm: Alarm) {
        save(alarm.copy(deletedAt = 0))
    }

    fun observeDeleted(): Flow<List<Alarm>> = alarmDao.observeDeleted()

    /** Permanently remove a single soft-deleted alarm. */
    suspend fun purge(alarmId: Long) = alarmDao.deleteById(alarmId)

    /** Permanently remove soft-deleted alarms whose per-type retention window has elapsed. */
    suspend fun purgeExpiredDeleted(now: Long = System.currentTimeMillis()) {
        alarmDao.getDeleted().forEach { a ->
            if (a.deletedAt > 0 && now - a.deletedAt >= a.retentionMs()) alarmDao.deleteById(a.id)
        }
    }

    // ---- Events ----

    fun observeEventsSince(since: Long): Flow<List<AlarmEvent>> = eventDao.observeSince(since)

    suspend fun snoozeCountFor(alarmId: Long, scheduledFor: Long): Int =
        eventDao.snoozeCountFor(alarmId, scheduledFor)

    /** Count of snoozes across all alarms since [since] — used by the weekly snooze budget. */
    suspend fun snoozeCountSince(since: Long): Int = eventDao.snoozeCountSince(since)

    suspend fun logEvent(
        alarmId: Long,
        type: String,
        scheduledFor: Long,
        snoozeCount: Int = 0,
        timeToDismissMs: Long? = null,
        missionDurationMs: Long? = null,
        detail: String? = null
    ) {
        eventDao.insert(
            AlarmEvent(
                alarmId = alarmId,
                type = type,
                scheduledFor = scheduledFor,
                occurredAt = System.currentTimeMillis(),
                occurredElapsed = SystemClock.elapsedRealtime(),
                snoozeCount = snoozeCount,
                timeToDismissMs = timeToDismissMs,
                missionDurationMs = missionDurationMs,
                detail = detail
            )
        )
    }

    /** Logs SCHEDULED once per (alarm, occurrence) — deduped against the latest SCHEDULED row. */
    suspend fun logScheduledOnce(alarmId: Long, scheduledFor: Long) {
        val latest = eventDao.latestOfType(alarmId, AlarmEvent.TYPE_SCHEDULED)
        if (latest?.scheduledFor != scheduledFor) {
            logEvent(alarmId, AlarmEvent.TYPE_SCHEDULED, scheduledFor)
        }
    }

    /** Problem events (MISSED / FIRED_LATE) since [since], newest first — for the postmortem. */
    suspend fun problemsSince(since: Long): List<AlarmEvent> = eventDao.problemsSince(since)

    /** Dismissals since [since], newest first — for the morning report card. */
    suspend fun dismissalsSince(since: Long): List<AlarmEvent> = eventDao.dismissalsSince(since)

    // ---- Bedtime / sleep signals (feature 6) ----

    suspend fun recordSleepSignal(source: String, at: Long = System.currentTimeMillis()) {
        sleepSignalDao.insert(SleepSignal(occurredAt = at, source = source))
    }

    suspend fun latestSleepSignal(): SleepSignal? = sleepSignalDao.latest()

    suspend fun latestSleepSignalOfSource(source: String): SleepSignal? =
        sleepSignalDao.latestOfSource(source)

    /** The best bedtime signal to pair with a wake at [wakeAt] (latest one in the prior window). */
    suspend fun bedtimeSignalFor(wakeAt: Long, windowMs: Long): SleepSignal? =
        sleepSignalDao.latestBetween(wakeAt - windowMs, wakeAt)

    // ---- Event triggers (v3 event alarms) ----

    fun observeEventTriggers(): Flow<List<EventTrigger>> = eventTriggerDao.observeAll()

    suspend fun getEventTrigger(alarmId: Long): EventTrigger? = eventTriggerDao.getByAlarmId(alarmId)

    suspend fun getEnabledEventTriggers(): List<EventTrigger> = eventTriggerDao.getEnabled()

    /** Every trigger row (used by connectors to reconcile their alarms against a fresh poll). */
    suspend fun allEventTriggers(): List<EventTrigger> = eventTriggerDao.getAll()

    /** Upserts the trigger for [alarmId], preserving its existing row id if present. */
    suspend fun saveEventTrigger(trigger: EventTrigger): Long {
        val existing = eventTriggerDao.getByAlarmId(trigger.alarmId)
        val withId = if (existing != null) trigger.copy(id = existing.id) else trigger
        return eventTriggerDao.upsert(withId)
    }

    suspend fun updateEventTrigger(trigger: EventTrigger) = eventTriggerDao.update(trigger)

    suspend fun deleteEventTrigger(alarmId: Long) = eventTriggerDao.deleteByAlarmId(alarmId)

    // ---- Export / wipe (feature 7) ----

    suspend fun allAlarms(): List<Alarm> = alarmDao.getAll()
    suspend fun allEvents(): List<AlarmEvent> = eventDao.getAll()
    suspend fun allSleepSignals(): List<SleepSignal> = sleepSignalDao.getAll()

    /** Destructive: clears every table. Prefs are cleared separately by the caller. */
    suspend fun wipeAll() {
        alarmDao.deleteAll()
        eventDao.deleteAll()
        sleepSignalDao.deleteAll()
        eventTriggerDao.deleteAll()
    }

    companion object {
        @Volatile
        private var instance: AlarmRepository? = null

        fun get(context: Context): AlarmRepository =
            instance ?: synchronized(this) {
                instance ?: AlarmRepository(
                    AppDatabase.get(context),
                    context.applicationContext
                ).also { instance = it }
            }
    }
}
