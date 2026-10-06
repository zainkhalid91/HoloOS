package com.zain.holoos.apps

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zain.holoos.core.LocalHolo
import com.zain.holoos.holo.HoloColors
import com.zain.holoos.holo.cutCornerPath
import com.zain.holoos.holo.glowArc
import com.zain.holoos.holo.glowCircle
import com.zain.holoos.holo.glowPath
import com.zain.holoos.holo.holoText
import com.zain.holoos.input.HoloTarget
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * Calculator logic with real operator precedence. Binary operators are stored as
 * display glyphs (+ − × ÷); a leading ASCII '-' on a number means unary minus.
 */
class CalculatorEngine {
    var expression by mutableStateOf("")
        private set
    var display by mutableStateOf("0")
        private set
    private var evaluated = false

    private val ops = setOf('+', '−', '×', '÷')

    fun press(key: String) {
        when (key) {
            "C" -> { expression = ""; display = "0"; evaluated = false; return }
            "⌫" -> { if (evaluated) { press("C"); return }; expression = expression.dropLast(1) }
            "=" -> {
                val r = evaluate(expression) ?: run { display = "ERROR"; return }
                expression = format(r); display = expression; evaluated = true; return
            }
            "±" -> toggleSign()
            "%" -> applyPercent()
            "+", "−", "×", "÷" -> {
                evaluated = false
                if (expression.isEmpty()) { if (key == "−") expression = "-"; return }
                expression = if (expression.last() in ops || expression.last() == '-') expression.dropLast(1) + key else expression + key
            }
            "." -> {
                if (evaluated) { expression = ""; evaluated = false }
                val num = expression.takeLastWhile { it.isDigit() || it == '.' }
                if ('.' !in num) expression += if (num.isEmpty()) "0." else "."
            }
            else -> {
                if (evaluated) { expression = ""; evaluated = false }
                val num = expression.takeLastWhile { it.isDigit() || it == '.' }
                expression = if (num == "0") expression.dropLast(1) + key else expression + key
            }
        }
        display = evaluate(expression)?.let(::format) ?: display
        if (expression.isEmpty()) display = "0"
    }

    private fun lastNumberStart(): Int {
        var i = expression.length
        while (i > 0 && (expression[i - 1].isDigit() || expression[i - 1] == '.')) i--
        return i
    }

    private fun toggleSign() {
        evaluated = false
        val s = lastNumberStart()
        if (s == expression.length) return
        expression = if (s > 0 && expression[s - 1] == '-') expression.removeRange(s - 1, s)
        else expression.substring(0, s) + "-" + expression.substring(s)
    }

    private fun applyPercent() {
        val s = lastNumberStart()
        val n = expression.substring(s).toDoubleOrNull() ?: return
        expression = expression.substring(0, s) + format(n / 100.0)
    }

    companion object {
        fun evaluate(expr: String): Double? {
            if (expr.isEmpty()) return null
            val nums = ArrayList<Double>(); val opers = ArrayList<Char>()
            var i = 0
            var expectNumber = true
            while (i < expr.length) {
                val c = expr[i]
                if (expectNumber) {
                    var j = i
                    if (expr[j] == '-') j++
                    while (j < expr.length && (expr[j].isDigit() || expr[j] == '.')) j++
                    val n = expr.substring(i, j).toDoubleOrNull() ?: return null
                    nums += n; i = j; expectNumber = false
                } else {
                    if (c !in "+−×÷") return null
                    opers += c; i++; expectNumber = true
                }
            }
            if (expectNumber) { if (opers.isNotEmpty()) opers.removeAt(opers.lastIndex) else return null }
            // Collapse × and ÷ first, then + and −.
            var k = 0
            while (k < opers.size) {
                val o = opers[k]
                if (o == '×' || o == '÷') {
                    val b = nums[k + 1]
                    if (o == '÷' && b == 0.0) return null
                    nums[k] = if (o == '×') nums[k] * b else nums[k] / b
                    nums.removeAt(k + 1); opers.removeAt(k)
                } else k++
            }
            var acc = nums[0]
            for (m in opers.indices) acc = if (opers[m] == '+') acc + nums[m + 1] else acc - nums[m + 1]
            return if (acc.isNaN() || acc.isInfinite()) null else acc
        }

        fun format(v: Double): String {
            if (abs(v) < 1e15 && v == v.roundToLong().toDouble()) return v.roundToLong().toString()
            val rounded = (v * 1e9).roundToLong() / 1e9
            return rounded.toString().let { if ('E' in it) it else it.trimEnd('0').trimEnd('.') }
        }
    }
}

private val keys = listOf(
    listOf("C", "±", "%", "÷"),
    listOf("7", "8", "9", "×"),
    listOf("4", "5", "6", "−"),
    listOf("1", "2", "3", "+"),
    listOf("⌫", "0", ".", "="),
)

@Composable
fun CalculatorView() {
    val holo = LocalHolo.current
    val calc = holo.calculator

    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val keySize = min((maxHeight.value - 300f) / 5.6f, (maxWidth.value - 60f) / 4.8f).coerceIn(52f, 96f).dp
        val panelW = keySize * 4 + 14.dp * 3

        Canvas(Modifier.fillMaxSize()) {
            val t = holo.seconds
            val c = Offset(size.width / 2, size.height * 0.56f)
            val r = keySize.toPx() * 3.6f
            glowArc(HoloColors.CyanDim, c, r, t * 6f, 220f, 1f, 0.5f)
            glowArc(HoloColors.Cyan, c, r * 1.04f, -t * 10f, 40f, 4f, 0.35f)
            glowArc(HoloColors.Cyan, c, r * 1.04f, -t * 10f + 180f, 40f, 4f, 0.35f)
        }

        Column(Modifier.padding(top = 70.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.width(panelW).height(110.dp)) {
                Canvas(Modifier.matchParentSize()) {
                    drawPath(cutCornerPath(size, 18f), HoloColors.Cyan, alpha = 0.04f, blendMode = BlendMode.Plus)
                    glowPath(HoloColors.Cyan, cutCornerPath(size, 18f, 1f), 1.2f, 0.8f)
                }
                Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp), horizontalAlignment = Alignment.End) {
                    BasicText(
                        calc.expression.ifEmpty { " " }, style = holoText(13.sp, HoloColors.Muted, glow = 0f, spacing = 1.sp).copy(textAlign = TextAlign.End),
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    BasicText(
                        calc.display,
                        style = holoText(if (calc.display.length > 12) 26.sp else 40.sp, if (calc.display == "ERROR") HoloColors.Alert else HoloColors.Ice, glow = 18f, spacing = 2.sp).copy(textAlign = TextAlign.End),
                        maxLines = 1,
                    )
                }
            }
            for (row in keys) {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    for (k in row) Key(k, keySize) { pressWithSound(holo, k) }
                }
            }
        }
    }
}

/** Digits play a pentatonic scale, so typing a number sounds like a little melody. */
private val pentatonic = floatArrayOf(1f, 1.125f, 1.25f, 1.5f, 1.6875f, 2f, 2.25f, 2.5f, 3f, 3.375f)

fun pressWithSound(holo: com.zain.holoos.core.HoloOsState, k: String) {
    val before = holo.calculator.display
    holo.calculator.press(k)
    when {
        holo.calculator.display == "ERROR" && before != "ERROR" -> holo.sfx(com.zain.holoos.audio.Sfx.Error)
        k.length == 1 && k[0].isDigit() -> holo.sfx(com.zain.holoos.audio.Sfx.Key, pitch = 0.75f * pentatonic[k[0] - '0'])
        k == "=" -> holo.sfx(com.zain.holoos.audio.Sfx.Lock)
        else -> holo.sfx(com.zain.holoos.audio.Sfx.KeyOp)
    }
}

@Composable
private fun Key(label: String, size: androidx.compose.ui.unit.Dp, onPress: () -> Unit) {
    val holo = LocalHolo.current
    val isOp = label in setOf("÷", "×", "−", "+", "=")
    val isFn = label in setOf("C", "±", "%", "⌫")
    HoloTarget("calc:$label", Modifier.size(size), onTap = onPress) { e ->
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Canvas(Modifier.matchParentSize()) {
                val c = Offset(this.size.width / 2, this.size.height / 2)
                val r = this.size.minDimension * 0.44f
                val base = if (isOp) HoloColors.Amber else HoloColors.Cyan
                val lit = (if (isFn) 0.45f else 0.6f) + e.hover * 0.7f + e.pulse * 1.2f
                drawCircle(base, r, c, alpha = 0.03f + e.hover * 0.06f + e.pulse * 0.2f, blendMode = BlendMode.Plus)
                glowCircle(base, c, r, 1.2f, lit)
                glowArc(base, c, r * 1.16f, holo.seconds * 60f + label.hashCode() % 360, 50f + e.hover * 120f, 1.6f, 0.3f + e.hover)
                if (e.charge > 0.05f) glowArc(HoloColors.Amber, c, r * 1.16f, -90f, 360f * e.charge, 2f, 0.8f)
                if (e.pulse > 0f) glowCircle(HoloColors.Ice, c, r * (1f + (1f - e.pulse) * 0.9f), 2f, e.pulse * 1.2f)
            }
            BasicText(
                label,
                style = holoText(
                    (size.value * 0.3f).sp,
                    when { isOp -> HoloColors.Amber; isFn -> HoloColors.Muted; else -> HoloColors.Ice },
                    glow = 12f, spacing = 0.sp,
                ),
            )
        }
    }
}
