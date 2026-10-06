package com.zain.holoos.geo

import android.graphics.BitmapShader
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.asAndroidBitmap

@RequiresApi(33)
private class AgslEarth(day: ImageBitmap, night: ImageBitmap) : EarthRenderer {
    private val shader = RuntimeShader(EARTH_SHADER).apply {
        setInputShader("dayTex", tex(day))
        setInputShader("nightTex", tex(night))
        setFloatUniform("texSize", day.width.toFloat(), day.height.toFloat())
    }

    private fun tex(b: ImageBitmap) = BitmapShader(b.asAndroidBitmap(), Shader.TileMode.REPEAT, Shader.TileMode.CLAMP)
        .apply { filterMode = BitmapShader.FILTER_MODE_LINEAR }

    override fun brush(cx: Float, cy: Float, r: Float, yaw: Float, pitch: Float, sun: FloatArray, sunView: FloatArray): Brush {
        shader.setFloatUniform("center", cx, cy)
        shader.setFloatUniform("radius", r)
        shader.setFloatUniform("rot", yaw, pitch)
        shader.setFloatUniform("sun", sun[0], sun[1], sun[2])
        shader.setFloatUniform("sunView", sunView[0], sunView[1], sunView[2])
        return ShaderBrush(shader)
    }
}

actual fun createEarthRenderer(day: ImageBitmap, night: ImageBitmap): EarthRenderer? =
    if (Build.VERSION.SDK_INT >= 33) runCatching { AgslEarth(day, night) }.getOrNull() else null

actual fun epochMillis(): Long = System.currentTimeMillis()
