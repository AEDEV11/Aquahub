package com.aesprt.aquahub.core.common

import org.junit.Assert.*
import org.junit.Test

class DateTimeUtilsTest {

    @Test
    fun `formatTime produces correct 12-hour AM-PM strings`() {
        assertEquals("07:00 AM", DateTimeUtils.formatTime(7, 0))
        assertEquals("12:30 PM", DateTimeUtils.formatTime(12, 30))
        assertEquals("06:45 PM", DateTimeUtils.formatTime(18, 45))
        assertEquals("12:00 AM", DateTimeUtils.formatTime(0, 0))
        assertEquals("11:59 PM", DateTimeUtils.formatTime(23, 59))
    }

    @Test
    fun `parseTime parses 12-hour AM-PM and 24-hour time strings correctly`() {
        val amResult = DateTimeUtils.parseTime("07:00 AM")
        assertNotNull(amResult)
        assertEquals(7, amResult!!.first)
        assertEquals(0, amResult.second)

        val pmResult = DateTimeUtils.parseTime("08:30 PM")
        assertNotNull(pmResult)
        assertEquals(20, pmResult!!.first)
        assertEquals(30, pmResult.second)

        val milResult = DateTimeUtils.parseTime("18:15")
        assertNotNull(milResult)
        assertEquals(18, milResult!!.first)
        assertEquals(15, milResult.second)

        assertNull(DateTimeUtils.parseTime(null))
        assertNull(DateTimeUtils.parseTime(""))
    }

    @Test
    fun `isClosingAfterOpening strictly verifies same-day operating hours`() {
        assertTrue(DateTimeUtils.isClosingAfterOpening("07:00 AM", "06:00 PM"))
        assertTrue(DateTimeUtils.isClosingAfterOpening("08:00 AM", "08:01 AM"))
        assertFalse(DateTimeUtils.isClosingAfterOpening("06:00 PM", "07:00 AM"))
        assertFalse(DateTimeUtils.isClosingAfterOpening("08:00 AM", "08:00 AM"))
    }

    @Test
    fun `formatEpochToDateString converts epoch millis without timezone drift`() {
        // 2026-08-23 12:00:00 UTC
        val epochMs = 1787486400000L
        val formatted = DateTimeUtils.formatEpochToDateString(epochMs)
        assertTrue("Formatted date should contain Aug 23, 2026 but was $formatted", formatted.contains("Aug 23, 2026"))
    }

    @Test
    fun `isEndAfterOrEqualStart enforces valid date ranges`() {
        val start = 1787400000000L
        val end = 1787486400000L
        assertTrue(DateTimeUtils.isEndAfterOrEqualStart(start, end))
        assertTrue(DateTimeUtils.isEndAfterOrEqualStart(start, start))
        assertFalse(DateTimeUtils.isEndAfterOrEqualStart(end, start))
    }
}
