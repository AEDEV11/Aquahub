package com.aesprt.aquahub.domain.repository

/** Recoverable state used when a business feature is opened before its tenant boundary is ready. */
class WorkspaceNotProvisionedException : IllegalStateException(
    "AquaHub workspace setup is still in progress"
)
