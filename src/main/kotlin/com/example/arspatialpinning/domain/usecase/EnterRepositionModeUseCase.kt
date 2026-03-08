package com.example.arspatialpinning.domain.usecase

import com.example.arspatialpinning.domain.model.PlacementMode

class EnterRepositionModeUseCase {
    operator fun invoke(hasPlacedImage: Boolean): PlacementMode {
        return if (hasPlacedImage) {
            PlacementMode.Repositioning
        } else {
            PlacementMode.WaitingForPlacement
        }
    }
}
