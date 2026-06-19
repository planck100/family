package com.familytimemanager.app.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.time.Instant
import java.util.UUID

/**
 * Family task workflow backed by Supabase `tasks` (+ `task_submissions`):
 * parent creates tasks, child submits with a comment, parent approves/rejects.
 *
 * Reward time (`reward_seconds`) is stored and shown but NOT auto-applied to a device on approval:
 * in the device-centric model there is no task↔device mapping, so the parent grants the reward via
 * Remote control after approving. Photo uploads (`task_submissions.photo_url`) are a separate
 * backlog item and left null here.
 *
 * Requires a configured project and a bound family.
 */
class SupabaseTaskClient(
    private val settingsStore: SupabaseSettingsStore,
    private val familyStore: FamilySettingsStore,
    private val authSessionStore: AuthSessionStore,
    private val restAuthHeaders: SupabaseRestAuthHeaders = SupabaseRestAuthHeaders(authSessionStore),
    private val storageClient: SupabaseStorageClient = SupabaseStorageClient(settingsStore, restAuthHeaders),
    private val auditClient: SupabaseAuditLogClient = SupabaseAuditLogClient(settingsStore, familyStore, authSessionStore, restAuthHeaders),
) {
    suspend fun listTasks(assignedDeviceId: String? = null): TaskListResult = withContext(Dispatchers.IO) {
        runCatching {
            SupabaseAuthClient(settingsStore, authSessionStore).refreshIfNeeded()
            val settings = settingsStore.snapshot()
            if (!settings.isConfigured) return@withContext TaskListResult.NotConfigured

            val targetDeviceId = assignedDeviceId?.takeIf { it.isNotBlank() }
            if (targetDeviceId != null) {
                // Child path: an anonymous child device cannot read the tasks table directly
                // (blocked by RLS), so it goes through a SECURITY DEFINER RPC, mirroring how the
                // store reads products for a device.
                val connection = openConnection(settings, "rpc/list_tasks_for_device")
                connection.requestMethod = "POST"
                connection.doOutput = true
                OutputStreamWriter(connection.outputStream).use { writer ->
                    writer.write("""{"p_device_id":"${targetDeviceId.jsonEscape()}"}""")
                }
                val body = readBody(connection)
                val code = connection.responseCode
                connection.disconnect()
                if (code !in 200..299) error(body.ifBlank { "HTTP $code" })
                return@withContext TaskListResult.Loaded(parseTasks(body))
            }

            // Parent path (authenticated): direct REST select over the family's tasks.
            val family = familyStore.snapshot()
            if (!family.isBound) return@withContext TaskListResult.NotConfigured
            runTaskMaintenance(settings)

            val familyId = URLEncoder.encode(family.familyId, "UTF-8")
            val path = "tasks?family_id=eq.$familyId" +
                "&select=id,title,description,reward_seconds,status,assigned_device_id,task_type,expires_at,created_at" +
                "&order=created_at.desc&limit=100"
            val connection = openConnection(settings, path)
            connection.requestMethod = "GET"
            val body = readBody(connection)
            val code = connection.responseCode
            connection.disconnect()
            if (code !in 200..299) error(body.ifBlank { "HTTP $code" })

            TaskListResult.Loaded(parseTasks(body))
        }.getOrElse { error ->
            TaskListResult.Error(error.message ?: error::class.java.simpleName)
        }
    }

    suspend fun createTask(
        title: String,
        description: String,
        rewardSeconds: Long,
        assignedDeviceId: String,
        assignedDeviceName: String = "",
        taskType: String = TASK_TYPE_PERMANENT,
        expiresAt: String = "",
    ): TaskOpResult = withContext(Dispatchers.IO) {
        runCatching {
            SupabaseAuthClient(settingsStore, authSessionStore).refreshIfNeeded()
            val settings = settingsStore.snapshot()
            val family = familyStore.snapshot()
            if (!settings.isConfigured || !family.isBound) return@withContext TaskOpResult.NotConfigured
            if (title.trim().isBlank()) return@withContext TaskOpResult.Error("Title is empty")
            if (assignedDeviceId.isBlank()) return@withContext TaskOpResult.Error("No child device selected")

            val createdBy = authSessionStore.snapshot().userId
            val createdByJson = if (createdBy.isBlank()) "null" else "\"${createdBy.jsonEscape()}\""
            val normalizedTaskType = if (taskType == TASK_TYPE_LIMITED) TASK_TYPE_LIMITED else TASK_TYPE_PERMANENT
            val expiresJson = expiresAt.takeIf { normalizedTaskType == TASK_TYPE_LIMITED && it.isNotBlank() }
                ?.let { "\"${it.jsonEscape()}\"" }
                ?: "null"
            val connection = openConnection(settings, "tasks")
            connection.requestMethod = "POST"
            connection.setRequestProperty("Prefer", "return=minimal")
            connection.doOutput = true
            OutputStreamWriter(connection.outputStream).use { writer ->
                writer.write(
                    """
                        {
                          "id": "${UUID.randomUUID()}",
                          "family_id": "${family.familyId.jsonEscape()}",
                          "title": "${title.trim().jsonEscape()}",
                          "description": "${description.trim().jsonEscape()}",
                          "reward_seconds": $rewardSeconds,
                          "assigned_device_id": "${assignedDeviceId.jsonEscape()}",
                          "task_type": "$normalizedTaskType",
                          "expires_at": $expiresJson,
                          "status": "open",
                          "created_by": $createdByJson
                        }
                    """.trimIndent(),
                )
            }
            checkOk(connection)
            sendTaskNotificationCommand(
                settings = settings,
                assignedDeviceId = assignedDeviceId,
                command = DeviceCommandType.TASK_ASSIGNED,
                taskTitle = title.trim(),
            )
            runCatching {
                auditClient.recordAction("CREATE_TASK", "Task created: ${title.trim()}; target: ${assignedDeviceName.ifBlank { assignedDeviceId }}")
            }
            TaskOpResult.Ok
        }.getOrElse { error ->
            TaskOpResult.Error(error.message ?: error::class.java.simpleName)
        }
    }

    /** Parent deletes a task and any submission photos linked to it. */
    suspend fun deleteTask(taskId: String, assignedDeviceName: String = ""): TaskOpResult = withContext(Dispatchers.IO) {
        runCatching {
            SupabaseAuthClient(settingsStore, authSessionStore).refreshIfNeeded()
            val settings = settingsStore.snapshot()
            if (!settings.isConfigured) return@withContext TaskOpResult.NotConfigured
            if (taskId.isBlank()) return@withContext TaskOpResult.Error("No task selected")
            val taskInfo = fetchTaskNotificationInfo(settings, taskId)

            listSubmissionPhotoUrls(settings, taskId).distinct().forEach { url ->
                when (val deleted = storageClient.deleteTaskPhoto(url)) {
                    StorageDeleteResult.Deleted -> Unit
                    StorageDeleteResult.NotConfigured -> return@withContext TaskOpResult.NotConfigured
                    is StorageDeleteResult.Error -> return@withContext TaskOpResult.Error(deleted.message)
                }
            }

            val id = URLEncoder.encode(taskId, "UTF-8")
            val connection = openConnection(settings, "tasks?id=eq.$id")
            connection.requestMethod = "DELETE"
            connection.setRequestProperty("Prefer", "return=minimal")
            checkOk(connection)
            if (taskInfo.assignedDeviceId.isNotBlank()) {
                runCatching {
                    sendTaskNotificationCommand(
                        settings = settings,
                        assignedDeviceId = taskInfo.assignedDeviceId,
                        command = DeviceCommandType.TASK_DELETED,
                        taskTitle = taskInfo.title,
                    )
                }
            }
            runCatching {
                auditClient.recordAction(
                    "DELETE_TASK",
                    "Task deleted: ${taskInfo.title}; target: ${assignedDeviceName.ifBlank { taskInfo.assignedDeviceId }}",
                )
            }
            TaskOpResult.Ok
        }.getOrElse { error ->
            TaskOpResult.Error(error.message ?: error::class.java.simpleName)
        }
    }

    /** Child submits a task: records a submission row and moves the task to `submitted`. */
    suspend fun submitTask(
        taskId: String,
        comment: String,
        photoUrl: String = "",
        deviceId: String = "",
    ): TaskOpResult = withContext(Dispatchers.IO) {
        runCatching {
            SupabaseAuthClient(settingsStore, authSessionStore).refreshIfNeeded()
            val settings = settingsStore.snapshot()
            if (!settings.isConfigured) return@withContext TaskOpResult.NotConfigured
            if (taskId.isBlank()) return@withContext TaskOpResult.Error("No task selected")
            if (deviceId.isBlank()) return@withContext TaskOpResult.Error("No child device selected")

            val photoJson = if (photoUrl.isBlank()) "null" else "\"${photoUrl.jsonEscape()}\""
            val submission = openConnection(settings, "rpc/submit_task_for_device")
            submission.requestMethod = "POST"
            submission.doOutput = true
            OutputStreamWriter(submission.outputStream).use { writer ->
                writer.write(
                    """
                        {
                          "p_task_id": "${taskId.jsonEscape()}",
                          "p_device_id": "${deviceId.jsonEscape()}",
                          "p_comment": "${comment.trim().jsonEscape()}",
                          "p_photo_url": $photoJson
                        }
                    """.trimIndent(),
                )
            }
            checkOk(submission)

            val taskInfo = fetchTaskNotificationInfo(settings, taskId)
            if (taskInfo.assignedDeviceId.isNotBlank()) {
                sendTaskNotificationCommand(
                    settings = settings,
                    assignedDeviceId = taskInfo.assignedDeviceId,
                    command = DeviceCommandType.TASK_SUBMITTED,
                    taskTitle = taskInfo.title,
                )
            }
            TaskOpResult.Ok
        }.getOrElse { error ->
            TaskOpResult.Error(error.message ?: error::class.java.simpleName)
        }
    }

    /** Lists submissions for a task (newest first), for the parent review screen. */
    suspend fun listSubmissions(taskId: String): SubmissionListResult = withContext(Dispatchers.IO) {
        runCatching {
            SupabaseAuthClient(settingsStore, authSessionStore).refreshIfNeeded()
            val settings = settingsStore.snapshot()
            if (!settings.isConfigured) return@withContext SubmissionListResult.NotConfigured
            if (taskId.isBlank()) return@withContext SubmissionListResult.Error("No task selected")

            val id = URLEncoder.encode(taskId, "UTF-8")
            val path = "task_submissions?task_id=eq.$id" +
                "&select=comment,photo_url,status,submitted_at" +
                "&order=submitted_at.desc&limit=20"
            val connection = openConnection(settings, path)
            connection.requestMethod = "GET"
            val body = readBody(connection)
            val code = connection.responseCode
            connection.disconnect()
            if (code !in 200..299) error(body.ifBlank { "HTTP $code" })

            SubmissionListResult.Loaded(parseSubmissions(body))
        }.getOrElse { error ->
            SubmissionListResult.Error(error.message ?: error::class.java.simpleName)
        }
    }

    private fun parseSubmissions(body: String): List<TaskSubmission> {
        if (body.isBlank() || body == "[]") return emptyList()
        return OBJECT_REGEX.findAll(body).mapNotNull { match ->
            val obj = match.value
            TaskSubmission(
                comment = obj.extractString("comment").orEmpty(),
                photoUrl = obj.extractString("photo_url").orEmpty(),
                status = obj.extractString("status").orEmpty(),
                submittedAt = obj.extractString("submitted_at").orEmpty(),
            )
        }.toList()
    }

    /** Parent approves or rejects a submitted task. */
    suspend fun reviewTask(
        taskId: String,
        approve: Boolean,
        assignedDeviceId: String,
        rewardSeconds: Long,
        assignedDeviceName: String = "",
    ): TaskOpResult = withContext(Dispatchers.IO) {
        runCatching {
            SupabaseAuthClient(settingsStore, authSessionStore).refreshIfNeeded()
            val settings = settingsStore.snapshot()
            if (!settings.isConfigured) return@withContext TaskOpResult.NotConfigured
            if (taskId.isBlank()) return@withContext TaskOpResult.Error("No task selected")

            val status = if (approve) "approved" else "rejected"
            // Guard up-front so we never leave the task marked approved on the server while the
            // reward silently fails to reach the child device.
            if (approve && rewardSeconds > 0L && assignedDeviceId.isBlank()) {
                return@withContext TaskOpResult.Error("Task has no assigned device")
            }
            val submissionPhotos = if (approve) listSubmissionPhotoUrls(settings, taskId) else emptyList()

            // 1) Update task + submission status so the parent board moves off "pending".
            patchTaskStatus(settings, taskId, status)
            patchSubmissionStatus(settings, taskId, status)

            // 2) Grant the reward FIRST (the critical side effect), before the best-effort
            //    notification, so a notification failure can never abort reward delivery.
            if (approve && rewardSeconds > 0L) {
                sendRewardCommand(settings, assignedDeviceId, rewardSeconds)
            }

            // 3) Notify the child device. Best-effort: a failure here must not fail the review
            //    or roll back the reward that was already queued above.
            if (assignedDeviceId.isNotBlank()) {
                runCatching {
                    val taskTitle = fetchTaskNotificationInfo(settings, taskId).title
                    sendTaskNotificationCommand(
                        settings = settings,
                        assignedDeviceId = assignedDeviceId,
                        command = if (approve) DeviceCommandType.TASK_APPROVED else DeviceCommandType.TASK_REJECTED,
                        taskTitle = taskTitle,
                    )
                }
            }
            if (approve) {
                val photosDeleted = submissionPhotos.all { url ->
                    storageClient.deleteTaskPhoto(url) == StorageDeleteResult.Deleted
                }
                // Keep the URL when Storage deletion fails so maintenance can retry later instead
                // of losing the only reference and leaving an orphan object.
                if (photosDeleted) {
                    clearSubmissionPhotos(settings, taskId)
                }
            }
            runCatching {
                val taskTitle = fetchTaskNotificationInfo(settings, taskId).title
                auditClient.recordAction(
                    if (approve) "APPROVE_TASK" else "REJECT_TASK",
                    if (approve) {
                        "Task approved: $taskTitle; target: ${assignedDeviceName.ifBlank { assignedDeviceId }}"
                    } else {
                        "Task rejected: $taskTitle; target: ${assignedDeviceName.ifBlank { assignedDeviceId }}"
                    },
                )
            }
            TaskOpResult.Ok
        }.getOrElse { error ->
            TaskOpResult.Error(error.message ?: error::class.java.simpleName)
        }
    }

    private fun fetchTaskNotificationInfo(settings: SupabaseSettings, taskId: String): TaskNotificationInfo {
        val id = URLEncoder.encode(taskId, "UTF-8")
        val connection = openConnection(settings, "tasks?id=eq.$id&select=title,assigned_device_id&limit=1")
        connection.requestMethod = "GET"
        val body = readBody(connection)
        val code = connection.responseCode
        connection.disconnect()
        if (code !in 200..299) error(body.ifBlank { "HTTP $code" })

        return TaskNotificationInfo(
            title = body.extractString("title").orEmpty(),
            assignedDeviceId = body.extractString("assigned_device_id").orEmpty(),
        )
    }

    private fun listSubmissionPhotoUrls(settings: SupabaseSettings, taskId: String): List<String> {
        val id = URLEncoder.encode(taskId, "UTF-8")
        val connection = openConnection(settings, "task_submissions?task_id=eq.$id&select=photo_url")
        connection.requestMethod = "GET"
        val body = readBody(connection)
        val code = connection.responseCode
        connection.disconnect()
        if (code !in 200..299) error(body.ifBlank { "HTTP $code" })

        return OBJECT_REGEX.findAll(body)
            .mapNotNull { it.value.extractString("photo_url") }
            .filter { it.isNotBlank() }
            .toList()
    }

    private fun clearSubmissionPhotos(settings: SupabaseSettings, taskId: String) {
        val id = URLEncoder.encode(taskId, "UTF-8")
        val connection = openConnection(settings, "task_submissions?task_id=eq.$id")
        connection.requestMethod = "PATCH"
        connection.setRequestProperty("Prefer", "return=minimal")
        connection.doOutput = true
        OutputStreamWriter(connection.outputStream).use { writer ->
            writer.write("""{ "photo_url": null }""")
        }
        checkOk(connection)
    }

    private fun sendRewardCommand(settings: SupabaseSettings, assignedDeviceId: String, rewardSeconds: Long) {
        val createdBy = authSessionStore.snapshot().userId
        val createdByJson = if (createdBy.isBlank()) "null" else "\"${createdBy.jsonEscape()}\""
        val connection = openConnection(settings, "commands")
        connection.requestMethod = "POST"
        connection.setRequestProperty("Prefer", "return=minimal")
        connection.doOutput = true
        OutputStreamWriter(connection.outputStream).use { writer ->
            writer.write(
                """
                    {
                      "id": "${UUID.randomUUID()}",
                      "device_id": "${assignedDeviceId.jsonEscape()}",
                      "command": "ADD_TIME",
                      "value": $rewardSeconds,
                      "remark": "Task approved reward",
                      "status": "pending",
                      "created_by": $createdByJson
                    }
                """.trimIndent(),
            )
        }
        checkOk(connection)
    }

    private fun sendTaskNotificationCommand(
        settings: SupabaseSettings,
        assignedDeviceId: String,
        command: DeviceCommandType,
        taskTitle: String,
    ) {
        val createdBy = authSessionStore.snapshot().userId
        val createdByJson = if (createdBy.isBlank()) "null" else "\"${createdBy.jsonEscape()}\""
        val connection = openConnection(settings, "commands")
        connection.requestMethod = "POST"
        connection.setRequestProperty("Prefer", "return=minimal")
        connection.doOutput = true
        OutputStreamWriter(connection.outputStream).use { writer ->
            writer.write(
                """
                    {
                      "id": "${UUID.randomUUID()}",
                      "device_id": "${assignedDeviceId.jsonEscape()}",
                      "command": "${command.name}",
                      "value": null,
                      "remark": "${taskTitle.jsonEscape()}",
                      "status": "pending",
                      "created_by": $createdByJson
                    }
                """.trimIndent(),
            )
        }
        checkOk(connection)
    }

    private fun patchTaskStatus(settings: SupabaseSettings, taskId: String, status: String) {
        val id = URLEncoder.encode(taskId, "UTF-8")
        val connection = openConnection(settings, "tasks?id=eq.$id")
        connection.requestMethod = "PATCH"
        connection.setRequestProperty("Prefer", "return=minimal")
        connection.doOutput = true
        val now = Instant.now().toString()
        val timestampField = when (status) {
            "closed" -> ", \"closed_at\": \"$now\""
            "approved", "rejected" -> ", \"reviewed_at\": \"$now\""
            else -> ""
        }
        OutputStreamWriter(connection.outputStream).use { writer ->
            writer.write("""{ "status": "${status.jsonEscape()}"$timestampField }""")
        }
        checkOk(connection)
    }

    private fun patchSubmissionStatus(settings: SupabaseSettings, taskId: String, status: String) {
        // approved/rejected only; submissions use the same two terminal states.
        val id = URLEncoder.encode(taskId, "UTF-8")
        val connection = openConnection(settings, "task_submissions?task_id=eq.$id&status=eq.submitted")
        connection.requestMethod = "PATCH"
        connection.setRequestProperty("Prefer", "return=minimal")
        connection.doOutput = true
        OutputStreamWriter(connection.outputStream).use { writer ->
            writer.write("""{ "status": "${status.jsonEscape()}" }""")
        }
        // Best effort: ignore non-2xx here so a missing submission row does not fail the review.
        connection.responseCode
        connection.disconnect()
    }

    private fun parseTasks(body: String): List<FamilyTask> {
        if (body.isBlank() || body == "[]") return emptyList()
        return OBJECT_REGEX.findAll(body).mapNotNull { match ->
            val obj = match.value
            val id = obj.extractString("id") ?: return@mapNotNull null
            FamilyTask(
                id = id,
                title = obj.extractString("title").orEmpty(),
                description = obj.extractString("description").orEmpty(),
                rewardSeconds = obj.extractLong("reward_seconds") ?: 0L,
                status = obj.extractString("status").orEmpty(),
                assignedDeviceId = obj.extractString("assigned_device_id").orEmpty(),
                taskType = obj.extractString("task_type").orEmpty().ifBlank { TASK_TYPE_PERMANENT },
                expiresAt = obj.extractString("expires_at").orEmpty(),
                createdAt = obj.extractString("created_at").orEmpty(),
            )
        }.toList()
    }

    private suspend fun runTaskMaintenance(settings: SupabaseSettings) {
        val family = familyStore.snapshot()
        val deviceId = settingsStore.snapshot().remoteDeviceId
        val familyJson = family.familyId.takeIf { it.isNotBlank() }?.let { "\"${it.jsonEscape()}\"" } ?: "null"
        val deviceJson = deviceId.takeIf { it.isNotBlank() }?.let { "\"${it.jsonEscape()}\"" } ?: "null"
        val scopeJson = """{"p_family_id":$familyJson,"p_device_id":$deviceJson}"""
        runCatching {
            openConnection(settings, "rpc/close_expired_limited_tasks").apply {
                requestMethod = "POST"
                doOutput = true
                OutputStreamWriter(outputStream).use { it.write(scopeJson) }
                responseCode
                disconnect()
            }
        }
        val candidates = runCatching {
            val connection = openConnection(settings, "rpc/list_task_cleanup_candidates")
            connection.requestMethod = "POST"
            connection.doOutput = true
            OutputStreamWriter(connection.outputStream).use { it.write(scopeJson) }
            val body = readBody(connection)
            val code = connection.responseCode
            connection.disconnect()
            if (code !in 200..299) error(body.ifBlank { "HTTP $code" })
            parseCleanupCandidates(body)
        }.getOrDefault(emptyList())

        candidates.groupBy { it.taskId }.forEach { (taskId, taskCandidates) ->
            val photosDeleted = taskCandidates
                .mapNotNull { it.photoUrl.takeIf(String::isNotBlank) }
                .distinct()
                .all { url ->
                    runCatching { storageClient.deleteTaskPhoto(url) }
                        .getOrNull() == StorageDeleteResult.Deleted
                }
            if (!photosDeleted) return@forEach
            runCatching {
                val connection = openConnection(settings, "rpc/delete_task_after_cleanup")
                connection.requestMethod = "POST"
                connection.doOutput = true
                OutputStreamWriter(connection.outputStream).use { writer ->
                    writer.write("""{"p_task_id":"${taskId.jsonEscape()}","p_family_id":$familyJson,"p_device_id":$deviceJson}""")
                }
                connection.responseCode
                connection.disconnect()
            }
        }
    }

    private fun parseCleanupCandidates(body: String): List<TaskCleanupCandidate> {
        if (body.isBlank() || body == "[]") return emptyList()
        return OBJECT_REGEX.findAll(body).mapNotNull { match ->
            TaskCleanupCandidate(
                taskId = match.value.extractString("task_id") ?: return@mapNotNull null,
                photoUrl = match.value.extractString("photo_url").orEmpty(),
            )
        }.toList()
    }

    private fun checkOk(connection: HttpURLConnection) {
        val body = readBody(connection)
        val code = connection.responseCode
        connection.disconnect()
        if (code !in 200..299) error(body.ifBlank { "HTTP $code" })
    }

    private fun openConnection(settings: SupabaseSettings, path: String): HttpURLConnection {
        val url = URL("${settings.projectUrl}/rest/v1/$path")
        return (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MILLIS
            readTimeout = TIMEOUT_MILLIS
            restAuthHeaders.applyTo(this, settings)
        }
    }

    private fun readBody(connection: HttpURLConnection): String {
        val input = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
        return BufferedReader(InputStreamReader(input)).use { it.readText() }
    }

    companion object {
        private const val TIMEOUT_MILLIS = 15_000
        private const val TASK_TYPE_PERMANENT = "permanent"
        private const val TASK_TYPE_LIMITED = "limited"
        private val OBJECT_REGEX = Regex("""[{][^}]*[}]""")
    }
}

data class FamilyTask(
    val id: String,
    val title: String,
    val description: String,
    val rewardSeconds: Long,
    val status: String,
    val assignedDeviceId: String,
    val taskType: String,
    val expiresAt: String,
    val createdAt: String,
)

data class TaskCleanupCandidate(
    val taskId: String,
    val photoUrl: String,
)

data class TaskSubmission(
    val comment: String,
    val photoUrl: String,
    val status: String,
    val submittedAt: String,
)

private data class TaskNotificationInfo(
    val title: String,
    val assignedDeviceId: String,
)

sealed interface TaskListResult {
    data class Loaded(val tasks: List<FamilyTask>) : TaskListResult
    data object NotConfigured : TaskListResult
    data class Error(val message: String) : TaskListResult
}

sealed interface SubmissionListResult {
    data class Loaded(val submissions: List<TaskSubmission>) : SubmissionListResult
    data object NotConfigured : SubmissionListResult
    data class Error(val message: String) : SubmissionListResult
}

sealed interface TaskOpResult {
    data object Ok : TaskOpResult
    data object NotConfigured : TaskOpResult
    data class Error(val message: String) : TaskOpResult
}

private fun String.extractString(field: String): String? {
    val regex = Regex(""""$field"\s*:\s*"((?:\\.|[^"])*)"""")
    return regex.find(this)?.groupValues?.getOrNull(1)?.jsonUnescape()
}

private fun String.extractLong(field: String): Long? {
    val regex = Regex(""""$field"\s*:\s*(null|-?\d+)""")
    val value = regex.find(this)?.groupValues?.getOrNull(1) ?: return null
    return value.takeIf { it != "null" }?.toLongOrNull()
}

private fun String.jsonEscape(): String {
    return buildString {
        this@jsonEscape.forEach { char ->
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(char)
            }
        }
    }
}

private fun String.jsonUnescape(): String {
    val builder = StringBuilder()
    var index = 0
    while (index < length) {
        val char = this[index]
        if (char == '\\' && index + 1 < length) {
            when (this[index + 1]) {
                '\\' -> builder.append('\\')
                '"' -> builder.append('"')
                'n' -> builder.append('\n')
                'r' -> builder.append('\r')
                't' -> builder.append('\t')
                else -> builder.append(this[index + 1])
            }
            index += 2
        } else {
            builder.append(char)
            index += 1
        }
    }
    return builder.toString()
}
