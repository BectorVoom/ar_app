package com.example.arspatialpinning.domain.usecase

import android.graphics.Bitmap
import android.net.Uri
import com.example.arspatialpinning.domain.model.SelectedImage
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
    fun `createPlacedState uses default height and keeps aspect ratio`() {
        val bitmap = Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888)
        val selectedImage = SelectedImage(
            uri = Uri.parse("content://test/image.png"),
            bitmap = bitmap
        )

        val placed = useCase.createPlacedState(
            anchorId = "anchor-1",
            selectedImage = selectedImage
        )

        assertEquals(0.30f, placed.heightMeters, 0.0001f)
        assertEquals(0.60f, placed.widthMeters, 0.0001f)
        assertEquals(1f, placed.transform.scale, 0.0001f)
    }
}
