package com.example.arspatialpinning.domain.usecase

import com.example.arspatialpinning.domain.model.PlacedImageState
import com.example.arspatialpinning.domain.model.PlacementMode
import com.example.arspatialpinning.domain.model.SelectedImage

class ReplaceImageUseCase {
    operator fun invoke(newImage: SelectedImage): ReplaceImageResult {
        return ReplaceImageResult(
            selectedImage = newImage,
            placedImage = null,
            placementMode = PlacementMode.WaitingForPlacement
        )
    }
}

data class ReplaceImageResult(
    val selectedImage: SelectedImage,
    val placedImage: PlacedImageState?,
    val placementMode: PlacementMode
)
