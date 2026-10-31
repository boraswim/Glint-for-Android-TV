package com.glint.player

import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Handler
import androidx.media3.common.Format
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener
import java.io.ByteArrayOutputStream

/**
 * Dolby Vision compatibility mode, as Kodi's (which uses libdovi): profile 7 films (UHD Blu-ray, base
 * layer + enhancement layer) are turned into profile 8.1 while they play. The enhancement layer's NAL
 * units (type 63) are dropped and each RPU (type 62) is rewritten the way libdovi's conversion mode 2
 * does it: no residual data, identity mapping curves, display metadata kept bit for bit.
 * For TVs whose Dolby Vision decoder handles 8.1 but not 7. Full enhancement layer detail is lost.
 */
object DoviConvert {
    /** Rewrites one Annex B access unit; returns the new bytes, or null to leave it as it is. */
    fun accessUnit(src: ByteArray, len: Int): ByteArray? {
        val starts = ArrayList<IntArray>()          // [start code offset, payload offset]
        var i = 0
        while (i + 3 <= len) {
            if (src[i].toInt() == 0 && src[i + 1].toInt() == 0) {
                if (src[i + 2].toInt() == 1) { starts += intArrayOf(i, i + 3); i += 3; continue }
                if (i + 4 <= len && src[i + 2].toInt() == 0 && src[i + 3].toInt() == 1) { starts += intArrayOf(i, i + 4); i += 4; continue }
            }
            i++
        }
        if (starts.isEmpty() || starts[0][0] != 0) return null
        if (starts.none { nalType(src, it[1], len) == 62 || nalType(src, it[1], len) == 63 }) return null
        val out = ByteArrayOutputStream(len)
        for ((k, s) in starts.withIndex()) {
            val end = if (k + 1 < starts.size) starts[k + 1][0] else len
            val p = s[1]
            when (nalType(src, p, end)) {
                63 -> {}                                                   // enhancement layer: dropped
                62 -> {
                    val rpu = convertRpu(unescape(src, p + 2, end)) ?: return null
                    out.write(START); out.write(src, p, 2); out.write(escape(rpu))
                }
                else -> { out.write(START); out.write(src, p, end - p) }
            }
        }
        return out.toByteArray()
    }

    private val START = byteArrayOf(0, 0, 0, 1)
    private fun nalType(b: ByteArray, p: Int, end: Int) = if (p < end) (b[p].toInt() shr 1) and 0x3F else -1

    // ---- RPU ----

    private class Reader(val d: ByteArray) {
        var p = 0
        /** Reads n bits; past 63 the value is meaningless, which is fine for fields only skipped. */
        fun u(n: Int): Long {
            var v = 0L
            repeat(n) { v = (v shl 1) or ((d[p ushr 3].toInt() shr (7 - (p and 7))) and 1).toLong(); p++ }
            return v
        }
        fun ue(): Long { var z = 0; while (u(1) == 0L) { z++; check(z < 32) }; return (1L shl z) - 1 + u(z) }
        fun se() { ue() }
    }

    private class Writer {
        private val out = ByteArrayOutputStream()
        private var cur = 0
        var bits = 0; private set
        fun u(n: Int, v: Long) {
            for (i in n - 1 downTo 0) {
                cur = (cur shl 1) or ((v ushr i) and 1).toInt(); bits++
                if (bits % 8 == 0) { out.write(cur); cur = 0 }
            }
        }
        fun ue(v: Long) { val x = v + 1; val n = 64 - java.lang.Long.numberOfLeadingZeros(x); u(n - 1, 0); u(n, x) }
        fun se(v: Long) = ue(if (v > 0) 2 * v - 1 else -2 * v)
        fun align() { while (bits % 8 != 0) u(1, 0) }
        fun bytes(): ByteArray { align(); return out.toByteArray() }
    }

    /** Unescaped RPU (from the 0x19 prefix to the 0x80 end byte) → profile 8.1 RPU, or null if not understood. */
    fun convertRpu(rpu: ByteArray): ByteArray? = runCatching {
        if (rpu.size < 8 || rpu[0].toInt() != 0x19 || rpu.last().toInt() and 0xFF != 0x80) return null
        val r = Reader(rpu)
        val w = Writer()
        w.u(8, r.u(8))
        val rpuType = r.u(6); w.u(6, rpuType)
        val rpuFormat = r.u(11); w.u(11, rpuFormat)
        if (rpuType != 2L || rpuFormat and 0x700L != 0L) return null
        w.u(4, r.u(4)); w.u(4, r.u(4))                                  // vdr_rpu_profile, vdr_rpu_level
        if (r.u(1) == 0L) return null; w.u(1, 1)                        // vdr_seq_info_present_flag
        w.u(1, r.u(1))                                                  // chroma_resampling_explicit_filter_flag
        val cdt = r.u(2); w.u(2, cdt)
        if (cdt != 0L) return null
        val denom = r.ue(); w.ue(denom)
        w.u(2, r.u(2)); w.u(1, r.u(1))                                  // vdr_rpu_normalized_idc, bl_video_full_range_flag
        val blBits = (r.ue() + 8).toInt(); w.ue(blBits - 8L)
        val elRaw = r.ue(); w.ue(elRaw)
        val elBits = ((elRaw and 0xFFL) + 8).toInt()
        w.ue(r.ue())                                                    // vdr_bit_depth_minus8
        w.u(1, r.u(1)); w.u(3, r.u(3))                                  // spatial_resampling_filter_flag, reserved
        r.u(1); w.u(1, 0)                                               // el_spatial_resampling_filter_flag
        val disableResidual = r.u(1) == 1L; w.u(1, 1)                   // disable_residual_flag
        val dmPresent = r.u(1); w.u(1, dmPresent)
        if (r.u(1) == 1L) return null; w.u(1, 0)                        // use_prev_vdr_rpu_flag
        w.ue(r.ue()); w.ue(r.ue()); w.ue(r.ue())                        // vdr_rpu_id, mapping_color_space, mapping_chroma_format_idc
        val pivots = IntArray(3)
        for (c in 0 until 3) { pivots[c] = (r.ue() + 2).toInt(); repeat(pivots[c]) { r.u(blBits) } }
        // With residual data: nlq_method_idc and the two NLQ pivots (nlq_num_pivots_minus2 is always 0).
        val nlqMethod = if (!disableResidual) r.u(3).also { repeat(2) { r.u(blBits) } } else 0L
        if (r.ue() != 0L || r.ue() != 0L) return null                   // partitions
        // New header tail: two pivots spanning the whole range, one partition.
        val top = (1L shl blBits) - 1
        repeat(3) { w.ue(0); w.u(blBits, 0); w.u(blBits, top) }
        w.ue(0); w.ue(0)
        // Skip the old mapping…
        for (c in 0 until 3) repeat(pivots[c] - 1) {
            when (r.ue()) {
                0L -> {
                    val order = (r.ue() + 1).toInt()
                    if (order == 1 && r.u(1) == 1L) return null          // linear interpolation: not in any known disc
                    repeat(order + 1) { r.se(); r.u(denom.toInt()) }
                }
                1L -> {
                    val mmr = (r.u(2) + 1).toInt()
                    r.se(); r.u(denom.toInt())
                    repeat(mmr * 7) { r.se(); r.u(denom.toInt()) }
                }
                else -> return null
            }
        }
        // …and write y = 0 + 1·x for each component.
        repeat(3) { w.ue(0); w.ue(0); w.u(1, 0); w.se(0); w.u(denom.toInt(), 0); w.se(1); w.u(denom.toInt(), 0) }
        // Skip the residual (NLQ) data.
        if (!disableResidual) {
            if (nlqMethod != 0L) return null
            repeat(3) { r.u(elBits); r.ue(); r.u(denom.toInt()); r.ue(); r.u(denom.toInt()); r.ue(); r.u(denom.toInt()) }
        }
        // Display metadata: fixed fields and the block count bit for bit; blocks start byte aligned in both.
        if (dmPresent == 1L) {
            val fixedStart = r.p
            r.ue(); r.ue(); r.ue()
            r.u(16 * 9); r.u(32 * 3); r.u(16 * 9)
            r.u(16 * 3); r.u(32); r.u(5 + 2 + 2 + 2); r.u(12 + 12 + 10)
            r.ue()
            val copyEnd = r.p
            r.p = fixedStart
            while (r.p < copyEnd) { val n = minOf(32, copyEnd - r.p); w.u(n, r.u(n)) }
            w.align()
            val tailStart = (copyEnd + 7) / 8
            val body = w.bytes() + rpu.copyOfRange(tailStart, rpu.size - 5)
            return finish(body)
        }
        finish(w.bytes())
    }.getOrNull()

    private fun finish(body: ByteArray): ByteArray {
        val crc = crc32Mpeg2(body, 1, body.size)
        return body + byteArrayOf((crc ushr 24).toByte(), (crc ushr 16).toByte(), (crc ushr 8).toByte(), crc.toByte(), 0x80.toByte())
    }

    private fun crc32Mpeg2(b: ByteArray, from: Int, to: Int): Int {
        var crc = -1
        for (i in from until to) {
            crc = crc xor ((b[i].toInt() and 0xFF) shl 24)
            repeat(8) { crc = if (crc < 0) (crc shl 1) xor 0x04C11DB7 else crc shl 1 }
        }
        return crc
    }

    private fun unescape(b: ByteArray, from: Int, to: Int): ByteArray {
        val out = ByteArrayOutputStream(to - from)
        var zeros = 0
        for (i in from until to) {
            val x = b[i].toInt() and 0xFF
            if (zeros >= 2 && x == 3) { zeros = 0; continue }
            out.write(x); zeros = if (x == 0) zeros + 1 else 0
        }
        // Trailing zero bytes belong to the next start code's padding, not the RPU.
        val a = out.toByteArray()
        var n = a.size
        while (n > 0 && a[n - 1].toInt() == 0) n--
        return a.copyOf(n)
    }

    private fun escape(b: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(b.size + 8)
        var zeros = 0
        for (v in b) {
            val x = v.toInt() and 0xFF
            if (zeros >= 2 && x <= 3) { out.write(3); zeros = 0 }
            out.write(x); zeros = if (x == 0) zeros + 1 else 0
        }
        return out.toByteArray()
    }
}

/** The video renderer used for Dolby Vision: converts profile 7 to 8.1 on the way to the decoder when asked. */
class DoviVideoRenderer(
    context: Context, private val factory: DoviRenderersFactory, selector: MediaCodecSelector, fallback: Boolean,
    handler: Handler?, listener: VideoRendererEventListener?, joiningMs: Long,
) : MediaCodecVideoRenderer(
    context, factory.codecAdapter(), selector, joiningMs, fallback, handler, listener,
    DefaultRenderersFactory.MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY,
) {
    private var converting = false
    private var scratch = ByteArray(0)

    override fun getMediaFormat(
        format: Format, codecMimeType: String, codecMaxValues: MediaCodecVideoRenderer.CodecMaxValues, codecOperatingRate: Float,
        deviceNeedsNoPostProcessWorkaround: Boolean, tunnelingAudioSessionId: Int,
    ): MediaFormat {
        val f = super.getMediaFormat(format, codecMimeType, codecMaxValues, codecOperatingRate, deviceNeedsNoPostProcessWorkaround, tunnelingAudioSessionId)
        converting = App.library.settings.doviCompat && format.codecs.orEmpty().startsWith("dvhe.07")
        // The decoder is set up as profile 8 (dvhe.st), the stream it will get.
        if (converting) f.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvheSt)
        android.util.Log.i("DoviConvert", "Format ${format.codecs}: ${if (converting) "converting profile 7 to 8.1" else "as is"}")
        return f
    }

    override fun onQueueInputBuffer(buffer: DecoderInputBuffer) {
        super.onQueueInputBuffer(buffer)
        factory.videoBytes += buffer.data?.limit() ?: 0
        if (!converting) return
        val data = buffer.data ?: return
        val len = data.limit()
        if (len == 0) return
        if (scratch.size < len) scratch = ByteArray(len + (len shr 2))
        data.position(0); data.get(scratch, 0, len)
        val out = DoviConvert.accessUnit(scratch, len) ?: run { data.position(0); return }
        data.clear(); data.put(out); data.flip()
    }
}

/** Builds Media3's renderers with [DoviVideoRenderer] in place of its plain video renderer. */
class DoviRenderersFactory(context: Context) : DefaultRenderersFactory(context) {
    fun codecAdapter() = codecAdapterFactory

    /** Subtitle delay in microseconds (positive: later), read live by the text renderers. */
    @Volatile var subtitleOffsetUs = 0L

    /** Video handed to the decoder so far: how fast it grows is the film's live bitrate. */
    @Volatile var videoBytes = 0L

    override fun buildTextRenderers(context: Context, output: androidx.media3.exoplayer.text.TextOutput, outputLooper: android.os.Looper, extensionRendererMode: Int, out: ArrayList<Renderer>) {
        super.buildTextRenderers(context, output, outputLooper, extensionRendererMode, out)
        // Subtitles run on a clock shifted by the delay: shown later (or earlier) than the picture.
        for (i in out.indices) if (out[i] is androidx.media3.exoplayer.text.TextRenderer) out[i] = DelayedRenderer(out[i]) { subtitleOffsetUs }
    }

    private class DelayedRenderer(r: Renderer, private val offsetUs: () -> Long) : androidx.media3.exoplayer.ForwardingRenderer(r) {
        override fun render(positionUs: Long, elapsedRealtimeUs: Long) = super.render(positionUs - offsetUs(), elapsedRealtimeUs)
    }

    override fun buildVideoRenderers(
        context: Context, extensionRendererMode: Int, mediaCodecSelector: MediaCodecSelector, enableDecoderFallback: Boolean,
        eventHandler: Handler, eventListener: VideoRendererEventListener, allowedVideoJoiningTimeMs: Long, out: ArrayList<Renderer>,
    ) {
        super.buildVideoRenderers(context, extensionRendererMode, mediaCodecSelector, enableDecoderFallback, eventHandler, eventListener, allowedVideoJoiningTimeMs, out)
        val i = out.indexOfFirst { it.javaClass == MediaCodecVideoRenderer::class.java }
        if (i >= 0) out[i] = DoviVideoRenderer(context, this, mediaCodecSelector, enableDecoderFallback, eventHandler, eventListener, allowedVideoJoiningTimeMs)
    }
}
