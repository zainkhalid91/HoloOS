package com.zain.holoos.geo

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.asComposeShader
import androidx.compose.ui.graphics.asSkiaBitmap
import org.jetbrains.skia.FilterTileMode
import org.jetbrains.skia.Image
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder
import org.jetbrains.skia.SamplingMode

private class SkiaEarth(day: ImageBitmap, night: ImageBitmap) : EarthRenderer {
    private val builder = RuntimeShaderBuilder(RuntimeEffect.makeForShader(EARTH_SHADER)).apply {
        child("dayTex", Image.makeFromBitmap(day.asSkiaBitmap()).makeShader(FilterTileMode.REPEAT, FilterTileMode.CLAMP, SamplingMode.LINEAR, null))
        child("nightTex", Image.makeFromBitmap(night.asSkiaBitmap()).makeShader(FilterTileMode.REPEAT, FilterTileMode.CLAMP, SamplingMode.LINEAR, null))
        uniform("texSize", day.width.toFloat(), day.height.toFloat())
    }

    override fun brush(cx: Float, cy: Float, r: Float, m: FloatArray, sun: FloatArray, sunView: FloatArray): Brush {
        builder.uniform("center", cx, cy)
        builder.uniform("radius", r)
        builder.uniform("r0", m[0], m[1], m[2])
        builder.uniform("r1", m[3], m[4], m[5])
        builder.uniform("r2", m[6], m[7], m[8])
        builder.uniform("sun", sun[0], sun[1], sun[2])
        builder.uniform("sunView", sunView[0], sunView[1], sunView[2])
        return ShaderBrush(builder.makeShader().asComposeShader())
    }
}

actual fun createEarthRenderer(day: ImageBitmap, night: ImageBitmap): EarthRenderer? =
    runCatching { SkiaEarth(day, night) }.onFailure { it.printStackTrace() }.getOrNull()

actual fun epochMillis(): Long = System.currentTimeMillis()
