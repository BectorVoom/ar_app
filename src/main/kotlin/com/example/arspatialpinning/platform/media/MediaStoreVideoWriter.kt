package com.example.arspatialpinning.platform.media

import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MediaStoreVideoWriter(
    private val contentResolver: ContentResolver,
    private val nowProvider: () -> Date = { Date() }
) {

    data class PendingOutput(
        val uri: Uri,
        val fileDescriptor: ParcelFileDescriptor
    )

    fun createPendingOutput(): PendingOutput {
        val fileName = generateFileName(nowProvider())
        val contentValues = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/ARSpatialPinning")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }

        val uri = checkNotNull(
            contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues)
        ) { "Failed to insert MediaStore item for recording." }

        val pfd = checkNotNull(contentResolver.openFileDescriptor(uri, "rw")) {
            "Failed to open file descriptor for MediaStore recording output."
        }

        return PendingOutput(uri = uri, fileDescriptor = pfd)
    }

    fun finalizeOutput(uri: Uri) {
        val finalizeValues = ContentValues().apply {
            put(MediaStore.Video.Media.IS_PENDING, 0)
        }
        contentResolver.update(uri, finalizeValues, null, null)
    }

    fun deleteOutput(uri: Uri) {
        contentResolver.delete(uri, null, null)
    }

    fun generateFileName(date: Date): String {
        return "ar_recording_${FILE_NAME_FORMAT.format(date)}.mp4"
    }

    private companion object {
        val FILE_NAME_FORMAT = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
    }
}
