package com.example.arspatialpinning.feature.ar.component

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.pointerInteropFilter

@Composable
fun TransformGestureOverlay(
    enabled: Boolean,
    onTransform: (scaleFactor: Float, rotationDegreesDelta: Float) -> Unit
) {
    // Keep overlay always present in z-order; only consume two-finger transforms when enabled.
    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInteropFilter { false }
            .pointerInput(enabled) {
                if (!enabled) {
                    return@pointerInput
                }
                awaitEachGesture {
                    var transformStarted = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressedCount = event.changes.count { it.pressed }
                        if (pressedCount < 2) {
                            if (transformStarted && event.changes.none { it.pressed }) {
                                break
                            }
                            if (!transformStarted && event.changes.none { it.pressed }) {
                                break
                            }
                            continue
                        }

                        transformStarted = true
                        val zoom = event.calculateZoom()
                        val rotation = event.calculateRotation()
                        if (!zoom.isNaN() && zoom.isFinite() && !rotation.isNaN() && rotation.isFinite()) {
                            onTransform(zoom, rotation)
                        }
                        event.changes.forEach { it.consume() }
                    }
                }
            }
    )
}
