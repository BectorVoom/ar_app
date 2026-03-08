package com.example.arspatialpinning.domain.usecase

import android.net.Uri
import com.example.arspatialpinning.domain.model.ImageFormat
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
        val selected = SelectedImage(
            uri = Uri.parse("content://test/new.png"),
            displayName = "new.png",
            mimeType = "image/png",
            widthPx = 100,
            heightPx = 100,
            format = ImageFormat.Png,
            selectionRevision = 42L
        )

        val result = useCase(selected)

        assertEquals(selected, result.selectedImage)
        assertNull(result.placedImage)
        assertEquals(PlacementMode.WaitingForPlacement, result.placementMode)
    }
}
