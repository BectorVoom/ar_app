package com.example.arspatialpinning.platform.ar

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class TrackableIdentityTest {

    @Test
    fun `stable trackable id does not depend on wrapper instance identity`() {
        val firstWrapper = FakeTrackable(logicalId = 42)
        val secondWrapper = FakeTrackable(logicalId = 42)

        val firstIdentity = System.identityHashCode(firstWrapper).toString()
        val secondIdentity = System.identityHashCode(secondWrapper).toString()
        assertNotEquals(firstIdentity, secondIdentity)

        val firstStable = stableTrackableId(firstWrapper)
        val secondStable = stableTrackableId(secondWrapper)
        assertEquals(firstStable, secondStable)
    }

    private data class FakeTrackable(val logicalId: Int)

    @Test
    fun `approx translation accepts small frame jitter`() {
        val first = floatArrayOf(1.0f, 0.5f, -2.0f)
        val second = floatArrayOf(1.04f, 0.53f, -1.95f)

        val result = isApproxTranslation(first, second, epsilonMeters = 0.12f)

        assertEquals(true, result)
    }

    @Test
    fun `approx translation rejects large movement`() {
        val first = floatArrayOf(0f, 0f, 0f)
        val second = floatArrayOf(0.25f, 0.02f, 0.02f)

        val result = isApproxTranslation(first, second, epsilonMeters = 0.12f)

        assertEquals(false, result)
    }
}
