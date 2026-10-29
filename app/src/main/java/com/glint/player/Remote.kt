package com.glint.player

import android.net.Uri
import com.emc.ecs.nfsclient.nfs.io.Nfs3File
import com.emc.ecs.nfsclient.nfs.io.NfsFileInputStream
import com.emc.ecs.nfsclient.nfs.io.NfsFileOutputStream
import com.emc.ecs.nfsclient.nfs.nfs3.Nfs3
import com.emc.ecs.nfsclient.rpc.CredentialUnix
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPSClient
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * One kind of network source. "Shares" only exist for SMB; the others have a single root and
 * use "" as their share. Paths use "/" and are relative to the source's root.
 */
interface Remote {
    fun shares(s: Source): List<String> = listOf("")
    fun list(s: Source, share: String, path: String): List<SmbEntry>
    fun open(s: Source, share: String, path: String): InputStream
    fun write(s: Source, share: String, path: String, bytes: ByteArray)
    /** What libVLC opens to play the file. */
    fun playUrl(s: Source, share: String, path: String): String
    fun forget(s: Source) {}
}

/** Picks the client for a source's type, so the rest of the app never cares which protocol it is. */
object Fs {
    val TYPES = listOf("smb", "usb", "webdav", "ftp", "ftps", "sftp", "nfs", "upnp")

    private fun of(s: Source): Remote = when (s.type) {
        "usb" -> UsbDisk
        "webdav" -> WebDav
        "ftp", "ftps" -> Ftp
        "sftp" -> Sftp
        "nfs" -> Nfs
        "upnp" -> Upnp
        else -> SmbRemote
    }

    fun listShares(s: Source) = of(s).shares(s)
    fun list(s: Source, share: String, path: String) = of(s).list(s, share, path)
    fun open(s: Source, share: String, path: String) = of(s).open(s, share, path)
    fun write(s: Source, share: String, path: String, bytes: ByteArray) = of(s).write(s, share, path, bytes)
    fun vlcUrl(s: Source, share: String, path: String) = of(s).playUrl(s, share, path)
    fun forget(s: Source) = of(s).forget(s)

    fun readBytes(s: Source, share: String, path: String, limit: Int = 20 shl 20): ByteArray = open(s, share, path).use { input ->
        val out = ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0 || out.size() > limit) break
            out.write(buf, 0, n)
        }
        out.toByteArray()
    }

    /** Reads for [seconds] like playback does and returns megabits per second. */
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

    /** "user:password@" for URLs, percent-encoded, or "" for anonymous access. */
    fun userInfo(s: Source) = if (s.user.isBlank()) "" else Uri.encode(s.user) + (if (s.password.isNotEmpty()) ":" + Uri.encode(s.password) else "") + "@"

    fun encodePath(path: String) = path.split('/').filter { it.isNotEmpty() }.joinToString("/") { Uri.encode(it) }

    /** The source's root folder joined with a relative path. */
    fun rooted(s: Source, path: String) = listOf(s.folder.trim('/'), path.trim('/')).filter { it.isNotEmpty() }.joinToString("/")

    fun visible(name: String) = !name.startsWith(".") || name == ".nomedia"
}

/**
 * A USB disk plugged into the TV, read straight from where Android mounts it ([Source.host], e.g.
 * /storage/1A2B-3C4D). Needs the storage permission; see [UsbDisks].
 */
private object UsbDisk : Remote {
    private fun file(s: Source, path: String) = java.io.File(listOf(s.host.trimEnd('/'), Fs.rooted(s, path)).filter { it.isNotEmpty() }.joinToString("/"))

    override fun list(s: Source, share: String, path: String): List<SmbEntry> {
        val dir = file(s, path)
        val kids = dir.listFiles() ?: throw IOException(if (!dir.exists()) "Disk not found: ${s.host}" else "No permission to read ${dir.path}")
        return kids.filter { Fs.visible(it.name) }.map { SmbEntry(it.name, Smb.join(path, it.name), it.isDirectory, if (it.isFile) it.length() else 0, it.lastModified()) }
    }
    override fun open(s: Source, share: String, path: String): InputStream = java.io.FileInputStream(file(s, path))
    override fun write(s: Source, share: String, path: String, bytes: ByteArray) {
        val f = file(s, path); f.parentFile?.mkdirs(); f.writeBytes(bytes)
    }
    override fun playUrl(s: Source, share: String, path: String) = Uri.fromFile(file(s, path)).toString()
}

/** USB disks the TV has mounted right now: name (the disk's label) and folder. */
object UsbDisks {
    fun find(context: android.content.Context): List<Pair<String, String>> {
        val sm = context.getSystemService(android.os.storage.StorageManager::class.java) ?: return emptyList()
        return sm.storageVolumes.filter { it.isRemovable && it.state == android.os.Environment.MEDIA_MOUNTED }.mapNotNull { v ->
            val dir = if (android.os.Build.VERSION.SDK_INT >= 30) v.directory?.path else v.uuid?.let { "/storage/$it" }
            dir?.let { v.getDescription(context) to it }
        }
    }

    /** True when Glint may read the disks: all-files access, or (older Android) the storage permission. */
    fun canRead(context: android.content.Context): Boolean =
        if (android.os.Build.VERSION.SDK_INT >= 30 && android.os.Environment.isExternalStorageManager()) true
        else context.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED
}

private object SmbRemote : Remote {
    override fun shares(s: Source) = Smb.listShares(s)
    override fun list(s: Source, share: String, path: String) = Smb.list(s, share, path)
    override fun open(s: Source, share: String, path: String) = Smb.open(s, share, path)
    override fun write(s: Source, share: String, path: String, bytes: ByteArray) { Smb.write(s, share, path, bytes) }
    override fun playUrl(s: Source, share: String, path: String) = Smb.vlcUrl(s, share, path)
    override fun forget(s: Source) { Smb.forget(s); SmbImages.forget(s) }
}

/** WebDAV over HTTP(S): PROPFIND to list, GET to read, PUT to write. [Source.host] is the base URL. */
private object WebDav : Remote {
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    private val dateFormat = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)

    private fun base(s: Source) = s.host.trimEnd('/').let { if (it.startsWith("http")) it else "http://$it" }
    private fun url(s: Source, path: String) = base(s) + "/" + Fs.encodePath(Fs.rooted(s, path))

    private fun request(s: Source, url: String) = Request.Builder().url(url).apply {
        if (s.user.isNotBlank()) header("Authorization", Credentials.basic(s.user, s.password))
    }

    override fun list(s: Source, share: String, path: String): List<SmbEntry> {
        val dirUrl = url(s, path).trimEnd('/') + "/"
        val body = """<?xml version="1.0"?><d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/><d:getcontentlength/><d:getlastmodified/></d:prop></d:propfind>"""
        val req = request(s, dirUrl).method("PROPFIND", body.toRequestBody("application/xml".toMediaType())).header("Depth", "1").build()
        val xml = http.newCall(req).execute().use { r ->
            if (r.code != 207 && !r.isSuccessful) throw IOException("WebDAV ${r.code}")
            r.body!!.string()
        }
        val selfPath = Uri.parse(dirUrl).path.orEmpty().trimEnd('/')
        return Regex("""<(?:\w+:)?response\b.*?</(?:\w+:)?response>""", RegexOption.DOT_MATCHES_ALL).findAll(xml).mapNotNull { m ->
            val r = m.value
            val href = Regex("""<(?:\w+:)?href>(.*?)</(?:\w+:)?href>""").find(r)?.groupValues?.get(1)?.trim() ?: return@mapNotNull null
            val hrefPath = URLDecoder.decode(Uri.parse(href).path ?: href, "UTF-8").trimEnd('/')
            if (hrefPath == URLDecoder.decode(selfPath, "UTF-8")) return@mapNotNull null
            val name = hrefPath.substringAfterLast('/')
            if (name.isEmpty() || !Fs.visible(name)) return@mapNotNull null
            val isDir = Regex("""<(?:\w+:)?collection\s*/?>""").containsMatchIn(r)
            val size = Regex("""<(?:\w+:)?getcontentlength>(\d+)<""").find(r)?.groupValues?.get(1)?.toLongOrNull() ?: 0
            val modified = Regex("""<(?:\w+:)?getlastmodified>(.*?)<""").find(r)?.groupValues?.get(1)?.let { runCatching { dateFormat.parse(it)?.time }.getOrNull() } ?: 0
            SmbEntry(name, Smb.join(path, name), isDir, size, modified)
        }.toList()
    }

    override fun open(s: Source, share: String, path: String): InputStream {
        val r = http.newCall(request(s, url(s, path)).build()).execute()
        if (!r.isSuccessful) { r.close(); throw IOException("WebDAV ${r.code}") }
        return r.body!!.byteStream()
    }

    override fun write(s: Source, share: String, path: String, bytes: ByteArray) {
        val dir = path.substringBeforeLast('/', "")
        if (dir.isNotEmpty()) http.newCall(request(s, url(s, dir) + "/").method("MKCOL", null).build()).execute().close()
        http.newCall(request(s, url(s, path)).put(bytes.toRequestBody()).build()).execute().use { if (!it.isSuccessful) throw IOException("WebDAV ${it.code}") }
    }

    override fun playUrl(s: Source, share: String, path: String): String {
        val b = Uri.parse(base(s))
        val port = if (b.port > 0) ":${b.port}" else ""
        return "${b.scheme}://${Fs.userInfo(s)}${b.host}$port${b.path.orEmpty().trimEnd('/')}/" + Fs.encodePath(Fs.rooted(s, path))
    }
}

/** FTP and explicit FTPS (commons-net). One connection per operation; streams own theirs. */
private object Ftp : Remote {
    private fun connect(s: Source): FTPClient {
        val c = if (s.type == "ftps") FTPSClient(false) else FTPClient()
        c.connectTimeout = 15000
        c.controlEncoding = "UTF-8"
        val host = s.host.substringBefore(':')
        val port = s.host.substringAfter(':', "").toIntOrNull() ?: 21
        c.connect(host, port)
        if (!c.login(s.user.ifBlank { "anonymous" }, if (s.user.isBlank()) "glint@" else s.password)) { c.disconnect(); throw IOException("FTP login failed") }
        if (c is FTPSClient) { c.execPBSZ(0); c.execPROT("P") }
        c.enterLocalPassiveMode()
        c.setFileType(FTP.BINARY_FILE_TYPE)
        c.soTimeout = 30000
        return c
    }

    private fun abs(s: Source, path: String) = "/" + Fs.rooted(s, path)

    override fun list(s: Source, share: String, path: String): List<SmbEntry> {
        val c = connect(s)
        try {
            return c.listFiles(abs(s, path)).filter { it.name != "." && it.name != ".." && Fs.visible(it.name) }.map {
                SmbEntry(it.name, Smb.join(path, it.name), it.isDirectory, it.size, it.timestamp?.timeInMillis ?: 0)
            }
        } finally {
            runCatching { c.logout(); c.disconnect() }
        }
    }

    override fun open(s: Source, share: String, path: String): InputStream {
        val c = connect(s)
        val stream = c.retrieveFileStream(abs(s, path)) ?: run { c.disconnect(); throw IOException("FTP ${c.replyString}") }
        return object : FilterInputStream(stream) {
            override fun close() {
                runCatching { super.close() }
                runCatching { c.abort(); c.disconnect() }
            }
        }
    }

    override fun write(s: Source, share: String, path: String, bytes: ByteArray) {
        val c = connect(s)
        try {
            val dir = abs(s, path).substringBeforeLast('/')
            dir.split('/').filter { it.isNotEmpty() }.fold("") { acc, part -> "$acc/$part".also { c.makeDirectory(it) } }
            if (!c.storeFile(abs(s, path), bytes.inputStream())) throw IOException("FTP ${c.replyString}")
        } finally {
            runCatching { c.logout(); c.disconnect() }
        }
    }

    override fun playUrl(s: Source, share: String, path: String) = "${s.type}://${Fs.userInfo(s)}${s.host}" + abs(s, Fs.encodePath(path))
}

/** SFTP over SSH (JSch). Sessions are kept open and reused. On a home LAN the host key is accepted as is. */
private object Sftp : Remote {
    private val sessions = ConcurrentHashMap<String, Session>()

    @Synchronized
    private fun channel(s: Source): ChannelSftp {
        val key = "${s.host}|${s.user}"
        val session = sessions[key]?.takeIf { it.isConnected } ?: JSch().getSession(s.user, s.host.substringBefore(':'), s.host.substringAfter(':', "").toIntOrNull() ?: 22).apply {
            setPassword(s.password)
            setConfig("StrictHostKeyChecking", "no")
            timeout = 20000
            connect(20000)
            sessions[key] = this
        }
        return (session.openChannel("sftp") as ChannelSftp).apply { connect(15000) }
    }

    private fun abs(s: Source, path: String) = "/" + Fs.rooted(s, path)

    override fun list(s: Source, share: String, path: String): List<SmbEntry> {
        val ch = channel(s)
        try {
            @Suppress("UNCHECKED_CAST")
            return (ch.ls(abs(s, path)) as java.util.Vector<ChannelSftp.LsEntry>)
                .filter { it.filename != "." && it.filename != ".." && Fs.visible(it.filename) }
                .map { SmbEntry(it.filename, Smb.join(path, it.filename), it.attrs.isDir, it.attrs.size, it.attrs.mTime * 1000L) }
        } finally {
            ch.disconnect()
        }
    }

    override fun open(s: Source, share: String, path: String): InputStream {
        val ch = channel(s)
        return object : FilterInputStream(ch.get(abs(s, path))) {
            override fun close() { runCatching { super.close() }; ch.disconnect() }
        }
    }

    override fun write(s: Source, share: String, path: String, bytes: ByteArray) {
        val ch = channel(s)
        try {
            abs(s, path).substringBeforeLast('/').split('/').filter { it.isNotEmpty() }
                .fold("") { acc, part -> "$acc/$part".also { runCatching { ch.mkdir(it) } } }
            ch.put(bytes.inputStream(), abs(s, path))
        } finally {
            ch.disconnect()
        }
    }

    override fun playUrl(s: Source, share: String, path: String) = "sftp://${Fs.userInfo(s)}${s.host}" + abs(s, Fs.encodePath(path))

    override fun forget(s: Source) { sessions.remove("${s.host}|${s.user}")?.disconnect() }
}

/** NFS v3. [Source.share] is the export path ("/i-data/…/video"); the NAS must allow this box's address. */
private object Nfs : Remote {
    private val mounts = ConcurrentHashMap<String, Nfs3>()

    private fun nfs(s: Source): Nfs3 = mounts.getOrPut("${s.host}|${s.share}") { Nfs3(s.host, s.share.ifEmpty { "/" }, CredentialUnix(0, 0, null), 3) }
    private fun file(s: Source, path: String) = Nfs3File(nfs(s), "/" + Fs.rooted(s, path))

    override fun shares(s: Source) = listOf(s.share)

    override fun list(s: Source, share: String, path: String): List<SmbEntry> =
        file(s, path).listFiles().filter { Fs.visible(it.name) }.map {
            SmbEntry(it.name, Smb.join(path, it.name), it.isDirectory, if (it.isDirectory) 0 else it.length(), it.lastModified())
        }

    override fun open(s: Source, share: String, path: String): InputStream = NfsFileInputStream(file(s, path))

    override fun write(s: Source, share: String, path: String, bytes: ByteArray) {
        val dir = path.substringBeforeLast('/', "")
        if (dir.isNotEmpty()) file(s, dir).mkdirs()
        val f = file(s, path)
        if (!f.exists()) f.createNewFile()
        NfsFileOutputStream(f).use { it.write(bytes) }
    }

    override fun playUrl(s: Source, share: String, path: String) =
        "nfs://${s.host}/" + Fs.encodePath(s.share.trim('/')) + "/" + Fs.encodePath(Fs.rooted(s, path))

    override fun forget(s: Source) { mounts.remove("${s.host}|${s.share}") }
}

/**
 * UPnP/DLNA media servers. Browsing only: no library scanning. [Source.host] is the
 * server's description URL; folder paths are "c:<object id>", files "u:<stream url>".
 */
object Upnp : Remote {
    private val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    private val control = ConcurrentHashMap<String, String>()

    /** Media servers answering an SSDP search: (friendly name, description URL). */
    fun discover(waitMs: Int = 3000): List<Pair<String, String>> {
        val msg = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: urn:schemas-upnp-org:device:MediaServer:1\r\n\r\n".toByteArray()
        val found = LinkedHashSet<String>()
        MulticastSocket().use { sock ->
            sock.soTimeout = 500
            sock.send(DatagramPacket(msg, msg.size, InetAddress.getByName("239.255.255.250"), 1900))
            val end = System.currentTimeMillis() + waitMs
            val buf = ByteArray(4096)
            while (System.currentTimeMillis() < end) {
                try {
                    val p = DatagramPacket(buf, buf.size)
                    sock.receive(p)
                    Regex("""(?im)^location:\s*(\S+)""").find(String(p.data, 0, p.length))?.groupValues?.get(1)?.let { found += it }
                } catch (_: SocketTimeoutException) {}
            }
        }
        return found.mapNotNull { loc ->
            runCatching {
                val xml = http.newCall(Request.Builder().url(loc).build()).execute().use { it.body!!.string() }
                (Regex("""<friendlyName>(.*?)</friendlyName>""").find(xml)?.groupValues?.get(1) ?: loc) to loc
            }.getOrNull()
        }
    }

    private fun controlUrl(s: Source): String = control.getOrPut(s.host) {
        val xml = http.newCall(Request.Builder().url(s.host).build()).execute().use { it.body!!.string() }
        val svc = Regex("""<service>(.*?)</service>""", RegexOption.DOT_MATCHES_ALL).findAll(xml).map { it.groupValues[1] }
            .first { "ContentDirectory" in it }
        val path = Regex("""<controlURL>(.*?)</controlURL>""").find(svc)!!.groupValues[1].trim()
        java.net.URL(java.net.URL(s.host), path).toString()
    }

    private fun browse(s: Source, id: String): String {
        val soap = """<?xml version="1.0"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body>""" +
            """<u:Browse xmlns:u="urn:schemas-upnp-org:service:ContentDirectory:1"><ObjectID>${id.xml()}</ObjectID><BrowseFlag>BrowseDirectChildren</BrowseFlag>""" +
            """<Filter>*</Filter><StartingIndex>0</StartingIndex><RequestedCount>2000</RequestedCount><SortCriteria></SortCriteria></u:Browse></s:Body></s:Envelope>"""
        val req = Request.Builder().url(controlUrl(s))
            .header("SOAPACTION", "\"urn:schemas-upnp-org:service:ContentDirectory:1#Browse\"")
            .post(soap.toRequestBody("text/xml; charset=\"utf-8\"".toMediaType())).build()
        val xml = http.newCall(req).execute().use { it.body!!.string() }
        return Regex("""<Result>(.*?)</Result>""", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.get(1)?.unxml().orEmpty()
    }

    override fun list(s: Source, share: String, path: String): List<SmbEntry> {
        val id = path.removePrefix("c:").ifEmpty { "0" }
        return parseDidl(browse(s, id))
    }

    /** Folders and video items of a DIDL-Lite listing. */
    fun parseDidl(didl: String): List<SmbEntry> {
        val folders = Regex("""<container\b([^>]*)>(.*?)</container>""", RegexOption.DOT_MATCHES_ALL).findAll(didl).map { m ->
            val cid = Regex("""\bid="([^"]*)"""").find(m.groupValues[1])?.groupValues?.get(1).orEmpty().unxml()
            SmbEntry(title(m.groupValues[2]), "c:$cid", true, 0, 0)
        }
        val files = Regex("""<item\b[^>]*>(.*?)</item>""", RegexOption.DOT_MATCHES_ALL).findAll(didl).mapNotNull { m ->
            val res = Regex("""<res\b([^>]*)>(.*?)</res>""", RegexOption.DOT_MATCHES_ALL).find(m.groupValues[1]) ?: return@mapNotNull null
            val url = res.groupValues[2].trim().unxml()
            val size = Regex("""\bsize="(\d+)"""").find(res.groupValues[1])?.groupValues?.get(1)?.toLongOrNull() ?: 0
            // The library wants a file extension; take it from the stream URL, or say it's a video.
            val ext = Names.ext(runCatching { java.net.URI(url).path.orEmpty() }.getOrDefault(url).substringAfterLast('/')).takeIf { it in Names.VIDEO_EXT } ?: "mkv"
            SmbEntry(title(m.groupValues[1]) + "." + ext, "u:$url", false, size, 0)
        }
        return (folders + files).toList()
    }

    private fun title(x: String) = Regex("""<dc:title>(.*?)</dc:title>""", RegexOption.DOT_MATCHES_ALL).find(x)?.groupValues?.get(1)?.unxml() ?: "?"

    override fun open(s: Source, share: String, path: String): InputStream {
        val r = http.newCall(Request.Builder().url(path.removePrefix("u:")).build()).execute()
        if (!r.isSuccessful) { r.close(); throw IOException("UPnP ${r.code}") }
        return r.body!!.byteStream()
    }

    override fun write(s: Source, share: String, path: String, bytes: ByteArray) = throw IOException("UPnP servers are read-only")
    override fun playUrl(s: Source, share: String, path: String) = path.removePrefix("u:")
    override fun forget(s: Source) { control.remove(s.host) }

    private fun String.xml() = replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    private fun String.unxml() = replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")
}
