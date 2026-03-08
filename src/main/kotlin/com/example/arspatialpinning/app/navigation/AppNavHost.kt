package com.example.arspatialpinning.app.navigation

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.arspatialpinning.app.AppContainer
import com.example.arspatialpinning.feature.ar.ArScreen
import com.example.arspatialpinning.feature.ar.ArViewModel
import com.example.arspatialpinning.feature.start.StartScreen

@Composable
fun AppNavHost(
    appContainer: AppContainer,
    arRouteContent: @Composable ((onNavigateBack: () -> Unit) -> Unit) = { onNavigateBack ->
        val viewModel: ArViewModel = viewModel(
            factory = ArViewModel.provideFactory(appContainer)
        )
        ArScreen(
            viewModel = viewModel,
            onNavigateBack = onNavigateBack
        )
    }
) {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = Routes.START
    ) {
        composable(Routes.START) {
            StartScreen(
                onStartAr = {
                    navController.navigate(Routes.AR)
                }
            )
        }

        composable(Routes.AR) {
            arRouteContent {
                navController.popBackStack()
            }
        }
    }
}
