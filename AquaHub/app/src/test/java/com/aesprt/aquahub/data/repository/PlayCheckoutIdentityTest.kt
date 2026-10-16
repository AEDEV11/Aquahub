package com.aesprt.aquahub.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotEquals
import org.junit.Test

class PlayCheckoutIdentityTest {
    @Test
    fun `account hash keeps legacy format and profile hash binds the selected organization`() {
        val organizationId = requireNotNull(PlayCheckoutIdentity.canonicalOrganizationId(
            "123E4567-E89B-12D3-A456-426614174000"
        ))

        assertEquals("123e4567-e89b-12d3-a456-426614174000", organizationId)
        assertEquals(
            "d81bcf3b129d194a596c2bb26ca43315a881139e9d582a1d446d6bdb9b55cb92",
            PlayCheckoutIdentity.accountId("owner-123")
        )
        assertEquals(
            "518f279da01d59d62894665a3ab02f1541ab68be667a1f2ac2c944bda99ec2bf",
            PlayCheckoutIdentity.profileId("owner-123", organizationId)
        )
        assertNotEquals(
            PlayCheckoutIdentity.profileId("owner-123", organizationId),
            PlayCheckoutIdentity.profileId("owner-123", "123e4567-e89b-12d3-a456-426614174001")
        )
        assertNull(PlayCheckoutIdentity.canonicalOrganizationId("not-a-uuid"))
    }
}
