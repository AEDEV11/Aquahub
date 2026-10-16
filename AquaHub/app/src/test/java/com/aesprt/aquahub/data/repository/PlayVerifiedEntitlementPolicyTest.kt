package com.aesprt.aquahub.data.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayVerifiedEntitlementPolicyTest {
    @Test
    fun `verified Play state grants current paid access only while entitling`() {
        listOf("ACTIVE", "ACTIVE_UNTIL_PERIOD_END", "GRACE_PERIOD").forEach {
            assertTrue(PlayVerifiedEntitlementPolicy.grantsPaidAccess(it))
        }
        listOf("SUSPENDED", "EXPIRED", "PENDING", "UNKNOWN").forEach {
            assertFalse(PlayVerifiedEntitlementPolicy.grantsPaidAccess(it))
        }
    }

    @Test
    fun `verified token for another business cannot complete selected business restore`() {
        assertTrue(PlayVerifiedEntitlementPolicy.matchesSelectedOrganization("business-a", "business-a"))
        assertFalse(PlayVerifiedEntitlementPolicy.matchesSelectedOrganization("business-b", "business-a"))
        assertFalse(PlayVerifiedEntitlementPolicy.matchesSelectedOrganization("business-a", null))
    }
}
