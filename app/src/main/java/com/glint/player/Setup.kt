package com.glint.player

import android.net.Uri

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material.icons.outlined.NorthWest
import androidx.compose.material.icons.outlined.NorthEast
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Usb
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Shares: an illustration on the left, the saved shares and "add" on the right. */
@Composable
private fun SharesScreen(nav: Nav) {
    val lib = App.library
    val sources by lib.sources.collectAsState()
    Box(Modifier.fillMaxSize().screenBackground()) {
        ScreenTitle(stringResource(R.string.sources))
        Row(Modifier.fillMaxSize().padding(top = 110.dp)) {
            Box(Modifier.weight(1f).fillMaxSize(), contentAlignment = Alignment.Center) {
                // Line art: a cloud over the NAS and the TV, arrows pointing up to it.
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(androidx.compose.material.icons.Icons.Outlined.Cloud, Palette.Faint, Modifier.size(130.dp))
                        Icon(androidx.compose.material.icons.Icons.Outlined.Wifi, Palette.Faint, Modifier.size(44.dp).padding(top = 8.dp))
                    }
                    Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(100.dp)) {
                        Icon(androidx.compose.material.icons.Icons.Outlined.NorthWest, Palette.Faint, Modifier.size(34.dp))
                        Icon(androidx.compose.material.icons.Icons.Outlined.NorthEast, Palette.Faint, Modifier.size(34.dp))
                    }
                    Gap(16.dp)
                    Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(90.dp)) {
                        Icon(androidx.compose.material.icons.Icons.Outlined.Storage, Palette.Faint, Modifier.size(110.dp))
                        Icon(androidx.compose.material.icons.Icons.Outlined.Tv, Palette.Faint, Modifier.size(110.dp))
                    }
                }
            }
            androidx.compose.foundation.lazy.LazyColumn(
                Modifier.weight(1f).fillMaxSize().padding(end = 80.dp),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 60.dp),
            ) {
                items(sources, key = { it.id }) { s ->
                    // OK browses the share; holding OK opens its settings (address, options, remove).
                    ListRow(
                        s.name, if (s.type == "upnp") Uri.parse(s.host).host.orEmpty() else s.host, if (s == sources.first()) FocusFirst() else Modifier,
                        chevron = true, icon = if (s.type == "usb") androidx.compose.material.icons.Icons.Filled.Usb else androidx.compose.material.icons.Icons.Filled.Storage, onLongClick = { nav.push(Screen.EditSource(s.id)) },
                    ) { nav.push(Screen.Folder(s.id)) }
                }
                item { ListRow(stringResource(R.string.add_source), icon = androidx.compose.material.icons.Icons.Filled.Add, modifier = if (sources.isEmpty()) FocusFirst() else Modifier) { nav.push(Screen.EditSource(null)) } }
                if (sources.isNotEmpty()) item { Txt(stringResource(R.string.hold_to_edit_share), Type.Small, Modifier.padding(start = 8.dp, top = 2.dp)) }
                item { SpeedTestRow() }
                item { UrlRow() }
            }
        }
    }
}

/** Times a read of the biggest film, so you know which remuxes the network can carry. */
@Composable
private fun SpeedTestRow() {
    val lib = App.library
    val scope = rememberCoroutineScope()
    var testing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var speed by remember { mutableStateOf(lib.settings.measuredMbps) }
    Box(Modifier.padding(top = 14.dp)) {
        ListRow(
            stringResource(R.string.speed_test),
            when {
                testing -> stringResource(R.string.speed_testing)
                speed > 0 -> stringResource(R.string.mbps, speed.toInt())
                else -> ""
            },
            sub = error ?: if (speed > 0) stringResource(R.string.speed_result_sub, (speed * 0.8f).toInt()) else stringResource(R.string.speed_test_sub),
        ) {
            if (!testing) {
                testing = true; error = null
                scope.launch {
                    withContext(Dispatchers.IO) { runCatching { lib.speedTest() } }.onFailure { error = it.message }
                    speed = lib.settings.measuredMbps
                    testing = false
                }
            }
        }
    }
}

/** "Play a URL": type or paste a link and play it straight away. Also on the Welcome screen, before any share. */
@Composable
fun UrlRow() {
    var url by remember { mutableStateOf("") }
    Column(Modifier.padding(top = 24.dp)) {
        Txt(stringResource(R.string.direct_url), Type.Meta, Modifier.padding(bottom = 8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextInput(url, { url = it }, "https://…", Modifier.width(640.dp), keyboard = KeyboardType.Uri) {
                if (url.isNotBlank()) PlayerActivity.start(App.instance, "url|" + url.trim())
            }
            Box(Modifier.width(14.dp))
            PillButton(stringResource(R.string.play), primary = true) { if (url.isNotBlank()) PlayerActivity.start(App.instance, "url|" + url.trim()) }
        }
    }
}

/** Folders a computer or the TV keeps on a USB disk for itself. */
private fun isUsbSystemFolder(name: String) = name.startsWith("$") || name.equals("System Volume Information", true) || name.equals("LOST.DIR", true) || name.equals("Android", true)

@Composable
fun EditSourceScreen(nav: Nav, id: String?) {
    val lib = App.library
    val existing = id?.let { lib.source(it) }
    var host by rememberSaveable { mutableStateOf(existing?.host ?: "") }
    var name by rememberSaveable { mutableStateOf(existing?.name ?: "") }
    var user by rememberSaveable { mutableStateOf(existing?.user ?: "") }
    var password by rememberSaveable { mutableStateOf(existing?.password ?: "") }
    var folder by rememberSaveable { mutableStateOf(existing?.folder ?: "") }
    var type by rememberSaveable { mutableStateOf(existing?.type ?: "smb") }
    var export by rememberSaveable { mutableStateOf(existing?.share?.takeIf { existing.type == "nfs" } ?: "") }
    var found by remember { mutableStateOf<List<FoundHost>?>(null) }
    var servers by remember { mutableStateOf<List<Pair<String, String>>?>(null) }
    var disks by remember { mutableStateOf<List<Pair<String, String>>?>(null) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var canRead by remember { mutableStateOf(UsbDisks.canRead(ctx)) }
    val askMedia = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()) { canRead = UsbDisks.canRead(ctx) }
    // Access to USB disks: the TV's own "photos and videos" permission prompt.
    fun askForDisks() = askMedia.launch(UsbDisks.PERMISSIONS)
    var shares by remember { mutableStateOf<List<String>?>(null) }
    // The share and folder open in the folder picker, and the folders in it.
    var browse by remember { mutableStateOf<Pair<String, String>?>(null) }
    var dirs by remember { mutableStateOf<List<SmbEntry>?>(null) }
    var dirsError by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    var removing by remember { mutableStateOf(false) }

    // A new name for a saved share needs no reconnect, so it's kept as soon as it's typed.
    LaunchedEffect(name) {
        val current = id?.let { lib.source(it) } ?: return@LaunchedEffect
        if (name.isNotBlank() && name.trim() != current.name) lib.saveSource(current.copy(name = name.trim()))
    }

    LaunchedEffect(type) {
        if (existing != null) return@LaunchedEffect
        when (type) {
            "usb" -> {
                disks = UsbDisks.find(App.instance)
                if (!host.startsWith("/")) { host = ""; name = "" }    // an address carried over from another kind
            }
            "smb" -> if (found == null) found = withContext(Dispatchers.IO) { runCatching { Smb.discover() }.getOrDefault(emptyList()) }
            "upnp" -> if (servers == null) servers = withContext(Dispatchers.IO) { runCatching { Upnp.discover() }.getOrDefault(emptyList()) }
        }
    }

    fun draft(share: String = existing?.share ?: "") = Source(
        existing?.id ?: "", name.ifBlank { host }, host.trim(), if (type == "nfs") export.trim() else if (type == "smb") share else "",
        folder.trim('/', ' '), user.trim(), password, "", type,
    )

    LaunchedEffect(browse) {
        val (share, path) = browse ?: return@LaunchedEffect
        dirs = null; dirsError = null
        withContext(Dispatchers.IO) {
            // A USB disk is browsed from its top, whatever folder it's set to now; its system folders aren't offered.
            val src = if (type == "usb") draft(share).copy(folder = "") else draft(share)
            runCatching { Fs.list(src, share, path).filter { it.isDir && !(type == "usb" && isUsbSystemFolder(it.name)) }.sortedBy { it.name.lowercase() } }
                .onSuccess { dirs = it }.onFailure { dirsError = it.message ?: it.javaClass.simpleName }
        }
    }

    fun up() {
        val (share, path) = browse ?: return
        browse = if (path.isEmpty()) null else share to path.substringBeforeLast('/', "")
        // A USB disk has no list of shares to step back to.
        if (browse == null && type == "usb") shares = null
    }

    fun connect() {
        if (host.isBlank()) return
        busy = true; error = null
        scope.launch {
            val d = if (type == "usb") draft().copy(folder = "") else draft()
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    Fs.forget(d)
                    // SMB offers a choice of shares; other kinds have one root, so a test listing is enough.
                    if (type == "smb") Fs.listShares(d) else { Fs.list(d, d.share, ""); null }
                }
            }
            busy = false
            result.onSuccess { list ->
                if (list != null) shares = list
                else if (type == "usb") {
                    // A disk goes straight to its folders, to use all of it or just one.
                    shares = emptyList()
                    browse = "" to ""
                } else {
                    val first = lib.sources.value.isEmpty()
                    lib.saveSource(d)
                    lib.scanAll()
                    if (first) firstShareAdded(nav) else nav.pop()
                }
            }.onFailure { error = it.message ?: it.javaClass.simpleName }
        }
    }

    fun save(share: String, path: String = "") {
        val first = lib.sources.value.isEmpty()
        val picked = draft(share).copy(folder = path)
        // A new share on one folder of a disk says which, so several from one disk can be told apart.
        val named = if (type == "usb" && existing == null && path.isNotEmpty()) picked.copy(name = "${picked.name} / ${path.substringAfterLast('/')}") else picked
        lib.saveSource(named)
        lib.scanAll()
        if (first) firstShareAdded(nav) else nav.pop()
    }

    Column(Modifier.fillMaxSize().screenBackground()) {
        if (removing && existing != null) androidx.compose.ui.window.Dialog({ removing = false }, androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
            TvScale {
                OptionSheet(stringResource(R.string.remove_source_confirm), listOf(
                    stringResource(R.string.cancel) to {},
                    stringResource(R.string.remove_source) to { lib.removeSource(existing.id); nav.pop() },
                ), message = stringResource(R.string.remove_source_sub), danger = 1) { removing = false }
            }
        }
        Header(stringResource(if (existing == null) R.string.add_source else R.string.edit_source), stringResource(R.string.add_source_sub))
        val sh = shares
        val at = browse
        // Back steps up a folder, then out of the share list, before leaving the screen.
        androidx.activity.compose.BackHandler(enabled = sh != null) { if (at != null) up() else shares = null }
        if (sh != null && at != null) {
            val (share, path) = at
            key(at) {
                ListColumn {
                    item { ListRow(stringResource(R.string.use_this_folder), sub = listOf(if (type == "usb") name.ifBlank { host } else share, path).filter { it.isNotEmpty() }.joinToString(" / "), modifier = FocusFirst()) { save(share, path) } }
                    val list = dirs
                    if (list == null && dirsError == null) item { Txt(stringResource(R.string.loading), Type.Body) }
                    dirsError?.let { item { Txt(it, Type.Body.copy(color = Palette.Accent)) } }
                    if (list != null) items(list, key = { it.path }) { e -> ListRow("📁  ${e.name}") { browse = share to e.path } }
                    item { ListRow(stringResource(R.string.back)) { up() } }
                }
            }
            return@Column
        }
        if (sh != null) {
            ListColumn {
                item { ListRow(stringResource(R.string.all_shares), sub = sh.joinToString(", "), modifier = FocusFirst()) { save("") } }
                items(sh) { share -> ListRow(share) { browse = share to "" } }
                item { ListRow(stringResource(R.string.back)) { shares = null } }
            }
            return@Column
        }
        ListColumn {
            // Kind of share: SMB (Windows/NAS), WebDAV, FTP, FTPS, SFTP, NFS, UPnP/DLNA.
            item {
                androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 6.dp)) {
                    items(Fs.TYPES) { t ->
                        PillButton(stringResource(typeLabel(t)), primary = t == type, modifier = if (t == type && existing == null) FocusFirst() else Modifier) { if (existing == null) type = t }
                    }
                }
                Gap(10.dp)
            }
            if (type == "usb") {
                if (!canRead) {
                    item { Txt(stringResource(R.string.usb_permission_sub), Type.Body, Modifier.padding(bottom = 8.dp)) }
                    item { ListRow(stringResource(R.string.usb_permission)) { askForDisks() } }
                    item { Gap(12.dp) }
                }
                if (existing == null) {
                    val list = disks
                    item { Txt(stringResource(R.string.usb_found), Type.Meta, Modifier.padding(bottom = 4.dp)) }
                    if (list != null) items(list, key = { it.second }) { (n, path) ->
                        ListRow(n, path) { host = path; if (name.isBlank()) name = n }
                    }
                    if (list.isNullOrEmpty()) item { Txt(stringResource(R.string.usb_none), Type.Body) }
                    item { ListRow(stringResource(R.string.rescan_disks)) { disks = UsbDisks.find(ctx) } }
                    item { Gap(12.dp) }
                }
                item { Field(R.string.field_address) { Txt(host.ifBlank { "-" }, Type.Body, Modifier.width(520.dp)) } }
                item { Field(R.string.field_name) { TextInput(name, { name = it }, host.ifBlank { "USB" }, Modifier.width(520.dp)) } }
                // The folder is picked after Connect; a saved share shows the one in use.
                if (existing != null && folder.isNotEmpty()) item { Field(R.string.field_folder) { Txt(folder, Type.Body, Modifier.width(520.dp)) } }
                item {
                    Gap(12.dp)
                    PillButton(stringResource(if (busy) R.string.connecting else R.string.connect), primary = true) { if (canRead) connect() else askForDisks() }
                    error?.let { Gap(10.dp); Txt(stringResource(R.string.connect_failed, it), Type.Body.copy(color = Palette.Accent)) }
                }
                if (existing != null) item { ShareOptions(existing.id) }
                if (existing != null) item { Gap(12.dp); PillButton(stringResource(R.string.remove_source)) { removing = true } }
                return@ListColumn
            }
            if (type == "upnp" && existing != null) {
                item { ListRow(existing.name, Uri.parse(existing.host).host.orEmpty(), FocusFirst()) { nav.push(Screen.Folder(existing.id)) } }
                item { Gap(12.dp); PillButton(stringResource(R.string.remove_source)) { removing = true } }
                return@ListColumn
            }
            if (type == "upnp") {
                val list = servers
                item { Txt(stringResource(if (list == null) R.string.discovering else R.string.found_on_network), Type.Meta, Modifier.padding(bottom = 4.dp)) }
                if (list != null) items(list, key = { it.second }) { (n, loc) ->
                    ListRow(n, Uri.parse(loc).host.orEmpty()) { host = loc; name = n; connect() }
                }
                if (list != null && list.isEmpty()) item { Txt(stringResource(R.string.no_servers), Type.Body) }
                return@ListColumn
            }
            val f = found
            if (existing == null && type == "smb") {
                item { Txt(stringResource(if (f == null) R.string.discovering else R.string.found_on_network), Type.Meta, Modifier.padding(bottom = 4.dp)) }
                if (f != null) items(f, key = { it.address }) { h ->
                    ListRow(h.name, h.address, if (h == f.first() && host.isEmpty()) FocusFirst() else Modifier) {
                        host = h.address; if (name.isBlank()) name = h.name
                    }
                }
                item { Gap(12.dp) }
            }
            item {
                val hint = when (type) { "webdav" -> "https://192.168.1.10:5006/dav"; "ftp", "ftps" -> "192.168.1.10:21"; "sftp" -> "192.168.1.10:22"; else -> "192.168.1.10" }
                Field(R.string.field_address) { TextInput(host, { host = it }, hint, Modifier.width(520.dp), keyboard = KeyboardType.Uri) }
            }
            if (type == "nfs") item { Field(R.string.field_export) { TextInput(export, { export = it }, "/export/video", Modifier.width(520.dp), keyboard = KeyboardType.Uri) } }
            item { Field(R.string.field_user) { TextInput(user, { user = it }, stringResource(R.string.field_user_hint), Modifier.width(520.dp)) } }
            item { Field(R.string.field_password) { TextInput(password, { password = it }, "", Modifier.width(520.dp), password = true) } }
            item { Field(R.string.field_name) { TextInput(name, { name = it }, host.ifBlank { "NAS" }, Modifier.width(520.dp)) } }
            item { Field(R.string.field_folder) { TextInput(folder, { folder = it }, stringResource(R.string.field_folder_hint), Modifier.width(520.dp)) } }
            item {
                Gap(12.dp)
                PillButton(stringResource(if (busy) R.string.connecting else R.string.connect), primary = true) { connect() }
                error?.let { Gap(10.dp); Txt(stringResource(R.string.connect_failed, it), Type.Body.copy(color = Palette.Accent)) }
            }
            if (existing != null) item { ShareOptions(existing.id) }
            // Last on the page, so moving down from the fields can't land on it.
            if (existing != null) item { Gap(12.dp); PillButton(stringResource(R.string.remove_source)) { removing = true } }
        }
    }
}

/** Per-share options; each change is saved at once. */
@Composable
private fun ShareOptions(id: String) {
    val lib = App.library
    val sources by lib.sources.collectAsState()
    val src = sources.firstOrNull { it.id == id } ?: return
    Column(Modifier.padding(top = 28.dp).width(720.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
        Txt(stringResource(R.string.share_options), Type.Meta)
        OnOffRow(stringResource(R.string.auto_scan), src.autoScan, sub = stringResource(R.string.auto_scan_sub)) { v -> lib.saveSource(src.copy(autoScan = v)) }
        OnOffRow(stringResource(R.string.precache_details), src.precacheDetails, sub = stringResource(R.string.precache_details_sub)) { v ->
            lib.saveSource(src.copy(precacheDetails = v))
            if (v) lib.scanAll()   // turned on: look everything up now
        }
        OnOffRow(stringResource(R.string.precache_artwork), src.precacheArtwork, sub = stringResource(R.string.precache_artwork_sub)) { v ->
            lib.saveSource(src.copy(precacheArtwork = v))
            if (v) lib.scanAll()
        }
        OnOffRow(stringResource(R.string.smart_folders), src.smartFolders, sub = stringResource(R.string.smart_folders_sub)) { v -> lib.saveSource(src.copy(smartFolders = v)) }
        // Pick the SMB version for NAS boxes that stutter on what they and the TV agree on.
        if (src.type == "smb") ChoiceRow(
            stringResource(R.string.smb_version), listOf("", "2.0", "2.1", "3.0").map { it to if (it.isEmpty()) stringResource(R.string.auto) else "SMB $it" }, src.smbVersion,
            sub = stringResource(R.string.smb_version_sub),
        ) { next ->
            Fs.forget(src)                     // close connections made with the old version
            lib.saveSource(src.copy(smbVersion = next))
        }
    }
}

private fun typeLabel(t: String) = when (t) {
    "webdav" -> R.string.type_webdav
    "ftp" -> R.string.type_ftp
    "ftps" -> R.string.type_ftps
    "sftp" -> R.string.type_sftp
    "nfs" -> R.string.type_nfs
    "upnp" -> R.string.type_upnp
    "usb" -> R.string.type_usb
    else -> R.string.type_smb
}

@Composable
private fun Field(label: Int, content: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Txt(stringResource(label), Type.Meta, Modifier.width(180.dp))
        content()
    }
}

/** Raw file browsing, for anything the library doesn't show. */
@Composable
fun FolderScreen(nav: Nav, screen: Screen.Folder) {
    val lib = App.library
    val sources by lib.sources.collectAsState()
    val progress by lib.progress.collectAsState()
    var entries by remember(screen) { mutableStateOf<List<SmbEntry>?>(null) }
    var shares by remember(screen) { mutableStateOf<List<String>?>(null) }
    var error by remember(screen) { mutableStateOf<String?>(null) }
    val src = screen.sourceId?.let { lib.source(it) }
    // Only SMB has shares to pick from; other kinds open straight into their single root.
    val share = screen.share ?: src?.takeIf { it.type != "smb" }?.share

    LaunchedEffect(screen) {
        if (src == null) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            runCatching {
                if (share == null) shares = if (src.share.isNotEmpty()) listOf(src.share) else Fs.listShares(src)
                else entries = Fs.list(src, share, screen.path)
                    .filter { it.isDir || Names.ext(it.name) in Names.VIDEO_EXT }
                    .sortedWith(compareBy({ !it.isDir }, { it.name.lowercase() }))
            }.onFailure { error = it.message ?: it.javaClass.simpleName }
        }
    }

    // Smart folders: a folder holding just one film opens that film (its page if it's in the library).
    LaunchedEffect(entries) {
        val list = entries ?: return@LaunchedEffect
        if (src == null || share == null) return@LaunchedEffect
        // Pre-cache Details off: what the viewer browses is looked up now.
        if (!src.precacheDetails) lib.fetchDetails(list.filter { !it.isDir }.map { "${src.id}|$share|${it.path}" } + "${src.id}|$share|${screen.path}")
        if (!src.smartFolders || screen.path.isEmpty()) return@LaunchedEffect
        // A DVD or Blu-ray folder opens as the film it holds.
        if (list.any { it.isDir && (it.name.equals("VIDEO_TS", true) || it.name.equals("BDMV", true)) }) {
            val key = "${src.id}|$share|${screen.path}"
            if (lib.findMovie(key) != null) { nav.pop(); nav.push(Screen.MovieDetail(key)) }
            return@LaunchedEffect
        }
        val vids = list.filter { !it.isDir }
        if (vids.size == 1 && list.none { it.isDir }) {
            val key = "${src.id}|${share}|${vids[0].path}"
            nav.pop()
            if (lib.findMovie(key) != null) nav.push(Screen.MovieDetail(key)) else PlayerActivity.start(App.instance, key)
        }
    }
    if (src == null) { SharesScreen(nav); return }
    Column(Modifier.fillMaxSize().screenBackground()) {
        ScreenTitle(if (screen.sourceId == null) stringResource(R.string.tab_folders) else listOfNotNull(src?.name, share?.ifEmpty { null }, screen.path.substringAfterLast('/').ifEmpty { null }).joinToString(" / "))
        error?.let { Txt(it, Type.Body.copy(color = Palette.Accent), Modifier.padding(start = 56.dp, bottom = 12.dp)) }
        ListColumn {
            when {
                src == null -> {
                    items(sources, key = { it.id }) { s ->
                        ListRow(s.name, s.host, if (s == sources.first()) FocusFirst() else Modifier) { nav.push(Screen.Folder(s.id)) }
                    }
                    item { UrlRow() }
                }
                share == null -> items(shares.orEmpty()) { sh ->
                    ListRow("📁  $sh", modifier = if (sh == shares?.first()) FocusFirst() else Modifier) { nav.push(Screen.Folder(src.id, sh, src.folder.takeIf { src.share == sh }.orEmpty())) }
                }
                else -> {
                    val list = entries.orEmpty()
                    if (entries == null && error == null) item { Txt(stringResource(R.string.loading), Type.Body) }
                    items(list, key = { it.path }) { e ->
                        val mod = if (e == list.first()) FocusFirst() else Modifier
                        if (e.isDir) ListRow("📁  ${e.name}", modifier = mod, onLongClick = { App.library.pin(Pin("folder:${src.id}|${share}|${e.path}", e.name, row = false)) }) { nav.push(Screen.Folder(src.id, share, e.path)) }
                        else {
                            val key = "${src.id}|${share}|${e.path}"
                            val p = progress[key]
                            val state = when {
                                p?.watched == true -> "✓  "
                                p?.inProgress == true -> clock(p.position) + "  ·  "
                                else -> ""
                            }
                            ListRow("▶  ${e.name}", state + bytes(e.size), mod) { PlayerActivity.startFile(App.instance, key) }
                        }
                    }
                }
            }
        }
    }
}

/** After the first share: Home, with the key setup on top when there's no TMDB key yet (Back from it goes Home). */
private fun firstShareAdded(nav: Nav) {
    nav.tab(Screen.Home)
    if (App.library.settings.tmdbKey.isBlank()) nav.push(Screen.Keys)
}
