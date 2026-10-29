package com.glint.player

import android.graphics.BlurMaskFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.dp

/**
 * A Favorites tile glyph with real depth: a blurred shadow under it, a bright bevel along its top
 * edges, a body lit from above in a light tint of the tile's colour, and a glassy sheen on its upper
 * half. Drawn once into a mask and cached; only redrawn if the size changes.
 */
@Composable
fun TileIcon(icon: ImageVector, colors: List<Color>) {
    val painter = rememberVectorPainter(icon)
    Spacer(
        Modifier.size(140.dp).drawWithCache {
            val g = 102.dp.toPx()
            val left = (size.width - g) / 2
            val top = (size.height - g) / 2 - 4.dp.toPx()
            val img = ImageBitmap(size.width.toInt(), size.height.toInt())
            CanvasDrawScope().draw(this, layoutDirection, androidx.compose.ui.graphics.Canvas(img), size) {
                translate(left, top) { with(painter) { draw(Size(g, g), colorFilter = ColorFilter.tint(Color.White)) } }
            }
            val mask = img.asAndroidBitmap().extractAlpha()
            val bright = lerp(colors.last(), Color.White, 0.82f)
            val deep = lerp(colors.last(), Color.White, 0.36f)
            val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = lerp(colors.first(), Color.Black, 0.6f).copy(alpha = 0.55f).toArgb()
                maskFilter = BlurMaskFilter(8.dp.toPx(), BlurMaskFilter.Blur.NORMAL)
            }
            val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.White.copy(alpha = 0.22f).toArgb()
                maskFilter = BlurMaskFilter(14.dp.toPx(), BlurMaskFilter.Blur.OUTER)
            }
            val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.White.copy(alpha = 0.95f).toArgb() }
            val atop = PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP)
            val body = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(0f, top, 0f, top + g, bright.toArgb(), deep.toArgb(), Shader.TileMode.CLAMP)
                xfermode = atop
            }
            val sheen = Paint().apply {
                shader = LinearGradient(0f, top, 0f, top + g * 0.5f, Color.White.copy(alpha = 0.3f).toArgb(), Color.Transparent.toArgb(), Shader.TileMode.CLAMP)
                xfermode = atop
            }
            val underside = Paint().apply {
                shader = LinearGradient(0f, top + g * 0.6f, 0f, top + g, Color.Transparent.toArgb(), lerp(colors.first(), Color.Black, 0.3f).copy(alpha = 0.35f).toArgb(), Shader.TileMode.CLAMP)
                xfermode = atop
            }
            val bevel = 1.8.dp.toPx()
            val drop = 7.dp.toPx()
            // Blur needs a software canvas: the TV's hardware renderer ignores mask filters on bitmaps.
            val soft = android.graphics.Bitmap.createBitmap(size.width.toInt(), size.height.toInt(), android.graphics.Bitmap.Config.ARGB_8888).also {
                val sc = android.graphics.Canvas(it)
                sc.drawBitmap(mask, 0f, 0f, glow)
                sc.drawBitmap(mask, 0f, drop, shadow)
            }
            onDrawBehind {
                drawIntoCanvas { c ->
                    val n = c.nativeCanvas
                    n.drawBitmap(soft, 0f, 0f, null)
                    val layer = n.saveLayer(null, null)
                    n.drawBitmap(mask, 0f, 0f, rim)
                    n.drawBitmap(mask, 0f, bevel, body)      // shifted down: the white shows only along top edges
                    n.drawRect(0f, 0f, size.width, size.height, sheen)
                    n.drawRect(0f, 0f, size.width, size.height, underside)
                    n.restoreToCount(layer)
                }
            }
        },
    )
}

// ---- Glint's own glyphs: chunky, rounded, solid shapes with cut-outs ----

private fun PathBuilder.circle(cx: Float, cy: Float, r: Float) {
    moveTo(cx - r, cy); arcToRelative(r, r, 0f, true, true, 2 * r, 0f); arcToRelative(r, r, 0f, true, true, -2 * r, 0f); close()
}

private fun PathBuilder.roundRect(l: Float, t: Float, r: Float, b: Float, rad: Float) {
    moveTo(l + rad, t); lineTo(r - rad, t); arcTo(rad, rad, 0f, false, true, r, t + rad)
    lineTo(r, b - rad); arcTo(rad, rad, 0f, false, true, r - rad, b)
    lineTo(l + rad, b); arcTo(rad, rad, 0f, false, true, l, b - rad)
    lineTo(l, t + rad); arcTo(rad, rad, 0f, false, true, l + rad, t); close()
}

private fun PathBuilder.poly(vararg p: Float) {
    moveTo(p[0], p[1]); for (i in 2 until p.size step 2) lineTo(p[i], p[i + 1]); close()
}

private fun glyph(name: String, block: ImageVector.Builder.(SolidColor) -> Unit) =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply { block(SolidColor(Color.White)) }.build()

object TileGlyphs {
    /** A stack of cards with a play mark: collections of films. */
    val Collections by lazy {
        glyph("Collections") { s ->
            path(fill = s) { roundRect(7f, 2.4f, 17f, 3.9f, 0.75f) }
            path(fill = s) { roundRect(4.8f, 5f, 19.2f, 6.8f, 0.9f) }
            path(fill = s, pathFillType = PathFillType.EvenOdd) {
                roundRect(2.5f, 8f, 21.5f, 21.2f, 2.4f)
                poly(10f, 11.2f, 15.4f, 14.6f, 10f, 18f)
            }
        }
    }

    /** An old-style film camera: two reels, body with a window, lens hood. */
    val Movies by lazy {
        glyph("Movies") { s ->
            path(fill = s, pathFillType = PathFillType.EvenOdd) { circle(6.6f, 6.3f, 3.7f); circle(6.6f, 6.3f, 1.15f) }
            path(fill = s, pathFillType = PathFillType.EvenOdd) { circle(14f, 6.8f, 3.2f); circle(14f, 6.8f, 1f) }
            path(fill = s, pathFillType = PathFillType.EvenOdd) { roundRect(2.6f, 10.8f, 16.6f, 20f, 1.9f); circle(7.2f, 15.4f, 1.55f) }
            path(fill = s) {
                moveTo(17.6f, 13.7f); lineTo(20.7f, 11.6f); arcToRelative(0.85f, 0.85f, 0f, false, true, 1.3f, 0.7f)
                lineTo(22f, 18.5f); arcToRelative(0.85f, 0.85f, 0f, false, true, -1.3f, 0.7f); lineTo(17.6f, 17.1f); close()
            }
        }
    }

    /** A television on a short stand, a play mark on its screen. */
    val Shows by lazy {
        glyph("Shows") { s ->
            path(fill = s, pathFillType = PathFillType.EvenOdd) {
                roundRect(1.8f, 3.8f, 22.2f, 17.4f, 2.6f)
                poly(10.2f, 7.6f, 15.2f, 10.6f, 10.2f, 13.6f)
            }
            path(fill = s) { roundRect(10.6f, 17f, 13.4f, 19.6f, 0.4f) }
            path(fill = s) { roundRect(7f, 19.2f, 17f, 21f, 0.9f) }
        }
    }

    /** A video file: a page with a folded corner and a play mark. */
    val Others by lazy {
        glyph("Others") { s ->
            path(fill = s, pathFillType = PathFillType.EvenOdd) {
                moveTo(6.6f, 1.8f); lineTo(13.6f, 1.8f); lineTo(20f, 8.2f); lineTo(20f, 19.8f)
                arcTo(2.2f, 2.2f, 0f, false, true, 17.8f, 22f); lineTo(6.2f, 22f)
                arcTo(2.2f, 2.2f, 0f, false, true, 4f, 19.8f); lineTo(4f, 4.4f)
                arcTo(2.6f, 2.6f, 0f, false, true, 6.6f, 1.8f); close()
                poly(9.8f, 11.4f, 15.2f, 14.7f, 9.8f, 18f)
            }
            // The fold, lifted off the page by a thin gap.
            path(fill = s) { poly(14.4f, 1.8f, 20f, 7.4f, 15.6f, 7.4f, 14.4f, 6.2f) }
        }
    }

    /** Comedy and tragedy masks, overlapping. */
    val Genres by lazy {
        glyph("Genres") { s ->
            // Back mask (tragedy), up and to the right.
            path(fill = s, pathFillType = PathFillType.EvenOdd) {
                moveTo(10.5f, 2.2f); lineTo(21.5f, 2.2f); lineTo(21.5f, 9.5f)
                arcTo(5.5f, 6f, 0f, false, true, 10.5f, 9.5f); close()
                circle(13.6f, 6.4f, 1.05f); circle(18.4f, 6.4f, 1.05f)
                moveTo(13.4f, 12f); arcTo(3.2f, 2.6f, 0f, false, true, 18.6f, 12f); lineTo(17.9f, 12.6f)
                arcTo(2.4f, 1.9f, 0f, false, false, 14.1f, 12.6f); close()
            }
            // Front mask (comedy), down and to the left, with a gap around it.
            path(fill = s, pathFillType = PathFillType.EvenOdd) {
                moveTo(2.5f, 8.2f); lineTo(13.5f, 8.2f); lineTo(13.5f, 15.5f)
                arcTo(5.5f, 6.1f, 0f, false, true, 2.5f, 15.5f); close()
                circle(5.6f, 12.3f, 1.05f); circle(10.4f, 12.3f, 1.05f)
                moveTo(4.9f, 16.2f); lineTo(11.1f, 16.2f); arcTo(3.1f, 2.7f, 0f, false, true, 4.9f, 16.2f); close()
            }
        }
    }

    /** A bookmark ribbon with a plus. */
    val Watchlist by lazy {
        glyph("Watchlist") { s ->
            path(fill = s, pathFillType = PathFillType.EvenOdd) {
                moveTo(6.6f, 2f); lineTo(17.4f, 2f); arcTo(2f, 2f, 0f, false, true, 19.4f, 4f)
                lineTo(19.4f, 21f); lineTo(12f, 16.6f); lineTo(4.6f, 21f); lineTo(4.6f, 4f)
                arcTo(2f, 2f, 0f, false, true, 6.6f, 2f); close()
                poly(11f, 5.6f, 13f, 5.6f, 13f, 8.4f, 15.8f, 8.4f, 15.8f, 10.4f, 13f, 10.4f, 13f, 13.2f, 11f, 13.2f, 11f, 10.4f, 8.2f, 10.4f, 8.2f, 8.4f, 11f, 8.4f)
            }
        }
    }

    /** Three list lines and a play mark. */
    val Playlists by lazy {
        glyph("Playlists") { s ->
            path(fill = s) { roundRect(2.5f, 4.2f, 16.5f, 6.8f, 1.3f) }
            path(fill = s) { roundRect(2.5f, 9.6f, 16.5f, 12.2f, 1.3f) }
            path(fill = s) { roundRect(2.5f, 15f, 10.5f, 17.6f, 1.3f) }
            path(fill = s) {
                moveTo(13.4f, 13.4f); arcTo(1f, 1f, 0f, false, true, 14.9f, 12.5f); lineTo(21.2f, 16.3f)
                arcTo(1f, 1f, 0f, false, true, 21.2f, 18f); lineTo(14.9f, 21.8f)
                arcTo(1f, 1f, 0f, false, true, 13.4f, 20.9f); close()
            }
        }
    }

    /** A folder with its tab and a front flap. */
    val Folders by lazy {
        glyph("Folders") { s ->
            path(fill = s) {
                moveTo(4.2f, 3.5f); lineTo(9f, 3.5f); arcTo(1.6f, 1.6f, 0f, false, true, 10.2f, 4f)
                lineTo(12.2f, 6f); lineTo(19.8f, 6f); arcTo(2.2f, 2.2f, 0f, false, true, 22f, 8.2f)
                lineTo(22f, 8.6f); lineTo(2f, 8.6f); lineTo(2f, 5.7f); arcTo(2.2f, 2.2f, 0f, false, true, 4.2f, 3.5f); close()
            }
            path(fill = s) { roundRect(2f, 9.8f, 22f, 20.5f, 2.2f) }
        }
    }
}
