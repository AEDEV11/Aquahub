package com.aesprt.aquahub.domain.model

import com.aesprt.aquahub.domain.usecase.analytics.buildDayWindows
import java.util.Calendar
import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class AnalyticsDateRangeTest {
    private lateinit var originalTimeZone: TimeZone

    @Before
    fun setUp() {
        originalTimeZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Manila"))
    }

    @After
    fun tearDown() {
        TimeZone.setDefault(originalTimeZone)
    }

    @Test
    fun `custom range includes the complete selected end date`() {
        val selected = timestamp(2026, Calendar.SEPTEMBER, 26, 0)

        assertEquals(
            selected to timestamp(2026, Calendar.SEPTEMBER, 27, 0),
            DateRangeFilter.CUSTOM.calculateTimestamps(selected, selected)
        )
    }

    @Test
    fun `this month equals the same custom calendar range`() {
        val now = timestamp(2026, Calendar.SEPTEMBER, 26, 14)
        val monthStart = timestamp(2026, Calendar.SEPTEMBER, 1, 0)
        val selectedEnd = timestamp(2026, Calendar.SEPTEMBER, 26, 0)

        assertEquals(
            DateRangeFilter.THIS_MONTH.calculateTimestamps(now = now),
            DateRangeFilter.CUSTOM.calculateTimestamps(monthStart, selectedEnd, now)
        )
    }

    @Test
    fun `daily chart windows start on first selected day and never pass range end`() {
        val start = timestamp(2026, Calendar.SEPTEMBER, 1, 0)
        val end = timestamp(2026, Calendar.SEPTEMBER, 27, 0)
        val windows = buildDayWindows(start, end)

        assertEquals(26, windows.size)
        assertEquals(start, windows.first().first)
        assertEquals(timestamp(2026, Calendar.SEPTEMBER, 26, 0), windows.last().first)
        assertEquals(end, windows.last().second)
    }

    private fun timestamp(year: Int, month: Int, day: Int, hour: Int): Long =
        Calendar.getInstance().apply {
            clear()
            set(year, month, day, hour, 0, 0)
        }.timeInMillis
}
