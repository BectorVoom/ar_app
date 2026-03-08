package com.example.arspatialpinning.platform.ar

import android.graphics.Bitmap
import com.example.arspatialpinning.domain.model.SelectedImage

class TextureLoader {
    fun bitmapFrom(image: SelectedImage): Bitmap = image.bitmap
}
