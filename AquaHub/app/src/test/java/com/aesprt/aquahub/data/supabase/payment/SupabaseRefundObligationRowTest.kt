package com.aesprt.aquahub.data.supabase.payment

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class SupabaseRefundObligationRowTest {
    @Test
    fun `maps status aggregates and centavos without converting money to floating point`() {
        val row = SupabaseRefundObligationRow(
            organizationId = "org-id",
            organizationName = "Synthetic business",
            stationId = "station-id",
            stationName = "Synthetic station",
            paymentMethod = "CASH",
            refundStatus = "PENDING",
            periodStartText = "2026-10-01T00:00:00Z",
            periodEndText = "2026-10-09T00:00:00Z",
            requestedCount = 2,
            requestedCentavos = 12_345,
            pendingCount = 1,
            pendingCentavos = 4_321,
            reservedRefundableCentavos = 4_321,
            completedCount = 0,
            completedCentavos = 0,
            failedAttemptCount = 1,
            failedAttemptCentavos = 8_024,
            partialPaymentCount = 0,
            partialRefundCentavos = 0,
            oldestPendingAtText = "2026-10-02T12:30:00Z",
            pendingUnder24HoursCount = 0,
            pendingUnder24HoursCentavos = 0,
            pending1To7DaysCount = 1,
            pending1To7DaysCentavos = 4_321,
            pending8To30DaysCount = 0,
            pending8To30DaysCentavos = 0,
            pendingOver30DaysCount = 0,
            pendingOver30DaysCentavos = 0
        )

        val model = row.toDomain()

        assertEquals(12_345L, model.requestedCentavos)
        assertEquals(4_321L, model.reservedRefundableCentavos)
        assertEquals(Instant.parse("2026-10-01T00:00:00Z").toEpochMilli(), model.periodStart)
        assertEquals(Instant.parse("2026-10-02T12:30:00Z").toEpochMilli(), model.oldestPendingAt)
        assertEquals(1L, model.pending1To7DaysCount)
        assertEquals(0L, model.pendingOver30DaysCentavos)
    }
}
