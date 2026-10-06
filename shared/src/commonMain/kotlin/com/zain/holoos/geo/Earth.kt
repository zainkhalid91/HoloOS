package com.zain.holoos.geo

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageBitmap
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Renders the photographic Earth with a per-pixel GPU shader. */
interface EarthRenderer {
    /**
     * @param sun world-space unit vector to the sun; [sunView] the same in screen space
     * (used for the ocean glint). Draw the brush into a circle of radius `r * 1.15`.
     */
    fun brush(cx: Float, cy: Float, r: Float, m: FloatArray, sun: FloatArray, sunView: FloatArray): Brush
}

/** Null when the platform can't run runtime shaders (Android < 13): fall back to HOLO. */
expect fun createEarthRenderer(day: ImageBitmap, night: ImageBitmap): EarthRenderer?

expect fun epochMillis(): Long

/** World-space direction to the sun right now: the day/night terminator is live. */
fun sunDirection(millis: Long = epochMillis()): FloatArray {
    val days = millis / 86_400_000.0
    val utcHours = (millis % 86_400_000L) / 3_600_000.0
    val dayOfYear = (days - 10_957.0) % 365.2422
    val decl = (23.44 * sin(2 * PI * (dayOfYear - 79.0) / 365.2422)).toFloat().rad
    val subLon = ((12.0 - utcHours) * 15.0).toFloat().rad
    return floatArrayOf(cos(decl) * sin(subLon), -sin(decl), cos(decl) * cos(subLon))
}

/**
 * Ray-casts a unit sphere per pixel, inverts the globe rotation to get lat/lon, samples
 * NASA Blue Marble (day) and Black Marble (night lights), lights it with the real sun,
 * adds an ocean glint and a Fresnel atmosphere. The same source compiles as Skia SkSL
 * (desktop) and AGSL (Android 13+).
 */
const val EARTH_SHADER = """
uniform shader dayTex;
uniform shader nightTex;
uniform float2 center;
uniform float radius;
uniform float3 r0;
uniform float3 r1;
uniform float3 r2;
uniform float3 sun;
uniform float3 sunView;
uniform float2 texSize;

half4 main(float2 fc) {
    float PI = 3.14159265;
    float2 d = (fc - center) / radius;
    float r2 = dot(d, d);
    if (r2 > 1.0) {
        float g = clamp(1.0 - (sqrt(r2) - 1.0) / 0.13, 0.0, 1.0);
        g = g * g * g * 0.6;
        return half4(half3(0.30, 0.62, 1.0) * g, g);
    }
    float z = sqrt(1.0 - r2);
    // Screen normal back to world: the matrix is orthonormal, so the inverse is its transpose.
    float3 w = r0 * d.x + r1 * d.y + r2 * z;
    float wx = w.x; float wy = w.y; float wz = w.z;
    float lat = asin(clamp(-wy, -1.0, 1.0));
    float lon = atan(wx, wz);
    float2 uv = float2((lon + PI) / (2.0 * PI) * texSize.x, (PI * 0.5 - lat) / PI * texSize.y);
    float3 day = float3(dayTex.eval(uv).rgb);
    float3 night = float3(nightTex.eval(uv).rgb);

    float l = dot(float3(wx, wy, wz), sun);
    float dayAmt = smoothstep(-0.10, 0.22, l);
    float diffuse = clamp(l, 0.0, 1.0) * 0.9 + 0.1;
    float3 col = mix(night * float3(1.6, 1.35, 0.9) + day * 0.03, day * diffuse, dayAmt);

    float ocean = clamp((day.b - max(day.r, day.g) * 0.9) * 6.0, 0.0, 1.0);
    float3 n = float3(d.x, d.y, z);
    // Half vector between sun and viewer. When the sun is right behind the globe it is
    // ~zero and normalize() returned NaN: a black hole in the middle of the Earth.
    float3 hv = sunView + float3(0.0, 0.0, 1.0);
    float hl = length(hv);
    float spec = 0.0;
    if (hl > 0.001) spec = pow(max(dot(n, hv / hl), 0.0), 60.0) * ocean * dayAmt;
    col += float3(1.0, 0.95, 0.85) * spec * 0.7;

    float rim = pow(1.0 - z, 2.5);
    col = mix(col, float3(0.35, 0.65, 1.0), rim * (0.35 + 0.45 * dayAmt));
    return half4(half3(col), 1.0);
}
"""
