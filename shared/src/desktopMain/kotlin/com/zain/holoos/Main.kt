package com.zain.holoos

import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.zain.holoos.core.CalculatorScreen
import com.zain.holoos.core.GlobeScreen
import com.zain.holoos.core.HoloOsApp
import com.zain.holoos.core.rememberHoloOsState
import kotlinx.coroutines.delay

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
        val holo = rememberHoloOsState()
        holo.onExit = ::exitApplication
        // Dev switch: HOLOOS_START=globe|calculator opens a screen on launch.
        LaunchedEffect(Unit) {
            val start = when (System.getenv("HOLOOS_START")) {
                "globe" -> GlobeScreen
                "calculator" -> CalculatorScreen
                else -> null
            } ?: return@LaunchedEffect
            delay(900)
            val c = Offset(holo.size.width / 2, holo.size.height / 2)
            holo.navigator.push(start, Rect(c.x - 80f, c.y - 80f, c.x + 80f, c.y + 80f))
        }
        HoloOsApp(holo)
    }
}
