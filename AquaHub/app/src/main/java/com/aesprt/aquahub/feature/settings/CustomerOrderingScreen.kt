package com.aesprt.aquahub.feature.settings

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.provider.MediaStore
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aesprt.aquahub.core.designsystem.components.AdaptiveContentFrame
import com.aesprt.aquahub.core.designsystem.components.AquaHubPhoneAndTabletPreview
import androidx.core.content.FileProvider
import com.aesprt.aquahub.core.designsystem.components.AquaHubButton
import com.aesprt.aquahub.core.designsystem.components.AquaHubGlassCard
import com.aesprt.aquahub.core.designsystem.components.AquaHubLoadingIndicator
import com.aesprt.aquahub.core.designsystem.components.AquaHubOutlinedButton
import com.aesprt.aquahub.core.designsystem.components.AquaHubTopBar
import com.aesprt.aquahub.domain.model.CustomerOrderingLink
import com.aesprt.aquahub.feature.settings.profile.StationProfileViewModel
import com.aesprt.aquahub.ui.theme.AquaHubTheme
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.launch
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix

@Composable
fun CustomerOrderingScreen(
    viewModel: StationProfileViewModel,
    onBackClick: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    LaunchedEffect(state.station?.id) { viewModel.ensurePublicOrderingCode() }

    val station = state.station
    val code = station?.publicStationCode
    val link = code?.let { CustomerOrderingLink.forStation(it, qr = true) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var showRegenerateDialog by remember { mutableStateOf(false) }

    if (showRegenerateDialog) {
        AlertDialog(
            onDismissRequest = { showRegenerateDialog = false },
            title = { Text("Regenerate station link?") },
            text = {
                Text("The current QR code will stop working for customers. Only regenerate it if the existing link has been shared publicly by mistake.")
            },
            confirmButton = {
                AquaHubButton(
                    text = "Regenerate",
                    icon = Icons.Rounded.QrCode2,
                    onClick = {
                        showRegenerateDialog = false
                        viewModel.regeneratePublicOrderingCode()
                        scope.launch { snackbarHostState.showSnackbar("New station QR link created.") }
                    },
                )
            },
            dismissButton = {
                AquaHubOutlinedButton(
                    text = "Cancel",
                    onClick = { showRegenerateDialog = false },
                )
            },
            shape = com.aesprt.aquahub.ui.theme.AquaHubShapes.dialog,
        )
    }

    Scaffold(
        topBar = {
            AquaHubTopBar(
                title = "Customer Ordering QR",
                subtitle = station?.name,
                onBackClick = onBackClick,
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { paddingValues ->
        AdaptiveContentFrame(
            modifier = Modifier.fillMaxSize().padding(paddingValues),
            maxWidth = 1320.dp
        ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
        ) {
            val isCompact = maxWidth < 520.dp
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = .55f),
                    shape = com.aesprt.aquahub.ui.theme.AquaHubShapes.cardPrimary,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        modifier = Modifier.padding(18.dp)
                    ) {
                        Surface(color = MaterialTheme.colorScheme.primary, shape = androidx.compose.foundation.shape.CircleShape) {
                            Icon(Icons.Rounded.QrCodeScanner, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.padding(11.dp))
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text("Your direct ordering channel", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text("Customers scan, choose this station, and order without seeing internal database IDs.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }

                if (station == null || link == null) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        AquaHubLoadingIndicator(size = 48.dp)
                        Text("Preparing your secure ordering link…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    val bitmap = remember(link) { createQrBitmap(link) }
                    AquaHubGlassCard(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = com.aesprt.aquahub.ui.theme.AquaHubShapes.chip) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)) {
                                    Icon(Icons.Rounded.Verified, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                                    Text("READY TO SHARE", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                                }
                            }
                            Text(
                                text = station.name,
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Surface(
                                color = MaterialTheme.colorScheme.surface,
                                shape = com.aesprt.aquahub.ui.theme.AquaHubShapes.cardSecondary,
                                tonalElevation = 1.dp,
                            ) {
                                Image(
                                    bitmap = bitmap.asImageBitmap(),
                                    contentDescription = "QR code for ${station.name}",
                                    modifier = Modifier.size(if (isCompact) 220.dp else 260.dp).padding(10.dp),
                                )
                            }
                            Text(
                                text = "Scan to order from this station",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Surface(
                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                                shape = com.aesprt.aquahub.ui.theme.AquaHubShapes.cardSecondary,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text("Customer link", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
                                            Text(link, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        }
                                        TextButton(onClick = {
                                            copyToClipboard(context, link)
                                            scope.launch { snackbarHostState.showSnackbar("Ordering link copied.") }
                                        }) { Text("Copy", fontWeight = FontWeight.Bold) }
                                    }
                                }
                            }
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OrderingActionTile("Copy", Icons.Rounded.ContentCopy, {
                                    copyToClipboard(context, link)
                                    scope.launch { snackbarHostState.showSnackbar("Ordering link copied.") }
                                }, Modifier.weight(1f))
                                OrderingActionTile("Share", Icons.Rounded.Share, {
                                    runCatching { shareQr(context, bitmap, link) }
                                        .onFailure { scope.launch { snackbarHostState.showSnackbar("Could not open sharing.") } }
                                }, Modifier.weight(1f))
                                OrderingActionTile("Save", Icons.Rounded.Download, {
                                    runCatching { saveQr(context, bitmap) }
                                        .onSuccess { scope.launch { snackbarHostState.showSnackbar("QR code saved to Pictures/AquaHub.") } }
                                        .onFailure { scope.launch { snackbarHostState.showSnackbar("Could not save the QR code.") } }
                                }, Modifier.weight(1f))
                            }
                        }
                    }
                    AquaHubButton(text = "Done", icon = Icons.Rounded.CheckCircle, onClick = onBackClick, modifier = Modifier.fillMaxWidth())
                    TextButton(onClick = { showRegenerateDialog = true }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                        Icon(Icons.Rounded.Refresh, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(6.dp))
                        Text("Regenerate secure link")
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
        }
    }
}

@Composable
private fun OrderingActionTile(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    AquaHubOutlinedButton(text = label, icon = icon, onClick = onClick, modifier = modifier)
}

private fun createQrBitmap(value: String): Bitmap {
    val matrix: BitMatrix = MultiFormatWriter().encode(value, BarcodeFormat.QR_CODE, 800, 800)
    return Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.ARGB_8888).also { bitmap ->
        for (x in 0 until matrix.width) for (y in 0 until matrix.height) {
            bitmap.setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
        }
    }
}

private fun copyToClipboard(context: Context, value: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("AquaHub ordering link", value))
}

private fun shareQr(context: Context, bitmap: Bitmap, link: String) {
    val directory = File(context.cacheDir, "qr").apply { mkdirs() }
    val file = File(directory, "aquahub-ordering.png")
    FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
        type = "image/png"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_TEXT, link)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }, "Share AquaHub QR code"))
}

private fun saveQr(context: Context, bitmap: Bitmap) {
    val values = android.content.ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "aquahub-ordering-${System.currentTimeMillis()}.png")
        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        if (android.os.Build.VERSION.SDK_INT >= 29) put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/AquaHub")
    }
    val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        ?: error("Could not save the QR code.")
    context.contentResolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
}

@AquaHubPhoneAndTabletPreview
@Composable
private fun CustomerOrderingScreenPreview() {
    val stationName = "AquaPure Main Station"
    val sampleLink = CustomerOrderingLink.forStation("AQPREVIEW12", qr = true)
    val qrBitmap = remember(sampleLink) { createQrBitmap(sampleLink) }
    AquaHubTheme {
        Scaffold(
            topBar = {
                AquaHubTopBar(
                    title = "Customer Ordering QR",
                    subtitle = stationName,
                    onBackClick = {}
                )
            },
            containerColor = MaterialTheme.colorScheme.background
        ) { padding ->
            AdaptiveContentFrame(
                modifier = Modifier.fillMaxSize().padding(padding),
                maxWidth = 1320.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                        shape = com.aesprt.aquahub.ui.theme.AquaHubShapes.cardPrimary,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                            modifier = Modifier.padding(18.dp)
                        ) {
                            Icon(Icons.Rounded.QrCodeScanner, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Column {
                                Text("Your direct ordering channel", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text("Customers can scan this code to order from your station.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    AquaHubGlassCard(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Text("READY TO SHARE", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                            Text(stationName, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                            Image(
                                bitmap = qrBitmap.asImageBitmap(),
                                contentDescription = "Preview QR code for $stationName",
                                modifier = Modifier.size(220.dp).padding(10.dp)
                            )
                            Text("Scan to order from this station", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(sampleLink, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OrderingActionTile("Copy", Icons.Rounded.ContentCopy, {}, Modifier.weight(1f))
                                OrderingActionTile("Share", Icons.Rounded.Share, {}, Modifier.weight(1f))
                                OrderingActionTile("Save", Icons.Rounded.Download, {}, Modifier.weight(1f))
                            }
                        }
                    }
                    AquaHubButton(text = "Done", icon = Icons.Rounded.CheckCircle, onClick = {}, modifier = Modifier.fillMaxWidth())
                    TextButton(onClick = {}, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                        Icon(Icons.Rounded.Refresh, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(6.dp))
                        Text("Regenerate secure link")
                    }
                }
            }
        }
    }
}
