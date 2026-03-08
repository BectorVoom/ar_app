package com.example.arspatialpinning.feature.ar

import android.graphics.Bitmap
import android.net.Uri
import com.example.arspatialpinning.domain.model.PlacementMode
import com.example.arspatialpinning.domain.model.SelectedImage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ArUiStateTest {

    @Test
    fun `canPlace requires ar ready image prepared selected image waiting mode and stabilized hit`() {
        val selected = SelectedImage(
            uri = Uri.parse("content://test/image.png"),
            bitmap = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
        )

        val validState = ArUiState(
            selectedImage = selected,
            placementMode = PlacementMode.WaitingForPlacement,
            isArReady = true,
            isImagePrepared = true,
            reticle = ReticleUiState(
                hasValidHit = true,
                stabilizationFrames = 3,
                isStabilized = true
            )
        )
        assertTrue(validState.canPlace)

        assertFalse(validState.copy(isArReady = false).canPlace)
        assertFalse(validState.copy(isImagePrepared = false).canPlace)
        assertFalse(validState.copy(selectedImage = null).canPlace)
        assertFalse(validState.copy(placementMode = PlacementMode.Placed).canPlace)
        assertFalse(
            validState.copy(
                reticle = validState.reticle.copy(isStabilized = false)
            ).canPlace
        )
    }
}
