package com.aesprt.aquahub.feature.reports.export

import android.content.Context
import android.content.Intent
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aesprt.aquahub.core.designsystem.components.*
import com.aesprt.aquahub.domain.export.ReportExportResult
import com.aesprt.aquahub.domain.export.ReportFormat
import com.aesprt.aquahub.domain.export.ReportType
import com.aesprt.aquahub.domain.model.DateRangeFilter
import com.aesprt.aquahub.domain.model.SubscriptionPlanCode
import com.aesprt.aquahub.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ExportReportDialog(
    currentPlanCode: SubscriptionPlanCode,
    currentDateRange: DateRangeFilter,
    allowedFormats: Set<ReportFormat>,
    allowedReportTypes: Set<ReportType>,
    isExporting: Boolean,
    exportResult: ReportExportResult?,
    successMessage: String?,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onExport: (ReportType, ReportFormat, DateRangeFilter) -> Unit,
    onNavigateToSubscription: () -> Unit
) {
    val context = LocalContext.current
    var selectedReportType by remember(allowedReportTypes) {
        mutableStateOf(allowedReportTypes.firstOrNull() ?: ReportType.FULL_BUSINESS_SUMMARY)
    }
    var selectedFormat by remember(allowedFormats) {
        mutableStateOf(allowedFormats.firstOrNull() ?: ReportFormat.PDF)
    }
    var selectedDateRange by remember { mutableStateOf(currentDateRange) }
    var saveStatus by remember(exportResult) { mutableStateOf<ReportSaveStatus>(ReportSaveStatus.Idle) }
    val coroutineScope = rememberCoroutineScope()
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { folderUri ->
        if (folderUri != null && exportResult != null) {
            saveStatus = ReportSaveStatus.Saving
            coroutineScope.launch {
                saveStatus = saveReportToFolder(context, folderUri, exportResult).fold(
                    onSuccess = { ReportSaveStatus.Saved },
                    onFailure = { ReportSaveStatus.Failed(it.message ?: "Could not save the report") }
                )
            }
        }
    }

    if (exportResult != null) {
        // Success Sheet
        AquaHubFormDialog(
            onDismissRequest = onDismiss,
            title = "Report ready",
            confirmButtonText = if (saveStatus is ReportSaveStatus.Saving) "Saving…" else "Download to folder",
            onConfirm = { folderPicker.launch(null) },
            dismissButtonText = "Close"
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth().padding(vertical = AquaHubSpacing.medium)
            ) {
                Surface(
                    shape = CircleShape,
                    color = SuccessGreenLight,
                    modifier = Modifier.size(56.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Rounded.CheckCircle,
                            contentDescription = null,
                            tint = SuccessGreen,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(AquaHubSpacing.medium))

                successMessage?.let { message ->
                    Text(
                        text = message.replace("downloaded successfully", "generated successfully", ignoreCase = true),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = SuccessGreen
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                Text(
                    text = exportResult.fileName,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "${(exportResult.fileSize / 1024).coerceAtLeast(1)} KB • ${exportResult.mimeType}",
                    style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                )

                Spacer(modifier = Modifier.height(AquaHubSpacing.large))

                Surface(
                    shape = AquaHubShapes.cardSecondary,
                    color = when (saveStatus) {
                        ReportSaveStatus.Saved -> SuccessGreenLight
                        is ReportSaveStatus.Failed -> MaterialTheme.colorScheme.errorContainer
                        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(12.dp)
                    ) {
                        if (saveStatus is ReportSaveStatus.Saving) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(
                                imageVector = when (saveStatus) {
                                    ReportSaveStatus.Saved -> Icons.Rounded.Folder
                                    is ReportSaveStatus.Failed -> Icons.Rounded.ErrorOutline
                                    else -> Icons.Rounded.FolderOpen
                                },
                                contentDescription = null,
                                tint = if (saveStatus is ReportSaveStatus.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = when (val status = saveStatus) {
                                ReportSaveStatus.Idle -> "Choose a device folder to keep your report."
                                ReportSaveStatus.Saving -> "Saving report…"
                                ReportSaveStatus.Saved -> "Saved successfully in your selected folder."
                                is ReportSaveStatus.Failed -> status.message
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (saveStatus is ReportSaveStatus.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(AquaHubSpacing.mediumSmall))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    AquaHubOutlinedButton(
                        text = "Open",
                        icon = Icons.Rounded.OpenInNew,
                        onClick = { openFile(context, exportResult) },
                        modifier = Modifier.weight(1f)
                    )
                    AquaHubOutlinedButton(
                        text = "Share",
                        icon = Icons.Rounded.Share,
                        onClick = { shareFile(context, exportResult) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
        return
    }

    AquaHubFormDialog(
        onDismissRequest = onDismiss,
        title = "Export Business Report",
        confirmButtonText = "Generate Report",
        onConfirm = {
            if (allowedFormats.contains(selectedFormat) && allowedReportTypes.contains(selectedReportType)) {
                onExport(selectedReportType, selectedFormat, selectedDateRange)
            }
        },
        dismissButtonText = "Cancel",
        isConfirmEnabled = !isExporting && allowedFormats.contains(selectedFormat) && allowedReportTypes.contains(selectedReportType),
        isConfirmLoading = isExporting
    ) {
        // 1. Report Type
        Text(
            text = "Select Report Content",
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
        )
        Spacer(modifier = Modifier.height(AquaHubSpacing.small))

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ReportType.entries.forEach { type ->
                val isSelected = selectedReportType == type
                val isLocked = type !in allowedReportTypes
                Surface(
                    shape = AquaHubShapes.cardSecondary,
                    color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, if (isSelected) MaterialTheme.colorScheme.primary else GlassBorderAqua),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !isLocked) { selectedReportType = type }
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                    ) {
                        Text(
                            text = if (isLocked) "${type.displayName} · Upgrade" else type.displayName,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                            )
                        )
                        if (isSelected) {
                            Icon(
                                imageVector = Icons.Rounded.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(AquaHubSpacing.medium))

        // 2. Export Format Selection
        Text(
            text = "Export Format",
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
        )
        Spacer(modifier = Modifier.height(AquaHubSpacing.small))

        Row(
            horizontalArrangement = Arrangement.spacedBy(AquaHubSpacing.small),
            modifier = Modifier.fillMaxWidth()
        ) {
            ReportFormat.entries.forEach { fmt ->
                val isSelected = selectedFormat == fmt
                val isLocked = fmt !in allowedFormats

                Surface(
                    shape = AquaHubShapes.cardSecondary,
                    color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, if (isSelected) MaterialTheme.colorScheme.primary else GlassBorderAqua),
                    modifier = Modifier
                        .weight(1f)
                        .clickable(enabled = !isLocked) { selectedFormat = fmt }
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(vertical = 10.dp)
                    ) {
                        Icon(
                            imageVector = when (fmt) {
                                ReportFormat.PDF -> Icons.Rounded.PictureAsPdf
                                ReportFormat.EXCEL -> Icons.Rounded.TableChart
                                ReportFormat.CSV -> Icons.Rounded.Description
                            },
                            contentDescription = null,
                            tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = fmt.name,
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                        )
                        if (isLocked) {
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "UPGRADE",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.ExtraBold,
                                    color = WarningAmber,
                                    fontSize = 9.sp
                                )
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(AquaHubSpacing.medium))

        // 3. Date Range Selector
        Text(
            text = "Date Filter",
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
        )
        Spacer(modifier = Modifier.height(AquaHubSpacing.small))

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(AquaHubSpacing.extraSmall),
            modifier = Modifier.fillMaxWidth()
        ) {
            items(DateRangeFilter.entries.filter { it != DateRangeFilter.CUSTOM }) { filter ->
                val isSelected = selectedDateRange == filter
                FilterChip(
                    selected = isSelected,
                    onClick = { selectedDateRange = filter },
                    label = { Text(filter.displayName) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                    )
                )
            }
        }

        if (!errorMessage.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(AquaHubSpacing.medium))
            Surface(
                shape = AquaHubShapes.cardSecondary,
                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ErrorOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = errorMessage,
                        style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.error)
                    )
                }
            }
        }
    }
}

private sealed interface ReportSaveStatus {
    data object Idle : ReportSaveStatus
    data object Saving : ReportSaveStatus
    data object Saved : ReportSaveStatus
    data class Failed(val message: String) : ReportSaveStatus
}

private suspend fun saveReportToFolder(
    context: Context,
    folderUri: android.net.Uri,
    result: ReportExportResult
): Result<Unit> = withContext(Dispatchers.IO) {
    runCatching {
        val resolver = context.contentResolver
        runCatching {
            resolver.takePersistableUriPermission(
                folderUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
        val directoryUri = DocumentsContract.buildDocumentUriUsingTree(
            folderUri,
            DocumentsContract.getTreeDocumentId(folderUri)
        )
        val destination = DocumentsContract.createDocument(
            resolver,
            directoryUri,
            result.mimeType,
            result.fileName
        ) ?: error("The selected folder did not allow creating the report")
        resolver.openOutputStream(destination, "w")?.use { output ->
            result.file.inputStream().buffered().use { input -> input.copyTo(output) }
        } ?: error("The selected folder could not be written")
        Unit
    }
}

private fun shareFile(context: Context, result: ReportExportResult) {
    val shareIntent = Intent(Intent.ACTION_SEND).apply {
        type = result.mimeType
        putExtra(Intent.EXTRA_STREAM, result.uri)
        putExtra(Intent.EXTRA_SUBJECT, "AquaHub Report: ${result.fileName}")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(shareIntent, "Share AquaHub Report"))
}

private fun openFile(context: Context, result: ReportExportResult) {
    try {
        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(result.uri, result.mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(viewIntent)
    } catch (e: Exception) {
        shareFile(context, result)
    }
}

@AquaHubPhoneAndTabletPreview
@Composable
private fun ExportReportDialogPreview() {
    AquaHubTheme {
        ExportReportDialog(
            currentPlanCode = SubscriptionPlanCode.STARTER,
            currentDateRange = DateRangeFilter.LAST_7_DAYS,
            allowedFormats = setOf(ReportFormat.CSV),
            allowedReportTypes = setOf(ReportType.SALES_AND_REVENUE, ReportType.ORDERS_LIST),
            isExporting = false,
            exportResult = null,
            successMessage = null,
            errorMessage = null,
            onDismiss = {},
            onExport = { _, _, _ -> },
            onNavigateToSubscription = {}
        )
    }
}
