package com.glint.player

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import java.util.Locale

/**
 * Lays everything out on a 1280dp-wide canvas whatever the box's density, so a 1080p box at
 * xhdpi (960dp wide) and a 4K one look the same.
 */
@Composable
fun TvScale(content: @Composable () -> Unit) {
    val metrics = androidx.compose.ui.platform.LocalContext.current.resources.displayMetrics
    val width = maxOf(metrics.widthPixels, metrics.heightPixels)
    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(width / 1280f, 1f),
        content = content,
    )
}

/**
 * Scroll only as far as needed to show the focused element. Compose's TV default instead parks
 * the focused element a third of the way down, which pushes a detail page's title off screen.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun MinimalScroll(content: @Composable () -> Unit) {
    val spec = remember {
        object : androidx.compose.foundation.gestures.BringIntoViewSpec {
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = when {
                offset >= 0 && offset + size <= containerSize -> 0f
                offset < 0 || size > containerSize -> offset
                else -> offset + size - containerSize
            }
        }
    }
    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides spec, content = content)
}

object Palette {
    /** Light appearance; set at start from Settings (changing it restarts the screen). */
    @Volatile var light = false
    /** Pure black page (AMOLED style) instead of the grey gradient; the default. */
    @Volatile var black = true

    // Slate greys like the tvOS dark appearance (or light greys), with an orange accent for progress and unwatched marks.
    val Top get() = if (light) Color(0xFFF4F5F7) else if (black) Color.Black else Color(0xFF45484E)
    val Bottom get() = if (light) Color(0xFFD5D8DD) else if (black) Color.Black else Color(0xFF1C1D20)
    val Background get() = if (light) Color(0xFFE6E8EB) else if (black) Color.Black else Color(0xFF26282C)
    val Surface get() = if (light) Color(0x1F000000) else Color(0x33FFFFFF)
    val SurfaceHigh get() = if (light) Color(0x33000000) else Color(0x4DFFFFFF)
    val Text get() = if (light) Color(0xFF1B1C1F) else Color(0xFFF4F5F7)
    val Dim get() = if (light) Color(0xFF4A4E56) else Color(0xFFB9BCC3)
    val Faint get() = if (light) Color(0xFF6E737C) else Color(0xFF8C9099)
    /** The theme colour picked in Settings, an index into [ACCENTS]; observable, so a change redraws in place. */
    var accent by androidx.compose.runtime.mutableIntStateOf(0)
    val Accent get() = ACCENTS.getOrElse(accent) { ACCENTS[0] }.second
    /** Text and glyphs drawn on the theme colour: black on the light ones (yellow, gold), white otherwise. */
    val OnAccent get() = if (accent in LIGHT_ACCENTS) Color.Black else Color.White
    val Watched = Color(0xFF5BC27A)
}

/** Theme colours, Settings' order: name and colour. Orange (Glint's own) is the default. */
val ACCENTS = listOf(
    R.string.color_orange to Color(0xFFFF8A1F),
    R.string.color_red to Color(0xFFE5383B),
    R.string.color_pink to Color(0xFFFF4D8D),
    R.string.color_purple to Color(0xFF9B5DE5),
    R.string.color_blue to Color(0xFF2F80ED),
    R.string.color_teal to Color(0xFF14B8A6),
    R.string.color_green to Color(0xFF34C759),
    R.string.color_yellow to Color(0xFFFFCC00),
)
private val LIGHT_ACCENTS = setOf(7)

object Type {
    val Hero get() = TextStyle(color = Palette.Text, fontSize = 40.sp, fontWeight = FontWeight.Bold, lineHeight = 44.sp)
    val Title get() = TextStyle(color = Palette.Text, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
    val Heading get() = TextStyle(color = Palette.Text, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
    val Section get() = TextStyle(color = Palette.Dim, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
    val Body get() = TextStyle(color = Palette.Dim, fontSize = 15.sp, lineHeight = 21.sp)
    val Meta get() = TextStyle(color = Palette.Dim, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    val Small get() = TextStyle(color = Palette.Faint, fontSize = 12.sp)
    val Button = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
}

/** The page background: a soft grey gradient. */
@Composable
fun ScreenBackground(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Palette.Top, Palette.Bottom))))
}

fun Modifier.screenBackground() = this.background(Brush.verticalGradient(listOf(Palette.Top, Palette.Bottom)))

@Composable
fun Txt(
    text: String, style: TextStyle, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE,
    color: Color = Color.Unspecified, align: TextAlign? = null,
) = BasicText(
    text, modifier,
    style = style.let { if (color != Color.Unspecified) it.copy(color = color) else it }.let { if (align != null) it.copy(textAlign = align) else it }
        .scaled(LocalTextScale.current),
    maxLines = maxLines, overflow = TextOverflow.Ellipsis,
)

/**
 * Enlarges text in part of the UI by an exact factor. (A larger fontScale on LocalDensity would not:
 * Android scales big text less than small text, so titles would grow less than captions.)
 */
val LocalTextScale = androidx.compose.runtime.staticCompositionLocalOf { 1f }

private fun TextStyle.scaled(k: Float): TextStyle = if (k == 1f) this else copy(
    fontSize = if (fontSize != androidx.compose.ui.unit.TextUnit.Unspecified) fontSize * k else fontSize,
    lineHeight = if (lineHeight != androidx.compose.ui.unit.TextUnit.Unspecified) lineHeight * k else lineHeight,
)

@Composable
fun Icon(icon: ImageVector, tint: Color, modifier: Modifier = Modifier) =
    Image(icon, null, modifier, colorFilter = ColorFilter.tint(tint))

/** Focus restoration: the card whose id matches asks for focus once when it appears. */
@Composable
fun Modifier.restoreFocus(id: String, wanted: String?): Modifier {
    if (id != wanted) return this
    val r = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { r.requestFocus() } }
    return this.focusRequester(r)
}

/** A card that lifts (grows, casts a shadow) when focused, the way tvOS cards do. */
@Composable
fun FocusCard(
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(12.dp),
    onFocus: () -> Unit = {},
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
    content: @Composable BoxScope.(focused: Boolean) -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (focused) 1.1f else 1f, label = "scale")
    Box(
        modifier
            .scale(scale)
            .shadow(if (focused) 18.dp else 0.dp, shape)
            .onFocusChanged { if (it.isFocused) onFocus() }
            .clip(shape)
            .combinedClickable(source, indication = null, onLongClick = onLongClick, onClick = onClick)
    ) {
        content(focused)
        // The tvOS-style glow: the focused card brightens, most at the top where the light catches it.
        val glow by animateFloatAsState(if (focused) 1f else 0f, label = "glow")
        if (glow > 0f) Box(
            Modifier.matchParentSize().background(
                Brush.verticalGradient(0f to Color.White.copy(alpha = 0.22f * glow), 0.55f to Color.White.copy(alpha = 0.08f * glow), 1f to Color.White.copy(alpha = 0.04f * glow))
            )
        )
    }
}

@Composable
fun Poster(model: Any?, title: String, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.background(Color(0xFF3A3D43)), contentAlignment = Alignment.Center) {
        Txt(title, Type.Meta.copy(color = Palette.Dim), Modifier.padding(10.dp), maxLines = 4, align = TextAlign.Center)
        if (model != null) {
            // Resized here, carefully, to exactly the pixels the card covers when focused (10% zoom), so the
            // screen draws it 1:1 instead of squeezing a larger image with plain filtering.
            val density = androidx.compose.ui.platform.LocalDensity.current
            val w = with(density) { (maxWidth.toPx() * 1.1f).toInt() }.coerceAtLeast(1)
            val h = with(density) { (maxHeight.toPx() * 1.1f).toInt() }.coerceAtLeast(1)
            val request = coil.request.ImageRequest.Builder(androidx.compose.ui.platform.LocalContext.current)
                .data(SizedPoster(sharper(model), w, h))
                .size(w, h)
                .build()
            AsyncImage(
                request, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
                filterQuality = androidx.compose.ui.graphics.FilterQuality.High,
                onError = { android.util.Log.w("Poster", "Couldn't load $model", it.result.throwable) },
            )
        }
    }
}

/** A poster or backdrop made ready for one card size; see [SizedPosterFetcher]. */
data class SizedPoster(val model: Any, val w: Int, val h: Int)

/**
 * Makes a card's picture once (full original, careful resize, sharpening) and keeps the result on disk,
 * lossless, at exactly the card's pixels. Coming back to a grid then loads small ready files instead of
 * redoing that work for every 2000×3000 original pushed out of the memory cache.
 */
class SizedPosterFetcher(private val data: SizedPoster, private val options: coil.request.Options, private val loader: coil.ImageLoader) : coil.fetch.Fetcher {
    override suspend fun fetch(): coil.fetch.FetchResult {
        val file = java.io.File(dir(options.context), key(data))
        if (!file.exists()) {
            val made = loader.execute(
                coil.request.ImageRequest.Builder(options.context)
                    .data(data.model)
                    .size(data.w * 2, data.h * 2)       // decode at least twice the target, then step down
                    .allowHardware(false)               // the resize needs a normal bitmap
                    .transformations(SharpResize(data.w, data.h))
                    .memoryCachePolicy(coil.request.CachePolicy.DISABLED)
                    .build(),
            )
            val bmp = ((made as? coil.request.SuccessResult)?.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
                ?: throw (made as? coil.request.ErrorResult)?.throwable ?: IllegalStateException("No picture for ${data.model}")
            val tmp = java.io.File(file.path + ".tmp")
            tmp.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            tmp.renameTo(file)
        } else file.setLastModified(System.currentTimeMillis())   // recently used: kept longest by trim()
        return coil.fetch.SourceResult(
            source = coil.decode.ImageSource(okio.Path.Companion.run { file.toOkioPath() }, okio.FileSystem.SYSTEM),
            mimeType = "image/png",
            dataSource = coil.decode.DataSource.DISK,
        )
    }

    class Factory : coil.fetch.Fetcher.Factory<SizedPoster> {
        override fun create(data: SizedPoster, options: coil.request.Options, imageLoader: coil.ImageLoader) = SizedPosterFetcher(data, options, imageLoader)
    }

    companion object {
        /** Bump when the look of finished posters changes, so old ones are made again. */
        private const val VERSION = 1
        fun dir(ctx: android.content.Context) = java.io.File(ctx.cacheDir, "posters-v$VERSION").apply { mkdirs() }
        fun key(d: SizedPoster): String {
            val id = "${d.model}|${d.w}x${d.h}"
            return java.security.MessageDigest.getInstance("MD5").digest(id.toByteArray()).joinToString("") { "%02x".format(it) } + ".png"
        }
        /** Keeps the folder under [limit] bytes, dropping the least recently used files first. */
        fun trim(ctx: android.content.Context, limit: Long = 400L shl 20) {
            val files = dir(ctx).listFiles()?.sortedBy { it.lastModified() } ?: return
            var total = files.sumOf { it.length() }
            for (f in files) { if (total <= limit) break; total -= f.length(); f.delete() }
        }
    }
}

/**
 * Crops to the card's shape and scales down in halving steps (each one filtered), the way photo
 * editors do it. One big jump from a 2000 px poster to 290 px drops detail and looks soft.
 */
class SharpResize(private val w: Int, private val h: Int) : coil.transform.Transformation {
    override val cacheKey = "sharp2-${w}x$h"

    override suspend fun transform(input: android.graphics.Bitmap, size: coil.size.Size): android.graphics.Bitmap {
        // Centre crop to the target's aspect ratio first.
        val scale = maxOf(w.toFloat() / input.width, h.toFloat() / input.height)
        val cw = (w / scale).toInt().coerceIn(1, input.width)
        val ch = (h / scale).toInt().coerceIn(1, input.height)
        var bmp = android.graphics.Bitmap.createBitmap(input, (input.width - cw) / 2, (input.height - ch) / 2, cw, ch)
        while (bmp.width >= w * 2 && bmp.height >= h * 2) {
            val next = android.graphics.Bitmap.createScaledBitmap(bmp, bmp.width / 2, bmp.height / 2, true)
            if (bmp !== input) bmp.recycle()
            bmp = next
        }
        val out = if (bmp.width == w && bmp.height == h) bmp else android.graphics.Bitmap.createScaledBitmap(bmp, w, h, true)
        if (bmp !== input && bmp !== out) bmp.recycle()
        return sharpen(if (out === input) out.copy(android.graphics.Bitmap.Config.ARGB_8888, true) else out)
    }

    /** Light unsharp mask (3×3 blur, 40%): brings back the edge contrast that downscaling softens. */
    private fun sharpen(b: android.graphics.Bitmap): android.graphics.Bitmap {
        val bw = b.width; val bh = b.height
        val src = IntArray(bw * bh).also { b.getPixels(it, 0, bw, 0, 0, bw, bh) }
        val dst = src.copyOf()
        for (y in 1 until bh - 1) for (x in 1 until bw - 1) {
            val i = y * bw + x
            var r = 0; var g = 0; var bl = 0
            for (dy in -1..1) for (dx in -1..1) {
                val c = src[i + dy * bw + dx]
                val k = if (dx == 0 && dy == 0) 4 else if (dx == 0 || dy == 0) 2 else 1
                r += (c shr 16 and 255) * k; g += (c shr 8 and 255) * k; bl += (c and 255) * k
            }
            val c = src[i]
            fun ch(v: Int, blur: Int) = (v + (v - blur / 16) * 2 / 5).coerceIn(0, 255)
            dst[i] = (c and -0x1000000) or (ch(c shr 16 and 255, r) shl 16) or (ch(c shr 8 and 255, g) shl 8) or ch(c and 255, bl)
        }
        val out = if (b.isMutable) b else b.copy(android.graphics.Bitmap.Config.ARGB_8888, true)
        out.setPixels(dst, 0, bw, 0, 0, bw, bh)
        return out
    }
}

/** TMDB serves resized copies (w342, w500…); "original" is the file as uploaded, with nothing redone. */
private fun sharper(model: Any): Any =
    if (model is String && model.contains("image.tmdb.org")) model.replace(Regex("""/t/p/w\d+/"""), "/t/p/original/") else model

@Composable
fun ProgressLine(fraction: Float, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(5.dp).background(Color(0x66000000))) {
        Box(Modifier.fillMaxWidth(fraction).height(5.dp).background(Palette.Accent))
    }
}

/** Orange folded corner on posters of things not watched yet. */
@Composable
fun UnwatchedCorner(modifier: Modifier = Modifier, size: Dp = 26.dp) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val path = Path().apply { moveTo(0f, 0f); lineTo(w, 0f); lineTo(w, this@Canvas.size.height); close() }
        drawPath(path, Palette.Accent)
    }
}

@Composable
fun WatchedBadge(modifier: Modifier = Modifier) {
    Box(modifier.size(24.dp).clip(CircleShape).background(Palette.Watched), contentAlignment = Alignment.Center) {
        Txt("✓", Type.Small.copy(color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp))
    }
}

/** Title and a second line centred under a card; brighter while the card has focus. */
@Composable
private fun Caption(title: String, sub: String?, focused: Boolean, width: Dp, subWeight: FontWeight? = null) {
    Column(Modifier.width(width).padding(top = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Txt(title, Type.Meta.copy(color = if (focused) Palette.Text else Palette.Dim, fontSize = 17.sp), maxLines = 1, align = TextAlign.Center)
        if (!sub.isNullOrBlank()) Txt(sub, Type.Small.copy(color = Palette.Faint, fontSize = 14.sp, fontWeight = subWeight ?: Type.Small.fontWeight), maxLines = 1, align = TextAlign.Center)
    }
}

/** Portrait card for films, series and collections. */
@Composable
fun PosterCard(item: Item, width: Dp = 160.dp, wanted: String? = null, onLongClick: ((Item) -> Unit)? = null, onFocus: (Item) -> Unit, onClick: (Item) -> Unit) {
    val lib = App.library
    val progress by lib.progress.collectAsState()
    val s = lib.settings
    var hasFocus by remember { mutableStateOf(false) }
    Column(Modifier.width(width).onFocusChanged { hasFocus = it.hasFocus }, horizontalAlignment = Alignment.CenterHorizontally) {
        FocusCard(
            Modifier.restoreFocus(item.id, wanted).fillMaxWidth().aspectRatio(2f / 3f),
            onFocus = { onFocus(item) },
            onLongClick = onLongClick?.let { { it(item) } },
            onClick = { onClick(item) },
        ) {
            Poster(item.poster, item.title, Modifier.fillMaxSize())
            if (s.watchedIndicators) when (item) {
                is Item.MovieItem -> {
                    val p = progress[item.id]
                    when {
                        p?.inProgress == true -> ProgressLine(p.fraction, Modifier.align(Alignment.BottomCenter))
                        p?.watched != true -> UnwatchedCorner(Modifier.align(Alignment.TopEnd))
                    }
                }
                is Item.ShowItem -> if (item.show.episodes.any { progress[it.id]?.watched != true }) UnwatchedCorner(Modifier.align(Alignment.TopEnd))
                else -> {}
            }
        }
        if (s.showPosterTitles) Caption(if (s.showFilenames) fileTitle(item) else item.title, subtitle(item), hasFocus, width)
    }
}

/** Landscape card: watching, up next, episodes, other videos. */
@Composable
fun WideCard(item: Item, width: Dp = 330.dp, wanted: String? = null, label: String? = null, captionOnFocus: Boolean = false, corner: Dp = 12.dp, onLongClick: ((Item) -> Unit)? = null, onFocus: (Item) -> Unit, onClick: (Item) -> Unit) {
    val all by App.library.progress.collectAsState()
    val p = all[item.id]
    var hasFocus by remember { mutableStateOf(false) }
    Column(Modifier.width(width).onFocusChanged { hasFocus = it.hasFocus }, horizontalAlignment = Alignment.CenterHorizontally) {
        FocusCard(
            Modifier.restoreFocus(item.id, wanted).fillMaxWidth().aspectRatio(16f / 9f),
            shape = RoundedCornerShape(corner),
            onFocus = { onFocus(item) },
            onLongClick = onLongClick?.let { { it(item) } },
            onClick = { onClick(item) },
        ) {
            Poster(item.backdrop ?: item.poster, item.title, Modifier.fillMaxSize())
            if (p?.inProgress == true) ProgressLine(p.fraction, Modifier.align(Alignment.BottomCenter))
        }
        // The year (or episode line) a touch heavier under these wide cards, where it sits below a big title.
        // captionOnFocus (Home's Watching row): only the selected card shows it; the others keep its room.
        val shown by animateFloatAsState(if (!captionOnFocus || hasFocus) 1f else 0f, label = "caption")
        Box(Modifier.alpha(shown)) { Caption(item.title, label ?: subtitle(item), hasFocus, width, subWeight = FontWeight.SemiBold) }
    }
}

private fun fileTitle(item: Item) = when (item) {
    is Item.MovieItem -> item.movie.file.name.substringBeforeLast('.')
    else -> item.title
}

/** An age rating as shown: Turkey's general-audience "Genel İzleyici Kitlesi" becomes "Genel", short like "16+". */
fun certLabel(cert: String): String = cert.trim().let { if (it.startsWith("Genel", ignoreCase = true)) "Genel" else it }

/** Second caption line: year, "S1 • E2 - Name", or how many films a collection holds. */
@Composable
fun subtitle(item: Item): String = when (item) {
    is Item.MovieItem -> item.movie.year
    is Item.ShowItem -> item.season?.let { if (it == 0) stringResource(R.string.specials) else stringResource(R.string.season_n, it) } ?: item.show.year
    is Item.EpisodeItem -> episodeLine(item.episode)
    is Item.CollectionItem -> stringResource(R.string.n_items, item.size)
}

fun episodeLine(e: Episode) = "S${e.season} • E${e.number}" + if (e.title.isNotBlank()) " - ${e.title}" else ""

@Composable
fun Shelf(title: String, state: LazyListState = rememberLazyListState(), titleGap: Dp = 4.dp, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 18.dp)) {
        Txt(title, Type.Section, Modifier.padding(start = 80.dp, bottom = titleGap))
        LazyRow(
            state = state,
            contentPadding = PaddingValues(horizontal = 80.dp, vertical = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(30.dp),
            content = content,
        )
    }
}

/** tvOS-style pill: translucent at rest, white when focused. [fill] shows progress inside the pill. */
@Composable
fun PillButton(text: String, modifier: Modifier = Modifier, primary: Boolean = false, fill: Float = 0f, icon: ImageVector? = null, bare: Boolean = false, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (focused) 1.06f else 1f, label = "pill")
    val shape = RoundedCornerShape(30.dp)
    Box(
        modifier
            .scale(scale)
            .shadow(if (focused) 12.dp else 0.dp, shape)
            .clip(shape)
            // Bare (the top-bar Search): only the glyph and label until focused.
            .background(if (focused) Color.White else if (bare) Color.Transparent else if (primary) Palette.SurfaceHigh else Palette.Surface)
            .combinedClickable(source, indication = null, onLongClick = onLongClick, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (fill > 0f) Box(Modifier.matchParentSize()) {
            Box(Modifier.fillMaxWidth(fill.coerceIn(0f, 1f)).fillMaxHeight().background(if (focused) Color(0x22000000) else Color(0x33FFFFFF)))
        }
        Row(Modifier.padding(horizontal = if (bare) 20.dp else 30.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null && bare) {
                // Sized from the label's capital height (C): glyph 1.84C × 1.88C (as tall as the
                // row titles' capitals look beside it), gap 0.27C, sitting 0.1C above the letters' centre.
                val c = Type.Button.fontSize.value * 0.711f * LocalTextScale.current
                Icon(icon, if (focused) Color.Black else Palette.Text, Modifier.offset(y = (-c * 0.1f).dp).size((c * 1.84f).dp, (c * 1.88f).dp)); Spacer(Modifier.width((c * 0.27f).dp))
            } else if (icon != null) { Icon(icon, if (focused) Color.Black else Palette.Text, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)) }
            Txt(text, Type.Button.copy(color = if (focused) Color.Black else Palette.Text, fontWeight = if (bare) FontWeight.Bold else Type.Button.fontWeight), maxLines = 1)
        }
    }
}

/**
 * The search glyph: a ring 1.27 capital heights across with a short round-ended handle, stroke 0.2
 * capital heights, in a 1.53 × 1.57 box (capital height = 10 units).
 */
val SearchGlyph: ImageVector by lazy {
    ImageVector.Builder("SearchGlyph", 15.3.dp, 15.7.dp, 15.3f, 15.7f).apply {
        val ink = androidx.compose.ui.graphics.SolidColor(Color.White)
        androidx.compose.ui.graphics.vector.addPathNodes("M6.35,0.9 A5.45,5.45 0 1,1 6.34,0.9 Z").let {
            addPath(it, stroke = ink, strokeLineWidth = 2.0f)
        }
        addPath(
            androidx.compose.ui.graphics.vector.addPathNodes("M10.2,10.3 L14.4,14.8"),
            stroke = ink, strokeLineWidth = 2.0f, strokeLineCap = androidx.compose.ui.graphics.StrokeCap.Round,
        )
    }.build()
}

/** Round icon button (watched, watchlist, rating, more…). */
@Composable
fun IconCircle(icon: ImageVector, modifier: Modifier = Modifier, active: Boolean = false, size: Dp = 56.dp, width: Dp? = null, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (focused) 1.12f else 1f, label = "icon")
    // A width makes it a capsule, like the top-bar and detail-page buttons; without one it's round.
    val shape = if (width != null) RoundedCornerShape(size / 2) else CircleShape
    Box(
        modifier
            .scale(scale)
            .shadow(if (focused) 10.dp else 0.dp, shape)
            .size(width ?: size, size)
            .clip(shape)
            .background(if (focused) Color.White else Palette.Surface)
            .clickable(source, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, when { focused -> Color.Black; active -> Palette.Accent; else -> Palette.Text }, Modifier.size(size * 0.45f))
    }
}

/**
 * A top-bar icon: just the glyph, no background at rest; focused, a white circle with a dark glyph.
 * [spinning] turns the glyph while something runs (a library scan).
 */
@Composable
fun BareIcon(icon: ImageVector, modifier: Modifier = Modifier, spinning: Boolean = false, size: Dp = 56.dp, glyph: Dp = size * 0.55f, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (focused) 1.12f else 1f, label = "bare")
    val turn = androidx.compose.animation.core.rememberInfiniteTransition(label = "spin")
    val angle by turn.animateFloat(
        0f, 360f, androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(1100, easing = androidx.compose.animation.core.LinearEasing)),
        label = "angle",
    )
    Box(
        modifier
            .scale(scale)
            .shadow(if (focused) 10.dp else 0.dp, CircleShape)
            .size(size)
            .clip(CircleShape)
            .background(if (focused) Color.White else Color.Transparent)
            .clickable(source, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, if (focused) Color.Black else Palette.Text, Modifier.size(glyph).graphicsLayer { rotationZ = if (spinning) angle else 0f })
    }
}

/** A row in a settings-style list: label on the left, value (or a chevron) on the right. */
@Composable
fun ListRow(label: String, value: String = "", modifier: Modifier = Modifier, sub: String? = null, chevron: Boolean = false, icon: ImageVector? = null, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (focused) 1.03f else 1f, label = "row")
    Row(
        modifier
            .scale(scale)
            .fillMaxWidth()
            .shadow(if (focused) 10.dp else 0.dp, RoundedCornerShape(20.dp))
            .clip(RoundedCornerShape(20.dp))
            .background(if (focused) Color.White else Palette.Surface)
            .combinedClickable(source, indication = null, onLongClick = onLongClick, onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) { Icon(icon, if (focused) Color.Black else Palette.Text, Modifier.size(22.dp)); Spacer(Modifier.width(14.dp)) }
        Column(Modifier.weight(1f)) {
            Txt(label, Type.Button.copy(color = if (focused) Color.Black else Palette.Text, fontWeight = FontWeight.Medium, fontSize = 19.sp), maxLines = 1)
            if (sub != null) Txt(sub, Type.Small.copy(color = if (focused) Color(0xFF444444) else Palette.Faint), maxLines = 2)
        }
        if (value.isNotEmpty()) Txt(value, Type.Meta.copy(color = if (focused) Color(0xFF333333) else Palette.Dim), maxLines = 1)
        if (chevron) { Spacer(Modifier.width(10.dp)); Txt("›", Type.Title.copy(color = if (focused) Color(0xFF555555) else Palette.Faint, fontSize = 24.sp)) }
    }
}

/** Full-bleed backdrop that fades into the page on the left and bottom. */
@Composable
fun Backdrop(model: Any?, modifier: Modifier = Modifier, strength: Float = 1f) {
    Box(modifier.fillMaxSize()) {
        if (model != null) AsyncImage(model, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alignment = Alignment.TopEnd)
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Palette.Bottom.copy(alpha = 0.95f * strength), 0.5f to Palette.Bottom.copy(alpha = 0.55f * strength), 1f to Color.Transparent)))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Palette.Bottom)))
    }
}

@Composable
fun Chip(text: String, modifier: Modifier = Modifier) {
    Box(modifier.clip(RoundedCornerShape(4.dp)).border(1.5.dp, Palette.Dim, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 1.dp)) {
        Txt(text, Type.Small.copy(color = Palette.Dim, fontWeight = FontWeight.Bold))
    }
}

/** Screen title centred at the top, dim like tvOS section headers. */
@Composable
fun ScreenTitle(text: String, modifier: Modifier = Modifier) {
    Txt(text, Type.Title.copy(color = Palette.Faint, fontSize = 30.sp), modifier.fillMaxWidth().padding(top = 36.dp, bottom = 12.dp), align = TextAlign.Center)
}

@Composable
fun Gap(h: Dp) = Spacer(Modifier.height(h))

fun minutes(m: Int): String = if (m >= 60) "${m / 60}h ${m % 60}m" else "${m}m"

fun clock(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

/** "00:01:39", the way resume points are written on detail pages. */
fun longClock(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%02d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60)
}

fun rating(r: Double) = if (r > 0) "★ " + String.format(Locale.getDefault(), "%.1f", r) else ""

fun bytes(n: Long): String = when {
    n >= 1L shl 30 -> String.format(Locale.getDefault(), "%.1f GB", n / (1L shl 30).toDouble())
    n >= 1L shl 20 -> String.format(Locale.getDefault(), "%.0f MB", n / (1L shl 20).toDouble())
    else -> "$n B"
}

/** Quality badges read from the release name: 4K, HDR, Dolby Vision, Atmos, DTS-HD… */
fun badges(name: String): List<String> {
    val n = name.lowercase(Locale.ROOT).replace('_', '.').replace(' ', '.')
    val out = ArrayList<String>()
    when {
        "2160p" in n || ".4k." in n || ".uhd." in n -> out += "4K"
        "1080p" in n || "1080i" in n -> out += "1080p"
        "720p" in n -> out += "720p"
    }
    if (Regex("""\.(dv|dovi)\.|dolby.?vision""").containsMatchIn(n)) out += "Dolby Vision"
    if ("hdr10p" in n || "hdr10+" in n || "hdr10plus" in n) out += "HDR10+"
    else if ("hdr" in n) out += "HDR"
    when {
        "atmos" in n -> out += "Atmos"
        "dts-x" in n || "dts.x" in n || "dtsx" in n -> out += "DTS:X"
    }
    when {
        "truehd" in n -> out += "TrueHD"
        "dts-hd" in n || "dts.hd" in n || "dtshd" in n -> out += "DTS-HD MA"
        ".dts." in n -> out += "DTS"
        "ddp" in n || "eac3" in n || "dd+" in n -> out += "Dolby Digital+"
    }
    Regex("""\.(7\.1|5\.1|2\.0)\.""").find(n)?.let { out += it.groupValues[1] }
    if ("remux" in n) out += "REMUX"
    return out
}
