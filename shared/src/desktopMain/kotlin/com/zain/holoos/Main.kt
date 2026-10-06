package com.zain.holoos

import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.zain.holoos.core.HoloOsApp

fun main() = application {
    val windowed = System.getProperty("holoos.windowed") == "true" || System.getenv("HOLOOS_WINDOWED") == "1"
    val state = rememberWindowState(
        placement = if (windowed) WindowPlacement.Floating else WindowPlacement.Fullscreen,
        width = 1600.dp, height = 900.dp,
    )
    Window(
        onCloseRequest = ::exitApplication,
        title = "HoloOS",
        state = state,
        undecorated = !windowed,
        onKeyEvent = { e ->
            if (e.key == androidx.compose.ui.input.key.Key.F4 && e.isAltPressed) { exitApplication(); true } else false
        },
    ) {
        HoloOsApp()
    }
}
