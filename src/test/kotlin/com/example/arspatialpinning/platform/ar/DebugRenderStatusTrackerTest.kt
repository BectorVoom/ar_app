package com.example.arspatialpinning.platform.ar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugRenderStatusTrackerTest {

    @Test
    fun `prepare registers asset handle and preview identity`() {
        val tracker = DebugRenderStatusTracker()

        tracker.onPreparedAssetRegistered("asset-1")
        tracker.onPreviewNodePrepared(assetHandleId = "asset-1", attached = true)
        val status = tracker.current()

        assertEquals("asset-1", status.preparedAssetHandleId)
        assertEquals("asset-1", status.previewAssetHandleId)
        assertTrue(status.previewNodeExists)
        assertTrue(status.previewNodeAttached)
        assertFalse(status.previewNodeVisible)
        assertEquals(0L, status.previewPoseUpdateFrameCount)
    }

    @Test
    fun `stable preview updates increase frame counter for matching handle only`() {
        val tracker = DebugRenderStatusTracker()
        tracker.onPreparedAssetRegistered("asset-1")
        tracker.onPreviewNodePrepared(assetHandleId = "asset-1", attached = true)

        tracker.onPreviewPoseUpdated(assetHandleId = "asset-1", attached = true, visible = true)
        tracker.onPreviewPoseUpdated(assetHandleId = "asset-1", attached = true, visible = true)
        val first = tracker.current()
        assertEquals(2L, first.previewPoseUpdateFrameCount)
        assertEquals("asset-1", first.previewPoseUpdatedForAssetHandleId)

        tracker.onPreviewPoseUpdated(assetHandleId = "asset-2", attached = true, visible = true)
        val second = tracker.current()
        assertEquals(1L, second.previewPoseUpdateFrameCount)
        assertEquals("asset-2", second.previewPoseUpdatedForAssetHandleId)
    }

    @Test
    fun `placing hides preview and marks placed node attached for same handle`() {
        val tracker = DebugRenderStatusTracker()
        tracker.onPreparedAssetRegistered("asset-1")
        tracker.onPreviewNodePrepared(assetHandleId = "asset-1", attached = true)
        tracker.onPreviewPoseUpdated(assetHandleId = "asset-1", attached = true, visible = true)

        tracker.onPlaced(assetHandleId = "asset-1", placedAttached = true)
        val status = tracker.current()

        assertFalse(status.previewNodeVisible)
        assertFalse(status.previewNodeExists)
        assertTrue(status.placedNodeExists)
        assertTrue(status.placedNodeAttached)
        assertEquals("asset-1", status.placedAssetHandleId)
    }

    @Test
    fun `delete clears placed node identity`() {
        val tracker = DebugRenderStatusTracker()
        tracker.onPreparedAssetRegistered("asset-1")
        tracker.onPlaced(assetHandleId = "asset-1", placedAttached = true)

        tracker.onDeleted()
        val status = tracker.current()

        assertFalse(status.placedNodeExists)
        assertFalse(status.placedNodeAttached)
        assertEquals(null, status.placedAssetHandleId)
    }

    @Test
    fun `prepared asset invalidation resets debug state and drops stale handles`() {
        val tracker = DebugRenderStatusTracker()
        tracker.onPreparedAssetRegistered("asset-1")
        tracker.onPreviewNodePrepared(assetHandleId = "asset-1", attached = true)
        tracker.onPreviewPoseUpdated(assetHandleId = "asset-1", attached = true, visible = true)
        tracker.onPlaced(assetHandleId = "asset-1", placedAttached = true)

        tracker.onPreparedAssetInvalidated()
        val status = tracker.current()

        assertFalse(status.previewNodeExists)
        assertFalse(status.previewNodeAttached)
        assertFalse(status.previewNodeVisible)
        assertFalse(status.placedNodeExists)
        assertFalse(status.placedNodeAttached)
        assertEquals(null, status.preparedAssetHandleId)
        assertEquals(null, status.previewAssetHandleId)
        assertEquals(null, status.placedAssetHandleId)
        assertEquals(0L, status.previewPoseUpdateFrameCount)
    }
}
