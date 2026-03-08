package com.example.arspatialpinning.app.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.arspatialpinning.app.AppContainer
import com.example.arspatialpinning.domain.model.RecordingState
import com.example.arspatialpinning.feature.ar.ArScreen
import com.example.arspatialpinning.feature.ar.ArViewModel
import com.example.arspatialpinning.feature.start.StartScreen
import com.example.arspatialpinning.platform.media.SharedRecordingUiState

@Composable
fun AppNavHost(
    appContainer: AppContainer,
    sharedRecordingUiState: SharedRecordingUiState,
    onRecordClick: () -> Unit,
    onStopRecordClick: () -> Unit,
    onDownloadRecordingClick: () -> Unit,
    arRouteContent: @Composable ((onNavigateBack: () -> Unit, viewModel: ArViewModel) -> Unit) = { onNavigateBack, viewModel ->
        ArScreen(
            viewModel = viewModel,
            onNavigateBack = onNavigateBack
        )
    }
) {
    val navController = rememberNavController()
    val arViewModel: ArViewModel = viewModel(
        factory = ArViewModel.provideFactory(appContainer)
    )
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    BackHandler(
        enabled = currentRoute == Routes.START &&
            sharedRecordingUiState.recordingState !is RecordingState.Idle
    ) {
        onStopRecordClick()
    }

    NavHost(
        navController = navController,
        startDestination = Routes.START
    ) {
        composable(Routes.START) {
            StartScreen(
                recordingUiState = sharedRecordingUiState,
                onStartAr = {
                    navController.navigate(Routes.AR)
                },
                onRecordClick = onRecordClick,
                onStopRecordClick = onStopRecordClick,
                onDownloadRecordingClick = onDownloadRecordingClick
            )
        }

        composable(Routes.AR) {
            arRouteContent(
                {
                    navController.popBackStack()
                },
                arViewModel
            )
        }
    }
}
