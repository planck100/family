package com.familytimemanager.app.ui

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

fun createCameraPhotoUri(context: Context): Uri {
    val directory = File(context.cacheDir, "camera").apply { mkdirs() }
    val photo = File.createTempFile("photo-", ".jpg", directory)
    return FileProvider.getUriForFile(
        context,
        "${context.packageName}.updates",
        photo,
    )
}
