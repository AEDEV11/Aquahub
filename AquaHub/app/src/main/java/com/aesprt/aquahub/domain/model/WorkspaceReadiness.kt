package com.aesprt.aquahub.domain.model

/** Business screens require the complete tenant boundary, not only a profile. */
enum class WorkspaceReadiness {
    PROFILE_ONLY,
    WORKSPACE_PROVISIONING_REQUIRED,
    WORKSPACE_PROVISIONING,
    WORKSPACE_READY,
    WORKSPACE_PROVISIONING_FAILED
}
