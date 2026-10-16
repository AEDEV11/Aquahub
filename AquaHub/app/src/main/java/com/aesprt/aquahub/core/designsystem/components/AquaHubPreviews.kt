package com.aesprt.aquahub.core.designsystem.components

import androidx.compose.ui.tooling.preview.Preview

/** Shared viewport previews for reviewing production UI at compact and expanded widths. */
@Preview(
    name = "Phone 393x852dp",
    showBackground = true,
    widthDp = 393,
    heightDp = 852
)
@Preview(
    name = "Tablet 1280x800dp",
    showBackground = true,
    widthDp = 1280,
    heightDp = 800
)
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
annotation class AquaHubPhoneAndTabletPreview
