package com.aesprt.aquahub.data.repository

import java.security.MessageDigest
import java.util.UUID

/** Stable Play identifiers; the account hash keeps its existing UID-only format. */
internal object PlayCheckoutIdentity {
    fun canonicalOrganizationId(value: String): String? =
        runCatching { UUID.fromString(value).toString() }.getOrNull()

    fun accountId(userId: String): String = sha256("aquahub-owner-v1:$userId")

    fun profileId(userId: String, organizationId: String): String =
        sha256("aquahub-org-v1:$userId:$organizationId")

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
