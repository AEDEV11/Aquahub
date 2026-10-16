package com.aesprt.aquahub.feature.products

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material.icons.rounded.LocalOffer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import com.aesprt.aquahub.core.designsystem.components.AquaHubPhoneAndTabletPreview
import androidx.compose.ui.unit.dp
import com.aesprt.aquahub.core.designsystem.components.*
import com.aesprt.aquahub.domain.model.Product
import com.aesprt.aquahub.domain.model.ProductType
import com.aesprt.aquahub.domain.model.ProductPromotion
import com.aesprt.aquahub.domain.model.PromotionType
import com.aesprt.aquahub.ui.theme.*
import coil.compose.AsyncImage
import java.text.NumberFormat
import java.util.Locale

@Composable
fun ProductsScreen(
    viewModel: ProductsViewModel,
    onBackClick: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()

    ProductsContent(
        uiState = uiState,
        onBackClick = onBackClick,
        onOpenAddDialog = viewModel::openAddDialog,
        onOpenEditDialog = viewModel::openEditDialog,
        onCloseDialog = viewModel::closeDialog,
        onToggleAvailability = viewModel::toggleAvailability,
        onArchiveProduct = viewModel::archiveProduct,
        onSaveProduct = viewModel::saveProduct,
        onAddSize = viewModel::addSize
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProductsContent(
    uiState: ProductsUiState,
    onBackClick: () -> Unit,
    onOpenAddDialog: () -> Unit,
    onOpenEditDialog: (Product) -> Unit,
    onCloseDialog: () -> Unit,
    onToggleAvailability: (Product) -> Unit,
    onArchiveProduct: (Product) -> Unit,
    onSaveProduct: (String, String, ProductType, Double, Int, Boolean, String?, ProductPromotion?) -> Unit,
    onAddSize: (String) -> Unit
) {
    val currencyFormat = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("en-PH"))

    var name by remember { mutableStateOf("") }
    var sizeLabel by remember { mutableStateOf("") }
    var productType by remember { mutableStateOf(ProductType.REFILL) }
    var priceText by remember { mutableStateOf("") }
    var stockText by remember { mutableStateOf("") }
    var isAvailable by remember { mutableStateOf(true) }
    var selectedImageUri by remember { mutableStateOf<String?>(null) }
    var promotionEnabled by remember { mutableStateOf(false) }
    var promotionLabel by remember { mutableStateOf("") }
    var promotionType by remember { mutableStateOf(PromotionType.PERCENTAGE) }
    var promotionValueText by remember { mutableStateOf("") }
    var promotionMinimumQuantityText by remember { mutableStateOf("1") }
    var promotionDurationDaysText by remember { mutableStateOf("") }
    var productPendingArchive by remember { mutableStateOf<Product?>(null) }

    LaunchedEffect(uiState.showAddEditDialog, uiState.editingProduct?.id) {
        if (!uiState.showAddEditDialog) return@LaunchedEffect
        val prod = uiState.editingProduct
        if (prod != null) {
            name = prod.name
            sizeLabel = prod.sizeLabel
            productType = prod.type
            priceText = prod.price.toString()
            stockText = prod.stockQuantity.toString()
            isAvailable = prod.isAvailable
            selectedImageUri = null
            promotionEnabled = prod.promotion?.isActive == true
            promotionLabel = prod.promotion?.label.orEmpty()
            promotionType = prod.promotion?.type ?: PromotionType.PERCENTAGE
            promotionValueText = when (prod.promotion?.type) {
                PromotionType.FIXED_PRICE -> prod.promotion.promotionalPrice?.toString().orEmpty()
                PromotionType.PERCENTAGE, PromotionType.QUANTITY_BREAK ->
                    prod.promotion.percentBps?.div(100.0)?.toString().orEmpty()
                null -> ""
            }
            promotionMinimumQuantityText = (prod.promotion?.minimumQuantity ?: 1).toString()
            promotionDurationDaysText = prod.promotion?.endsAt?.let { end ->
                (((end - System.currentTimeMillis()).coerceAtLeast(0L) + 86_399_999L) / 86_400_000L).toString()
            }.orEmpty()
        } else {
            name = ""
            sizeLabel = ""
            productType = ProductType.REFILL
            priceText = ""
            stockText = ""
            isAvailable = true
            selectedImageUri = null
            promotionEnabled = false
            promotionLabel = ""
            promotionType = PromotionType.PERCENTAGE
            promotionValueText = ""
            promotionMinimumQuantityText = "1"
            promotionDurationDaysText = ""
        }
    }

    Scaffold(
        topBar = {
            AquaHubTopBar(
                title = "Products & Pricing",
                subtitle = "Refills, containers & items",
                onBackClick = onBackClick
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onOpenAddDialog,
                containerColor = if (uiState.canAddProducts) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                contentColor = if (uiState.canAddProducts) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                shape = CircleShape
            ) {
                Icon(imageVector = Icons.Rounded.Add, contentDescription = "Add Product")
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        AnimatedContent(
            targetState = uiState.isLoading,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "productsContentAnim"
        ) { loading ->
            if (loading) {
                Column(
                    modifier = Modifier
                        .padding(paddingValues)
                        .padding(AquaHubSpacing.medium),
                    verticalArrangement = Arrangement.spacedBy(AquaHubSpacing.mediumSmall)
                ) {
                    repeat(4) {
                        AquaHubSkeleton(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(96.dp)
                        )
                    }
                }
            } else if (uiState.products.isEmpty()) {
                AquaHubEmptyState(
                    icon = Icons.Rounded.Inventory2,
                    message = "No Products Added Yet",
                    description = uiState.featureRestrictionMessage
                        ?: "Create your water refilling items to start receiving orders",
                    actionText = if (uiState.canAddProducts) "Add Product" else null,
                    onActionClick = onOpenAddDialog,
                    modifier = Modifier.padding(paddingValues)
                )
            } else {
                AdaptiveContentFrame(
                    modifier = Modifier.fillMaxSize().padding(paddingValues),
                    maxWidth = 1560.dp
                ) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 420.dp),
                    contentPadding = PaddingValues(
                        start = AquaHubSpacing.medium,
                        end = AquaHubSpacing.medium,
                        top = AquaHubSpacing.medium,
                        bottom = 80.dp
                    ),
                    horizontalArrangement = Arrangement.spacedBy(AquaHubSpacing.mediumSmall),
                    verticalArrangement = Arrangement.spacedBy(AquaHubSpacing.mediumSmall),
                    modifier = Modifier.fillMaxSize()
                ) {
                    if (uiState.featureRestrictionMessage != null) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Surface(
                                color = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                                shape = AquaHubShapes.cardSecondary,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = uiState.featureRestrictionMessage,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(AquaHubSpacing.medium)
                                )
                            }
                        }
                    }
                    items(uiState.products, key = { it.id }) { product ->
                        AquaHubGlassCard(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier
                                            .size(46.dp)
                                            .background(MaterialTheme.colorScheme.primaryContainer, AquaHubShapes.cardSecondary)
                                    ) {
                                        if (!product.imagePath.isNullOrBlank()) {
                                            StorageAssetImage(
                                                reference = product.imagePath,
                                                contentDescription = product.name,
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier.fillMaxSize().clip(AquaHubShapes.cardSecondary)
                                            )
                                        } else {
                                            Icon(
                                                imageVector = Icons.Rounded.WaterDrop,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(24.dp)
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.width(AquaHubSpacing.mediumSmall))
                                    Column {
                                        Text(
                                            text = product.name,
                                            style = MaterialTheme.typography.titleMedium.copy(
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                        )
                                        Text(
                                            text = "${product.sizeLabel} • Stock: ${product.stockQuantity}",
                                            style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        )
                                        Text(
                                            text = product.promotion?.takeIf { it.appliesAt() && it.minimumQuantity == 1 }
                                                ?.let { promo ->
                                                    val promoPrice = when (promo.type) {
                                                        PromotionType.FIXED_PRICE -> promo.promotionalPrice
                                                        PromotionType.PERCENTAGE, PromotionType.QUANTITY_BREAK -> promo.percentBps?.let { product.price * (10_000 - it) / 10_000.0 }
                                                    }
                                                    promoPrice?.let(currencyFormat::format) ?: currencyFormat.format(product.price)
                                                } ?: currencyFormat.format(product.price),
                                            style = MaterialTheme.typography.titleSmall.copy(
                                                fontWeight = FontWeight.ExtraBold,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        )
                                        product.promotion?.takeIf { it.appliesAt() }?.let { promo ->
                                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                                Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = AquaHubShapes.chip) {
                                                    Text(
                                                        text = if (promo.minimumQuantity > 1) "${promo.label} · Buy ${promo.minimumQuantity}+" else promo.label,
                                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                }
                                                if (promo.minimumQuantity == 1) {
                                                    Text(
                                                        currencyFormat.format(product.price),
                                                        style = MaterialTheme.typography.labelSmall.copy(textDecoration = TextDecoration.LineThrough),
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }

                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(onClick = { onOpenEditDialog(product) }) {
                                        Icon(
                                            imageVector = Icons.Rounded.Edit,
                                            contentDescription = "Edit Product",
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    IconButton(onClick = { productPendingArchive = product }) {
                                        Icon(
                                            imageVector = Icons.Rounded.Delete,
                                            contentDescription = "Archive Product",
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    }
                                    Switch(
                                        checked = product.isAvailable,
                                        onCheckedChange = { onToggleAvailability(product) },
                                        colors = SwitchDefaults.colors(
                                            checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                                            checkedTrackColor = MaterialTheme.colorScheme.primary
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
                }
            }
        }
    }

    // Add / Edit Dialog
    if (uiState.showAddEditDialog) {
        val title = if (uiState.editingProduct != null) "Edit Product" else "Add New Product"
        val isSavedSize = uiState.availableSizes.any { it.equals(sizeLabel.trim(), ignoreCase = true) }
        val isConfirmEnabled = name.isNotBlank() && isSavedSize &&
            priceText.toDoubleOrNull()?.let { it > 0 } == true &&
            (stockText.isBlank() || stockText.toIntOrNull()?.let { it >= 0 } == true) &&
            (!promotionEnabled || (
                promotionLabel.trim().length >= 2 &&
                    promotionValueText.toDoubleOrNull()?.let { it > 0 } == true &&
                    promotionMinimumQuantityText.toIntOrNull()?.let { it in 1..99 } == true
                ))

        AddProductsDialogContent(
            onCloseDialog = onCloseDialog,
            title = title,
            onConfirm = {
                val price = priceText.toDoubleOrNull() ?: 0.0
                val stock = stockText.toIntOrNull() ?: 0
                val now = System.currentTimeMillis()
                val promotion = if (promotionEnabled && uiState.canManagePromotions) {
                    val value = promotionValueText.toDoubleOrNull() ?: 0.0
                    ProductPromotion(
                        label = promotionLabel.trim(),
                        type = promotionType,
                        percentBps = if (promotionType == PromotionType.FIXED_PRICE) null else (value * 100).toInt(),
                        promotionalPrice = if (promotionType == PromotionType.FIXED_PRICE) value else null,
                        minimumQuantity = if (promotionType == PromotionType.QUANTITY_BREAK) promotionMinimumQuantityText.toIntOrNull() ?: 2 else 1,
                        startsAt = uiState.editingProduct?.promotion?.startsAt ?: now,
                        endsAt = promotionDurationDaysText.toLongOrNull()?.takeIf { it > 0 }?.let { now + it * 86_400_000L },
                        isActive = true
                    )
                } else null
                onSaveProduct(name, sizeLabel, productType, price, stock, isAvailable, selectedImageUri, promotion)
            },
            isConfirmEnabled = isConfirmEnabled,
            isSaving = uiState.isSaving,
            imageModel = selectedImageUri ?: uiState.editingProduct?.imagePath,
            onImageSelected = { selectedImageUri = it },
            name = name,
            onNameChange = { name = it },
            sizeLabel = sizeLabel,
            onSizeLabelChange = { sizeLabel = it },
            availableSizes = uiState.availableSizes,
            onAddSize = onAddSize,
            productType = productType,
            onProductTypeChange = { productType = it },
            priceText = priceText,
            onPriceTextChange = { priceText = it },
            stockText = stockText,
            onStockTextChange = { stockText = it },
            canManagePromotions = uiState.canManagePromotions,
            promotionEnabled = promotionEnabled,
            onPromotionEnabledChange = { promotionEnabled = it },
            promotionLabel = promotionLabel,
            onPromotionLabelChange = { promotionLabel = it.take(80) },
            promotionType = promotionType,
            onPromotionTypeChange = { promotionType = it },
            promotionValueText = promotionValueText,
            onPromotionValueChange = { promotionValueText = it },
            promotionMinimumQuantityText = promotionMinimumQuantityText,
            onPromotionMinimumQuantityChange = { promotionMinimumQuantityText = it },
            promotionDurationDaysText = promotionDurationDaysText,
            onPromotionDurationDaysChange = { promotionDurationDaysText = it },
            errorMessage = uiState.errorMessage,
            canManageInventory = uiState.canManageInventory
        )
    }

    productPendingArchive?.let { product ->
        AlertDialog(
            onDismissRequest = { productPendingArchive = null },
            title = { Text("Archive product?") },
            text = { Text("${product.name} will be removed from active products and online ordering. You can restore it from the product catalog later.") },
            confirmButton = {
                TextButton(onClick = {
                    productPendingArchive = null
                    onArchiveProduct(product)
                }) { Text("Archive", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { productPendingArchive = null }) { Text("Cancel") }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddProductsDialogContent(
    onCloseDialog: () -> Unit,
    title: String,
    onConfirm: () -> Unit,
    isConfirmEnabled: Boolean,
    isSaving: Boolean,
    imageModel: String?,
    onImageSelected: (String) -> Unit,
    name: String,
    onNameChange: (String) -> Unit,
    sizeLabel: String,
    onSizeLabelChange: (String) -> Unit,
    availableSizes: List<String>,
    onAddSize: (String) -> Unit,
    productType: ProductType,
    onProductTypeChange: (ProductType) -> Unit,
    priceText: String,
    onPriceTextChange: (String) -> Unit,
    stockText: String,
    onStockTextChange: (String) -> Unit,
    canManagePromotions: Boolean,
    promotionEnabled: Boolean,
    onPromotionEnabledChange: (Boolean) -> Unit,
    promotionLabel: String,
    onPromotionLabelChange: (String) -> Unit,
    promotionType: PromotionType,
    onPromotionTypeChange: (PromotionType) -> Unit,
    promotionValueText: String,
    onPromotionValueChange: (String) -> Unit,
    promotionMinimumQuantityText: String,
    onPromotionMinimumQuantityChange: (String) -> Unit,
    promotionDurationDaysText: String,
    onPromotionDurationDaysChange: (String) -> Unit,
    canManageInventory: Boolean,
    errorMessage: String? = null
) {
    var sizeMenuExpanded by remember { mutableStateOf(false) }
    var showAddSizeDialog by remember { mutableStateOf(false) }
    var newSizeLabel by remember { mutableStateOf("") }
    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri -> uri?.let { onImageSelected(it.toString()) } }

    AquaHubFormDialog(
        onDismissRequest = onCloseDialog,
        title = title,
        confirmButtonText = "Save Product",
        onConfirm = onConfirm,
        dismissButtonText = "Cancel",
        isConfirmEnabled = isConfirmEnabled && !isSaving,
        isConfirmLoading = isSaving
    ) {
        Surface(
            shape = AquaHubShapes.cardSecondary,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
            border = BorderStroke(1.dp, GlassBorderAqua),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(AquaHubSpacing.mediumSmall)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(76.dp)
                        .clip(AquaHubShapes.cardSecondary)
                        .background(MaterialTheme.colorScheme.primaryContainer)
                ) {
                    if (!imageModel.isNullOrBlank()) {
                        StorageAssetImage(
                            reference = imageModel,
                            contentDescription = "Product image preview",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Icon(Icons.Rounded.AddPhotoAlternate, null, modifier = Modifier.size(30.dp))
                    }
                }
                Spacer(Modifier.width(AquaHubSpacing.mediumSmall))
                Column(Modifier.weight(1f)) {
                    Text("Product photo", fontWeight = FontWeight.Bold)
                    Text("JPG, PNG, or WebP · up to 5 MB", style = MaterialTheme.typography.bodySmall)
                    TextButton(
                        onClick = {
                            imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        }
                    ) {
                        Icon(Icons.Rounded.PhotoLibrary, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(if (imageModel.isNullOrBlank()) "Choose image" else "Replace image")
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(AquaHubSpacing.mediumSmall))

        AquaHubTextField(
            value = name,
            onValueChange = onNameChange,
            label = "Product Name *",
            placeholder = "e.g. 5 Gallon Mineral Refill",
            leadingIcon = Icons.Rounded.WaterDrop
        )

        Spacer(modifier = Modifier.height(AquaHubSpacing.medium))

        Surface(
            shape = AquaHubShapes.cardSecondary,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
            border = BorderStroke(1.dp, GlassBorderAqua),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(AquaHubSpacing.mediumSmall), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.LocalOffer, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Product promotion", fontWeight = FontWeight.Bold)
                        Text(
                            if (canManagePromotions) "Automatic discount at checkout" else "Available on Business and Pro plans",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = promotionEnabled && canManagePromotions,
                        onCheckedChange = onPromotionEnabledChange,
                        enabled = canManagePromotions
                    )
                }
                if (promotionEnabled && canManagePromotions) {
                    OutlinedTextField(
                        value = promotionLabel,
                        onValueChange = onPromotionLabelChange,
                        label = { Text("Promotion name") },
                        placeholder = { Text("e.g. Weekend Water Saver") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        PromotionType.entries.forEachIndexed { index, type ->
                            SegmentedButton(
                                selected = promotionType == type,
                                onClick = { onPromotionTypeChange(type) },
                                shape = SegmentedButtonDefaults.itemShape(index, PromotionType.entries.size),
                                label = { Text(when (type) {
                                    PromotionType.PERCENTAGE -> "% off"
                                    PromotionType.FIXED_PRICE -> "Sale price"
                                    PromotionType.QUANTITY_BREAK -> "Buy more"
                                }) }
                            )
                        }
                    }
                    if (promotionType == PromotionType.FIXED_PRICE) {
                        AquaHubCurrencyField(
                            value = promotionValueText,
                            onValueChange = onPromotionValueChange,
                            label = "Promotional price (₱)",
                            placeholder = "30.00"
                        )
                    } else {
                        AquaHubTextField(
                            value = promotionValueText,
                            onValueChange = onPromotionValueChange,
                            label = "Discount percent",
                            placeholder = "10",
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal
                            )
                        )
                    }
                    if (promotionType == PromotionType.QUANTITY_BREAK) {
                        AquaHubTextField(
                            value = promotionMinimumQuantityText,
                            onValueChange = onPromotionMinimumQuantityChange,
                            label = "Minimum quantity",
                            placeholder = "3",
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                            )
                        )
                    }
                    AquaHubTextField(
                        value = promotionDurationDaysText,
                        onValueChange = onPromotionDurationDaysChange,
                        label = "Duration in days (optional)",
                        placeholder = "Leave blank until disabled",
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                        )
                    )
                    Text(
                        "Discounts never stack. The server validates the final price and saves the promotion on the order.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(AquaHubSpacing.small))

        ExposedDropdownMenuBox(
            expanded = sizeMenuExpanded,
            onExpandedChange = { sizeMenuExpanded = !sizeMenuExpanded }
        ) {
            OutlinedTextField(
                value = sizeLabel,
                onValueChange = {},
                readOnly = true,
                label = { Text("Configured size / format *") },
                placeholder = { Text("Choose a saved size") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(sizeMenuExpanded) },
                modifier = Modifier.menuAnchor().fillMaxWidth()
            )
            ExposedDropdownMenu(
                expanded = sizeMenuExpanded,
                onDismissRequest = { sizeMenuExpanded = false }
            ) {
                if (availableSizes.isEmpty()) {
                    DropdownMenuItem(
                        text = { Text("No saved sizes yet") },
                        enabled = false,
                        onClick = {}
                    )
                } else {
                    availableSizes.forEach { size ->
                        DropdownMenuItem(
                            text = { Text(size) },
                            onClick = {
                                onSizeLabelChange(size)
                                sizeMenuExpanded = false
                            }
                        )
                    }
                }
                DropdownMenuItem(
                    text = { Text("Add new size") },
                    leadingIcon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                    onClick = {
                        sizeMenuExpanded = false
                        showAddSizeDialog = true
                    }
                )
            }
        }

        if (availableSizes.isEmpty()) {
            TextButton(onClick = { showAddSizeDialog = true }) {
                Icon(Icons.Rounded.Add, contentDescription = null)
                Spacer(Modifier.width(AquaHubSpacing.extraSmall))
                Text("Add size")
            }
        }

        Spacer(modifier = Modifier.height(AquaHubSpacing.small))

        var typeMenuExpanded by remember { mutableStateOf(false) }
        ExposedDropdownMenuBox(
            expanded = typeMenuExpanded,
            onExpandedChange = { typeMenuExpanded = !typeMenuExpanded }
        ) {
            OutlinedTextField(
                value = productType.displayName(),
                onValueChange = {},
                readOnly = true,
                label = { Text("Product category") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(typeMenuExpanded) },
                modifier = Modifier.menuAnchor().fillMaxWidth()
            )
            ExposedDropdownMenu(
                expanded = typeMenuExpanded,
                onDismissRequest = { typeMenuExpanded = false }
            ) {
                ProductType.entries.forEach { type ->
                    DropdownMenuItem(
                        text = { Text(type.displayName()) },
                        onClick = {
                            onProductTypeChange(type)
                            typeMenuExpanded = false
                        }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(AquaHubSpacing.small))

        AquaHubCurrencyField(
            value = priceText,
            onValueChange = onPriceTextChange,
            label = "Price (₱) *",
            placeholder = "35.00"
        )

        Spacer(modifier = Modifier.height(AquaHubSpacing.small))

        AquaHubTextField(
            value = stockText,
            onValueChange = onStockTextChange,
            label = "Saleable Stock (units)",
            placeholder = "Physical stock or prepared refill units",
            enabled = canManageInventory,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
            )
        )
        if (!canManageInventory) {
            Text(
                "Saleable stock adjustments are not included in your current plan.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (errorMessage != null) {
            Spacer(modifier = Modifier.height(AquaHubSpacing.small))
            Text(
                text = errorMessage,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }

    if (showAddSizeDialog) {
        AlertDialog(
            onDismissRequest = { showAddSizeDialog = false },
            title = { Text("Add bottle size") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(AquaHubSpacing.small)) {
                    Text(
                        "Save this size for the product catalog. Station-owned bottle tracking may require another plan.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedTextField(
                        value = newSizeLabel,
                        onValueChange = { newSizeLabel = it },
                        label = { Text("Size label") },
                        placeholder = { Text("e.g. 5 Gallon Slim") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = newSizeLabel.trim().isNotBlank(),
                    onClick = {
                        val normalized = newSizeLabel.trim()
                        onSizeLabelChange(normalized)
                        onAddSize(normalized)
                        newSizeLabel = ""
                        showAddSizeDialog = false
                    }
                ) { Text("Save size") }
            },
            dismissButton = {
                TextButton(onClick = { showAddSizeDialog = false }) { Text("Cancel") }
            }
        )
    }
}

@AquaHubPhoneAndTabletPreview
@Composable
private fun ProductsScreenPreview() {
    AquaHubTheme {
        ProductsContent(
            uiState = ProductsUiState(
                products = listOf(
                    Product(id = 1, name = "5 Gallon Round Refill", sizeLabel = "5 Gallon", price = 35.0, stockQuantity = 120, isAvailable = true),
                    Product(id = 2, name = "3 Gallon Slim Refill", sizeLabel = "3 Gallon", price = 25.0, stockQuantity = 50, isAvailable = true)
                )
            ),
            onBackClick = {},
            onOpenAddDialog = {},
            onOpenEditDialog = {},
            onCloseDialog = {},
            onToggleAvailability = {},
            onArchiveProduct = {},
            onSaveProduct = { _, _, _, _, _, _, _, _ -> },
            onAddSize = {}
        )
    }
}

@AquaHubPhoneAndTabletPreview
@Composable
private fun ProductsAddDialogScreenPreview() {
    AquaHubTheme {
        AddProductsDialogContent(
            onCloseDialog = {},
            title = "Add Products",
            onConfirm = {},
            isConfirmEnabled = false,
            isSaving = false,
            imageModel = null,
            onImageSelected = {},
            name = "1 Gallon",
            onNameChange = {},
            sizeLabel = "1 Gallon",
            productType = ProductType.REFILL,
            onProductTypeChange = {},
            onSizeLabelChange = {},
            priceText = "35",
            onPriceTextChange = {},
            stockText = "100",
            onStockTextChange = {},
            canManagePromotions = true,
            canManageInventory = true,
            promotionEnabled = false,
            onPromotionEnabledChange = {},
            promotionLabel = "",
            onPromotionLabelChange = {},
            promotionType = PromotionType.PERCENTAGE,
            onPromotionTypeChange = {},
            promotionValueText = "",
            onPromotionValueChange = {},
            promotionMinimumQuantityText = "1",
            onPromotionMinimumQuantityChange = {},
            promotionDurationDaysText = "",
            onPromotionDurationDaysChange = {},
            availableSizes = listOf("1 Gallon"),
            onAddSize = {}
        )
    }
}
