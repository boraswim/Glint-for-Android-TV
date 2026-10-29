package com.glint.player

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.EnumSet
import kotlin.concurrent.thread

/** Separate SMB connection for the relay, so playback never waits behind a scan. */
object SmbRelayLane : SmbLane()

/**
 * libVLC reads SMB with its own client, which always picks the SMB version itself. For a share set to a
 * fixed version (an old NAS that stutters on SMB 3, say), the film goes through this instead: a tiny HTTP
 * server on the box that reads the file with smbj at that version and hands it to libVLC, seeks included.
 * URL: http://127.0.0.1:<port>/<sourceId>/<share>/<path>, each part percent-encoded.
 */
object SmbRelay {
    @Volatile private var server: ServerSocket? = null

    @Synchronized
    private fun port(): Int {
        server?.takeIf { !it.isClosed }?.let { return it.localPort }
        val s = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        server = s
        thread(name = "smb-relay", isDaemon = true) {
            while (!s.isClosed) {
                val client = runCatching { s.accept() }.getOrNull() ?: break
                thread(name = "smb-relay-conn", isDaemon = true) { runCatching { serve(client) } }
            }
        }
        return s.localPort
    }

    fun url(s: Source, share: String, path: String): String =
        "http://127.0.0.1:${port()}/" + (listOf(s.id, share) + path.split('/')).joinToString("/") { android.net.Uri.encode(it) }

    private fun serve(socket: Socket): Unit = socket.use { sock ->
        sock.soTimeout = 60_000
        val reader = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.ISO_8859_1))
        val request = reader.readLine() ?: return
        var range: String? = null
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            if (line.startsWith("Range:", ignoreCase = true)) range = line.substringAfter(':').trim()
        }
        val (method, target) = request.split(' ').let { (it.getOrNull(0) ?: "") to (it.getOrNull(1) ?: "") }
        val parts = target.trimStart('/').split('/').map { URLDecoder.decode(it.replace("+", "%2B"), "UTF-8") }
        val out = sock.getOutputStream()
        val src = parts.getOrNull(0)?.let { App.library.source(it) }
        if (src == null || parts.size < 3) { out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n".toByteArray()); return }
        val share = parts[1]
        val path = parts.drop(2).joinToString("\\")
        val file = SmbRelayLane.withRetry(src) {
            SmbRelayLane.share(src, share).openFile(path, EnumSet.of(AccessMask.GENERIC_READ), null, SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null)
        }
        file.use { f ->
            val size = f.fileInformation.standardInformation.endOfFile
            var start = 0L
            var end = size - 1
            range?.removePrefix("bytes=")?.split('-')?.let { r ->
                r.getOrNull(0)?.toLongOrNull()?.let { start = it }
                r.getOrNull(1)?.toLongOrNull()?.let { end = minOf(it, size - 1) }
            }
            if (start >= size && size > 0) {
                out.write("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */$size\r\nContent-Length: 0\r\n\r\n".toByteArray()); return
            }
            val length = (end - start + 1).coerceAtLeast(0)
            val head = if (range != null) "HTTP/1.1 206 Partial Content\r\nContent-Range: bytes $start-$end/$size\r\n" else "HTTP/1.1 200 OK\r\n"
            out.write((head + "Accept-Ranges: bytes\r\nContent-Type: application/octet-stream\r\nContent-Length: $length\r\nConnection: close\r\n\r\n").toByteArray())
            if (method == "HEAD") return
            // Reads of the server's largest size, straight to libVLC.
            val maxRead = runCatching { f.diskShare.treeConnect.session.connection.negotiatedProtocol.maxReadSize }.getOrDefault(64 * 1024)
            val buf = ByteArray(maxRead.coerceIn(64 * 1024, 1 shl 20))
            var pos = start
            while (pos <= end) {
                val n = f.read(buf, pos, 0, minOf(buf.size.toLong(), end - pos + 1).toInt())
                if (n <= 0) break
                out.write(buf, 0, n)   // throws once libVLC hangs up (a seek): this connection is done
                pos += n
            }
            out.flush()
        }
    }
}
