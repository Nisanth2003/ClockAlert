package com.example.alarmtracker.ui.timer

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.example.alarmtracker.util.Prefs
import org.json.JSONArray
import org.json.JSONObject

/** One countdown in the multi-timer list. */
data class TimerItem(
    val id: Long,
    val label: String,
    val durationMs: Long,
    /** Epoch millis it fires while [running]; 0 otherwise. */
    val endAt: Long,
    /** Ms left while NOT running (paused / reset). */
    val remainingMs: Long,
    val running: Boolean,
    /** Optional "when it ends, open this app" action: launch package + its display label. */
    val actionPackage: String? = null,
    val actionLabel: String? = null
) {
    fun remaining(now: Long): Long =
        if (running) (endAt - now).coerceAtLeast(0L) else remainingMs
}

/**
 * Store + scheduler for MULTIPLE independent countdown timers, persisted as JSON in
 * SharedPreferences so they survive tab-switches and process death. Each running timer gets its
 * own exact [AlarmManager] alarm (unique request code per id) so it fires in the background;
 * [TimerReceiver] alerts and marks it finished.
 */
object TimerController {

    private const val K_LIST = "timers_json"
    private const val K_DELETED = "timers_deleted_json"
    private const val K_NEXT = "timers_next_id"
    private const val RC_BASE = 91_000
    private const val TIMER_RETENTION_MS = 24L * 60 * 60 * 1000

    fun all(ctx: Context): List<TimerItem> = parse(Prefs.get(ctx).getString(K_LIST, "[]"))

    fun anyRunning(ctx: Context): Boolean = all(ctx).any { it.running }

    fun add(
        ctx: Context,
        label: String,
        durationMs: Long,
        actionPackage: String? = null,
        actionLabel: String? = null
    ): Long {
        if (durationMs <= 0L) return -1L
        val prefs = Prefs.get(ctx)
        val id = prefs.getLong(K_NEXT, 1L)
        prefs.edit().putLong(K_NEXT, id + 1).apply()
        val endAt = System.currentTimeMillis() + durationMs
        persist(
            ctx,
            all(ctx) + TimerItem(id, label, durationMs, endAt, 0L, true, actionPackage, actionLabel)
        )
        schedule(ctx, id, endAt)
        return id
    }

    /** The finish-action (openable app package + label) for [id], or null. */
    fun actionOf(ctx: Context, id: Long): Pair<String, String>? {
        val item = all(ctx).firstOrNull { it.id == id } ?: return null
        val pkg = item.actionPackage ?: return null
        return pkg to (item.actionLabel ?: pkg)
    }

    fun itemOf(ctx: Context, id: Long): TimerItem? = all(ctx).firstOrNull { it.id == id }

    /**
     * Edit an existing timer's label, duration and finish-action. A running timer is rescheduled to
     * the new duration from now; a paused/idle one is reset to the new duration.
     */
    fun edit(
        ctx: Context,
        id: Long,
        label: String,
        durationMs: Long,
        actionPackage: String?,
        actionLabel: String?
    ) {
        val item = all(ctx).firstOrNull { it.id == id } ?: return
        if (durationMs <= 0L) return
        cancel(ctx, id)
        val now = System.currentTimeMillis()
        val updated = if (item.running) {
            item.copy(
                label = label, durationMs = durationMs, endAt = now + durationMs, remainingMs = 0L,
                running = true, actionPackage = actionPackage, actionLabel = actionLabel
            )
        } else {
            item.copy(
                label = label, durationMs = durationMs, endAt = 0L, remainingMs = durationMs,
                running = false, actionPackage = actionPackage, actionLabel = actionLabel
            )
        }
        update(ctx, id) { updated }
        if (updated.running) schedule(ctx, id, updated.endAt)
    }

    /**
     * Snooze: re-run the SAME timer (same list entry) for [durationMs] instead of spawning a new
     * one. Returns false if the timer no longer exists (deleted) so the caller can fall back.
     */
    fun snoozeRestart(ctx: Context, id: Long, durationMs: Long): Boolean {
        if (all(ctx).none { it.id == id }) return false
        cancel(ctx, id)
        val endAt = System.currentTimeMillis() + durationMs
        update(ctx, id) { it.copy(running = true, endAt = endAt, remainingMs = 0L, durationMs = durationMs) }
        schedule(ctx, id, endAt)
        return true
    }

    /** Play/pause toggle. */
    fun toggle(ctx: Context, id: Long) {
        val item = all(ctx).firstOrNull { it.id == id } ?: return
        val now = System.currentTimeMillis()
        if (item.running) {
            cancel(ctx, id)
            update(ctx, id) { it.copy(running = false, remainingMs = (it.endAt - now).coerceAtLeast(0L), endAt = 0L) }
        } else {
            val remaining = if (item.remainingMs > 0L) item.remainingMs else item.durationMs
            val endAt = now + remaining
            update(ctx, id) { it.copy(running = true, endAt = endAt, remainingMs = 0L) }
            schedule(ctx, id, endAt)
        }
    }

    fun addTime(ctx: Context, id: Long, extraMs: Long) {
        val item = all(ctx).firstOrNull { it.id == id } ?: return
        if (item.running) {
            val endAt = item.endAt + extraMs
            update(ctx, id) { it.copy(endAt = endAt, durationMs = it.durationMs + extraMs) }
            schedule(ctx, id, endAt)
        } else {
            update(ctx, id) { it.copy(remainingMs = it.remainingMs + extraMs, durationMs = it.durationMs + extraMs) }
        }
    }

    fun reset(ctx: Context, id: Long) {
        cancel(ctx, id)
        update(ctx, id) { it.copy(running = false, endAt = 0L, remainingMs = it.durationMs) }
    }

    /** Soft-delete to the timer recycle bin (kept 1 day), pausing + unscheduling it. */
    fun delete(ctx: Context, id: Long) {
        val item = all(ctx).firstOrNull { it.id == id } ?: return
        cancel(ctx, id)
        persist(ctx, all(ctx).filterNot { it.id == id })
        val paused = item.copy(
            running = false,
            remainingMs = item.remaining(System.currentTimeMillis()).coerceAtLeast(0L),
            endAt = 0L
        )
        val bin = deleted(ctx).toMutableList()
        bin.add(0, paused to System.currentTimeMillis())
        persistDeleted(ctx, bin)
    }

    /** Timers currently in the recycle bin, paired with the epoch millis each was deleted. */
    fun deleted(ctx: Context): List<Pair<TimerItem, Long>> =
        parseDeleted(Prefs.get(ctx).getString(K_DELETED, "[]"))

    fun retentionMs(): Long = TIMER_RETENTION_MS

    /** Restore a binned timer as a fresh (paused) timer, then remove it from the bin. */
    fun restore(ctx: Context, id: Long) {
        val entry = deleted(ctx).firstOrNull { it.first.id == id } ?: return
        val item = entry.first
        val remaining = if (item.remainingMs > 0L) item.remainingMs else item.durationMs
        val prefs = Prefs.get(ctx)
        val newId = prefs.getLong(K_NEXT, 1L)
        prefs.edit().putLong(K_NEXT, newId + 1).apply()
        persist(ctx, all(ctx) + item.copy(id = newId, running = false, endAt = 0L, remainingMs = remaining))
        persistDeleted(ctx, deleted(ctx).filterNot { it.first.id == id })
    }

    fun purgeFromBin(ctx: Context, id: Long) {
        persistDeleted(ctx, deleted(ctx).filterNot { it.first.id == id })
    }

    fun purgeExpired(ctx: Context, now: Long = System.currentTimeMillis()) {
        persistDeleted(ctx, deleted(ctx).filter { now - it.second < TIMER_RETENTION_MS })
    }

    /** Called by [TimerReceiver] when a timer's alarm fires. */
    fun onFired(ctx: Context, id: Long) {
        update(ctx, id) { it.copy(running = false, endAt = 0L, remainingMs = 0L) }
    }

    fun labelOf(ctx: Context, id: Long): String = all(ctx).firstOrNull { it.id == id }?.label.orEmpty()

    // ---- internals ----

    private fun update(ctx: Context, id: Long, transform: (TimerItem) -> TimerItem) {
        persist(ctx, all(ctx).map { if (it.id == id) transform(it) else it })
    }

    /**
     * Total by construction: a single malformed entry is skipped rather than thrown.
     * [all] is read from `AlarmTrackerApp.onCreate`, so a JSONException escaping here would make
     * the app unlaunchable until its data was cleared — one corrupt or half-written list should
     * cost you your timers, not the whole app.
     */
    private fun parse(json: String?): List<TimerItem> {
        val arr = try { JSONArray(json ?: "[]") } catch (_: Exception) { JSONArray() }
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optLong("id", -1L)
            val duration = o.optLong("dur", -1L)
            if (id < 0 || duration < 0) return@mapNotNull null
            TimerItem(
                id = id,
                label = o.optString("label", ""),
                durationMs = duration,
                endAt = o.optLong("end", 0L),
                remainingMs = o.optLong("rem", 0L),
                running = o.optBoolean("run", false),
                actionPackage = o.optString("actPkg", "").ifBlank { null },
                actionLabel = o.optString("actLabel", "").ifBlank { null }
            )
        }
    }

    private fun persist(ctx: Context, list: List<TimerItem>) {
        Prefs.get(ctx).edit().putString(K_LIST, encode(list)).apply()
    }

    private fun encode(list: List<TimerItem>): String {
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject()
                    .put("id", it.id).put("label", it.label).put("dur", it.durationMs)
                    .put("end", it.endAt).put("rem", it.remainingMs).put("run", it.running)
                    .put("actPkg", it.actionPackage ?: "").put("actLabel", it.actionLabel ?: "")
            )
        }
        return arr.toString()
    }

    /** Same total-parsing rule as [parse] — the recycle bin is read on every app start. */
    private fun parseDeleted(json: String?): List<Pair<TimerItem, Long>> {
        val arr = try { JSONArray(json ?: "[]") } catch (_: Exception) { JSONArray() }
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optLong("id", -1L)
            val duration = o.optLong("dur", -1L)
            if (id < 0 || duration < 0) return@mapNotNull null
            TimerItem(
                id = id,
                label = o.optString("label", ""),
                durationMs = duration,
                endAt = 0L,
                remainingMs = o.optLong("rem", 0L),
                running = false,
                actionPackage = o.optString("actPkg", "").ifBlank { null },
                actionLabel = o.optString("actLabel", "").ifBlank { null }
            ) to o.optLong("delAt", 0L)
        }
    }

    private fun persistDeleted(ctx: Context, list: List<Pair<TimerItem, Long>>) {
        val arr = JSONArray()
        list.forEach { (it, delAt) ->
            arr.put(
                JSONObject()
                    .put("id", it.id).put("label", it.label).put("dur", it.durationMs)
                    .put("rem", it.remainingMs).put("delAt", delAt)
                    .put("actPkg", it.actionPackage ?: "").put("actLabel", it.actionLabel ?: "")
            )
        }
        Prefs.get(ctx).edit().putString(K_DELETED, arr.toString()).apply()
    }

    private fun pendingIntent(ctx: Context, id: Long): PendingIntent {
        val intent = Intent(ctx, TimerReceiver::class.java)
            .setAction(TimerReceiver.ACTION_FIRE)
            .putExtra(TimerReceiver.EXTRA_ID, id)
        return PendingIntent.getBroadcast(
            ctx, RC_BASE + id.toInt(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun schedule(ctx: Context, id: Long, endAt: Long) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, endAt, pendingIntent(ctx, id))
        } catch (_: SecurityException) {
            am.set(AlarmManager.RTC_WAKEUP, endAt, pendingIntent(ctx, id))
        }
    }

    private fun cancel(ctx: Context, id: Long) {
        ctx.getSystemService(AlarmManager::class.java)?.cancel(pendingIntent(ctx, id))
    }
}
