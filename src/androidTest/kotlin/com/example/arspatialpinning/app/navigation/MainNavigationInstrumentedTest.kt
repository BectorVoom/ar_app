package com.example.arspatialpinning.app.navigation

import android.content.Context
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.arspatialpinning.app.AppContainer
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainNavigationInstrumentedTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun startRoute_navigatesToArRoute() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        composeRule.setContent {
            AppNavHost(
                appContainer = AppContainer(context),
                arRouteContent = {
                    Text("AR Route Stub")
                }
            )
        }

        composeRule.onNodeWithText("AR Spatial Pinning").assertIsDisplayed()
        composeRule.onNodeWithText("Start AR").performClick()

        composeRule.onNodeWithText("AR Route Stub").assertIsDisplayed()
    }
}
