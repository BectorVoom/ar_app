package com.example.arspatialpinning.domain.usecase

import com.example.arspatialpinning.domain.model.PlacementMode

class DeleteImageUseCase {
    operator fun invoke(): PlacementMode = PlacementMode.WaitingForPlacement
}
