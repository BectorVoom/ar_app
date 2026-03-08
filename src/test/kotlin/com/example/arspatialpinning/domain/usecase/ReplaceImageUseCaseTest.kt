package com.example.arspatialpinning.domain.usecase

import android.graphics.Bitmap
import android.net.Uri
import com.example.arspatialpinning.domain.model.PlacementMode
import com.example.arspatialpinning.domain.model.SelectedImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ReplaceImageUseCaseTest {

    private val useCase = ReplaceImageUseCase()

    @Test
    fun `replace resets placed image and returns waiting mode`() {
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        val selected = SelectedImage(
            uri = Uri.parse("content://test/new.png"),
            bitmap = bitmap
        )

        val result = useCase(selected)

        assertEquals(selected, result.selectedImage)
        assertNull(result.placedImage)
        assertEquals(PlacementMode.WaitingForPlacement, result.placementMode)
    }
}
