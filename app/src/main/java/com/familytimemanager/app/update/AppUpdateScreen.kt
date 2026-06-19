package com.familytimemanager.app.update

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.InstallMobile
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.familytimemanager.app.R
import com.familytimemanager.app.ui.FamilyButton
import com.familytimemanager.app.ui.FamilyCard
import com.familytimemanager.app.ui.FamilyMessage
import com.familytimemanager.app.ui.FamilyScaffold
import com.familytimemanager.app.ui.FamilySecondaryButton
import kotlinx.coroutines.launch

@Composable
fun AppUpdateScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    val client = remember { AppUpdateClient(context) }
    val coroutineScope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var downloading by remember { mutableStateOf(false) }
    var progress by remember { mutableIntStateOf(0) }
    var updateInfo by remember { mutableStateOf<AppUpdateInfo?>(null) }
    var ready by remember { mutableStateOf<UpdateDownloadResult.Ready?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    fun checkForUpdate() {
        coroutineScope.launch {
            checking = true
            message = null
            ready = null
            when (val result = client.check()) {
                UpdateCheckResult.NotConfigured ->
                    message = context.getString(R.string.update_not_configured)
                is UpdateCheckResult.Error ->
                    message = context.getString(R.string.update_check_failed, result.message.toUpdateError(context))
                is UpdateCheckResult.UpToDate -> {
                    updateInfo = null
                    message = context.getString(R.string.update_up_to_date)
                }
                is UpdateCheckResult.Available -> {
                    updateInfo = result.info
                    message = null
                }
            }
            checking = false
        }
    }

    fun download(info: AppUpdateInfo) {
        coroutineScope.launch {
            downloading = true
            progress = 0
            message = null
            when (val result = client.downloadAndVerify(info) { progress = it }) {
                is UpdateDownloadResult.Error ->
                    message = context.getString(
                        R.string.update_download_failed,
                        result.message.toUpdateError(context),
                    )
                is UpdateDownloadResult.Ready -> {
                    ready = result
                    message = context.getString(R.string.update_download_ready)
                }
            }
            downloading = false
        }
    }

    fun install(result: UpdateDownloadResult.Ready) {
        UpdateInstallAuthorization.grant(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            message = context.getString(R.string.update_allow_unknown_apps)
            context.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}"),
                ),
            )
            return
        }
        context.startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(result.contentUri, APK_MIME_TYPE)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
    }

    LaunchedEffect(Unit) { checkForUpdate() }

    FamilyScaffold(title = stringResource(R.string.app_update_title), onBack = onDone) {
        FamilyCard {
            Text(stringResource(R.string.update_current_version, currentVersionName(context)))
            Text(stringResource(R.string.update_data_preserved))
        }

        if (checking) {
            Text(stringResource(R.string.update_checking))
            LinearProgressIndicator(modifier = Modifier)
        }

        updateInfo?.let { info ->
            FamilyCard {
                Text(
                    stringResource(
                        R.string.update_available_version,
                        info.versionName.ifBlank { info.versionCode.toString() },
                    ),
                )
                if (info.releaseNotes.isNotBlank()) Text(info.releaseNotes)
                if (info.mandatory) Text(stringResource(R.string.update_mandatory))
            }
            FamilyButton(
                text = if (downloading) {
                    stringResource(R.string.update_downloading, progress)
                } else {
                    stringResource(R.string.update_download)
                },
                icon = Icons.Filled.Download,
                enabled = !downloading,
                onClick = { download(info) },
            )
        }

        if (downloading) {
            LinearProgressIndicator(
                progress = { progress / 100f },
                modifier = Modifier,
            )
        }

        ready?.let { result ->
            FamilyButton(
                text = stringResource(R.string.update_install),
                icon = Icons.Filled.InstallMobile,
                onClick = { install(result) },
            )
        }

        message?.let { FamilyMessage(it) }
        FamilySecondaryButton(
            text = stringResource(R.string.check_for_updates),
            icon = Icons.Filled.Refresh,
            enabled = !checking && !downloading,
            onClick = { checkForUpdate() },
        )
    }
}

private fun currentVersionName(context: android.content.Context): String {
    return context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
}

private fun String.toUpdateError(context: android.content.Context): String {
    return when {
        contains("sha256_mismatch", true) -> context.getString(R.string.update_error_hash)
        contains("package_name_mismatch", true) -> context.getString(R.string.update_error_package)
        contains("signer_mismatch", true) -> context.getString(R.string.update_error_signer)
        contains("version_code_mismatch", true) -> context.getString(R.string.update_error_version)
        contains("invalid_apk", true) -> context.getString(R.string.update_error_invalid_apk)
        else -> this
    }
}

private const val APK_MIME_TYPE = "application/vnd.android.package-archive"
