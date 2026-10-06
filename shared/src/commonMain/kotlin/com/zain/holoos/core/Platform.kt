package com.zain.holoos.core

import androidx.compose.ui.Modifier

/** Hides the OS pointer so only the holographic reticle is visible (desktop). */
expect fun Modifier.hiddenCursor(): Modifier

/** Whether to start camera hand tracking (desktop: unless HOLOOS_TRACKER=off). */
expect fun trackingEnabled(): Boolean

/** Local wall-clock time as HH:mm:ss. */
expect fun clockText(): String
