package com.example.arspatialpinning.domain.usecase

import com.example.arspatialpinning.domain.model.PlacedImageState
import com.example.arspatialpinning.domain.model.PlacementTransform
import com.example.arspatialpinning.domain.model.SelectedImage
import kotlin.math.pow
import kotlin.math.round

class PlaceImageUseCase {

    fun createPlacedState(
        anchorId: String,
        selectedImage: SelectedImage,
        rotationYDegrees: Float = 0f
    ): PlacedImageState {
        val heightMeters = DEFAULT_HEIGHT_METERS
        val widthMeters = (heightMeters * selectedImage.aspectRatio).roundTo(4)
        return PlacedImageState(
            anchorId = anchorId,
            widthMeters = widthMeters,
            heightMeters = heightMeters,
            transform = PlacementTransform(scale = 1f, rotationYDegrees = rotationYDegrees)
        )
    }

    companion object {
        const val DEFAULT_HEIGHT_METERS: Float = 0.30f
    }
}

private fun Float.roundTo(decimals: Int): Float {
    val factor = 10f.pow(decimals)
    return round(this * factor) / factor
}
