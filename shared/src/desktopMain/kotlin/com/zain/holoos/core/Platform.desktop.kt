package com.zain.holoos.core

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import java.awt.Point
import java.awt.Toolkit
import java.awt.image.BufferedImage
import java.time.LocalTime
import java.time.format.DateTimeFormatter

private val blankCursor by lazy {
    PointerIcon(Toolkit.getDefaultToolkit().createCustomCursor(BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB), Point(0, 0), "blank"))
}

actual fun Modifier.hiddenCursor(): Modifier = pointerHoverIcon(blankCursor, overrideDescendants = true)

private val fmt = DateTimeFormatter.ofPattern("HH:mm:ss")
actual fun clockText(): String = LocalTime.now().format(fmt)
