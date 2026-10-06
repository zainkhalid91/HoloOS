package com.zain.holoos.apps

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zain.holoos.core.FilesScreen
import com.zain.holoos.core.LocalHolo
import com.zain.holoos.fs.FileKind
import com.zain.holoos.fs.FsNode
import com.zain.holoos.fs.formatBytes
import com.zain.holoos.fs.kind
import com.zain.holoos.holo.HoloColors
import com.zain.holoos.holo.cutCornerPath
import com.zain.holoos.holo.glowCircle
import com.zain.holoos.holo.glowDot
import com.zain.holoos.holo.glowLine
import com.zain.holoos.holo.glowPath
import com.zain.holoos.holo.holoText
import com.zain.holoos.input.HoloTarget
import com.zain.holoos.input.HoloTargetEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.min

@Composable
fun FilesView(node: FsNode) {
    val holo = LocalHolo.current
    val children by produceState(holo.listings.cached(node), node) { value = holo.listings.list(node) }
    androidx.compose.runtime.LaunchedEffect(node) { holo.sfx(com.zain.holoos.audio.Sfx.Telemetry, gain = 0.8f) }
    val grid = rememberLazyGridState()
    androidx.compose.runtime.DisposableEffect(grid) {
        holo.screenDrag = { _, dy -> grid.dispatchRawDelta(-dy * 1.2f) }
        onDispose { holo.screenDrag = null }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 28.dp)) {
        Spacer(Modifier.height(78.dp))
        val list = children
        val folders = list?.count { it.isDirectory } ?: 0
        BasicText(node.name.uppercase(), style = holoText(26.sp, HoloColors.Cyan, spacing = 5.sp, glow = 16f))
        BasicText(
            if (list == null) "SCANNING SECTOR…" else "$folders FOLDERS  ·  ${list.size - folders} FILES  ·  ${node.path}",
            style = holoText(10.sp, HoloColors.Muted, glow = 0f), maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(18.dp))

        when {
            list == null -> {}
            list.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                BasicText("EMPTY SECTOR", style = holoText(14.sp, HoloColors.Muted, spacing = 6.sp))
            }
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(176.dp),
                state = grid,
                contentPadding = PaddingValues(bottom = 60.dp, top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                itemsIndexed(list, key = { _, n -> n.path }) { i, child ->
                    Assemble(i) {
                        if (child.isDirectory) FolderCard(child) else FileCard(child)
                    }
                }
            }
        }
    }
}

/** Staggered "materialise" entrance: each card scans in from below. */
@Composable
private fun Assemble(index: Int, content: @Composable () -> Unit) {
    val a = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(min(index, 24) * 22L)
        a.animateTo(1f, tween(360))
    }
    Box(Modifier.graphicsLayer {
        alpha = a.value
        translationY = (1f - a.value) * 36f
        scaleX = 0.92f + a.value * 0.08f
        scaleY = 0.6f + a.value * 0.4f
    }) { content() }
}

@Composable
private fun FolderCard(folder: FsNode) {
    val holo = LocalHolo.current
    val inner by produceState(holo.listings.cached(folder), folder) { value = holo.listings.list(folder) }
    HoloTarget(
        "fs:${folder.path}",
        Modifier.fillMaxWidth().height(150.dp),
        onDive = { r -> holo.navigator.push(FilesScreen(folder), r) },
        zoomScale = 0.12f,
        payload = folder,
        grab = com.zain.holoos.input.Grab.Spring,
    ) { e ->
        Box(Modifier.fillMaxSize()) {
            Canvas(Modifier.matchParentSize()) {
                panel(e, holo.seconds)
                miniHologram(inner, e, holo.seconds)
            }
            Column(Modifier.padding(start = 14.dp, top = 12.dp, end = 14.dp)) {
                BasicText(
                    folder.name, style = holoText(13.sp, if (e.charge > 0.2f) HoloColors.Amber else HoloColors.Ice),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                BasicText(
                    (inner?.let { "${it.size} ITEMS" } ?: "…") + if (holo.listings.isPrefetched(folder)) "  ·  PRE-LOADED" else "",
                    style = holoText(9.sp, if (holo.listings.isPrefetched(folder)) HoloColors.Amber else HoloColors.Muted, glow = 0f),
                )
            }
        }
    }
}

@Composable
private fun FileCard(file: FsNode) {
    val holo = LocalHolo.current
    val kind = file.kind()
    HoloTarget("fs:${file.path}", Modifier.fillMaxWidth().height(150.dp), onTap = {}, grab = com.zain.holoos.input.Grab.Spring) { e ->
        Box(Modifier.fillMaxSize()) {
            Canvas(Modifier.matchParentSize()) {
                panel(e, holo.seconds, dim = true)
                fileGlyph(kind, Offset(size.width / 2, size.height * 0.55f), size.minDimension * 0.2f, kindColor(kind), holo.seconds, 0.7f + e.hover * 0.5f + e.pulse)
            }
            Column(Modifier.padding(start = 14.dp, top = 12.dp, end = 14.dp)) {
                BasicText(file.name, style = holoText(12.sp, HoloColors.Text, glow = 4f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                BasicText("${kind.name.uppercase()}  ·  ${formatBytes(file.sizeBytes)}", style = holoText(9.sp, HoloColors.Muted, glow = 0f))
            }
        }
    }
}

private fun kindColor(k: FileKind): Color = when (k) {
    FileKind.Image, FileKind.Video -> HoloColors.Amber
    FileKind.Audio -> Color(0xFFB98CFF)
    FileKind.Code -> HoloColors.Cyan
    FileKind.Document -> HoloColors.Ice
    FileKind.Archive -> HoloColors.Alert
    else -> HoloColors.CyanDim
}

private fun DrawScope.panel(e: HoloTargetEntry, t: Float, dim: Boolean = false) {
    val col = if (e.charge > 0.2f) HoloColors.Amber else HoloColors.Cyan
    val lit = (if (dim) 0.35f else 0.55f) + e.hover * 0.6f + e.pulse + e.zoom * 0.8f
    drawPath(cutCornerPath(size, 14f), HoloColors.Cyan, alpha = 0.035f + e.hover * 0.05f, blendMode = BlendMode.Plus)
    glowPath(col, cutCornerPath(size, 14f, 1f), 1.1f, lit)
    // Corner brackets that slide out on hover.
    val k = 10f + e.hover * 6f
    glowLine(col, Offset(size.width - 18f, size.height - 4f + e.hover * 3f), Offset(size.width - 18f - k * 2, size.height - 4f + e.hover * 3f), 1.5f, lit)
    if (e.charge > 0.02f) {
        drawRect(HoloColors.Amber, Offset(3f, size.height - 6f), Size((size.width - 6f) * e.charge, 2f), alpha = 0.7f, blendMode = BlendMode.Plus)
    }
    // Scan line sweeping across the hovered card.
    if (e.hover > 0.05f) {
        val y = ((t * 0.7f) % 1f) * size.height
        drawLine(HoloColors.Cyan, Offset(4f, y), Offset(size.width - 4f, y), 1f, alpha = 0.25f * e.hover, blendMode = BlendMode.Plus)
    }
}

/**
 * The hologram inside the hologram: a live miniature of the folder's contents.
 * Zooming toward the card (scroll / pinch) spreads the miniature out as a preview of
 * the dive.
 */
private fun DrawScope.miniHologram(inner: List<FsNode>?, e: HoloTargetEntry, t: Float) {
    val area = Size(size.width - 28f, size.height - 62f)
    val origin = Offset(14f, 50f)
    if (inner == null) {
        val y = origin.y + ((t * 1.3f) % 1f) * area.height
        glowLine(HoloColors.CyanDim, Offset(origin.x, y), Offset(origin.x + area.width, y), 1f, 0.6f)
        return
    }
    if (inner.isEmpty()) {
        glowCircle(HoloColors.CyanDim, Offset(size.width / 2, origin.y + area.height / 2), 10f, 1f, 0.5f)
        return
    }
    val shown = inner.take(18)
    val cols = 6
    val spread = 1f + e.zoom * 0.35f + e.hover * 0.08f
    val cw = area.width / cols
    val ch = area.height / 3
    val center = Offset(origin.x + area.width / 2, origin.y + area.height / 2)
    shown.forEachIndexed { i, n ->
        val gx = origin.x + (i % cols) * cw + cw / 2
        val gy = origin.y + (i / cols) * ch + ch / 2
        val p = Offset(center.x + (gx - center.x) * spread, center.y + (gy - center.y) * spread)
        val bob = kotlin.math.sin(t * 2f + i) * 1.2f
        val lit = 0.45f + e.hover * 0.5f + e.zoom * 0.6f
        if (n.isDirectory) {
            val s = Size(cw * 0.62f, ch * 0.5f)
            glowPath(HoloColors.Cyan, cutCornerPath(s, 4f).also { it.translate(Offset(p.x - s.width / 2, p.y - s.height / 2 + bob)) }, 0.9f, lit)
        } else {
            glowDot(kindColor(n.kind()), Offset(p.x, p.y + bob), 2f, lit)
        }
    }
    if (inner.size > shown.size) {
        drawCircle(HoloColors.Muted, 1.5f, Offset(size.width - 20f, size.height - 16f), alpha = 0.8f, style = Stroke(1f))
    }
}

private fun DrawScope.fileGlyph(k: FileKind, c: Offset, r: Float, col: Color, t: Float, lit: Float) {
    when (k) {
        FileKind.Image -> {
            glowPath(col, cutCornerPath(Size(r * 2.2f, r * 1.6f), 6f).also { it.translate(Offset(c.x - r * 1.1f, c.y - r * 0.8f)) }, 1.2f, lit)
            glowLine(col, Offset(c.x - r * 0.8f, c.y + r * 0.5f), Offset(c.x - r * 0.1f, c.y - r * 0.2f), 1.2f, lit)
            glowLine(col, Offset(c.x - r * 0.1f, c.y - r * 0.2f), Offset(c.x + r * 0.8f, c.y + r * 0.5f), 1.2f, lit)
            glowDot(col, Offset(c.x + r * 0.5f, c.y - r * 0.4f), 2.5f, lit)
        }
        FileKind.Video -> {
            glowCircle(col, c, r, 1.2f, lit)
            val p = androidx.compose.ui.graphics.Path().apply {
                moveTo(c.x - r * 0.3f, c.y - r * 0.45f); lineTo(c.x + r * 0.5f, c.y); lineTo(c.x - r * 0.3f, c.y + r * 0.45f); close()
            }
            glowPath(col, p, 1.4f, lit)
        }
        FileKind.Audio -> for (i in 0 until 9) {
            val hgt = r * (0.3f + 0.7f * kotlin.math.abs(kotlin.math.sin(t * 3f + i * 0.8f)))
            val x = c.x - r + i * r / 4
            glowLine(col, Offset(x, c.y - hgt / 2), Offset(x, c.y + hgt / 2), 1.6f, lit)
        }
        FileKind.Code -> {
            glowLine(col, Offset(c.x - r * 0.3f, c.y - r * 0.6f), Offset(c.x - r, c.y), 1.4f, lit)
            glowLine(col, Offset(c.x - r, c.y), Offset(c.x - r * 0.3f, c.y + r * 0.6f), 1.4f, lit)
            glowLine(col, Offset(c.x + r * 0.3f, c.y - r * 0.6f), Offset(c.x + r, c.y), 1.4f, lit)
            glowLine(col, Offset(c.x + r, c.y), Offset(c.x + r * 0.3f, c.y + r * 0.6f), 1.4f, lit)
        }
        else -> {
            val s = Size(r * 1.4f, r * 1.8f)
            glowPath(col, cutCornerPath(s, r * 0.4f).also { it.translate(Offset(c.x - s.width / 2, c.y - s.height / 2)) }, 1.2f, lit)
            for (i in 0..2) glowLine(col, Offset(c.x - r * 0.4f, c.y - r * 0.2f + i * r * 0.3f), Offset(c.x + r * 0.4f, c.y - r * 0.2f + i * r * 0.3f), 1f, lit * 0.7f)
        }
    }
}
