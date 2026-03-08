package com.example.arspatialpinning.domain.model

import android.net.Uri

data class SelectedImage(
    val uri: Uri,
    val displayName: String?,
    val mimeType: String,
    val widthPx: Int,
    val heightPx: Int,
    val format: ImageFormat,
    val selectionRevision: Long
) {
    val aspectRatio: Float = if (heightPx == 0) 1f else widthPx.toFloat() / heightPx.toFloat()
}

enum class ImageFormat {
    Png,
    Jpeg
}
