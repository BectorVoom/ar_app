package com.example.arspatialpinning.platform.media

import android.app.Application
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Date

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MediaStoreVideoWriterTest {

    @Test
    fun `generateFileName uses required pattern`() {
        val app = RuntimeEnvironment.getApplication() as Application
        val writer = MediaStoreVideoWriter(app.contentResolver)

        val fileName = writer.generateFileName(Date(0L))

        assertTrue(fileName.startsWith("ar_recording_"))
        assertTrue(fileName.endsWith(".mp4"))
        assertTrue(fileName.matches(Regex("ar_recording_\\d{8}_\\d{6}\\.mp4")))
    }
}
