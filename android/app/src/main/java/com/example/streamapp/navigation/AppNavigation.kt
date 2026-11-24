package com.example.streamapp.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.streamapp.presentation.home.HomeScreen
import com.example.streamapp.receiver.ReceiverScreen
import com.example.streamapp.sender.SenderScreen

@Composable
fun AppNavigation(
    navController: NavHostController = rememberNavController()
) {
    NavHost(
        navController = navController,
        startDestination = "home"
    ) {
        composable("home") {
            HomeScreen(
                onTransmitterSelected = { navController.navigate("sender") },
                onReceiverSelected = { navController.navigate("receiver") }
            )
        }
        composable("sender") {
            SenderScreen(onBackPressed = { navController.popBackStack() })
        }
        composable("receiver") {
            ReceiverScreen(onBackPressed = { navController.popBackStack() })
        }
    }
}
