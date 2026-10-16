package com.aesprt.aquahub.core.designsystem.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.DateRange
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.aesprt.aquahub.core.common.DateTimeUtils
import com.aesprt.aquahub.ui.theme.AquaHubShapes
import com.aesprt.aquahub.ui.theme.AquaHubSpacing
import com.aesprt.aquahub.ui.theme.GlassBorderAqua
import com.aesprt.aquahub.ui.theme.AquaHubTheme

/**
 * AquaHubDatePickerField
 * A read-only, clickable Material 3 Date Picker field that opens a native DatePickerDialog.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AquaHubDatePickerField(
    selectedDateMillis: Long?,
    onDateSelected: (Long) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String = "Select date",
    isError: Boolean = false,
    errorMessage: String? = null
) {
    var showDialog by remember { mutableStateOf(false) }

    val displayText = remember(selectedDateMillis) {
        if (selectedDateMillis != null && selectedDateMillis > 0) {
            DateTimeUtils.formatEpochToDateString(selectedDateMillis)
        } else {
            ""
        }
    }

    Column(modifier = modifier) {
        Box(modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = displayText,
                onValueChange = {},
                readOnly = true,
                label = { Text(label) },
                placeholder = { Text(placeholder) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Rounded.CalendarMonth,
                        contentDescription = "Select Date",
                        tint = MaterialTheme.colorScheme.primary
                    )
                },
                trailingIcon = {
                    IconButton(onClick = { showDialog = true }) {
                        Icon(
                            imageVector = Icons.Rounded.DateRange,
                            contentDescription = "Open Calendar",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                isError = isError,
                shape = AquaHubShapes.cardSecondary,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = MaterialTheme.colorScheme.onSurface,
                    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                ),
                modifier = Modifier.fillMaxWidth()
            )
            // Overlay interceptor: ensures clicking anywhere on the text field triggers the dialog
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clickable(
                        onClick = { showDialog = true },
                        role = Role.Button
                    )
            )
        }

        if (isError && errorMessage != null) {
            Text(
                text = errorMessage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(start = 16.dp, top = 4.dp)
            )
        }
    }

    if (showDialog) {
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = selectedDateMillis ?: System.currentTimeMillis()
        )

        DatePickerDialog(
            onDismissRequest = { showDialog = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        val selected = datePickerState.selectedDateMillis
                        if (selected != null) {
                            onDateSelected(selected)
                        }
                        showDialog = false
                    }
                ) {
                    Text("OK", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text("Cancel")
                }
            },
            colors = DatePickerDefaults.colors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            DatePicker(
                state = datePickerState,
                showModeToggle = false
            )
        }
    }
}

/**
 * AquaHubDateRangePickerDialog
 * Native Material 3 Date Range Picker for Analytics and Custom Reports.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AquaHubDateRangePickerDialog(
    onDismiss: () -> Unit,
    onDateRangeSelected: (startDateMillis: Long, endDateMillis: Long) -> Unit,
    initialStartDateMillis: Long? = null,
    initialEndDateMillis: Long? = null
) {
    val dateRangePickerState = rememberDateRangePickerState(
        initialSelectedStartDateMillis = initialStartDateMillis ?: (System.currentTimeMillis() - 7 * 86400000L),
        initialSelectedEndDateMillis = initialEndDateMillis ?: System.currentTimeMillis()
    )

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, GlassBorderAqua),
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.90f)
                .padding(vertical = AquaHubSpacing.medium)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(AquaHubSpacing.medium)
            ) {
                // Top Header Row
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = AquaHubSpacing.small)
                ) {
                    Text(
                        text = "Select Date Range",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Row {
                        TextButton(onClick = onDismiss) {
                            Text("Cancel")
                        }
                        Spacer(modifier = Modifier.width(AquaHubSpacing.small))
                        Button(
                            onClick = {
                                val start = dateRangePickerState.selectedStartDateMillis
                                val end = dateRangePickerState.selectedEndDateMillis ?: start
                                if (start != null && end != null) {
                                    onDateRangeSelected(start, end)
                                    onDismiss()
                                }
                            },
                            enabled = dateRangePickerState.selectedStartDateMillis != null,
                            shape = AquaHubShapes.chip
                        ) {
                            Text("Apply", fontWeight = FontWeight.Bold)
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                DateRangePicker(
                    state = dateRangePickerState,
                    showModeToggle = false,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                )
            }
        }
    }
}

/**
 * AquaHubTimePickerField
 * A read-only, clickable Material 3 Time Picker field displaying 12-hour formatted time (e.g. 07:00 AM).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AquaHubTimePickerField(
    timeString: String?,
    onTimeSelected: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String = "Select time",
    isError: Boolean = false,
    errorMessage: String? = null
) {
    var showDialog by remember { mutableStateOf(false) }

    Column(modifier = modifier) {
        Box(modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = if (timeString.isNullOrBlank()) "" else timeString,
                onValueChange = {},
                readOnly = true,
                label = { Text(label) },
                placeholder = { Text(placeholder) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Rounded.Schedule,
                        contentDescription = "Select Time",
                        tint = MaterialTheme.colorScheme.primary
                    )
                },
                trailingIcon = {
                    IconButton(onClick = { showDialog = true }) {
                        Icon(
                            imageVector = Icons.Rounded.Schedule,
                            contentDescription = "Open Time Picker",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                isError = isError,
                shape = AquaHubShapes.cardSecondary,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = MaterialTheme.colorScheme.onSurface,
                    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                ),
                modifier = Modifier.fillMaxWidth()
            )
            // Overlay interceptor: ensures clicking anywhere on the text field triggers the dialog
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clickable(
                        onClick = { showDialog = true },
                        role = Role.Button
                    )
            )
        }

        if (isError && errorMessage != null) {
            Text(
                text = errorMessage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(start = 16.dp, top = 4.dp)
            )
        }
    }

    if (showDialog) {
        val initialParsed = DateTimeUtils.parseTime(timeString)
        val initialHour = initialParsed?.first ?: 7
        val initialMinute = initialParsed?.second ?: 0

        AquaHubTimePickerDialog(
            onDismiss = { showDialog = false },
            onConfirm = { hour, minute ->
                val formatted = DateTimeUtils.formatTime(hour, minute)
                onTimeSelected(formatted)
                showDialog = false
            },
            initialHour = initialHour,
            initialMinute = initialMinute
        )
    }
}

/**
 * AquaHubTimePickerDialog
 * Dialog containing Material 3 TimePicker with 12-hour AM/PM support.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AquaHubTimePickerDialog(
    onDismiss: () -> Unit,
    onConfirm: (hour: Int, minute: Int) -> Unit,
    initialHour: Int = 7,
    initialMinute: Int = 0
) {
    val timePickerState = rememberTimePickerState(
        initialHour = initialHour,
        initialMinute = initialMinute,
        is24Hour = false
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Select Time",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface
            )
        },
        text = {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.fillMaxWidth()
            ) {
                TimePicker(
                    state = timePickerState,
                    colors = TimePickerDefaults.colors(
                        clockDialColor = MaterialTheme.colorScheme.surfaceVariant,
                        selectorColor = MaterialTheme.colorScheme.primary,
                        periodSelectorBorderColor = MaterialTheme.colorScheme.primary,
                        periodSelectorSelectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        periodSelectorSelectedContentColor = MaterialTheme.colorScheme.primary
                    )
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(timePickerState.hour, timePickerState.minute)
                }
            ) {
                Text("OK", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(24.dp)
    )
}

@AquaHubPhoneAndTabletPreview
@Composable
private fun AquaHubDateTimeFieldsPreview() {
    AquaHubTheme {
        Column(
            modifier = Modifier.fillMaxWidth().padding(AquaHubSpacing.medium),
            verticalArrangement = Arrangement.spacedBy(AquaHubSpacing.medium)
        ) {
            AquaHubDatePickerField(
                selectedDateMillis = System.currentTimeMillis(),
                onDateSelected = {},
                label = "Opening date"
            )
            AquaHubTimePickerField(
                timeString = "08:00 AM",
                onTimeSelected = {},
                label = "Opening time"
            )
        }
    }
}

@AquaHubPhoneAndTabletPreview
@Composable
private fun AquaHubDateRangePickerDialogPreview() {
    AquaHubTheme {
        AquaHubDateRangePickerDialog(
            onDismiss = {},
            onDateRangeSelected = { _, _ -> }
        )
    }
}

@AquaHubPhoneAndTabletPreview
@Composable
private fun AquaHubTimePickerDialogPreview() {
    AquaHubTheme {
        AquaHubTimePickerDialog(
            onDismiss = {},
            onConfirm = { _, _ -> }
        )
    }
}
