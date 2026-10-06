package com.zain.holoos.core

import androidx.compose.ui.Modifier
import java.time.LocalTime
import java.time.format.DateTimeFormatter

actual fun Modifier.hiddenCursor(): Modifier = this

private val fmt = DateTimeFormatter.ofPattern("HH:mm:ss")
actual fun clockText(): String = LocalTime.now().format(fmt)
