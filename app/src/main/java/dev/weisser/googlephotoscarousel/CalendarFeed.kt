package dev.weisser.googlephotoscarousel

import okhttp3.OkHttpClient
import okhttp3.Request
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

internal enum class CalendarEventKind { NORMAL, BIRTHDAY, GARBAGE, TODO }

internal data class CalendarEvent(
    val title: String,
    val startsAt: Date,
    val allDay: Boolean,
    val kind: CalendarEventKind
)

internal fun fetchCalendarEvents(feedAddress: String, now: Date = Date()): List<CalendarEvent> {
    val httpsUrl = feedAddress.trim().replaceFirst(Regex("(?i)^webcal://"), "https://")
    require(httpsUrl.startsWith("https://", ignoreCase = true)) { "Bitte eine sichere iCal-Adresse (https:// oder webcal://) verwenden." }
    val client = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).followRedirects(true).build()
    val request = Request.Builder().url(httpsUrl).header("Accept", "text/calendar, text/plain;q=0.9, */*;q=0.1").build()
    return client.newCall(request).execute().use { response ->
        check(response.isSuccessful) { "Kalender konnte nicht geladen werden (HTTP " + response.code + ")." }
        val body = response.body?.string().orEmpty()
        check(body.length <= 2_000_000) { "Der Kalender-Feed ist ungewöhnlich groß." }
        parseCalendarEvents(body, now)
    }
}

internal fun parseCalendarEvents(ics: String, now: Date = Date()): List<CalendarEvent> {
    val zone = TimeZone.getDefault()
    val from = dayStart(now, zone)
    val through = addDays(from, 30, zone)
    val unfolded = mutableListOf<String>()
    ics.replace("\r\n", "\n").replace('\r', '\n').split('\n').forEach { line ->
        if ((line.startsWith(' ') || line.startsWith('\t')) && unfolded.isNotEmpty()) unfolded[unfolded.lastIndex] += line.drop(1)
        else unfolded += line
    }
    val records = mutableListOf<Pair<String, MutableList<String>>>()
    var currentComponent: String? = null
    var current: MutableList<String>? = null
    unfolded.forEach { line ->
        when (line.trim().uppercase(Locale.ROOT)) {
            "BEGIN:VEVENT", "BEGIN:VTODO" -> {
                currentComponent = line.substringAfter(':').uppercase(Locale.ROOT)
                current = mutableListOf()
            }
            "END:VEVENT", "END:VTODO" -> current?.let {
                records += (currentComponent ?: "VEVENT") to it
                current = null
                currentComponent = null
            }
            else -> current?.add(line)
        }
    }
    return records.flatMap { (component, lines) ->
        val properties = lines.mapNotNull(::parseIcsProperty)
        val title = properties.firstOrNull { it.name == "SUMMARY" }?.value?.unescapeIcs().orEmpty().ifBlank { "Termin" }
        val startProp = if (component == "VTODO") {
            properties.firstOrNull { it.name == "DUE" } ?: properties.firstOrNull { it.name == "DTSTART" }
        } else {
            properties.firstOrNull { it.name == "DTSTART" }
        } ?: return@flatMap emptyList()
        val isTodo = component == "VTODO" || calendarTodoText(title) != null
        if (component == "VTODO" && (properties.any { it.name == "STATUS" && it.value.equals("COMPLETED", true) } ||
                properties.any { it.name == "PERCENT-COMPLETE" && it.value.toIntOrNull() == 100 })) {
            return@flatMap emptyList()
        }
        val start = parseIcsDate(startProp, zone) ?: return@flatMap emptyList()
        val isAllDay = startProp.params["VALUE"]?.equals("DATE", true) == true || startProp.value.length == 8
        val rule = if (component == "VEVENT") properties.firstOrNull { it.name == "RRULE" }?.value else null
        val kind = if (isTodo) CalendarEventKind.TODO else classifyCalendarEvent(title, rule)
        expandOccurrences(start, rule, from, through, zone).map { CalendarEvent(title, it, isAllDay, kind) }
    }.distinctBy { it.title to it.startsAt.time }.sortedBy { it.startsAt.time }
}

internal fun calendarTodoText(title: String): String? {
    val match = Regex("(?i)^\\s*to\\s*[- ]?\\s*do\\b\\s*[:：\\-–—]?\\s*(.*)$").matchEntire(title) ?: return null
    return match.groupValues[1].trim().replace(Regex("\\s+"), " ").ifBlank { "Todo" }
}

private data class IcsProperty(val name: String, val params: Map<String, String>, val value: String)

private fun parseIcsProperty(line: String): IcsProperty? {
    val separator = line.indexOf(':')
    if (separator < 1) return null
    val header = line.substring(0, separator).split(';')
    val params = header.drop(1).mapNotNull { part ->
        val equals = part.indexOf('=')
        if (equals <= 0) null else part.substring(0, equals).uppercase(Locale.ROOT) to part.substring(equals + 1).trim('"')
    }.toMap()
    return IcsProperty(header.first().uppercase(Locale.ROOT), params, line.substring(separator + 1))
}

private fun parseIcsDate(property: IcsProperty, fallbackZone: TimeZone): Date? {
    val value = property.value.trim()
    val formatter = when {
        value.length == 8 -> SimpleDateFormat("yyyyMMdd", Locale.US)
        value.endsWith('Z') && value.length >= 15 -> SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        value.length == 13 -> SimpleDateFormat("yyyyMMdd'T'HHmm", Locale.US)
        else -> SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.US)
    }.apply {
        isLenient = false
        if (property.params["VALUE"]?.equals("DATE", true) != true && !value.endsWith('Z')) {
            timeZone = property.params["TZID"]?.let { TimeZone.getTimeZone(it) } ?: fallbackZone
        }
    }
    return runCatching {
        val position = ParsePosition(0)
        formatter.parse(value, position)?.takeIf { position.index == value.length }
    }.getOrNull()
}

private fun expandOccurrences(start: Date, rule: String?, from: Date, through: Date, zone: TimeZone): List<Date> {
    val startDay = dayKey(start, zone)
    val fromDay = dayKey(from, zone)
    val throughDay = dayKey(through, zone)
    if (rule.isNullOrBlank()) return if (startDay in fromDay..throughDay) listOf(start) else emptyList()
    val parts = rule.split(';').mapNotNull { segment ->
        val separator = segment.indexOf('=')
        if (separator <= 0) null else segment.substring(0, separator).uppercase(Locale.ROOT) to segment.substring(separator + 1)
    }.toMap()
    val frequency = parts["FREQ"]?.uppercase(Locale.ROOT) ?: return emptyList()
    val interval = parts["INTERVAL"]?.toIntOrNull()?.coerceIn(1, 366) ?: 1
    val countLimit = parts["COUNT"]?.toIntOrNull()?.coerceIn(1, 1000)
    val until = parts["UNTIL"]?.let { parseIcsDate(IcsProperty("UNTIL", emptyMap(), it), zone) }
    val occurrence = Calendar.getInstance(zone).apply { time = start }
    var occurrenceCount = 0
    var guard = 0
    val result = mutableListOf<Date>()
    if (frequency == "DAILY" && occurrence.time.before(from)) {
        val approximateDays = ((from.time - occurrence.timeInMillis) / 86_400_000L).coerceAtLeast(0)
        val skip = (approximateDays / interval - 1).coerceAtLeast(0).toInt()
        if (countLimit == null || skip < countLimit) {
            occurrence.add(Calendar.DAY_OF_YEAR, skip * interval)
            occurrenceCount = skip
        }
    }
    while (dayKey(occurrence.time, zone) <= throughDay && guard++ < 1500) {
        val date = occurrence.time
        if (countLimit != null && occurrenceCount >= countLimit) break
        if (until != null && date.after(until)) break
        if (dayKey(date, zone) >= fromDay) result += Date(date.time)
        occurrenceCount++
        when (frequency) {
            "DAILY" -> occurrence.add(Calendar.DAY_OF_YEAR, interval)
            "WEEKLY" -> occurrence.add(Calendar.WEEK_OF_YEAR, interval)
            "MONTHLY" -> occurrence.add(Calendar.MONTH, interval)
            "YEARLY" -> occurrence.add(Calendar.YEAR, interval)
            else -> break
        }
    }
    return result
}

internal fun dayStart(date: Date, zone: TimeZone): Date = Calendar.getInstance(zone).apply {
    time = date
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.time

internal fun addDays(date: Date, days: Int, zone: TimeZone): Date = Calendar.getInstance(zone).apply { time = date; add(Calendar.DAY_OF_YEAR, days) }.time

internal fun dayKey(date: Date, zone: TimeZone): String = SimpleDateFormat("yyyyMMdd", Locale.US).apply { timeZone = zone }.format(date)

private fun classifyCalendarEvent(title: String, rule: String?): CalendarEventKind {
    val normalized = title.lowercase(Locale.ROOT)
    if (listOf("müll", "muell", "tonne", "abfall", "restmüll", "restmuell", "biomüll", "biomuell", "papier", "gelber sack", "wertstoff").any(normalized::contains)) return CalendarEventKind.GARBAGE
    if (listOf("geburtstag", "birthday", "jahrestag").any(normalized::contains) || rule?.contains("FREQ=YEARLY", true) == true) return CalendarEventKind.BIRTHDAY
    return CalendarEventKind.NORMAL
}

private fun String.unescapeIcs(): String = replace("\\n", " ", true).replace("\\N", " ", true)
    .replace("\\,", ",").replace("\\;", ";").replace("\\\\", "\\")
