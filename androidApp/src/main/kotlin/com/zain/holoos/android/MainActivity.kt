package com.zain.holoos.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.zain.holoos.core.HoloOsApp
import com.zain.holoos.core.rememberHoloOsState

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val state = rememberHoloOsState()
            BackHandler(enabled = state.navigator.canGoBack) { state.navigator.back() }
            HoloOsApp(state)
        }
    }
}
