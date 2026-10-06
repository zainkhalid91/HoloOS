package com.zain.holoos.geo

import kotlin.io.encoding.Base64
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin

data class WorldCity(
    val name: String,
    val country: String,
    val lat: Float,
    val lon: Float,
    val population: Long,
    val capital: Boolean,
) {
    /** Unit-sphere position, precomputed so per-frame work is a rotation only. */
    val x = cos(lat.rad) * sin(lon.rad)
    val y = -sin(lat.rad)
    val z = cos(lat.rad) * cos(lon.rad)
}

val Float.rad: Float get() = this * (PI.toFloat() / 180f)

fun greatCircleKm(aLat: Float, aLon: Float, bLat: Float, bLon: Float): Int {
    val c = (sin(aLat.rad) * sin(bLat.rad) + cos(aLat.rad) * cos(bLat.rad) * cos((bLon - aLon).rad)).coerceIn(-1f, 1f)
    return (acos(c) * 6371f).toInt()
}

/**
 * Decoded coastlines as unit-sphere vertices. `xyz` holds 3 floats per point and
 * `starts[i]..starts[i+1]` is polyline i. Format of the source: per polyline a varint
 * count, then zigzag-varint deltas of lon/lat in hundredths of a degree.
 */
object Coastlines {
    val xyz: FloatArray
    val starts: IntArray

    init {
        val bytes = Base64.decode(WorldData.coastBase64)
        val pts = ArrayList<Float>(32000)
        val st = ArrayList<Int>(1500)
        var i = 0
        fun varint(): Int {
            var shift = 0; var result = 0
            while (true) {
                val b = bytes[i++].toInt() and 0xFF
                result = result or ((b and 0x7F) shl shift)
                if (b < 0x80) return result
                shift += 7
            }
        }
        fun unzz(n: Int) = (n ushr 1) xor -(n and 1)
        while (i < bytes.size) {
            val n = varint()
            st += pts.size / 3
            var x = 0; var y = 0
            repeat(n) {
                x += unzz(varint()); y += unzz(varint())
                val lon = (x / 100f).rad; val lat = (y / 100f).rad
                pts += cos(lat) * sin(lon); pts += -sin(lat); pts += cos(lat) * cos(lon)
            }
        }
        st += pts.size / 3
        xyz = pts.toFloatArray()
        starts = st.toIntArray()
    }

    val pointCount get() = xyz.size / 3
}

val worldCities: List<WorldCity> get() = WorldData.cities
