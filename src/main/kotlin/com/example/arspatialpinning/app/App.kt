package com.example.arspatialpinning.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import com.example.arspatialpinning.app.navigation.AppNavHost

@Composable
fun ArSpatialPinningApp(
    appContainer: AppContainer
) {
    MaterialTheme {
        AppNavHost(appContainer = appContainer)
    }
}
