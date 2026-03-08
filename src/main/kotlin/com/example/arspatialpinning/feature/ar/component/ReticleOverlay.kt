package com.example.arspatialpinning.feature.ar.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import com.example.arspatialpinning.feature.ar.ReticleUiState

@Composable
fun ReticleOverlay(
    reticleState: ReticleUiState
) {
    val color = when {
        reticleState.isStabilized -> Color(0xFF22C55E)
        reticleState.hasValidHit -> Color(0xFFF59E0B)
        else -> Color(0xFFEF4444)
    }

    Canvas(modifier = Modifier.fillMaxSize()) {
        val center = Offset(size.width / 2f, size.height / 2f)
        drawCircle(
            color = color,
            radius = 26f,
            center = center,
            style = Stroke(width = 4f)
        )
        drawLine(
            color = color,
            start = Offset(center.x - 40f, center.y),
            end = Offset(center.x + 40f, center.y),
            strokeWidth = 3f
        )
        drawLine(
            color = color,
            start = Offset(center.x, center.y - 40f),
            end = Offset(center.x, center.y + 40f),
            strokeWidth = 3f
        )
    }
}
