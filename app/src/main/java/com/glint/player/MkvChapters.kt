package com.glint.player

import java.io.EOFException
import java.io.IOException
import java.io.InputStream

/**
 * Chapter names and start times (ms) of an MKV file, for the Dolby Vision player: Media3 doesn't expose
 * MKV chapters, and intro and credits skipping needs them. Reads the file from the start up to its
 * Chapters element, which muxers put before the video; a file that keeps it further on has none here.
 */
object MkvChapters {
    private const val EBML = 0x1A45DFA3L
    private const val SEGMENT = 0x18538067L
    private const val SEEKHEAD = 0x114D9B74L
    private const val SEEK = 0x4DBBL
    private const val SEEK_ID = 0x53ABL
    private const val SEEK_POSITION = 0x53ACL
    private const val CLUSTER = 0x1F43B675L
    private const val CHAPTERS = 0x1043A770L
    private const val EDITION = 0x45B9L
    private const val ATOM = 0xB6L
    private const val TIME_START = 0x91L
    private const val HIDDEN = 0x98L
    private const val DISPLAY = 0x80L
    private const val NAME = 0x85L
    private const val LIMIT = 64L shl 20   // how far into the file to look

    private const val TRACKS = 0x1654AE6BL
    private const val TRACK_ENTRY = 0xAEL
    private const val VIDEO = 0xE0L
    private const val PIXEL_WIDTH = 0xB0L
    private const val PIXEL_HEIGHT = 0xBAL

    /**
     * The picture size of an MKV's first video track, as "4K", "1080p" or "720p" (blank when unknown or smaller):
     * for files whose name doesn't say. Reads only the start of the file, up to its track list.
     */
    fun resolution(src: Source, share: String, path: String): String {
        if (Names.ext(path) != "mkv") return ""
        val (w, h) = Fs.open(src, share, path).use { runCatching { Reader(it.buffered(64 * 1024)).videoSize() }.getOrNull() } ?: return ""
        return when {
            w >= 3200 || h >= 2000 -> "4K"
            w >= 1800 || h >= 1000 -> "1080p"
            w >= 1200 || h >= 700 -> "720p"
            else -> ""
        }
    }

    fun read(src: Source, share: String, path: String): List<Pair<String, Long>> {
        if (Names.ext(path) != "mkv") return emptyList()
        return Fs.open(src, share, path).use { runCatching { Reader(it.buffered(64 * 1024)).chapters() }.getOrDefault(emptyList()) }
    }

    private class Reader(private val input: InputStream) {
        var pos = 0L

        fun byte(): Int {
            val b = input.read()
            if (b < 0) throw EOFException()
            pos++
            return b
        }

        fun skipTo(target: Long) {
            if (target < pos || target > LIMIT) throw IOException("Out of reach")
            while (pos < target) {
                val n = input.skip(target - pos)
                if (n > 0) pos += n else byte()
            }
        }

        /** An element ID keeps its length marker; a size drops it (all ones means "unknown"). */
        fun vint(keepMarker: Boolean): Long {
            val first = byte()
            var len = 1
            while (len <= 8 && first and (0x80 shr (len - 1)) == 0) len++
            var v = (if (keepMarker) first else first and (0xFF shr len)).toLong()
            repeat(len - 1) { v = (v shl 8) or byte().toLong() }
            return if (!keepMarker && v == (1L shl (7 * len)) - 1) -1 else v
        }

        fun uint(size: Long): Long {
            var v = 0L
            repeat(size.toInt()) { v = (v shl 8) or byte().toLong() }
            return v
        }

        /** Width and height of the first video track; the track list comes before the video in a muxed file. */
        fun videoSize(): Pair<Int, Int>? {
            if (vint(true) != EBML) return null
            skipTo(vint(false) + pos)
            if (vint(true) != SEGMENT) return null
            vint(false)
            while (true) {
                val id = vint(true)
                val size = vint(false)
                if (id == CLUSTER || size < 0) return null
                val end = pos + size
                if (id == TRACKS) {
                    while (pos < end) {
                        val entry = vint(true)
                        val entryEnd = vint(false) + pos
                        if (entry == TRACK_ENTRY) {
                            while (pos < entryEnd) {
                                val inner = vint(true)
                                val innerEnd = vint(false) + pos
                                if (inner == VIDEO) {
                                    var w = 0L
                                    var h = 0L
                                    while (pos < innerEnd) {
                                        val v = vint(true)
                                        val vs = vint(false)
                                        when (v) {
                                            PIXEL_WIDTH -> w = uint(vs)
                                            PIXEL_HEIGHT -> h = uint(vs)
                                            else -> skipTo(pos + vs)
                                        }
                                    }
                                    if (w > 0 && h > 0) return w.toInt() to h.toInt()
                                }
                                skipTo(innerEnd)
                            }
                        }
                        skipTo(entryEnd)
                    }
                    return null
                }
                skipTo(end)
            }
        }

        fun chapters(): List<Pair<String, Long>> {
            if (vint(true) != EBML) return emptyList()
            skipTo(vint(false) + pos)
            if (vint(true) != SEGMENT) return emptyList()
            vint(false)
            val segment = pos
            var chaptersAt: Long? = null
            while (true) {
                val id = vint(true)
                val size = vint(false)
                val end = pos + size
                when {
                    id == CHAPTERS -> return editions(end)
                    // The video starts: the SeekHead says whether chapters come later.
                    id == CLUSTER || size < 0 -> {
                        val at = chaptersAt?.takeIf { it > pos } ?: return emptyList()
                        skipTo(at)
                        chaptersAt = null
                        continue
                    }
                    id == SEEKHEAD -> chaptersAt = seekHead(end)?.let { segment + it }
                }
                skipTo(end)
            }
        }

        private fun seekHead(end: Long): Long? {
            var found: Long? = null
            while (pos < end) {
                val id = vint(true)
                val stop = vint(false) + pos
                if (id == SEEK) {
                    var target = 0L
                    var at = 0L
                    while (pos < stop) {
                        val inner = vint(true)
                        val size = vint(false)
                        when (inner) {
                            SEEK_ID -> target = uint(size)
                            SEEK_POSITION -> at = uint(size)
                            else -> skipTo(pos + size)
                        }
                    }
                    if (target == CHAPTERS) found = at
                }
                skipTo(stop)
            }
            return found
        }

        /** The first edition's chapters; later editions are alternative cuts. */
        private fun editions(end: Long): List<Pair<String, Long>> {
            val out = ArrayList<Pair<String, Long>>()
            while (pos < end) {
                val id = vint(true)
                val stop = vint(false) + pos
                if (id == EDITION && out.isEmpty()) {
                    while (pos < stop) {
                        val inner = vint(true)
                        val atomEnd = vint(false) + pos
                        if (inner == ATOM) atom(atomEnd)?.let { out += it }
                        skipTo(atomEnd)
                    }
                }
                skipTo(stop)
            }
            return out.sortedBy { it.second }
        }

        private fun atom(end: Long): Pair<String, Long>? {
            var start = 0L
            var name = ""
            var hidden = false
            while (pos < end) {
                val id = vint(true)
                val size = vint(false)
                val stop = pos + size
                when (id) {
                    TIME_START -> start = uint(size)
                    HIDDEN -> hidden = uint(size) == 1L
                    DISPLAY -> while (pos < stop) {
                        val inner = vint(true)
                        val len = vint(false)
                        if (inner == NAME && name.isEmpty()) name = String(ByteArray(len.toInt()) { byte().toByte() }, Charsets.UTF_8)
                        else skipTo(pos + len)
                    }
                }
                skipTo(stop)
            }
            return if (hidden) null else name to start / 1_000_000
        }
    }
}
