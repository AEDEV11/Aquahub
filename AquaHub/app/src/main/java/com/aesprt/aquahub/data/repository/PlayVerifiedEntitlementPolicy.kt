package com.aesprt.aquahub.data.repository

/** A 200 verifies Play ownership, but only these states currently grant paid access. */
internal object PlayVerifiedEntitlementPolicy {
    fun grantsPaidAccess(status: String): Boolean = status in setOf(
        "ACTIVE", "ACTIVE_UNTIL_PERIOD_END", "GRACE_PERIOD"
    )

    fun matchesSelectedOrganization(verifiedOrganizationId: String, selectedOrganizationId: String?): Boolean =
        selectedOrganizationId != null && verifiedOrganizationId == selectedOrganizationId
}
