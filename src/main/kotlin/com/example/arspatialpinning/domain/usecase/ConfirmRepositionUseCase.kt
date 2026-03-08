package com.example.arspatialpinning.domain.usecase

import com.example.arspatialpinning.domain.model.PlacementMode

class ConfirmRepositionUseCase {
    operator fun invoke(hasPlacedImage: Boolean, hasSelectedImage: Boolean): PlacementMode {
        return when {
            hasPlacedImage -> PlacementMode.Placed
            hasSelectedImage -> PlacementMode.WaitingForPlacement
            else -> PlacementMode.WaitingForPlacement
        }
    }
}
