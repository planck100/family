package com.familytimemanager.app.parent

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.familytimemanager.app.R
import com.familytimemanager.app.sync.AuthSessionStore
import com.familytimemanager.app.sync.FamilySettingsStore
import com.familytimemanager.app.sync.RemoteDevice
import com.familytimemanager.app.sync.RemoteDevicesResult
import com.familytimemanager.app.sync.StorageDeleteResult
import com.familytimemanager.app.sync.StorageUploadResult
import com.familytimemanager.app.sync.StoreOpResult
import com.familytimemanager.app.sync.StoreProduct
import com.familytimemanager.app.sync.StoreProductListResult
import com.familytimemanager.app.sync.SupabaseDeviceAdminClient
import com.familytimemanager.app.sync.SupabaseRestAuthHeaders
import com.familytimemanager.app.sync.SupabaseSettingsStore
import com.familytimemanager.app.sync.SupabaseStorageClient
import com.familytimemanager.app.sync.SupabaseStoreClient
import com.familytimemanager.app.ui.FamilyButton
import com.familytimemanager.app.ui.FamilyCard
import com.familytimemanager.app.ui.FamilyChipButton
import com.familytimemanager.app.ui.FamilyMessage
import com.familytimemanager.app.ui.FamilyScaffold
import com.familytimemanager.app.ui.FamilySecondaryButton
import com.familytimemanager.app.ui.createCameraPhotoUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StoreManagementScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    val settingsStore = remember { SupabaseSettingsStore(context) }
    val familyStore = remember { FamilySettingsStore(context) }
    val authStore = remember { AuthSessionStore(context) }
    val client = remember { SupabaseStoreClient(settingsStore, familyStore, authStore) }
    val storageClient = remember {
        SupabaseStorageClient(settingsStore, SupabaseRestAuthHeaders(authStore))
    }
    val deviceClient = remember {
        SupabaseDeviceAdminClient(settingsStore, familyStore, authStore)
    }
    val coroutineScope = rememberCoroutineScope()
    var products by remember { mutableStateOf<List<StoreProduct>>(emptyList()) }
    var devices by remember { mutableStateOf<List<RemoteDevice>>(emptyList()) }
    var selectedDeviceId by remember { mutableStateOf("") }
    var targetExpanded by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var priceMinutes by remember { mutableStateOf("10") }
    var iconUrl by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var pendingDeleteProduct by remember { mutableStateOf<StoreProduct?>(null) }
    var pendingCameraUri by remember { mutableStateOf<android.net.Uri?>(null) }

    fun uploadIcon(uri: android.net.Uri) {
        coroutineScope.launch {
            message = context.getString(R.string.store_icon_uploading)
            val payload = withContext(Dispatchers.IO) {
                runCatching {
                    val type = context.contentResolver.getType(uri) ?: "image/jpeg"
                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    bytes?.let { it to type }
                }.getOrNull()
            }
            if (payload == null) {
                message = context.getString(R.string.store_icon_failed, "read")
                return@launch
            }
            val familyId = familyStore.snapshot().familyId
            message = when (val r = storageClient.uploadStoreIcon(payload.first, payload.second, familyId)) {
                StorageUploadResult.NotConfigured -> context.getString(R.string.supabase_sync_not_configured)
                is StorageUploadResult.Error -> context.getString(R.string.store_icon_failed, r.message)
                is StorageUploadResult.Uploaded -> {
                    val previousIconUrl = iconUrl
                    iconUrl = r.publicUrl
                    if (previousIconUrl.isNotBlank() && previousIconUrl != r.publicUrl) {
                        storageClient.deleteTaskPhoto(previousIconUrl)
                    }
                    context.getString(R.string.store_icon_attached)
                }
            }
        }
    }

    val iconPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let(::uploadIcon)
    }
    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { captured ->
        val uri = pendingCameraUri
        pendingCameraUri = null
        if (captured && uri != null) {
            uploadIcon(uri)
        } else if (!captured) {
            message = context.getString(R.string.photo_capture_cancelled)
        }
    }

    fun reload() {
        coroutineScope.launch {
            loading = true
            message = when (val d = deviceClient.listFamilyDevices()) {
                RemoteDevicesResult.NotConfigured -> context.getString(R.string.supabase_sync_not_configured)
                is RemoteDevicesResult.Error -> context.getString(R.string.device_admin_failed, d.message)
                is RemoteDevicesResult.Loaded -> {
                    devices = d.devices
                    null
                }
            }
            message = message ?: when (val p = client.listProducts()) {
                StoreProductListResult.NotConfigured -> context.getString(R.string.supabase_sync_not_configured)
                is StoreProductListResult.Error -> context.getString(R.string.store_op_failed, p.message)
                is StoreProductListResult.Loaded -> {
                    products = p.products
                    null
                }
            }
            loading = false
        }
    }

    LaunchedEffect(Unit) { reload() }

    FamilyScaffold(title = stringResource(R.string.store_management_title), onBack = onDone) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it.take(80) },
            label = { Text(stringResource(R.string.store_product_name)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = description,
            onValueChange = { description = it.take(300) },
            label = { Text(stringResource(R.string.store_product_description)) },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = priceMinutes,
            onValueChange = { priceMinutes = it.filter(Char::isDigit).take(5) },
            label = { Text(stringResource(R.string.store_product_price_minutes)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        val allChildrenLabel = stringResource(R.string.store_target_all_children)
        val targetLabel = if (selectedDeviceId.isBlank()) {
            allChildrenLabel
        } else {
            devices.firstOrNull { it.id == selectedDeviceId }?.name?.ifBlank { allChildrenLabel } ?: allChildrenLabel
        }
        ExposedDropdownMenuBox(
            expanded = targetExpanded,
            onExpandedChange = { targetExpanded = it },
        ) {
            OutlinedTextField(
                value = targetLabel,
                onValueChange = {},
                readOnly = true,
                label = { Text(stringResource(R.string.store_product_target)) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = targetExpanded) },
                modifier = Modifier
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth(),
            )
            ExposedDropdownMenu(
                expanded = targetExpanded,
                onDismissRequest = { targetExpanded = false },
            ) {
                DropdownMenuItem(
                    text = { Text(allChildrenLabel) },
                    onClick = {
                        selectedDeviceId = ""
                        targetExpanded = false
                    },
                )
                devices.forEach { device ->
                    DropdownMenuItem(
                        text = { Text(device.name.ifBlank { device.deviceUuid }) },
                        onClick = {
                            selectedDeviceId = device.id
                            targetExpanded = false
                        },
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FamilyChipButton(
                text = stringResource(R.string.store_take_photo),
                icon = Icons.Filled.CameraAlt,
                onClick = {
                    runCatching { createCameraPhotoUri(context) }
                        .onSuccess { uri ->
                            pendingCameraUri = uri
                            cameraLauncher.launch(uri)
                        }
                        .onFailure {
                            message = context.getString(R.string.store_icon_failed, it.message ?: "camera")
                        }
                },
            )
            FamilyChipButton(
                text = stringResource(R.string.store_choose_photo),
                icon = Icons.Filled.AddPhotoAlternate,
                onClick = { iconPicker.launch("image/*") },
            )
            if (iconUrl.isNotBlank()) {
                Text(stringResource(R.string.store_icon_attached))
            }
        }
        if (iconUrl.isNotBlank()) {
            AsyncImage(
                model = iconUrl,
                contentDescription = stringResource(R.string.store_icon_description),
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().height(120.dp),
            )
        }
        FamilyButton(
            text = stringResource(R.string.store_create_product),
            icon = Icons.Filled.Add,
            enabled = name.isNotBlank() && (priceMinutes.toLongOrNull() ?: 0L) > 0L,
            onClick = {
                coroutineScope.launch {
                    val price = (priceMinutes.toLongOrNull() ?: 0L) * 60L
                    val auditTarget = devices.firstOrNull { it.id == selectedDeviceId }?.displayName().orEmpty()
                    message = when (
                        val r = client.createProduct(
                            name,
                            description,
                            price,
                            selectedDeviceId,
                            iconUrl,
                            targetDeviceName = auditTarget.ifBlank { "ALL_CHILDREN" },
                        )
                    ) {
                        StoreOpResult.NotConfigured -> context.getString(R.string.supabase_sync_not_configured)
                        is StoreOpResult.Error -> context.getString(R.string.store_op_failed, r.message)
                        StoreOpResult.Ok -> {
                            name = ""
                            description = ""
                            priceMinutes = "10"
                            iconUrl = ""
                            context.getString(R.string.store_product_created)
                        }
                    }
                    reload()
                }
            },
        )
        message?.let { FamilyMessage(it) }
        Text(stringResource(R.string.store_product_list))
        if (loading && products.isEmpty()) {
            Text(stringResource(R.string.store_loading))
        } else if (products.isEmpty()) {
            Text(stringResource(R.string.store_empty))
        } else {
            products.forEach { product ->
                FamilyCard {
                    Text(stringResource(R.string.store_product_line, product.name, product.priceSeconds / 60L))
                    if (product.description.isNotBlank()) Text(product.description)
                    Text(
                        if (product.targetDeviceId.isBlank()) {
                            stringResource(R.string.store_target_all_children)
                        } else {
                            stringResource(R.string.store_target_specific_child)
                        },
                    )
                    FamilySecondaryButton(
                        text = stringResource(R.string.store_delete_product),
                        icon = Icons.Filled.Delete,
                        onClick = { pendingDeleteProduct = product },
                    )
                }
            }
        }
        FamilySecondaryButton(
            text = stringResource(R.string.refresh),
            icon = Icons.Filled.Refresh,
            onClick = { reload() },
        )
    }

    pendingDeleteProduct?.let { product ->
        AlertDialog(
            onDismissRequest = { pendingDeleteProduct = null },
            title = { Text(stringResource(R.string.store_delete_confirm_title)) },
            text = { Text(stringResource(R.string.store_delete_confirm_message, product.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDeleteProduct = null
                        coroutineScope.launch {
                            val iconDeleted = if (product.iconUrl.isBlank()) {
                                StorageDeleteResult.Deleted
                            } else {
                                storageClient.deleteTaskPhoto(product.iconUrl)
                            }
                            message = when (iconDeleted) {
                                is StorageDeleteResult.Error -> context.getString(R.string.store_icon_failed, iconDeleted.message)
                                StorageDeleteResult.NotConfigured -> context.getString(R.string.supabase_sync_not_configured)
                                StorageDeleteResult.Deleted -> when (
                                    val r = client.deleteProduct(
                                        product.id,
                                        product.name,
                                        devices.firstOrNull { it.id == product.targetDeviceId }?.displayName().orEmpty()
                                            .ifBlank { "ALL_CHILDREN" },
                                    )
                                ) {
                                StoreOpResult.NotConfigured -> context.getString(R.string.supabase_sync_not_configured)
                                is StoreOpResult.Error -> context.getString(R.string.store_op_failed, r.message)
                                    StoreOpResult.Ok -> context.getString(R.string.store_product_deleted)
                                }
                            }
                            reload()
                        }
                    },
                ) {
                    Text(stringResource(R.string.store_delete_product))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteProduct = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

private fun RemoteDevice.displayName(): String = name.ifBlank { deviceUuid }
