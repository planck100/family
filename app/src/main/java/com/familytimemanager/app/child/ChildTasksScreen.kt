package com.familytimemanager.app.child

import android.content.Intent
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import java.util.Locale
import com.familytimemanager.app.R
import com.familytimemanager.app.parent.taskStatusLabel
import com.familytimemanager.app.sync.AuthSessionStore
import com.familytimemanager.app.sync.FamilySettingsStore
import com.familytimemanager.app.sync.FamilyTask
import com.familytimemanager.app.sync.StorageUploadResult
import com.familytimemanager.app.sync.SupabaseRestAuthHeaders
import com.familytimemanager.app.sync.SupabaseSettingsStore
import com.familytimemanager.app.sync.SupabaseStorageClient
import com.familytimemanager.app.sync.SupabaseTaskClient
import com.familytimemanager.app.sync.TaskListResult
import com.familytimemanager.app.sync.TaskOpResult
import com.familytimemanager.app.ui.FamilyButton
import com.familytimemanager.app.ui.FamilyCard
import com.familytimemanager.app.ui.FamilyMessage
import com.familytimemanager.app.ui.FamilyScaffold
import com.familytimemanager.app.ui.FamilySecondaryButton
import com.familytimemanager.app.ui.createCameraPhotoUri
import com.familytimemanager.app.ui.formatAppDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant

@Composable
fun ChildTasksScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    val settingsStore = remember { SupabaseSettingsStore(context) }
    val client = remember {
        SupabaseTaskClient(
            settingsStore = settingsStore,
            familyStore = FamilySettingsStore(context),
            authSessionStore = AuthSessionStore(context),
        )
    }
    val storageClient = remember {
        SupabaseStorageClient(
            settingsStore = settingsStore,
            restAuthHeaders = SupabaseRestAuthHeaders(AuthSessionStore(context)),
        )
    }
    val localDeviceUuid = remember { settingsStore.snapshot().localDeviceUuid }
    val remoteDeviceId = remember { settingsStore.snapshot().remoteDeviceId }
    val coroutineScope = rememberCoroutineScope()
    var tasks by remember { mutableStateOf<List<FamilyTask>>(emptyList()) }
    var selectedTaskId by remember { mutableStateOf("") }
    var comment by remember { mutableStateOf("") }
    var photoUrl by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var pendingCameraUri by remember { mutableStateOf<android.net.Uri?>(null) }

    // Built-in text-to-speech so the child can have a task read aloud (Chinese or English).
    var ttsReady by remember { mutableStateOf(false) }
    val tts = remember {
        TextToSpeech(
            context,
            { status ->
                ttsReady = status == TextToSpeech.SUCCESS
                Log.i("FTM-TTS", "Tasks TTS init status=$status ready=$ttsReady")
            },
        )
    }
    DisposableEffect(Unit) {
        onDispose {
            tts.stop()
            tts.shutdown()
        }
    }

    fun speak(vararg parts: String) {
        val text = parts.filter { it.isNotBlank() }.joinToString("。")
        if (text.isBlank()) return

        fun openTtsSettings() {
            listOf(
                Intent("com.android.settings.TTS_SETTINGS"),
                Intent(Settings.ACTION_SETTINGS),
            ).firstOrNull { intent ->
                runCatching {
                    context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }.isSuccess
            }
        }

        if (!ttsReady) {
            message = context.getString(R.string.tts_not_ready)
            Log.w("FTM-TTS", "Tasks TTS clicked before engine ready")
            return
        }

        val preferred = if (text.any { it.code in 0x3400..0x9FFF }) Locale.TRADITIONAL_CHINESE else Locale.ENGLISH
        val availableLocale = listOf(
            preferred,
            Locale.forLanguageTag("cmn-TW"),
            Locale.getDefault(),
            Locale.TRADITIONAL_CHINESE,
            Locale.TAIWAN,
            Locale.ENGLISH,
        )
            .distinctBy { it.toLanguageTag() }
            .firstOrNull { locale ->
                val result = tts.setLanguage(locale)
                Log.i("FTM-TTS", "Tasks setLanguage ${locale.toLanguageTag()} -> $result")
                result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
            }
        if (availableLocale == null) {
            Log.w("FTM-TTS", "Tasks no reported supported locale; trying default voice")
        }

        if (tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "task") == TextToSpeech.ERROR) {
            Log.e("FTM-TTS", "Tasks speak returned ERROR")
            message = context.getString(R.string.tts_not_ready)
            openTtsSettings()
        }
    }

    fun uploadPhoto(uri: android.net.Uri) {
        coroutineScope.launch {
            message = context.getString(R.string.task_photo_uploading)
            val payload = withContext(Dispatchers.IO) {
                runCatching {
                    val type = context.contentResolver.getType(uri) ?: "image/jpeg"
                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    bytes?.let { it to type }
                }.getOrNull()
            }
            if (payload == null) {
                message = context.getString(R.string.task_photo_failed, "read")
                return@launch
            }
            message = when (val r = storageClient.uploadTaskPhoto(payload.first, payload.second, localDeviceUuid)) {
                StorageUploadResult.NotConfigured -> context.getString(R.string.supabase_sync_not_configured)
                is StorageUploadResult.Error -> context.getString(R.string.task_photo_failed, r.message)
                is StorageUploadResult.Uploaded -> {
                    val previousPhotoUrl = photoUrl
                    photoUrl = r.publicUrl
                    if (previousPhotoUrl.isNotBlank() && previousPhotoUrl != r.publicUrl) {
                        storageClient.deleteTaskPhoto(previousPhotoUrl)
                    }
                    context.getString(R.string.task_photo_attached)
                }
            }
        }
    }

    val photoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri ->
        uri?.let(::uploadPhoto)
    }
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture(),
    ) { captured ->
        val uri = pendingCameraUri
        pendingCameraUri = null
        if (captured && uri != null) {
            uploadPhoto(uri)
        } else if (!captured) {
            message = context.getString(R.string.photo_capture_cancelled)
        }
    }

    fun reload() {
        coroutineScope.launch {
            loading = true
            if (remoteDeviceId.isBlank()) {
                tasks = emptyList()
                message = context.getString(R.string.not_bound)
                loading = false
                return@launch
            }
            message = when (val r = client.listTasks(remoteDeviceId)) {
                TaskListResult.NotConfigured -> context.getString(R.string.supabase_sync_not_configured)
                is TaskListResult.Error -> context.getString(R.string.task_op_failed, r.message)
                is TaskListResult.Loaded -> {
                    tasks = r.tasks
                    null
                }
            }
            loading = false
        }
    }

    LaunchedEffect(Unit) { reload() }

    FamilyScaffold(title = stringResource(R.string.child_tasks_title), onBack = onDone) {
        message?.let { FamilyMessage(it) }

        if (loading && tasks.isEmpty()) {
            Text(stringResource(R.string.task_loading))
        } else if (tasks.isEmpty()) {
            Text(stringResource(R.string.task_empty))
        } else {
            tasks.forEach { task ->
                val selected = task.id == selectedTaskId
                FamilyCard {
                    Text(
                        stringResource(
                            R.string.task_line,
                            task.title,
                            taskStatusLabel(task.status, context),
                            task.rewardSeconds / 60L,
                        ),
                    )
                    if (task.description.isNotBlank()) {
                        Text(task.description)
                    }
                    Text(
                        if (task.taskType == "limited") {
                            context.getString(
                                R.string.task_limited_until,
                                formatAppDateTime(task.expiresAt).ifBlank { task.expiresAt },
                            )
                        } else {
                            context.getString(R.string.task_permanent_label)
                        },
                    )
                    FamilySecondaryButton(
                        text = stringResource(R.string.task_read_aloud),
                        icon = Icons.Filled.VolumeUp,
                        onClick = { speak(task.title, task.description) },
                    )
                    if (task.status == "open" && task.canSubmitNow()) {
                        FamilySecondaryButton(
                            text = stringResource(
                                if (selected) R.string.task_submit_cancel else R.string.task_submit_start,
                            ),
                            icon = Icons.Filled.Edit,
                            onClick = {
                                if (selected && photoUrl.isNotBlank()) {
                                    val abandonedPhotoUrl = photoUrl
                                    coroutineScope.launch {
                                        storageClient.deleteTaskPhoto(abandonedPhotoUrl)
                                    }
                                }
                                selectedTaskId = if (selected) "" else task.id
                                comment = ""
                                photoUrl = ""
                                message = null
                            },
                        )
                        if (selected) {
                            OutlinedTextField(
                                value = comment,
                                onValueChange = { comment = it.take(300) },
                                label = { Text(stringResource(R.string.task_submit_comment)) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            FamilySecondaryButton(
                                text = stringResource(R.string.task_take_photo),
                                icon = Icons.Filled.CameraAlt,
                                onClick = {
                                    runCatching { createCameraPhotoUri(context) }
                                        .onSuccess { uri ->
                                            pendingCameraUri = uri
                                            cameraLauncher.launch(uri)
                                        }
                                        .onFailure {
                                            message = context.getString(R.string.task_photo_failed, it.message ?: "camera")
                                        }
                                },
                            )
                            FamilySecondaryButton(
                                text = stringResource(R.string.task_choose_photo),
                                icon = Icons.Filled.AddAPhoto,
                                onClick = { photoPicker.launch("image/*") },
                            )
                            if (photoUrl.isNotBlank()) {
                                Text(stringResource(R.string.task_photo_attached))
                                AsyncImage(
                                    model = photoUrl,
                                    contentDescription = stringResource(R.string.task_photo_description),
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(200.dp),
                                )
                            }
                            FamilyButton(
                                text = stringResource(R.string.task_submit_confirm),
                                icon = Icons.Filled.Check,
                                onClick = {
                                    coroutineScope.launch {
                                        message = when (val r = client.submitTask(task.id, comment, photoUrl, remoteDeviceId)) {
                                            TaskOpResult.NotConfigured -> context.getString(R.string.supabase_sync_not_configured)
                                            is TaskOpResult.Error -> context.getString(R.string.task_op_failed, r.message)
                                            TaskOpResult.Ok -> {
                                                selectedTaskId = ""
                                                comment = ""
                                                photoUrl = ""
                                                context.getString(R.string.task_submitted)
                                            }
                                        }
                                        reload()
                                    }
                                },
                            )
                        }
                    } else if (task.status == "open" && !task.canSubmitNow()) {
                        Text(stringResource(R.string.task_expired_no_submit))
                    } else if (task.status == "rejected") {
                        Text(stringResource(R.string.task_rejected_no_resubmit))
                    }
                }
            }
        }

        FamilySecondaryButton(
            text = stringResource(R.string.refresh),
            icon = Icons.Filled.Refresh,
            onClick = { reload() },
        )
    }
}

private fun FamilyTask.canSubmitNow(): Boolean {
    if (taskType != "limited") return true
    val expires = runCatching { Instant.parse(expiresAt) }.getOrNull() ?: return true
    return Instant.now().isBefore(expires)
}
