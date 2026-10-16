package com.aesprt.aquahub.core.common

import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.*

object DateTimeUtils {

    private val DATE_FORMATTER = DateTimeFormatter.ofPattern("MMM dd, yyyy", Locale.US)
    private val TIME_FORMATTER_12H = DateTimeFormatter.ofPattern("hh:mm a", Locale.US)

    /**
     * Converts epoch milliseconds (from Material DatePicker UTC selection)
     * to a timezone-safe formatted string (e.g. "Aug 23, 2026") without day-shift drift.
     */
    fun formatEpochToDateString(epochMillis: Long?): String {
        if (epochMillis == null || epochMillis <= 0) return ""
        return try {
            val localDate = Instant.ofEpochMilli(epochMillis)
                .atZone(ZoneId.of("UTC"))
                .toLocalDate()
            localDate.format(DATE_FORMATTER)
        } catch (e: Exception) {
            val sdf = SimpleDateFormat("MMM dd, yyyy", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            sdf.format(Date(epochMillis))
        }
    }

    /**
     * Converts LocalDate to formatted string.
     */
    fun formatDate(localDate: LocalDate?): String {
        if (localDate == null) return ""
        return localDate.format(DATE_FORMATTER)
    }

    /**
     * Formats 24h hour and minute to 12-hour AM/PM string (e.g. "07:00 AM").
     */
    fun formatTime(hour: Int, minute: Int): String {
        val safeHour = hour.coerceIn(0, 23)
        val safeMinute = minute.coerceIn(0, 59)
        val localTime = LocalTime.of(safeHour, safeMinute)
        return localTime.format(TIME_FORMATTER_12H)
    }

    /**
     * Parses a 12-hour or 24-hour time string into (hour, minute) in 24h format.
     */
    fun parseTime(timeStr: String?): Pair<Int, Int>? {
        if (timeStr.isNullOrBlank()) return null
        val clean = timeStr.trim().uppercase(Locale.US)
        return try {
            if (clean.contains("AM") || clean.contains("PM")) {
                val parsed = LocalTime.parse(clean, TIME_FORMATTER_12H)
                parsed.hour to parsed.minute
            } else {
                val parts = clean.split(":")
                if (parts.size >= 2) {
                    val h = parts[0].trim().toInt().coerceIn(0, 23)
                    val m = parts[1].trim().toInt().coerceIn(0, 59)
                    h to m
                } else null
            }
        } catch (e: Exception) {
            // Fallback parser using SimpleDateFormat
            try {
                val sdf = SimpleDateFormat("hh:mm a", Locale.US)
                val d = sdf.parse(clean)
                if (d != null) {
                    val cal = Calendar.getInstance().apply { time = d }
                    cal.get(Calendar.HOUR_OF_DAY) to cal.get(Calendar.MINUTE)
                } else null
            } catch (ex: Exception) {
                null
            }
        }
    }

    /**
     * Validates that closingTime is strictly later than openingTime.
     */
    fun isClosingAfterOpening(openingTimeStr: String?, closingTimeStr: String?): Boolean {
        val opening = parseTime(openingTimeStr) ?: return true
        val closing = parseTime(closingTimeStr) ?: return true
        val openMinutes = opening.first * 60 + opening.second
        val closeMinutes = closing.first * 60 + closing.second
        return closeMinutes > openMinutes
    }

    fun isAtOrAfterClosingTime(closingTimeStr: String?, now: LocalTime = LocalTime.now()): Boolean {
        val closing = parseTime(closingTimeStr) ?: return false
        return now.hour * 60 + now.minute >= closing.first * 60 + closing.second
    }

    /**
     * Validates that endDateMillis is >= startDateMillis.
     */
    fun isEndAfterOrEqualStart(startDateMillis: Long?, endDateMillis: Long?): Boolean {
        if (startDateMillis == null || endDateMillis == null) return true
        return endDateMillis >= startDateMillis
    }
}
