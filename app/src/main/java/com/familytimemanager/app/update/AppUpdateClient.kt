package com.familytimemanager.app.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.FileProvider
import com.familytimemanager.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

class AppUpdateClient(private val context: Context) {
    suspend fun check(): UpdateCheckResult = withContext(Dispatchers.IO) {
        runCatching {
            val manifestUrl = context.getString(R.string.update_manifest_url).trim()
            if (!manifestUrl.startsWith("https://")) return@withContext UpdateCheckResult.NotConfigured

            val separator = if ('?' in manifestUrl) '&' else '?'
            val cacheBustedUrl = "$manifestUrl${separator}t=${System.currentTimeMillis()}"
            val connection = (URL(cacheBustedUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MILLIS
                readTimeout = TIMEOUT_MILLIS
                useCaches = false
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Cache-Control", "no-cache")
                setRequestProperty("Pragma", "no-cache")
                setRequestProperty("User-Agent", "Family-Time-Manager-Android")
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val code = connection.responseCode
            connection.disconnect()
            if (code !in 200..299) error("HTTP $code")

            val json = JSONObject(body)
            val info = AppUpdateInfo(
                versionCode = json.getLong("versionCode"),
                versionName = json.optString("versionName"),
                apkUrl = json.getString("apkUrl"),
                sha256 = json.getString("sha256").replace(" ", "").uppercase(),
                mandatory = json.optBoolean("mandatory", false),
                releaseNotes = json.optString("releaseNotes"),
            )
            if (!info.apkUrl.startsWith("https://")) error("apk_url_must_use_https")
            if (!info.sha256.matches(Regex("[0-9A-F]{64}"))) error("invalid_sha256")

            val currentVersion = currentVersionCode()
            if (info.versionCode > currentVersion) {
                UpdateCheckResult.Available(info)
            } else {
                UpdateCheckResult.UpToDate(currentVersion)
            }
        }.getOrElse { error ->
            UpdateCheckResult.Error(error.message ?: error::class.java.simpleName)
        }
    }

    suspend fun downloadAndVerify(
        info: AppUpdateInfo,
        onProgress: (Int) -> Unit,
    ): UpdateDownloadResult = withContext(Dispatchers.IO) {
        runCatching {
            val updateDir = File(context.cacheDir, "updates").apply { mkdirs() }
            val partial = File(updateDir, "family-time-manager-${info.versionCode}.download")
            val apk = File(updateDir, "family-time-manager-${info.versionCode}.apk")

            val connection = (URL(info.apkUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MILLIS
                readTimeout = DOWNLOAD_TIMEOUT_MILLIS
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Family-Time-Manager-Android")
            }
            val code = connection.responseCode
            if (code !in 200..299) {
                connection.disconnect()
                error("HTTP $code")
            }
            val total = connection.contentLengthLong
            BufferedInputStream(connection.inputStream).use { input ->
                FileOutputStream(partial).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var downloaded = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        downloaded += count
                        if (total > 0) onProgress(((downloaded * 100L) / total).toInt().coerceIn(0, 100))
                    }
                }
            }
            connection.disconnect()

            val actualHash = sha256(partial)
            if (actualHash != info.sha256) {
                partial.delete()
                return@withContext UpdateDownloadResult.Error("sha256_mismatch")
            }

            val archive = packageArchiveInfo(partial)
                ?: return@withContext UpdateDownloadResult.Error("invalid_apk")
            if (archive.packageName != context.packageName) {
                partial.delete()
                return@withContext UpdateDownloadResult.Error("package_name_mismatch")
            }
            if (archive.longVersionCodeValue() != info.versionCode || info.versionCode <= currentVersionCode()) {
                partial.delete()
                return@withContext UpdateDownloadResult.Error("version_code_mismatch")
            }
            if (!sameSigner(currentPackageInfo(), archive)) {
                partial.delete()
                return@withContext UpdateDownloadResult.Error("signer_mismatch")
            }

            if (apk.exists()) apk.delete()
            if (!partial.renameTo(apk)) {
                partial.copyTo(apk, overwrite = true)
                partial.delete()
            }
            onProgress(100)
            UpdateDownloadResult.Ready(
                apk = apk,
                contentUri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.updates",
                    apk,
                ),
            )
        }.getOrElse { error ->
            UpdateDownloadResult.Error(error.message ?: error::class.java.simpleName)
        }
    }

    private fun currentVersionCode(): Long = currentPackageInfo().longVersionCodeValue()

    private fun currentPackageInfo(): PackageInfo {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
        return context.packageManager.getPackageInfo(context.packageName, flags)
    }

    private fun packageArchiveInfo(file: File): PackageInfo? {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
        return context.packageManager.getPackageArchiveInfo(file.absolutePath, flags)
    }

    private fun sameSigner(current: PackageInfo, archive: PackageInfo): Boolean {
        return signerDigests(current) == signerDigests(archive) && signerDigests(current).isNotEmpty()
    }

    private fun signerDigests(info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners.orEmpty()
        } else {
            @Suppress("DEPRECATION")
            info.signatures.orEmpty()
        }
        return signatures.map { signature ->
            MessageDigest.getInstance("SHA-256")
                .digest(signature.toByteArray())
                .joinToString("") { "%02X".format(it) }
        }.toSet()
    }

    private fun PackageInfo.longVersionCodeValue(): Long {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) longVersionCode else {
            @Suppress("DEPRECATION")
            versionCode.toLong()
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02X".format(it) }
    }

    companion object {
        private const val TIMEOUT_MILLIS = 15_000
        private const val DOWNLOAD_TIMEOUT_MILLIS = 120_000
    }
}

data class AppUpdateInfo(
    val versionCode: Long,
    val versionName: String,
    val apkUrl: String,
    val sha256: String,
    val mandatory: Boolean,
    val releaseNotes: String,
)

sealed interface UpdateCheckResult {
    data class Available(val info: AppUpdateInfo) : UpdateCheckResult
    data class UpToDate(val versionCode: Long) : UpdateCheckResult
    data object NotConfigured : UpdateCheckResult
    data class Error(val message: String) : UpdateCheckResult
}

sealed interface UpdateDownloadResult {
    data class Ready(val apk: File, val contentUri: android.net.Uri) : UpdateDownloadResult
    data class Error(val message: String) : UpdateDownloadResult
}
