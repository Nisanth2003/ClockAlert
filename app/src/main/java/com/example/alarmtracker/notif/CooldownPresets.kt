package com.example.alarmtracker.notif

import com.example.alarmtracker.R
import com.example.alarmtracker.data.NotificationMatchRule
import java.util.Calendar

/**
 * Catalog of well-known services whose usage limit / energy / quota replenishes on a schedule —
 * the "alarm me when X is back" family. Each preset seeds a cooldown [data.EventTrigger]:
 *  - a DEFAULT reset time (the guaranteed timer) — [resetMillisFor];
 *  - an optional [NotificationMatchRule] (CONDITION_RESET) so a "resets at 3:30" notification can
 *    refine the timer where the service posts one — [ruleFor]. Presets with no known package are
 *    pure-timer (still fully reliable, just no auto-detect).
 *
 * Windows are honest DEFAULTS, editable via the alarm's time picker — reset lengths and package
 * names for these services change often, so nothing here is a hard promise; the timer + the user's
 * own adjustment are what keep it correct.
 */
object CooldownPresets {

    const val KIND_ROLLING = "ROLLING" // reset = now + defaultWindowMinutes
    const val KIND_DAILY = "DAILY"     // reset = next local occurrence of dailyResetHour

    data class Preset(
        val id: String,
        val labelRes: Int,
        val packRes: Int,
        /** Package(s) to watch for a reset notification; empty = pure timer, no auto-detect. */
        val packages: List<String>,
        val kind: String,
        val defaultWindowMinutes: Int = 0,
        val dailyResetHour: Int = 0
    )

    // ── AI assistants — rolling usage windows (Claude's direct siblings) ──
    private val AI = listOf(
        Preset("claude", R.string.cooldown_svc_claude, R.string.cooldown_pack_ai,
            listOf("com.anthropic.claude"), KIND_ROLLING, 300),
        Preset("chatgpt", R.string.cooldown_svc_chatgpt, R.string.cooldown_pack_ai,
            listOf("com.openai.chatgpt"), KIND_ROLLING, 180),
        Preset("gemini", R.string.cooldown_svc_gemini, R.string.cooldown_pack_ai,
            listOf("com.google.android.apps.bard"), KIND_ROLLING, 180),
        Preset("perplexity", R.string.cooldown_svc_perplexity, R.string.cooldown_pack_ai,
            listOf("ai.perplexity.app.android"), KIND_DAILY, dailyResetHour = 0),
        Preset("copilot", R.string.cooldown_svc_copilot, R.string.cooldown_pack_ai,
            listOf("com.microsoft.copilot"), KIND_ROLLING, 180),
        Preset("grok", R.string.cooldown_svc_grok, R.string.cooldown_pack_ai,
            listOf("ai.x.grok"), KIND_ROLLING, 120),
    )

    // ── AI credit tools — quota / credit refills ──
    private val CREDITS = listOf(
        Preset("midjourney", R.string.cooldown_svc_midjourney, R.string.cooldown_pack_credits,
            emptyList(), KIND_ROLLING, 240),
        Preset("suno", R.string.cooldown_svc_suno, R.string.cooldown_pack_credits,
            listOf("com.suno.android"), KIND_DAILY, dailyResetHour = 0),
        Preset("leonardo", R.string.cooldown_svc_leonardo, R.string.cooldown_pack_credits,
            emptyList(), KIND_DAILY, dailyResetHour = 0),
    )

    // ── Games — energy / stamina / lives refills ──
    private val GAMES = listOf(
        Preset("genshin", R.string.cooldown_svc_genshin, R.string.cooldown_pack_games,
            listOf("com.miHoYo.GenshinImpact"), KIND_ROLLING, 480),
        Preset("hsr", R.string.cooldown_svc_hsr, R.string.cooldown_pack_games,
            listOf("com.HoYoverse.hkrpgoversea"), KIND_ROLLING, 480),
        Preset("candycrush", R.string.cooldown_svc_candycrush, R.string.cooldown_pack_games,
            listOf("com.king.candycrushsaga"), KIND_ROLLING, 150),
        Preset("duolingo", R.string.cooldown_svc_duolingo, R.string.cooldown_pack_games,
            listOf("com.duolingo"), KIND_ROLLING, 240),
        Preset("clash", R.string.cooldown_svc_clash, R.string.cooldown_pack_games,
            listOf("com.supercell.clashofclans"), KIND_ROLLING, 240),
    )

    // ── Daily fixed-clock resets ──
    private val DAILY = listOf(
        Preset("wordle", R.string.cooldown_svc_wordle, R.string.cooldown_pack_daily,
            listOf("com.nytimes.crossword", "com.nytimes.android"), KIND_DAILY, dailyResetHour = 0),
        Preset("daily", R.string.cooldown_svc_daily_generic, R.string.cooldown_pack_daily,
            emptyList(), KIND_DAILY, dailyResetHour = 0),
    )

    /** All presets, grouped in display order (AI → credits → games → daily). */
    val ALL: List<Preset> = AI + CREDITS + GAMES + DAILY

    fun byId(id: String?): Preset? = ALL.firstOrNull { it.id == id }

    /** The default guaranteed reset time this preset seeds. */
    fun resetMillisFor(preset: Preset, now: Long = System.currentTimeMillis()): Long = when (preset.kind) {
        KIND_DAILY -> nextDailyReset(now, preset.dailyResetHour)
        else -> now + preset.defaultWindowMinutes.coerceAtLeast(1) * 60_000L
    }

    /**
     * The notification match rule that lets a live "resets at X" message refine the timer, or null
     * for a pure-timer preset (no known package). [serviceName] is the resolved display label.
     */
    fun ruleFor(preset: Preset, serviceName: String): NotificationMatchRule? {
        if (preset.packages.isEmpty()) return null
        return NotificationMatchRule(
            packages = preset.packages,
            condition = NotificationMatchRule.CONDITION_RESET,
            parseReset = true,
            label = serviceName
        )
    }

    private fun nextDailyReset(now: Long, hour: Int): Long {
        val cal = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (cal.timeInMillis <= now) cal.add(Calendar.DAY_OF_YEAR, 1)
        return cal.timeInMillis
    }
}
