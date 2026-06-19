package com.familytimemanager.app.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.util.UUID

/**
 * Uploads task-submission photos to the Supabase Storage `task-submissions` bucket and returns a
 * URL to store on `task_submissions.photo_url`.
 *
 * The returned URL is the bucket's public object URL. If the bucket is private, swap [publicUrlFor]
 * for a signed-URL call (`POST /storage/v1/object/sign/{bucket}/{path}`) and store that instead.
 *
 * Requires a configured project; auth uses the signed-in parent token when present, otherwise the
 * anon key (same fallback as the other REST clients).
 */
class SupabaseStorageClient(
    private val settingsStore: SupabaseSettingsStore,
    private val restAuthHeaders: SupabaseRestAuthHeaders = SupabaseRestAuthHeaders(),
) {
    suspend fun uploadTaskPhoto(
        bytes: ByteArray,
        contentType: String,
        deviceUuid: String,
    ): StorageUploadResult = withContext(Dispatchers.IO) {
        runCatching {
            val settings = settingsStore.snapshot()
            if (!settings.isConfigured) return@withContext StorageUploadResult.NotConfigured
            if (bytes.isEmpty()) return@withContext StorageUploadResult.Error("Empty file")

            val ext = extensionFor(contentType)
            val safeDevice = deviceUuid.ifBlank { "unknown" }
            val path = "tasks/$safeDevice/${UUID.randomUUID()}.$ext"

            val url = URL("${settings.projectUrl}/storage/v1/object/$BUCKET/$path")
            val connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MILLIS
                readTimeout = TIMEOUT_MILLIS
                restAuthHeaders.applyTo(this, settings)
                // applyTo sets Content-Type to application/json; override with the real image type.
                setRequestProperty("Content-Type", contentType.ifBlank { "image/jpeg" })
                setRequestProperty("x-upsert", "true")
                requestMethod = "POST"
                doOutput = true
            }
            connection.outputStream.use { it.write(bytes) }
            val body = readBody(connection)
            val code = connection.responseCode
            connection.disconnect()
            if (code !in 200..299) error(body.ifBlank { "HTTP $code" })

            StorageUploadResult.Uploaded(publicUrl = publicUrlFor(settings, path), path = path)
        }.getOrElse { error ->
            StorageUploadResult.Error(error.message ?: error::class.java.simpleName)
        }
    }

    suspend fun uploadStoreIcon(
        bytes: ByteArray,
        contentType: String,
        familyId: String,
    ): StorageUploadResult = withContext(Dispatchers.IO) {
        runCatching {
            val settings = settingsStore.snapshot()
            if (!settings.isConfigured) return@withContext StorageUploadResult.NotConfigured
            if (bytes.isEmpty()) return@withContext StorageUploadResult.Error("Empty file")

            val ext = extensionFor(contentType)
            val safeFamily = familyId.ifBlank { "unknown" }
            val path = "store-icons/$safeFamily/${UUID.randomUUID()}.$ext"

            val url = URL("${settings.projectUrl}/storage/v1/object/$BUCKET/$path")
            val connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MILLIS
                readTimeout = TIMEOUT_MILLIS
                restAuthHeaders.applyTo(this, settings)
                setRequestProperty("Content-Type", contentType.ifBlank { "image/jpeg" })
                setRequestProperty("x-upsert", "true")
                requestMethod = "POST"
                doOutput = true
            }
            connection.outputStream.use { it.write(bytes) }
            val body = readBody(connection)
            val code = connection.responseCode
            connection.disconnect()
            if (code !in 200..299) error(body.ifBlank { "HTTP $code" })

            StorageUploadResult.Uploaded(publicUrl = publicUrlFor(settings, path), path = path)
        }.getOrElse { error ->
            StorageUploadResult.Error(error.message ?: error::class.java.simpleName)
        }
    }

    suspend fun deleteTaskPhoto(publicUrl: String): StorageDeleteResult = withContext(Dispatchers.IO) {
        runCatching {
            val settings = settingsStore.snapshot()
            if (!settings.isConfigured) return@withContext StorageDeleteResult.NotConfigured
            val path = pathFromPublicUrl(settings, publicUrl)
                ?: return@withContext StorageDeleteResult.Deleted

            val url = URL("${settings.projectUrl}/storage/v1/object/$BUCKET/${path.urlPathEncode()}")
            val connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MILLIS
                readTimeout = TIMEOUT_MILLIS
                restAuthHeaders.applyTo(this, settings)
                requestMethod = "DELETE"
            }
            val body = readBody(connection)
            val code = connection.responseCode
            connection.disconnect()
            if (code !in 200..299 && code != 404) error(body.ifBlank { "HTTP $code" })

            StorageDeleteResult.Deleted
        }.getOrElse { error ->
            StorageDeleteResult.Error(error.message ?: error::class.java.simpleName)
        }
    }

    private fun publicUrlFor(settings: SupabaseSettings, path: String): String {
        return "${settings.projectUrl}/storage/v1/object/public/$BUCKET/$path"
    }

    private fun pathFromPublicUrl(settings: SupabaseSettings, publicUrl: String): String? {
        val prefix = "${settings.projectUrl}/storage/v1/object/public/$BUCKET/"
        return publicUrl.takeIf { it.startsWith(prefix) }?.removePrefix(prefix)
    }

    private fun extensionFor(contentType: String): String {
        return when (contentType.lowercase()) {
            "image/png" -> "png"
            "image/webp" -> "webp"
            "image/heic" -> "heic"
            "image/jpeg", "image/jpg" -> "jpg"
            else -> "jpg"
        }
    }

    private fun readBody(connection: HttpURLConnection): String {
        val input = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
        return BufferedReader(InputStreamReader(input)).use { it.readText() }
    }

    companion object {
        private const val TIMEOUT_MILLIS = 20_000
        private const val BUCKET = "task-submissions"
    }
}

sealed interface StorageUploadResult {
    data class Uploaded(val publicUrl: String, val path: String) : StorageUploadResult
    data object NotConfigured : StorageUploadResult
    data class Error(val message: String) : StorageUploadResult
}

sealed interface StorageDeleteResult {
    data object Deleted : StorageDeleteResult
    data object NotConfigured : StorageDeleteResult
    data class Error(val message: String) : StorageDeleteResult
}

private fun String.urlPathEncode(): String {
    return split("/").joinToString("/") { segment ->
        URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
    }
}
