package com.zain.holoos.tracking

// Phone camera tracking comes next (MediaPipe Android on the GPU, or the phone streaming
// landmarks to the PC). Until then Android runs on touch only.
actual fun startTracker(): TrackerLink? = null
actual fun loadSetting(key: String): String? = null
actual fun saveSetting(key: String, value: String) {}
