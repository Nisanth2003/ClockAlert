package com.example.alarmtracker.notif

import java.util.Calendar

/**
 * Best-effort parser for a usage-limit RESET moment out of a notification's text, e.g.
 * "You've hit your limit. Resets at 3:30 PM", "Try again in 2 hours", "Available at 15:00",
 * "come back in 45 min". Returns the absolute epoch-millis the limit is expected back, or null.
 *
 * Like [MapsEtaParser] this is deliberately forgiving and locale-fragile — that is FINE by design:
 * the guaranteed timer fallback ([data.EventTrigger.fallbackEtaMillis]) is what keeps a cooldown
 * alarm trustworthy, and a parsed reset only ever makes the timing more accurate. Every path is
 * guarded so unparseable text returns null and never throws.
 */
object ResetTimeParser {

    // "in 2 hours", "in 2h 15m", "2 hours left", "45 minutes remaining" — a relative duration.
    //
    // Anchored on a cue on one side or the other, exactly like CLOCK below. An unanchored number
    // grabbed anything that happened to be followed by an "h": "your 5h streak ended, resets at
    // 9 PM" parsed as "in 5 hours" and moved the alarm, because a relative hit wins over the
    // absolute time that was actually the answer.
    private const val BEFORE = """\b(?:in|after|within|for|another)\s+"""
    private const val AFTER = """\s*(?:left|remaining|to go|until reset)\b"""
    private val HOUR_UNIT = """(?:hours?|hrs?|h)"""
    private val MINUTE_UNIT = """min(?:ute)?s?"""

    private val HOURS = listOf(
        Regex("""$BEFORE(\d+)\s*$HOUR_UNIT\b""", RegexOption.IGNORE_CASE),
        Regex("""(\d+)\s*$HOUR_UNIT$AFTER""", RegexOption.IGNORE_CASE)
    )
    private val MINUTES = listOf(
        Regex("""$BEFORE(\d+)\s*$MINUTE_UNIT\b""", RegexOption.IGNORE_CASE),
        Regex("""(\d+)\s*$MINUTE_UNIT$AFTER""", RegexOption.IGNORE_CASE),
        // "in 2h 15m" — the minutes ride along after an already-anchored hour value.
        Regex("""$BEFORE\d+\s*$HOUR_UNIT\s*(\d+)\s*m\b""", RegexOption.IGNORE_CASE)
    )

    // "at 3:30 PM", "by 15:00", "around 9am" — an absolute clock time, anchored on a preposition so
    // it won't grab an unrelated number (a "5 messages left" count, a date, etc.).
    private val CLOCK = Regex(
        """\b(?:at|by|around|until|till)\s+(\d{1,2})(?::(\d{2}))?\s*([ap])\.?m?\.?\b""",
        RegexOption.IGNORE_CASE
    )
    private val CLOCK_24H = Regex("""\b(?:at|by|around|until|till)\s+(\d{1,2}):(\d{2})\b""")

    // Only treat the text as a limit/reset message if it actually reads like one.
    private val RESET_CUES = listOf(
        "reset", "resets", "available", "try again", "come back", "back in",
        "renew", "refill", "refills", "limit", "cooldown", "cools down", "full at", "full in"
    )

    /** True when the text looks like a usage-limit / cooldown message worth parsing. */
    fun looksLikeReset(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        val lower = text.lowercase()
        return RESET_CUES.any { lower.contains(it) }
    }

    /**
     * Parses the reset moment. [now] is injectable for tests. Relative durations win over absolute
     * clock times (they're less ambiguous); an absolute time in the past rolls to the next day.
     */
    fun parse(text: String?, now: Long = System.currentTimeMillis()): Long? {
        if (text.isNullOrBlank()) return null
        return try {
            parseDuration(text, now) ?: parseClock(text, now)
        } catch (_: Exception) {
            null
        }
    }

    private fun parseDuration(text: String, now: Long): Long? {
        val hours = firstNumber(HOURS, text) ?: 0
        val mins = firstNumber(MINUTES, text) ?: 0
        val total = hours * 60 + mins
        // Sanity bound: a "reset" more than a week out is a misread, not a cooldown.
        if (total <= 0 || total > 7 * 24 * 60) return null
        return now + total * 60_000L
    }

    private fun firstNumber(patterns: List<Regex>, text: String): Int? =
        patterns.firstNotNullOfOrNull { it.find(text)?.groupValues?.get(1)?.toIntOrNull() }

    private fun parseClock(text: String, now: Long): Long? {
        CLOCK.find(text)?.let { m ->
            val hour12 = m.groupValues[1].toIntOrNull() ?: return@let
            val minute = m.groupValues.getOrNull(2)?.toIntOrNull() ?: 0
            val isPm = m.groupValues.getOrNull(3)?.equals("p", ignoreCase = true) == true
            if (hour12 !in 1..12 || minute !in 0..59) return@let
            val hour24 = when {
                isPm && hour12 < 12 -> hour12 + 12
                !isPm && hour12 == 12 -> 0
                else -> hour12
            }
            return nextOccurrence(now, hour24, minute)
        }
        CLOCK_24H.find(text)?.let { m ->
            val hour = m.groupValues[1].toIntOrNull() ?: return@let
            val minute = m.groupValues[2].toIntOrNull() ?: return@let
            if (hour !in 0..23 || minute !in 0..59) return@let
            return nextOccurrence(now, hour, minute)
        }
        return null
    }

    /** The next time the wall clock reads [hour]:[minute] at or after [now]. */
    private fun nextOccurrence(now: Long, hour: Int, minute: Int): Long {
        val cal = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (cal.timeInMillis <= now) cal.add(Calendar.DAY_OF_YEAR, 1)
        return cal.timeInMillis
    }
}
