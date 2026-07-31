package com.example.alarmtracker.notif

/**
 * Best-effort parser for a remaining time + distance out of a navigation notification's text
 * (Maps-style "12 km · 14 min", "1 hr 5 min", "500 m · 2 min", …).
 *
 * This is deliberately forgiving and locale-fragile — that is FINE by design: the guaranteed
 * fallback ETA is what keeps an event alarm trustworthy, and a parsed refinement only ever makes
 * it better. Every path is guarded so unparseable text returns [ParsedEta.EMPTY] and never throws.
 */
object MapsEtaParser {

    data class ParsedEta(val remainingMinutes: Int?, val distanceMeters: Int?) {
        val hasSignal: Boolean get() = remainingMinutes != null || distanceMeters != null

        companion object {
            val EMPTY = ParsedEta(null, null)
        }
    }

    // "1 hr", "2 hours", "1 h" — hours component.
    private val HOURS = Regex("""(\d+)\s*(?:hours?|hrs?|h)\b""", RegexOption.IGNORE_CASE)
    // "14 min", "5 mins", "3 minutes" — minutes component (requires "min" so it can't eat "m" = metres).
    private val MINUTES = Regex("""(\d+)\s*min(?:ute)?s?\b""", RegexOption.IGNORE_CASE)
    // "12 km", "0.5 km" — kilometres.
    private val KM = Regex("""([\d.,]+)\s*km\b""", RegexOption.IGNORE_CASE)
    // "500 m" — metres; the negative look-ahead avoids matching the "m" in "min".
    private val METRES = Regex("""(\d+)\s*m(?![a-z])""", RegexOption.IGNORE_CASE)

    fun parse(text: String?): ParsedEta {
        if (text.isNullOrBlank()) return ParsedEta.EMPTY
        return try {
            val hours = HOURS.find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val mins = MINUTES.find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val totalMinutes = hours * 60 + mins
            val remaining = if (totalMinutes > 0) totalMinutes else null

            val distanceMeters = KM.find(text)?.groupValues?.get(1)
                ?.replace(',', '.')
                ?.toDoubleOrNull()
                ?.let { (it * 1000).toInt() }
                ?: METRES.find(text)?.groupValues?.get(1)?.toIntOrNull()

            ParsedEta(remaining, distanceMeters)
        } catch (_: Exception) {
            ParsedEta.EMPTY
        }
    }
}
