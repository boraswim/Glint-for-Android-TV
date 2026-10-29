package com.glint.player

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.security.bc.BCSecurityProvider
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.rapid7.client.dcerpc.mssrvs.ServerService
import com.rapid7.client.dcerpc.transport.SMBTransportFactories
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.EnumSet
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class SmbEntry(val name: String, val path: String, val isDir: Boolean, val size: Long, val modified: Long)

data class FoundHost(val address: String, val name: String)

/**
 * SMB access through smbj. Sessions and shares are kept open and reused; a broken one is
 * dropped and reopened once. Android has no MD4 for NTLM, hence BouncyCastle.
 */
open class SmbLane {
    /** One client per SMB version a share asks for ("" = any); each offers only that dialect to the server. */
    private val clients = ConcurrentHashMap<String, SMBClient>()
    private fun client(version: String) = clients.getOrPut(version) {
        SMBClient(
            SmbConfig.builder()
                .withSecurityProvider(BCSecurityProvider())
                .withTimeout(20, TimeUnit.SECONDS)
                .withSoTimeout(30, TimeUnit.SECONDS)
                .withMultiProtocolNegotiate(true)
                .apply {
                    when (version) {
                        "2.0" -> withDialects(com.hierynomus.mssmb2.SMB2Dialect.SMB_2_0_2)
                        "2.1" -> withDialects(com.hierynomus.mssmb2.SMB2Dialect.SMB_2_1)
                        "3.0" -> withDialects(com.hierynomus.mssmb2.SMB2Dialect.SMB_3_0)
                    }
                }
                .build()
        )
    }
    private val sessions = ConcurrentHashMap<String, Session>()
    private val shares = ConcurrentHashMap<String, DiskShare>()

    private fun sessionKey(s: Source) = "${s.host}|${s.domain}|${s.user}|${s.smbVersion}"

    @Synchronized
    private fun session(s: Source): Session {
        sessions[sessionKey(s)]?.takeIf { it.connection.isConnected }?.let { return it }
        val connection = client(s.smbVersion).connect(s.host)
        val contexts = if (s.user.isBlank()) listOf(AuthenticationContext.guest(), AuthenticationContext.anonymous())
        else listOf(AuthenticationContext(s.user, s.password.toCharArray(), s.domain.ifBlank { null }))
        var error: Exception? = null
        for (auth in contexts) {
            try {
                val session = connection.authenticate(auth)
                sessions[sessionKey(s)] = session
                return session
            } catch (e: Exception) {
                error = e
            }
        }
        throw error!!
    }

    @Synchronized
    fun share(s: Source, name: String): DiskShare {
        val key = "${sessionKey(s)}|$name"
        shares[key]?.takeIf { it.isConnected }?.let { return it }
        val share = session(s).connectShare(name) as DiskShare
        shares[key] = share
        return share
    }

    /**
     * Runs [block], reconnecting once if the server dropped us (NAS sleep, Wi-Fi blip). An error the
     * server answered with (access denied, no such file) isn't a broken connection: reconnecting
     * for it would cut off everything else using the share, like posters loading during a scan.
     */
    fun <T> withRetry(s: Source, block: () -> T): T = try {
        block()
    } catch (e: SMBApiException) {
        throw e
    } catch (e: Exception) {
        forget(s)
        block()
    }

    /**
     * Drops the cached session and shares and force-closes their connection. A NAS that stopped
     * answering (too many requests at once) never replies to a polite logoff, and reusing that
     * connection would hang the reconnect too.
     */
    fun forget(s: Source) {
        val session = sessions.remove(sessionKey(s))
        shares.keys.filter { it.startsWith(sessionKey(s) + "|") }.forEach { shares.remove(it) }
        session?.let { runCatching { it.connection.close(true) } }
    }

    /** Forgets a cached share that was closed or lost its connection, leaving the session to others. */
    fun dropClosed(s: Source, name: String) {
        val key = "${sessionKey(s)}|$name"
        shares[key]?.takeIf { !it.isConnected }?.let { shares.remove(key) }
        sessions[sessionKey(s)]?.takeIf { !it.connection.isConnected }?.let { sessions.remove(sessionKey(s)) }
    }

    /** Visible disk shares on the host (hidden "$" shares and printers left out). */
    fun listShares(s: Source): List<String> = withRetry(s) {
        try {
            val transport = SMBTransportFactories.SRVSVC.getTransport(session(s))
            ServerService(transport).shares1
                .filter { (it.type and 0xFFFF) == 0 && !it.netName.endsWith("$") }
                .map { it.netName }
                .sortedBy { it.lowercase() }
        } catch (e: LinkageError) {
            // dcerpc reports malformed replies with java.rmi exceptions, which Android doesn't have.
            throw java.io.IOException("Couldn't list shares", e)
        }
    }

    fun list(s: Source, share: String, path: String): List<SmbEntry> = withRetry(s) {
        share(s, share).list(path.toSmb()).mapNotNull { f ->
            val name = f.fileName
            if (name == "." || name == "..") return@mapNotNull null
            val isDir = (f.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value) != 0L
            val hidden = (f.fileAttributes and FileAttributes.FILE_ATTRIBUTE_HIDDEN.value) != 0L
            if (name == ".nomedia") return@mapNotNull SmbEntry(name, join(path, name), false, 0, 0)
            if (hidden || name.startsWith(".") || name.startsWith("$") || name.startsWith("@") || name in SKIP_DIRS) return@mapNotNull null
            SmbEntry(name, join(path, name), isDir, f.endOfFile, f.lastWriteTime.toEpochMillis())
        }
    }

    /** Writes a whole file, creating folders on the way. */
    fun write(s: Source, share: String, path: String, bytes: ByteArray) = withRetry(s) {
        val disk = share(s, share)
        val dir = path.substringBeforeLast('/', "")
        if (dir.isNotEmpty() && !disk.folderExists(dir.toSmb())) disk.mkdir(dir.toSmb())
        disk.openFile(
            path.toSmb(), EnumSet.of(AccessMask.GENERIC_WRITE), null,
            SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OVERWRITE_IF, null,
        ).use { f -> f.write(bytes, 0) }
    }

    fun open(s: Source, share: String, path: String): InputStream = withRetry(s) {
        val file = share(s, share).openFile(
            path.toSmb(), EnumSet.of(AccessMask.GENERIC_READ), null,
            SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null,
        )
        object : java.io.FilterInputStream(file.inputStream) {
            override fun close() {
                super.close()
                file.close()
            }
        }
    }

    /** Reads a file for [seconds] the way playback does (one sequential stream) and returns megabits per second. */
    fun measure(s: Source, share: String, path: String, seconds: Int = 10): Double = open(s, share, path).use { input ->
        val buf = ByteArray(1 shl 20)
        val start = System.nanoTime()
        val deadline = start + seconds * 1_000_000_000L
        var total = 0L
        while (System.nanoTime() < deadline) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
        }
        total * 8 / 1e6 / ((System.nanoTime() - start) / 1e9)
    }

    fun readBytes(s: Source, share: String, path: String, limit: Int = 20 shl 20): ByteArray =
        open(s, share, path).use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                if (out.size() > limit) break
            }
            out.toByteArray()
        }

    fun join(dir: String, name: String) = if (dir.isEmpty()) name else "$dir/$name"

    private fun String.toSmb() = replace('/', '\\')

    /**
     * URL libVLC opens with its own SMB client; credentials go in as media options instead. A share set to
     * a fixed SMB version plays through [SmbRelay] instead, as libVLC's client can't be told the version.
     */
    fun vlcUrl(s: Source, share: String, path: String): String {
        if (s.smbVersion.isNotEmpty()) return SmbRelay.url(s, share, path)
        val encoded = (listOf(share) + path.split('/')).joinToString("/") { android.net.Uri.encode(it) }
        return "smb://${s.host}/$encoded"
    }

    /** Hosts on this subnet with SMB open, named by NetBIOS where they answer. */
    fun discover(): List<FoundHost> {
        val local = NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress } ?: return emptyList()
        val prefix = local.hostAddress!!.substringBeforeLast('.')
        val pool = Executors.newFixedThreadPool(48)
        try {
            val jobs = (1..254).map { i ->
                pool.submit(Callable {
                    val ip = "$prefix.$i"
                    val open = runCatching { Socket().use { it.connect(InetSocketAddress(ip, 445), 400); true } }.getOrDefault(false)
                    if (open) FoundHost(ip, netbiosName(ip) ?: ip) else null
                })
            }
            return jobs.mapNotNull { runCatching { it.get(5, TimeUnit.SECONDS) }.getOrNull() }
        } finally {
            pool.shutdownNow()
        }
    }

    /** NetBIOS node status query (UDP 137); the first unique name is the machine name. */
    private fun netbiosName(ip: String): String? = runCatching {
        val q = ByteArray(50)
        q[0] = 0x13; q[1] = 0x37         // transaction id
        q[5] = 1                         // one question
        q[12] = 0x20                     // encoded name length
        q[13] = 'C'.code.toByte(); q[14] = 'K'.code.toByte()   // "*" encoded
        for (i in 15 until 45) q[i] = 'A'.code.toByte()
        q[47] = 0x21; q[49] = 1          // type NBSTAT, class IN
        DatagramSocket().use { sock ->
            sock.soTimeout = 700
            sock.send(DatagramPacket(q, q.size, InetAddress.getByName(ip), 137))
            val r = ByteArray(1024)
            val p = DatagramPacket(r, r.size)
            sock.receive(p)
            val count = r[56].toInt() and 0xFF
            (0 until count).map { i -> String(r, 57 + i * 18, 15, Charsets.US_ASCII).trim() }.firstOrNull { it.isNotEmpty() }
        }
    }.getOrNull()

    private val SKIP_DIRS = setOf("System Volume Information", "lost+found", "#recycle", "Recycle Bin")
}

/** Everything SMB: browsing, scanning, playback setup. */
object Smb : SmbLane()

/**
 * Posters and fanart get their own connection to the NAS, so a scan keeping the main one busy
 * (or a slow NAS stalling it) never leaves the library without pictures.
 */
object SmbImages : SmbLane()
