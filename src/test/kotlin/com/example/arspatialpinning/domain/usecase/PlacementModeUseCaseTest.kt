package com.example.arspatialpinning.domain.usecase

import com.example.arspatialpinning.domain.model.PlacementMode
import org.junit.Assert.assertEquals
import org.junit.Test

class PlacementModeUseCaseTest {

    private val deleteImageUseCase = DeleteImageUseCase()
    private val enterRepositionModeUseCase = EnterRepositionModeUseCase()
    private val confirmRepositionUseCase = ConfirmRepositionUseCase()

    @Test
    fun `delete always returns waiting for placement`() {
        assertEquals(PlacementMode.WaitingForPlacement, deleteImageUseCase())
    }

    @Test
    fun `enter reposition mode only when image is placed`() {
        assertEquals(
            PlacementMode.Repositioning,
            enterRepositionModeUseCase(hasPlacedImage = true)
        )
        assertEquals(
            PlacementMode.WaitingForPlacement,
            enterRepositionModeUseCase(hasPlacedImage = false)
        )
    }

    @Test
    fun `confirm reposition returns placed when placed image still exists`() {
        assertEquals(
            PlacementMode.Placed,
            confirmRepositionUseCase(hasPlacedImage = true, hasSelectedImage = true)
        )
    }
}
