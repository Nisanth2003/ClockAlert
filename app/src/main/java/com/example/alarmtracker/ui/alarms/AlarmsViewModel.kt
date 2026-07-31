package com.example.alarmtracker.ui.alarms

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.alarmtracker.data.Alarm
import com.example.alarmtracker.data.AlarmRepository
import com.example.alarmtracker.data.EventTrigger
import com.example.alarmtracker.scheduling.AlarmScheduler
import com.example.alarmtracker.scheduling.EventAlarmCoordinator
import com.example.alarmtracker.util.NextTrigger
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class AlarmsViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = AlarmRepository.get(application)

    /** null = still loading (avoids a flash of the empty state). */
    val alarms: StateFlow<List<Alarm>?> = repo.observeAlarms()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val nextAlarm: StateFlow<Alarm?> = repo.observeNextEnabled()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Live event-trigger state keyed by alarmId — drives the event-alarm status line in the list. */
    val eventTriggers: StateFlow<Map<Long, EventTrigger>> = repo.observeEventTriggers()
        .map { list -> list.associateBy { it.alarmId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    fun setEnabled(alarm: Alarm, enabled: Boolean) {
        viewModelScope.launch {
            // Turning off an alarm that is mid-snooze must actually cancel the pending snooze ring,
            // not just clear the flag — the switch reads as ON while snoozed, so this is the only
            // way the user can stop it.
            if (!enabled && alarm.isSnoozedAt(System.currentTimeMillis())) {
                AlarmScheduler.cancelSnooze(getApplication())
            }
            // save() rewrites the whole row, so clearing the marker here is what persists it.
            repo.setEnabled(alarm.copy(snoozedUntil = if (enabled) alarm.snoozedUntil else 0), enabled)
            if (alarm.scheduleType == Alarm.SCHEDULE_EVENT) {
                if (enabled) {
                    // Re-arm: mark the trigger enabled again, then reschedule fallback + geofences.
                    repo.getEventTrigger(alarm.id)?.let {
                        repo.updateEventTrigger(it.copy(enabled = true))
                    }
                    EventAlarmCoordinator.onTriggerConfigured(getApplication(), alarm.id)
                } else {
                    EventAlarmCoordinator.onTriggerDisabled(getApplication(), alarm.id)
                }
            }
            AlarmScheduler.rescheduleNext(getApplication())
        }
    }

    /** Creates an independent copy of [alarm]; for an event alarm, clones its trigger and re-arms it. */
    fun duplicate(alarm: Alarm) {
        viewModelScope.launch {
            val app = getApplication<Application>()
            val newId = repo.save(alarm.copy(id = 0L))
            if (alarm.scheduleType == Alarm.SCHEDULE_EVENT) {
                repo.getEventTrigger(alarm.id)?.let { trigger ->
                    repo.saveEventTrigger(
                        trigger.copy(
                            id = 0L,
                            alarmId = newId,
                            currentEtaMillis = null,
                            lastSignalAt = null,
                            lastDistanceM = null
                        )
                    )
                    EventAlarmCoordinator.onTriggerConfigured(app, newId)
                }
            }
            AlarmScheduler.rescheduleNext(app)
        }
    }

    fun delete(alarm: Alarm) {
        viewModelScope.launch {
            if (alarm.scheduleType == Alarm.SCHEDULE_EVENT) {
                EventAlarmCoordinator.onAlarmDeleted(getApplication(), alarm.id)
            }
            repo.delete(alarm)
            AlarmScheduler.rescheduleNext(getApplication())
        }
    }

    fun restore(alarm: Alarm) {
        viewModelScope.launch {
            repo.restore(alarm)
            if (alarm.scheduleType == Alarm.SCHEDULE_EVENT && alarm.enabled) {
                // Re-arm the (dormant) trigger kept by onAlarmDeleted so UNDO fully restores it.
                repo.getEventTrigger(alarm.id)?.let {
                    repo.updateEventTrigger(it.copy(enabled = true))
                }
                EventAlarmCoordinator.onTriggerConfigured(getApplication(), alarm.id)
            }
            AlarmScheduler.rescheduleNext(getApplication())
        }
    }

    /** Clears an active pause window so the alarm resumes immediately. */
    fun resume(alarm: Alarm) {
        viewModelScope.launch {
            repo.setPause(alarm, null, null)
            AlarmScheduler.rescheduleNext(getApplication())
        }
    }

    /** Skips just the next occurrence; the alarm then resumes at the one after it. */
    fun skipNext(alarm: Alarm) {
        viewModelScope.launch {
            val next = alarm.nextTriggerAt
                ?: NextTrigger.compute(getApplication(), alarm)
                ?: return@launch
            repo.save(alarm.copy(skipUntil = next + 60_000L))
            AlarmScheduler.rescheduleNext(getApplication())
        }
    }

    /** Cancels a pending skip so the next occurrence fires normally. */
    fun clearSkip(alarm: Alarm) {
        viewModelScope.launch {
            repo.save(alarm.copy(skipUntil = 0))
            AlarmScheduler.rescheduleNext(getApplication())
        }
    }
}
