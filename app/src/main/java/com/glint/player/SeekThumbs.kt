package com.glint.player

import android.graphics.Bitmap
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.EnumSet

/** Its own SMB connection, so grabbing preview frames never waits behind (or stalls) the playing film. */
object SmbThumbs : SmbLane()

/**
 * Frames for the seek preview while scrubbing: the picture at the time the playhead
 * is moved to. Android's frame retriever decodes the nearest keyframe (in software, so the TV's video
 * decoder stays with the film) from the file on the NAS, read through [SmbThumbs]. Requests are
 * conflated: only the latest time is fetched; frames are kept per 10 seconds.
 */
class SeekThumbs(private val src: Source, private val share: String, private val path: String) {
    /** The latest frame: (time in ms it was asked for, picture). */
    val frame: StateFlow<Pair<Long, Bitmap>?> get() = _frame
    private val _frame = MutableStateFlow<Pair<Long, Bitmap>?>(null)
    /** True once frames turn out unreadable for this file (no frame on the first tries): no preview then. */
    val unavailable: StateFlow<Boolean> get() = _unavailable
    private val _unavailable = MutableStateFlow(false)
    private var misses = 0
    // One thread for all frame work: the GL context the base-layer decoder renders into belongs to it.
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val scope = CoroutineScope(SupervisorJob() + worker.asCoroutineDispatcher())
    private val asks = Channel<Long>(Channel.CONFLATED)
    private val cache = object : LinkedHashMap<Long, Bitmap>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Bitmap>?) = size > 60
    }
    private var retriever: MediaMetadataRetriever? = null
    private var data: SmbMediaSource? = null
    /** Set once the retriever fails on this file (Dolby Vision): frames come from [BaseLayerFrames]. */
    private var base: BaseLayerFrames? = null

    init {
        scope.launch {
            for (ms in asks) {
                val key = ms / 10_000
                val bmp = synchronized(cache) { cache[key] } ?: grab(key * 10_000 + 5_000)?.also { synchronized(cache) { cache[key] = it } }
                if (bmp != null) { _frame.value = ms to bmp; misses = 0 }
                else if (_frame.value == null && ++misses >= 2) _unavailable.value = true
            }
        }
    }

    /** Ask for the picture at [ms]; a cached one shows at once. */
    fun request(ms: Long) {
        synchronized(cache) { cache[ms / 10_000] }?.let { _frame.value = ms to it; return }
        asks.trySend(ms)
    }

    private fun grab(ms: Long): Bitmap? {
        base?.let { return it.frameAt(ms) }
        retrieve(ms)?.let { return it }
        // The retriever hands Dolby Vision to the TV's DV decoder, which can't give back one frame;
        // decode the HEVC base layer in software instead.
        if (base == null) base = runCatching { BaseLayerFrames(SmbMediaSource(src, share, path)) }
            .onFailure { android.util.Log.w("SeekThumbs", "No base layer decoder", it) }.getOrNull()
        return base?.frameAt(ms)
    }

    private fun retrieve(ms: Long): Bitmap? = runCatching {
        val r = retriever ?: MediaMetadataRetriever().also {
            val d = SmbMediaSource(src, share, path)
            data = d
            it.setDataSource(d)
            retriever = it
        }
        r.getScaledFrameAtTime(ms * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 480, 270)
    }.onFailure { android.util.Log.w("SeekThumbs", "No frame at $ms", it) }.getOrNull()

    fun release() {
        scope.cancel()
        asks.close()
        worker.execute {
            runCatching { retriever?.release() }
            runCatching { data?.close() }
            runCatching { base?.release() }
        }
        worker.shutdown()
    }
}

/**
 * One frame at a time from a video's keyframes, decoded beside the film (by a software decoder where
 * it can cope, else a second hardware one) onto a GPU texture and read back small: hardware decoders
 * write their own tiled pixel layouts that only the GPU reads. Dolby Vision files are read as their
 * HEVC base layer (the decoder skips the enhancement and metadata units). HDR (PQ) pictures are mapped
 * to SDR brightness. Not thread safe: every call has to come from the same thread (EGL).
 */
private class BaseLayerFrames(private val data: SmbMediaSource) {
    private val extractor = android.media.MediaExtractor()
    private val codec: android.media.MediaCodec
    private val pq: Boolean
    private val reader = FrameReader(480, 270)

    init {
        extractor.setDataSource(data)
        val track = (0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(android.media.MediaFormat.KEY_MIME).orEmpty().startsWith("video/") }
        extractor.selectTrack(track)
        val f = extractor.getTrackFormat(track)
        val mime = f.getString(android.media.MediaFormat.KEY_MIME).orEmpty()
        val decodeMime = if (mime == "video/avc") "video/avc" else "video/hevc"
        val w = f.getInteger(android.media.MediaFormat.KEY_WIDTH); val h = f.getInteger(android.media.MediaFormat.KEY_HEIGHT)
        val out = android.media.MediaFormat.createVideoFormat(decodeMime, w, h)
        for (k in listOf("csd-0", "csd-1", "csd-2")) if (f.containsKey(k) && !(k == "csd-2" && mime.contains("dolby"))) out.setByteBuffer(k, f.getByteBuffer(k))
        pq = (f.containsKey(android.media.MediaFormat.KEY_COLOR_TRANSFER) && f.getInteger(android.media.MediaFormat.KEY_COLOR_TRANSFER) == android.media.MediaFormat.COLOR_TRANSFER_ST2084) || mime.contains("dolby")
        // A software decoder when it can (it leaves the TV's to the film); TV software decoders often stop at
        // 8-bit 2K, so 4K HDR keyframes go to a hardware HEVC decoder, which runs beside the film's.
        val probe = android.media.MediaFormat.createVideoFormat(decodeMime, w, h).apply {
            if (f.containsKey(android.media.MediaFormat.KEY_PROFILE)) setInteger(android.media.MediaFormat.KEY_PROFILE, f.getInteger(android.media.MediaFormat.KEY_PROFILE))
        }
        val decoders = android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { !it.isEncoder && it.supportedTypes.any { t -> t.equals(decodeMime, true) } && !it.name.contains("secure", true) }
            .filter { runCatching { it.getCapabilitiesForType(decodeMime).isFormatSupported(probe) }.getOrDefault(false) }
        val name = (decoders.firstOrNull { it.isSoftwareOnly } ?: decoders.firstOrNull())?.name ?: error("No $decodeMime decoder for ${w}x$h")
        codec = android.media.MediaCodec.createByCodecName(name)
        codec.configure(out, reader.surface, null, 0)
        codec.start()
    }

    fun frameAt(ms: Long): Bitmap? = runCatching {
        codec.flush()
        extractor.seekTo(ms * 1000, android.media.MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        var fed = 0
        val info = android.media.MediaCodec.BufferInfo()
        val deadline = System.currentTimeMillis() + 8000
        while (System.currentTimeMillis() < deadline) {
            // Feed the keyframe (and a few after it if the decoder wants more), then end of stream.
            val inIndex = codec.dequeueInputBuffer(10_000)
            if (inIndex >= 0) {
                val inBuf = codec.getInputBuffer(inIndex)!!
                val n = if (fed < 4) extractor.readSampleData(inBuf, 0) else -1
                if (n < 0) codec.queueInputBuffer(inIndex, 0, 0, 0, android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                else { codec.queueInputBuffer(inIndex, 0, n, extractor.sampleTime, 0); extractor.advance(); fed++ }
            }
            val outIndex = codec.dequeueOutputBuffer(info, 10_000)
            if (outIndex >= 0) {
                val eos = info.flags and android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                val picture = info.size > 0 || !eos
                codec.releaseOutputBuffer(outIndex, picture)       // render the first picture to the texture
                if (picture) return@runCatching reader.read(pq)
                if (eos) return@runCatching null
            }
        }
        null
    }.onFailure { android.util.Log.w("SeekThumbs", "Base layer frame failed at $ms", it) }.getOrNull()

    fun release() {
        runCatching { codec.stop() }; runCatching { codec.release() }
        runCatching { extractor.release() }; runCatching { data.close() }
        reader.release()
    }
}

/**
 * An offscreen GL target: decoders render into [surface]; [read] draws the latest picture at w×h and
 * reads it back as a bitmap. Must be used from the thread that created it.
 */
private class FrameReader(private val w: Int, private val h: Int) {
    private val display = android.opengl.EGL14.eglGetDisplay(android.opengl.EGL14.EGL_DEFAULT_DISPLAY)
    private val context: android.opengl.EGLContext
    private val pbuffer: android.opengl.EGLSurface
    private val program: Int
    private val texture: Int
    private val texture2d: android.graphics.SurfaceTexture
    val surface: android.view.Surface
    private val events = android.os.HandlerThread("thumb-frames").apply { start() }
    private val lock = Object()
    private var ready = false

    init {
        val v = IntArray(2)
        check(android.opengl.EGL14.eglInitialize(display, v, 0, v, 1))
        val configs = arrayOfNulls<android.opengl.EGLConfig>(1); val n = IntArray(1)
        android.opengl.EGL14.eglChooseConfig(display, intArrayOf(
            android.opengl.EGL14.EGL_RED_SIZE, 8, android.opengl.EGL14.EGL_GREEN_SIZE, 8, android.opengl.EGL14.EGL_BLUE_SIZE, 8, android.opengl.EGL14.EGL_ALPHA_SIZE, 8,
            android.opengl.EGL14.EGL_RENDERABLE_TYPE, android.opengl.EGL14.EGL_OPENGL_ES2_BIT,
            android.opengl.EGL14.EGL_SURFACE_TYPE, android.opengl.EGL14.EGL_PBUFFER_BIT, android.opengl.EGL14.EGL_NONE,
        ), 0, configs, 0, 1, n, 0)
        val config = configs[0] ?: error("No EGL config")
        context = android.opengl.EGL14.eglCreateContext(display, config, android.opengl.EGL14.EGL_NO_CONTEXT, intArrayOf(android.opengl.EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, android.opengl.EGL14.EGL_NONE), 0)
        pbuffer = android.opengl.EGL14.eglCreatePbufferSurface(display, config, intArrayOf(android.opengl.EGL14.EGL_WIDTH, w, android.opengl.EGL14.EGL_HEIGHT, h, android.opengl.EGL14.EGL_NONE), 0)
        check(android.opengl.EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context))
        program = link(
            "attribute vec4 aPos; attribute vec4 aTex; uniform mat4 uTex; varying vec2 vTex;" +
                "void main() { gl_Position = aPos; vTex = (uTex * aTex).xy; }",
            "#extension GL_OES_EGL_image_external : require\nprecision mediump float; uniform samplerExternalOES sTex; varying vec2 vTex;" +
                "void main() { gl_FragColor = texture2D(sTex, vTex); }",
        )
        val t = IntArray(1); android.opengl.GLES20.glGenTextures(1, t, 0); texture = t[0]
        android.opengl.GLES20.glBindTexture(android.opengl.GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
        android.opengl.GLES20.glTexParameteri(android.opengl.GLES11Ext.GL_TEXTURE_EXTERNAL_OES, android.opengl.GLES20.GL_TEXTURE_MIN_FILTER, android.opengl.GLES20.GL_LINEAR)
        android.opengl.GLES20.glTexParameteri(android.opengl.GLES11Ext.GL_TEXTURE_EXTERNAL_OES, android.opengl.GLES20.GL_TEXTURE_MAG_FILTER, android.opengl.GLES20.GL_LINEAR)
        texture2d = android.graphics.SurfaceTexture(texture)
        texture2d.setOnFrameAvailableListener({ synchronized(lock) { ready = true; lock.notifyAll() } }, android.os.Handler(events.looper))
        surface = android.view.Surface(texture2d)
    }

    private fun link(vs: String, fs: String): Int {
        fun shader(type: Int, src: String) = android.opengl.GLES20.glCreateShader(type).also { android.opengl.GLES20.glShaderSource(it, src); android.opengl.GLES20.glCompileShader(it) }
        return android.opengl.GLES20.glCreateProgram().also {
            android.opengl.GLES20.glAttachShader(it, shader(android.opengl.GLES20.GL_VERTEX_SHADER, vs))
            android.opengl.GLES20.glAttachShader(it, shader(android.opengl.GLES20.GL_FRAGMENT_SHADER, fs))
            android.opengl.GLES20.glLinkProgram(it)
        }
    }

    /** Waits for the decoder's picture, draws it at w×h and reads it back; PQ is tone mapped to SDR. */
    fun read(pq: Boolean): Bitmap? {
        synchronized(lock) {
            val until = System.currentTimeMillis() + 3000
            while (!ready) { val left = until - System.currentTimeMillis(); if (left <= 0) return null; lock.wait(left) }
            ready = false
        }
        texture2d.updateTexImage()
        val m = FloatArray(16); texture2d.getTransformMatrix(m)
        android.opengl.GLES20.glViewport(0, 0, w, h)
        android.opengl.GLES20.glUseProgram(program)
        val quad = java.nio.ByteBuffer.allocateDirect(16 * 4).order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer()
            .put(floatArrayOf(-1f, -1f, 0f, 0f, 1f, -1f, 1f, 0f, -1f, 1f, 0f, 1f, 1f, 1f, 1f, 1f)).also { it.position(0) }
        val aPos = android.opengl.GLES20.glGetAttribLocation(program, "aPos")
        val aTex = android.opengl.GLES20.glGetAttribLocation(program, "aTex")
        quad.position(0); android.opengl.GLES20.glVertexAttribPointer(aPos, 2, android.opengl.GLES20.GL_FLOAT, false, 16, quad); android.opengl.GLES20.glEnableVertexAttribArray(aPos)
        quad.position(2); android.opengl.GLES20.glVertexAttribPointer(aTex, 2, android.opengl.GLES20.GL_FLOAT, false, 16, quad); android.opengl.GLES20.glEnableVertexAttribArray(aTex)
        android.opengl.GLES20.glUniformMatrix4fv(android.opengl.GLES20.glGetUniformLocation(program, "uTex"), 1, false, m, 0)
        android.opengl.GLES20.glActiveTexture(android.opengl.GLES20.GL_TEXTURE0)
        android.opengl.GLES20.glBindTexture(android.opengl.GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
        android.opengl.GLES20.glDrawArrays(android.opengl.GLES20.GL_TRIANGLE_STRIP, 0, 4)
        val pixels = java.nio.ByteBuffer.allocateDirect(w * h * 4).order(java.nio.ByteOrder.nativeOrder())
        android.opengl.GLES20.glReadPixels(0, 0, w, h, android.opengl.GLES20.GL_RGBA, android.opengl.GLES20.GL_UNSIGNED_BYTE, pixels)
        val px = IntArray(w * h)
        val lut = if (pq) IntArray(256) { (toneMap(it / 255f) * 255f + 0.5f).toInt().coerceIn(0, 255) } else null
        for (y in 0 until h) for (x in 0 until w) {
            val i = ((h - 1 - y) * w + x) * 4          // GL rows run bottom up
            var r = pixels.get(i).toInt() and 0xFF; var g = pixels.get(i + 1).toInt() and 0xFF; var b = pixels.get(i + 2).toInt() and 0xFF
            if (lut != null) { r = lut[r]; g = lut[g]; b = lut[b] }
            px[y * w + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    /** PQ signal → light in nits → a simple roll-off to SDR, with display gamma. */
    private fun toneMap(v: Float): Float {
        val e = Math.pow(v.coerceIn(0f, 1f).toDouble(), 1 / 78.84375)
        val nits = 10000 * Math.pow(Math.max(e - 0.8359375, 0.0) / (18.8515625 - 18.6875 * e), 1 / 0.1593017578125)
        val l = nits / 160.0
        return Math.pow(l / (1 + l * 0.6), 1 / 2.2).toFloat()
    }

    fun release() {
        runCatching { surface.release() }; runCatching { texture2d.release() }
        android.opengl.EGL14.eglMakeCurrent(display, android.opengl.EGL14.EGL_NO_SURFACE, android.opengl.EGL14.EGL_NO_SURFACE, android.opengl.EGL14.EGL_NO_CONTEXT)
        android.opengl.EGL14.eglDestroySurface(display, pbuffer)
        android.opengl.EGL14.eglDestroyContext(display, context)
        events.quitSafely()
    }
}

/** A file on an SMB share as random-access bytes for [MediaMetadataRetriever], read in large chunks. */
class SmbMediaSource(private val src: Source, private val share: String, private val path: String) : MediaDataSource() {
    private var file: com.hierynomus.smbj.share.File? = null
    private var length = -1L
    private val chunk = ByteArray(1 shl 20)
    private var chunkStart = 0L
    private var chunkLength = 0

    private fun open(): com.hierynomus.smbj.share.File = file ?: run {
        var last: Exception? = null
        repeat(3) { attempt ->
            try {
                if (attempt == 2) SmbThumbs.forget(src)
                val f = SmbThumbs.share(src, share).openFile(
                    path.replace('/', '\\'), EnumSet.of(AccessMask.GENERIC_READ), null, SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null,
                )
                length = f.fileInformation.standardInformation.endOfFile
                file = f
                return f
            } catch (e: Exception) {
                last = e
                SmbThumbs.dropClosed(src, share)
            }
        }
        throw java.io.IOException("Couldn't open $path", last)
    }

    override fun getSize(): Long { open(); return length }

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        val f = open()
        if (position >= length) return -1
        if (size <= 0) return 0
        if (position < chunkStart || position >= chunkStart + chunkLength) {
            val want = minOf(chunk.size.toLong(), length - position).toInt()
            val n = try { f.read(chunk, position, 0, want) } catch (e: Exception) { file = null; open().read(chunk, position, 0, want) }
            if (n <= 0) return -1
            chunkStart = position; chunkLength = n
        }
        val from = (position - chunkStart).toInt()
        val n = minOf(size, chunkLength - from)
        System.arraycopy(chunk, from, buffer, offset, n)
        return n
    }

    override fun close() { runCatching { file?.close() }; file = null }
}
