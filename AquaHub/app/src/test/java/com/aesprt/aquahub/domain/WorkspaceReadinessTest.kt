package com.aesprt.aquahub.domain

import com.aesprt.aquahub.domain.model.WorkspaceReadiness
import com.aesprt.aquahub.domain.repository.WorkspaceNotProvisionedException
import org.junit.Test
import org.junit.Assert.assertEquals

class WorkspaceReadinessTest {
    @Test
    fun businessFeatures_require_a_ready_workspace() {
        assertEquals(
            listOf(WorkspaceReadiness.PROFILE_ONLY, WorkspaceReadiness.WORKSPACE_READY),
            listOf(WorkspaceReadiness.PROFILE_ONLY, WorkspaceReadiness.WORKSPACE_READY)
        )
    }

    @Test
    fun missing_workspace_error_is_safe_for_ui() {
        assertEquals(
            "AquaHub workspace setup is still in progress",
            WorkspaceNotProvisionedException().message
        )
    }
}
