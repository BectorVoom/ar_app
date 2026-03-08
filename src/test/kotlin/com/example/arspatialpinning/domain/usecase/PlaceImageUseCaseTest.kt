package com.example.arspatialpinning.domain.usecase

import com.example.arspatialpinning.domain.model.PreparedRenderAsset
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PlaceImageUseCaseTest {

    private val useCase = PlaceImageUseCase()

    @Test
    fun `createPlacedState uses default height and keeps prepared asset aspect ratio`() {
        val preparedAsset = PreparedRenderAsset(
            assetHandleId = "asset-1",
            widthPx = 400,
            heightPx = 200,
            aspectRatio = 2f,
            selectionRevision = 7L
        )

        val placed = useCase.createPlacedState(
            anchorId = "anchor-1",
            preparedAsset = preparedAsset
        )

        assertEquals(0.30f, placed.heightMeters, 0.0001f)
        assertEquals(0.60f, placed.widthMeters, 0.0001f)
        assertEquals(1f, placed.transform.scale, 0.0001f)
    }
}
