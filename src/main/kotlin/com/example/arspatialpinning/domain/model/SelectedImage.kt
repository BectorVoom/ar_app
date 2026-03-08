package com.example.arspatialpinning.domain.model

import android.graphics.Bitmap
import android.net.Uri

data class SelectedImage(
    val uri: Uri,
    val bitmap: Bitmap
) {
    val widthPx: Int = bitmap.width
    val heightPx: Int = bitmap.height
    val aspectRatio: Float = if (heightPx == 0) 1f else widthPx.toFloat() / heightPx.toFloat()
}
