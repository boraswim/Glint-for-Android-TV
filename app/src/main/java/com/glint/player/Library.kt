package com.glint.player

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Locale
import java.util.UUID

data class ScanState(val message: String, val done: Int = 0, val total: Int = 0, val error: String? = null, val finished: Boolean = false)

/**
 * Everything the app knows: sources, the matched library, playback progress and settings.
 * Kept as JSON files in app storage; the UI watches the StateFlows.
 */
class Library(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val dir = context.filesDir
    private val scope = CoroutineScope(Dispatchers.IO)

    val settings = Settings(context)
    val tmdb = Tmdb({ settings.tmdbKey }, { settings.language }, { settings.artLanguage })

    // ---- profiles: each has its own progress and lists; the library itself is shared ----
    private val _profiles = MutableStateFlow(load("profiles.json") { listOf(Profile("main", "Glint")) })
    val profiles: StateFlow<List<Profile>> = _profiles
    private val _profile = MutableStateFlow(_profiles.value.firstOrNull { it.id == settings.profileId } ?: _profiles.value.first())
    val profile: StateFlow<Profile> = _profile
    private val progressFile get() = if (_profile.value.id == "main") "progress.json" else "progress-${_profile.value.id}.json"
    private val userFile get() = if (_profile.value.id == "main") "user.json" else "user-${_profile.value.id}.json"

    private val _sources = MutableStateFlow(load("sources.json") { emptyList<Source>() })
    val sources: StateFlow<List<Source>> = _sources

    private val _data = MutableStateFlow(load("library.json") { LibraryData() })
    // What this profile may see: everything, or only titles within its age limit.
    private val _visible = MutableStateFlow(visible(_data.value))
    val data: StateFlow<LibraryData> = _visible

    private val _progress = MutableStateFlow(load(progressFile) { emptyMap<String, Progress>() })
    val progress: StateFlow<Map<String, Progress>> = _progress

    private val _user = MutableStateFlow(load(userFile) { UserData() })
    val user: StateFlow<UserData> = _user

    fun toggleWatchlist(id: String) {
        val u = _user.value
        _user.value = u.copy(watchlist = if (id in u.watchlist) u.watchlist - id else listOf(id) + u.watchlist)
        store(userFile, _user.value)
    }

    fun rate(id: String, stars: Int) {
        val u = _user.value
        _user.value = u.copy(ratings = if (stars <= 0) u.ratings - id else u.ratings + (id to stars))
        store(userFile, _user.value)
    }

    // ---- playlists and the viewer's own collections ----

    fun addToPlaylist(name: String, id: String) = editUser { it.copy(playlists = it.playlists + (name to (it.playlists[name].orEmpty() - id + id))) }
    fun removeFromPlaylist(name: String, id: String) = editUser { it.copy(playlists = it.playlists + (name to (it.playlists[name].orEmpty() - id))) }
    fun deletePlaylist(name: String) = editUser { it.copy(playlists = it.playlists - name) }
    fun addToCollection(name: String, id: String) = editUser { it.copy(collections = it.collections + (name to (it.collections[name].orEmpty() - id + id))) }
    fun removeFromCollection(name: String, id: String) = editUser { it.copy(collections = it.collections + (name to (it.collections[name].orEmpty() - id))) }

    fun pin(p: Pin) = editUser { it.copy(pins = it.pins.filter { x -> x.key != p.key || x.row != p.row } + p) }
    fun unpin(p: Pin) = editUser { it.copy(pins = it.pins - p) }
    fun hideTile(i: Int) = editUser { it.copy(hiddenTiles = (it.hiddenTiles + i).distinct()) }
    fun showAllTiles() = editUser { it.copy(hiddenTiles = emptyList()) }
    fun setTileOrder(ids: List<String>) = editUser { it.copy(tileOrder = ids) }

    /** The titles a pinned category stands for. */
    fun pinItems(key: String): List<Item> {
        val d = _visible.value
        val (kind, arg) = key.substringBefore(':') to key.substringAfter(':')
        return when (kind) {
            "genre" -> d.movies.filter { it.catalogued && arg in it.genres }.sortedByDescending { it.rating }.map { Item.MovieItem(it) } +
                d.shows.filter { arg in it.genres }.map { Item.ShowItem(it) }
            "actor" -> d.movies.filter { m -> m.cast.any { it.name == arg } || m.director == arg }.map { Item.MovieItem(it) } +
                d.shows.filter { s -> s.cast.any { it.name == arg } }.map { Item.ShowItem(it) }
            "playlist" -> _user.value.playlists[arg].orEmpty().mapNotNull { id -> findMovie(id)?.let { Item.MovieItem(it) } ?: findEpisode(id)?.let { (s, e) -> Item.EpisodeItem(s, e) } }
            "collection" -> collections().firstOrNull { it.collectionId.toString() == arg }?.all.orEmpty()
            else -> emptyList()
        }
    }

    private fun editUser(change: (UserData) -> UserData) {
        _user.value = change(_user.value)
        store(userFile, _user.value)
    }

    /** Other copies of the same film (another cut or resolution), this one first. */
    fun versions(movie: Movie): List<Movie> =
        if (movie.tmdbId == 0) listOf(movie)
        else listOf(movie) + _data.value.movies.filter { it.tmdbId == movie.tmdbId && it.id != movie.id }

    /** Ten unwatched films chosen afresh each day. */
    fun picksOfTheDay(): List<Item.MovieItem> {
        val p = _progress.value
        val day = System.currentTimeMillis() / 86_400_000L
        return _visible.value.movies.filter { it.catalogued && p[it.id]?.watched != true }
            .shuffled(java.util.Random(day)).take(10).map { Item.MovieItem(it) }
    }

    data class Stats(val episodes: Int, val shows: Int, val movies: Int)

    fun stats(): Stats {
        val p = _progress.value
        val d = _visible.value
        val eps = d.shows.flatMap { s -> s.episodes.filter { p[it.id]?.watched == true }.map { s.key } }
        return Stats(eps.size, eps.toSet().size, d.movies.count { p[it.id]?.watched == true })
    }

    // Manual "Fix match" choices, kept across rescans: file key or "show:<name>" → TMDB id (-1 = no match).
    private var overrides: Map<String, Int> = load("overrides.json") { emptyMap() }

    // Where each series' intro starts and ends (ms), marked by the viewer in the player. Shared by all profiles.
    private var intros: Map<String, List<Long>> = load("intros.json") { emptyMap() }

    /** The marked intro of the series this episode belongs to, as start to end in ms. */
    fun introOf(episodeKey: String): Pair<Long, Long>? =
        findEpisode(episodeKey)?.first?.key?.let { intros[it] }?.takeIf { it.size == 2 }?.let { it[0] to it[1] }

    fun setIntro(episodeKey: String, start: Long, end: Long) {
        val show = findEpisode(episodeKey)?.first?.key ?: return
        intros = intros + (show to listOf(start, end))
        store("intros.json", intros)
    }

    fun clearIntro(episodeKey: String) {
        val show = findEpisode(episodeKey)?.first?.key ?: return
        intros = intros - show
        store("intros.json", intros)
    }

    private val _scan = MutableStateFlow<ScanState?>(null)
    val scan: StateFlow<ScanState?> = _scan
    private var scanJob: Job? = null

    fun source(id: String) = _sources.value.firstOrNull { it.id == id }

    // ---- sources ----

    fun saveSource(s: Source): Source {
        val src = if (s.id.isEmpty()) s.copy(id = UUID.randomUUID().toString().take(8)) else s
        _sources.value = _sources.value.filter { it.id != src.id } + src
        Fs.forget(src)
        store("sources.json", _sources.value)
        return src
    }

    fun removeSource(id: String) {
        _sources.value = _sources.value.filter { it.id != id }
        store("sources.json", _sources.value)
        val d = _data.value
        publish(d.copy(
            movies = d.movies.filter { it.file.source != id },
            shows = d.shows.map { s -> s.copy(episodes = s.episodes.filter { it.file.source != id }) }.filter { it.episodes.isNotEmpty() },
        ))
    }

    private val woken = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * NAS disks go to sleep and take 10-20 seconds to spin up, which the player would spend on "Loading".
     * Reading a byte of the film and its dubs while its page is open or it's focused wakes them before Play.
     */
    fun wake(file: VideoFile) {
        val now = System.currentTimeMillis()
        if ((woken[file.key] ?: 0L) > now - 60_000) return
        woken[file.key] = now
        val targets = listOf(Triple(file.source, file.share, file.parts.firstOrNull() ?: file.path)) +
            file.dubs.map { Triple(it.source, it.share, it.path) }
        for ((id, share, path) in targets) scope.launch {
            val src = source(id) ?: return@launch
            runCatching { Fs.open(src, share, path).use { it.read() } }
                .onFailure { runCatching { Fs.list(src, share, path) } }   // a DVD or Blu-ray folder
        }
    }

    // ---- progress ----

    fun progressOf(key: String) = _progress.value[key]

    fun saveProgress(key: String, p: Progress) {
        _progress.value = _progress.value + (key to p)
        val file = progressFile
        storeLater(file) { _progress.value }
    }

    fun setWatched(keys: Collection<String>, watched: Boolean) {
        val now = System.currentTimeMillis()
        val map = _progress.value.toMutableMap()
        for (k in keys) {
            val old = map[k]
            map[k] = if (watched) Progress(old?.duration ?: 0, old?.duration ?: 0, now, watched = true, old?.audioTrack ?: -2, old?.subtitleTrack ?: -2)
            else Progress(0, old?.duration ?: 0, now, watched = false, old?.audioTrack ?: -2, old?.subtitleTrack ?: -2)
        }
        _progress.value = map
        store(progressFile, map)
    }

    fun isWatched(key: String) = _progress.value[key]?.watched == true

    // ---- shelves ----

    fun continueWatching(): List<Item> {
        val d = _visible.value
        val p = _progress.value
        val movies = d.movies.filter { p[it.id]?.started == true }.map { Item.MovieItem(it) to p.getValue(it.id).updated }
        val episodes = d.shows.flatMap { s -> s.episodes.filter { p[it.id]?.started == true }.map { Item.EpisodeItem(s, it) to p.getValue(it.id).updated } }
        return (movies + episodes).sortedByDescending { it.second }.map { it.first }
    }

    /** The next unwatched episode of shows the viewer has been watching, newest activity first. */
    fun upNext(): List<Item.EpisodeItem> {
        val p = _progress.value
        return _visible.value.shows.mapNotNull { s ->
            val eps = s.sortedEpisodes()
            val lastIdx = eps.indexOfLast { p[it.id]?.watched == true }
            if (lastIdx < 0) return@mapNotNull null
            val next = eps.drop(lastIdx + 1).firstOrNull { p[it.id]?.watched != true && p[it.id]?.started != true } ?: return@mapNotNull null
            Item.EpisodeItem(s, next) to (p[eps[lastIdx].id]?.updated ?: 0)
        }.sortedByDescending { it.second }.map { it.first }
    }

    fun nextEpisode(key: String): Pair<Show, Episode>? {
        for (s in _data.value.shows) {
            val eps = s.sortedEpisodes()
            val i = eps.indexOfFirst { it.id == key }
            if (i >= 0) return eps.getOrNull(i + 1)?.let { s to it }
        }
        return null
    }

    fun findMovie(key: String) = _data.value.movies.firstOrNull { it.id == key }
    fun findShow(key: String) = _data.value.shows.firstOrNull { it.key == key }
    fun findEpisode(key: String) = _data.value.shows.firstNotNullOfOrNull { s -> s.episodes.firstOrNull { it.id == key }?.let { s to it } }

    /** A series ("tmdb:1") or one season of it ("season:tmdb:1#2"), if this profile may see it. */
    fun showItem(id: String): Item.ShowItem? {
        val shows = _visible.value.shows
        if (!id.startsWith("season:")) return shows.firstOrNull { it.key == id }?.let { Item.ShowItem(it) }
        val key = id.removePrefix("season:").substringBeforeLast('#')
        val n = id.substringAfterLast('#').toIntOrNull() ?: return null
        val show = shows.firstOrNull { it.key == key } ?: return null
        return show.copy(episodes = show.episodes.filter { it.season == n }).takeIf { it.episodes.isNotEmpty() }?.let { Item.ShowItem(it, n) }
    }

    fun collections(): List<Item.CollectionItem> {
        val movies = _visible.value.movies.distinctBy { if (it.tmdbId != 0) "t${it.tmdbId}" else it.id }
        val tmdbCols = movies
            .filter { it.collectionId != 0 }
            .groupBy { it.collectionId }
            .filter { it.value.size >= 2 }
            .map { (id, list) -> Item.CollectionItem(id, list.first().collectionName, list.sortedBy { it.releaseDate.ifEmpty { it.year } }) }
        // The viewer's own collections get negative ids so they never clash with TMDB's.
        val own = _user.value.collections.mapNotNull { (name, ids) ->
            val list = ids.mapNotNull { findMovie(it) }
            val others = ids.mapNotNull { showItem(it) }
            if (list.isEmpty() && others.isEmpty()) null else Item.CollectionItem(-(name.hashCode() and 0x7fffffff) - 1, name, list, others)
        }
        return (tmdbCols + own).sortedBy { it.name }
    }

    // ---- scanning ----

    /** Rescans the shares; [auto] (at app start) leaves out shares with Auto Scan off. */
    fun scanAll(auto: Boolean = false) {
        if (scanJob?.isActive == true) return
        scanJob = scope.launch {
            try {
                scanInternal(auto)
            } catch (e: Exception) {
                _scan.value = ScanState(context.getString(R.string.scan_failed), error = e.message ?: e.javaClass.simpleName, finished = true)
            }
        }
    }

    private suspend fun scanInternal(auto: Boolean) {
        val sources = _sources.value
        val files = ArrayList<VideoFile>()
        val dubFiles = ArrayList<DubFile>()
        val old = _data.value
        val known = HashMap<String, Long>()   // first-seen times survive rescans
        old.movies.forEach { known[it.file.key] = it.file.added }
        old.shows.forEach { s -> s.episodes.forEach { known[it.file.key] = it.file.added } }
        // Resolutions read from inside files are kept, so a rescan doesn't read them again.
        val knownRes = HashMap<String, String>()
        old.movies.forEach { if (it.file.resolution.isNotEmpty()) knownRes[it.file.key] = it.file.resolution }
        old.shows.forEach { s -> s.episodes.forEach { if (it.file.resolution.isNotEmpty()) knownRes[it.file.key] = it.file.resolution } }
        val failed = ArrayList<String>()
        val notScanned = HashSet<String>()   // sources whose part of the library stays as it was

        for (src in sources) {
            // DLNA servers are browsed as folders only, not indexed.
            if (src.type == "upnp") continue
            if (auto && !src.autoScan) { notScanned += src.id; continue }
            _scan.value = ScanState(context.getString(R.string.scan_reading, src.name))
            try {
                val shares = if (src.share.isNotEmpty()) listOf(src.share) else Fs.listShares(src)
                var reachedAny = false
                var lastError: Exception? = null
                for (share in shares) {
                    _scan.value = ScanState(context.getString(R.string.scan_reading, "${src.name}/$share"))
                    try {
                        walk(src, share, if (src.type == "smb" && src.share.isNotEmpty()) src.folder.trim('/') else "", files, dubFiles, known, knownRes)
                        reachedAny = true
                    } catch (e: Exception) {
                        // One locked share (often "admin") shouldn't stop the rest.
                        Log.w(TAG, "Skipping share $share on ${src.host}", e)
                        lastError = e
                    }
                }
                if (!reachedAny) throw lastError ?: java.io.IOException("No shares")
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't scan ${src.host}", e)
                failed += src.name
                notScanned += src.id
            }
        }
        // Two shares can reach the same file (the whole NAS, and a folder of it): keep one copy, the one
        // with details or watch history if only one has them, else the first share's.
        val byId = sources.associateBy { it.id }
        val matchedBefore = old.movies.filter { it.tmdbId != 0 }.map { it.file.key }.toSet()
        val watched = _progress.value
        val unique = files.groupBy { f -> byId[f.source]?.let { "${it.type}|${it.host.trim().lowercase()}|${f.share.lowercase()}|${f.path.lowercase()}" } ?: f.key }
            .values.map { same -> same.maxBy { f -> (if (f.key in matchedBefore) 2 else 0) + (if (f.key in watched) 1 else 0) } }
        files.clear(); files.addAll(unique)
        // Keep the library of a source we couldn't reach (or didn't scan) rather than emptying it. Films of a
        // source that's gone (a restored backup brings its own share list) go, or they'd show twice.
        val reached = sources.map { it.id }.toSet() - notScanned
        fun kept(f: VideoFile) = f.source in byId && f.source !in reached
        val keepMovies = old.movies.filter { kept(it.file) }
        val keepShows = old.shows.map { s -> s.copy(episodes = s.episodes.filter { kept(it.file) }) }.filter { it.episodes.isNotEmpty() }

        val oldMovies = old.movies.associateBy { it.file.key }
        val movieFiles = ArrayList<Pair<VideoFile, Parsed.MovieName>>()
        val episodeFiles = ArrayList<Pair<VideoFile, Parsed.EpisodeName>>()
        for (f in files) when (val p = parseName(f)) {
            is Parsed.MovieName -> movieFiles += f to p
            is Parsed.EpisodeName -> episodeFiles += f to p
        }

        val toMatch = movieFiles.count { oldMovies[it.first.key]?.tmdbId?.let { id -> id != 0 } != true && lookUpWhileScanning(it.first) }
        val done = java.util.concurrent.atomic.AtomicInteger()
        val gate = Semaphore(4)
        val matched = withContext(Dispatchers.IO) {
            movieFiles.map { (f, p) ->
                async {
                    val prev = oldMovies[f.key]
                    if (prev != null && (prev.tmdbId != 0 || !tmdb.enabled)) return@async prev.copy(file = f)
                    // Pre-cache Details off: looked up when the viewer first opens its folder or page.
                    if (!lookUpWhileScanning(f)) return@async prev?.copy(file = f) ?: Movie(f, p.title.ifBlank { f.name.substringBeforeLast('.') }, p.year)
                    gate.withPermit {
                        val m = matchMovie(f, p, overrides[f.key])
                        val n = done.incrementAndGet()
                        _scan.value = ScanState(context.getString(R.string.scan_matching, n, toMatch), n, toMatch)
                        m
                    }
                }
            }.awaitAll()
        }
        val movies = attachDubs(matched, dubFiles)
        publish(LibraryData(movies + keepMovies, old.shows, old.scannedAt))

        // Episodes: group by series, match each series once.
        val oldShows = old.shows
        val groups = episodeFiles.groupBy { (_, p) -> Names.normalise(p.show) + "|" + p.year }
        val shows = ArrayList<Show>()
        var g = 0
        for ((groupKey, list) in groups) {
            g++
            val first = list.first().second
            _scan.value = ScanState(context.getString(R.string.scan_series, first.show), g, groups.size)
            val nameKey = "show:" + groupKey
            val prev = oldShows.firstOrNull { s -> s.episodes.any { e -> list.any { it.first.key == e.file.key } } }
            val allKnown = prev != null && list.all { (f, _) -> prev.episodes.any { it.file.key == f.key } }
            if (prev != null && allKnown && (prev.tmdbId != 0 || !tmdb.enabled || !lookUpWhileScanning(list.first().first)) && overrides[nameKey] == null) {
                val byKey = list.associate { it.first.key to it.first }
                shows += prev.copy(episodes = prev.episodes.mapNotNull { e -> byKey[e.file.key]?.let { e.copy(file = it) } })
            } else {
                shows += matchShow(first, list, overrides[nameKey], lookUp = lookUpWhileScanning(list.first().first))
            }
        }
        // Two folders of the same series (season packs) end up as one show.
        val merged = (shows + keepShows).groupBy { it.key }.map { (_, same) ->
            same.first().copy(episodes = same.flatMap { it.episodes }.distinctBy { it.file.key })
        }
        publish(LibraryData(movies + keepMovies, merged, System.currentTimeMillis()))
        precacheArtwork()
        val summary = context.getString(R.string.scan_done, movies.size + keepMovies.size, merged.size)
        _scan.value = ScanState(
            summary, finished = true,
            error = if (failed.isEmpty()) null else context.getString(R.string.scan_unreachable, failed.joinToString()),
        )
    }

    private fun walk(src: Source, share: String, root: String, out: MutableList<VideoFile>, dubs: MutableList<DubFile>, known: Map<String, Long>, knownRes: Map<String, String>) {
        // Each folder, and whether it sits in a dub folder ("Film sesleri"), where every media file is a dub.
        val queue = ArrayDeque<Pair<String, Boolean>>()
        queue += root to Dubs.FOLDER.containsMatchIn(root.substringAfterLast('/'))
        val now = System.currentTimeMillis()
        while (queue.isNotEmpty()) {
            val (dir, inDubs) = queue.removeFirst()
            val entries = try {
                Fs.list(src, share, dir)
            } catch (e: Exception) {
                if (dir == root) throw e
                continue
            }
            if (inDubs) {
                val files = entries.filter { !it.isDir }
                val audio = files.filter { Names.ext(it.name) in Dubs.AUDIO_EXT }
                // A small folder holds one film's dub with its subtitles; a big one is a pile of unrelated dubs.
                val subs = if (audio.size <= 3) files.filter { Names.ext(it.name) in Names.SUB_EXT }.map { it.path } else emptyList()
                audio.forEach { dubs += DubFile(src.id, share, it.path, subs) }
                entries.filter { it.isDir }.forEach { queue += it.path to true }
                continue
            }
            // A ".nomedia" file keeps a folder (and everything under it) out of the library.
            if (entries.any { it.name == ".nomedia" }) continue
            val names = entries.filter { !it.isDir }
            val nfos = names.filter { Names.ext(it.name) == "nfo" || (Names.ext(it.name) == "xml" && it.name != "folder.xml") }.associateBy { it.name.lowercase(Locale.ROOT) }
            val allVideos = names.filter { Names.ext(it.name) in Names.VIDEO_EXT && !isSample(it) }
            // "Name-trailer.mkv" and friends belong to "Name.mkv" as extras, not as films of their own.
            val suffixExtras = allVideos.mapNotNull { v -> Names.extraOf(v.name.substringBeforeLast('.'))?.let { (base, kind) -> Triple(base.lowercase(Locale.ROOT), kind, v) } }
            val videos = allVideos.filter { v -> suffixExtras.none { it.third == v } }
            // Extras folders ("Trailers", "Featurettes"…) inside a film's own folder.
            val folderExtras = if (videos.size != 1) emptyList() else entries.filter { it.isDir && it.name.lowercase(Locale.ROOT) in Names.EXTRA_FOLDERS }
                .flatMap { dir ->
                    val kind = Names.EXTRA_FOLDERS.getValue(dir.name.lowercase(Locale.ROOT))
                    runCatching { Fs.list(src, share, dir.path) }.getOrDefault(emptyList())
                        .filter { !it.isDir && Names.ext(it.name) in Names.VIDEO_EXT }
                        .map { Extra(it.path, kind, it.name.substringBeforeLast('.').replace('.', ' ')) }
                }
            val subs = names.filter { Names.ext(it.name) in Names.SUB_EXT }
            val images = names.filter { Names.ext(it.name) in Names.IMAGE_EXT }.associateBy { it.name.lowercase(Locale.ROOT) }
            val single = videos.size == 1
            // A DVD or Blu-ray copied as a folder (VIDEO_TS / BDMV inside): the folder is one film.
            discTitle(src, share, entries)?.let { t ->
                fun img(vararg n: String) = n.firstNotNullOfOrNull { b -> listOf("jpg", "jpeg", "png").firstNotNullOfOrNull { images["$b.$it"] } }?.path
                val key = "${src.id}|$share|$dir"
                out += VideoFile(
                    src.id, share, dir, t.size, t.modified, known[key] ?: minOf(now, t.modified.takeIf { it > 0 } ?: now),
                    subs.map { it.path }, img("poster", "folder", "cover", "movie"), img("fanart", "backdrop", "background"),
                    localLogo = img("logo", "clearlogo"), nfo = (nfos["movie.nfo"] ?: nfos.values.firstOrNull())?.path,
                    edition = Names.edition(dir.substringAfterLast('/')), disc = t.kind, parts = t.parts,
                )
            }
            for (v in videos) {
                val base = v.name.substringBeforeLast('.')
                val lb = base.lowercase(Locale.ROOT)
                fun img(vararg names: String) = names.firstNotNullOfOrNull { n -> listOf("jpg", "jpeg", "png").firstNotNullOfOrNull { images["$n.$it"] } }?.path
                // Sidecar naming: "Name.jpg" or "Name-poster.jpg" poster, "Name-fanart.jpg" backdrop, "Name-logo.png" logo.
                val poster = img("$lb-poster", lb, "$lb.poster", "$lb-cover") ?: if (single) img("poster", "folder", "cover", "movie") else null
                val backdrop = img("$lb-fanart", "$lb-backdrop", "$lb.fanart") ?: if (single) img("fanart", "backdrop", "background") else null
                val logo = img("$lb-logo", "$lb-clearlogo") ?: if (single) img("logo", "clearlogo") else null
                val nfo = (nfos["$lb.nfo"] ?: nfos["$lb.xml"] ?: if (single) nfos["movie.nfo"] else null)?.path
                val strm = if (Names.ext(v.name) == "strm") runCatching { String(Fs.readBytes(src, share, v.path, 8192)).lines().firstOrNull { it.isNotBlank() }?.trim() }.getOrNull() else null
                val mySubs = subs.filter { it.name.lowercase(Locale.ROOT).startsWith(lb) || single }.map { it.path }
                val key = "${src.id}|$share|${v.path}"
                // A name without "2160p", "1080p"… : the picture size is read from the file's header (MKV only).
                val res = if (namesResolution(v.name)) "" else knownRes[key] ?: runCatching { MkvChapters.resolution(src, share, v.path) }.getOrDefault("")
                out += VideoFile(
                    src.id, share, v.path, v.size, v.modified, known[key] ?: minOf(now, v.modified.takeIf { it > 0 } ?: now), mySubs, poster, backdrop,
                    localLogo = logo, nfo = nfo, edition = Names.edition(v.name), strmUrl = strm, resolution = res,
                    extras = suffixExtras.filter { it.first == lb }.map { (_, kind, e) -> Extra(e.path, kind, e.name.substringBeforeLast('.').replace('.', ' ')) } + folderExtras,
                )
            }
            entries.filter { it.isDir && it.name.lowercase(Locale.ROOT) !in SKIP_FOLDERS }
                .forEach { queue += it.path to Dubs.FOLDER.containsMatchIn(it.name) }
        }
    }

    /** Gives each film the dubs whose names match its file name or its (Turkish) TMDB title. */
    private fun attachDubs(movies: List<Movie>, dubs: List<DubFile>): List<Movie> {
        if (dubs.isEmpty()) return movies.map { if (it.file.dubs.isEmpty()) it else it.copy(file = it.file.copy(dubs = emptyList())) }
        val films = movies.map { m ->
            val parsed = Names.parse(m.file.path) as? Parsed.MovieName
            Dubs.Film(m.id, listOfNotNull(parsed?.title, m.title), m.year.ifEmpty { parsed?.year.orEmpty() })
        }
        val found = Dubs.match(films, dubs)
        return movies.map { m -> m.copy(file = m.file.copy(dubs = Dubs.ordered(found[m.id].orEmpty(), m.file.name))) }
    }

    private class DiscTitle(val kind: String, val parts: List<String>, val size: Long, val modified: Long)

    /** The main film of a DVD (the title set with the most data) or Blu-ray (the biggest stream) in [entries]. */
    private fun discTitle(src: Source, share: String, entries: List<SmbEntry>): DiscTitle? {
        val ts = entries.firstOrNull { it.isDir && it.name.equals("VIDEO_TS", true) }
        if (ts != null) {
            val vobs = runCatching { Fs.list(src, share, ts.path) }.getOrNull() ?: return null
            // VTS_01_0.VOB is the title set's menu; VTS_01_1, _2… are the film in 1 GB pieces.
            val sets = vobs.mapNotNull { e -> DVD_PART.matchEntire(e.name)?.let { m -> Triple(m.groupValues[1], m.groupValues[2].toInt(), e) } }
                .filter { it.second > 0 }.groupBy { it.first }
            val main = sets.values.maxByOrNull { set -> set.sumOf { it.third.size } } ?: return null
            val parts = main.sortedBy { it.second }.map { it.third }
            return DiscTitle("dvd", parts.map { it.path }, parts.sumOf { it.size }, parts.maxOf { it.modified })
        }
        val bd = entries.firstOrNull { it.isDir && it.name.equals("BDMV", true) } ?: return null
        val stream = runCatching { Fs.list(src, share, bd.path).firstOrNull { it.isDir && it.name.equals("STREAM", true) } }.getOrNull() ?: return null
        val main = runCatching { Fs.list(src, share, stream.path) }.getOrDefault(emptyList()).filter { Names.ext(it.name) == "m2ts" }.maxByOrNull { it.size } ?: return null
        return DiscTitle("bluray", listOf(main.path), main.size, main.modified)
    }

    private fun isSample(e: SmbEntry) = e.size < 400L shl 20 && Regex("""(?i)\bsample\b|-sample|\.sample""").containsMatchIn(e.name)

    private fun matchMovie(f: VideoFile, p: Parsed.MovieName, override: Int?): Movie {
        val nfo = f.nfo?.let { path -> source(f.source)?.let { src -> runCatching { Nfo.parse(String(Fs.readBytes(src, f.share, path, 1 shl 20), Charsets.UTF_8)) }.getOrNull() } }
        val fallback = Movie(f, nfo?.title ?: p.title.ifBlank { f.name.substringBeforeLast('.') }, nfo?.year ?: p.year, overview = nfo?.plot.orEmpty())
        if (!tmdb.enabled || override == -1) return fallback
        val m = runCatching {
            // A pinned match, then ids written in the file name or its .nfo, then a search.
            val id = override ?: Names.tmdbTag(f.path) ?: nfo?.tmdbId
                ?: (Names.imdbTag(f.path) ?: nfo?.imdbId)?.let { tmdb.findImdb(it) }
                ?: tmdb.match(p.title, p.year, tv = false)
            if (id == 0) fallback else tmdb.movie(id, f, fallback.title)
        }.getOrDefault(fallback)
        // "Prefer local metadata": the .nfo's own title and plot win over TMDB's.
        return if (nfo != null && settings.preferLocalMetadata) m.copy(title = nfo.title ?: m.title, overview = nfo.plot ?: m.overview) else m
    }

    private fun matchShow(first: Parsed.EpisodeName, list: List<Pair<VideoFile, Parsed.EpisodeName>>, override: Int?, lookUp: Boolean = true): Show {
        val plain = Show(
            key = "name:" + Names.normalise(first.show) + first.year,
            title = first.show.ifBlank { list.first().first.name },
            year = first.year,
            episodes = list.map { (f, p) -> Episode(f, p.season, p.episode) },
        )
        if (!tmdb.enabled || override == -1 || !lookUp) return plain
        return runCatching {
            val id = override ?: tmdb.match(first.show, first.year, tv = true).takeIf { it != 0 }
                ?: tmdb.match(first.show, "", tv = true)
            if (id == 0) return plain
            val (show, eps) = tmdb.show(id, plain.title, list.map { it.second.season }.toSet())
            show.copy(episodes = list.map { (f, p) -> tmdb.episode(f, p.season, p.episode, eps[p.season to p.episode]) })
        }.getOrDefault(plain)
    }

    private fun lookUpWhileScanning(f: VideoFile) = source(f.source)?.precacheDetails != false

    /** Disc folders have no extension; give the parser one so "Alien.1979.DVD" keeps its last word. */
    private fun parseName(f: VideoFile) = Names.parse(if (f.disc.isEmpty()) f.path else f.path + ".disc")

    private val lookedUp = HashSet<String>()

    /**
     * Pre-cache Details off: looks up the films and series among [keys] (files the viewer is browsing)
     * that the scan left without details. Each is tried once per run of the app.
     */
    fun fetchDetails(keys: Collection<String>) {
        if (!tmdb.enabled) return
        val d = _data.value
        val movies = keys.mapNotNull { k -> d.movies.firstOrNull { it.id == k } }
            .filter { it.tmdbId == 0 && overrides[it.id] != -1 && synchronized(lookedUp) { lookedUp.add(it.id) } }
        val shows = keys.mapNotNull { k -> d.shows.firstOrNull { s -> s.episodes.any { it.id == k } } }.distinct()
            .filter { it.tmdbId == 0 && synchronized(lookedUp) { lookedUp.add(it.key) } }
        if (movies.isEmpty() && shows.isEmpty()) return
        scope.launch {
            for (m in movies) {
                val p = parseName(m.file) as? Parsed.MovieName ?: continue
                val found = runCatching { matchMovie(m.file, p, overrides[m.id]) }.getOrNull() ?: continue
                val cur = _data.value
                publish(cur.copy(movies = cur.movies.map { if (it.id == m.id) found.copy(file = it.file) else it }))
            }
            for (s in shows) {
                val parsed = s.episodes.mapNotNull { e -> (parseName(e.file) as? Parsed.EpisodeName)?.let { e.file to it } }
                val first = parsed.firstOrNull()?.second ?: continue
                val found = runCatching { matchShow(first, parsed, overrides["show:" + Names.normalise(first.show) + "|" + first.year]) }.getOrNull() ?: continue
                val cur = _data.value
                publish(cur.copy(shows = cur.shows.map { if (it.key == s.key) found else it }))
            }
            precacheArtwork()
        }
    }

    /** Pre-cache Artwork: fetches posters and backdrops of shares that ask for it, so grids open without waiting. */
    private suspend fun precacheArtwork() {
        val wanted = _sources.value.filter { it.precacheArtwork }.map { it.id }.toSet()
        if (wanted.isEmpty()) return
        val d = _data.value
        val items = d.movies.filter { it.file.source in wanted }.map { Item.MovieItem(it) } +
            d.shows.filter { s -> s.episodes.any { it.file.source in wanted } }.map { Item.ShowItem(it) }
        val loader = coil.Coil.imageLoader(context)
        val gate = Semaphore(3)
        withContext(Dispatchers.IO) {
            items.flatMap { listOfNotNull(it.poster, it.backdrop) }.distinct().map { img ->
                async {
                    gate.withPermit {
                        // Small decode: only the cached download matters, not the bitmap.
                        loader.execute(coil.request.ImageRequest.Builder(context).data(img).size(160, 240)
                            .memoryCachePolicy(coil.request.CachePolicy.DISABLED).build())
                    }
                }
            }.awaitAll()
        }
    }

    /** "Fix match": pins a file (or a whole series) to a TMDB id and refetches it. */
    fun rematchMovie(movie: Movie, tmdbId: Int) = scope.launch {
        overrides = overrides + (movie.file.key to tmdbId)
        store("overrides.json", overrides)
        val m = matchMovie(movie.file, parseName(movie.file) as? Parsed.MovieName ?: Parsed.MovieName(movie.title, movie.year), tmdbId)
        val d = _data.value
        publish(d.copy(movies = d.movies.map { if (it.id == movie.id) m else it }))
    }

    fun rematchShow(show: Show, tmdbId: Int) = scope.launch {
        val parsed = show.episodes.map { e -> e.file to (Names.parse(e.file.path) as? Parsed.EpisodeName ?: Parsed.EpisodeName(show.title, show.year, e.season, e.number)) }
        val first = parsed.first().second
        overrides = overrides + ("show:" + Names.normalise(first.show) + "|" + first.year to tmdbId)
        store("overrides.json", overrides)
        val s = matchShow(first, parsed, tmdbId)
        val d = _data.value
        publish(d.copy(shows = d.shows.map { if (it.key == show.key) s else it }))
    }

    /** Times a 10-second read of the biggest film, which is what 4K playback has to keep up with. */
    fun speedTest(): Double {
        val m = _data.value.movies.maxByOrNull { it.file.size } ?: throw java.io.IOException(context.getString(R.string.speed_no_files))
        val src = source(m.file.source) ?: throw java.io.IOException(context.getString(R.string.source_missing))
        val mbps = Fs.measure(src, m.file.share, m.file.parts.firstOrNull() ?: m.file.path)
        settings.measuredMbps = mbps.toFloat()
        return mbps
    }

    // ---- backup to the NAS: profiles, history, lists and settings, so the other TV (or a reinstall) can restore them ----

    private fun backupTarget(): Triple<Source, String, String> {
        val src = _sources.value.firstOrNull() ?: throw java.io.IOException(context.getString(R.string.source_missing))
        val share = src.share.ifEmpty { Fs.listShares(src).firstOrNull { it.equals("video", true) } ?: Fs.listShares(src).first() }
        return Triple(src, share, "Glint/backup.json")
    }

    fun backupToNas(): String {
        flush()
        val files = dir.listFiles().orEmpty().filter { it.name.endsWith(".json") && it.name != "library.json" && !it.name.endsWith(".tmp") }
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE).all
        val o = org.json.JSONObject()
        o.put("files", org.json.JSONObject().apply { files.forEach { put(it.name, it.readText()) } })
        o.put("settings", org.json.JSONObject().apply { prefs.forEach { (k, v) -> put(k, org.json.JSONArray().put(v?.javaClass?.simpleName).put(v)) } })
        val (src, share, path) = backupTarget()
        Fs.write(src, share, path, o.toString().toByteArray())
        return "${src.name}/$share/$path"
    }

    /** Puts a backup back; the app restarts to load it. */
    fun restoreFromNas() {
        val (src, share, path) = backupTarget()
        val o = org.json.JSONObject(String(Fs.readBytes(src, share, path, 32 shl 20)))
        val files = o.getJSONObject("files")
        files.keys().forEach { name -> if (!name.contains('/')) File(dir, name).writeText(files.getString(name)) }
        val ed = context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
        val st = o.getJSONObject("settings")
        st.keys().forEach { k ->
            val a = st.getJSONArray(k)
            when (a.getString(0)) {
                "Boolean" -> ed.putBoolean(k, a.getBoolean(1))
                "Integer" -> ed.putInt(k, a.getInt(1))
                "Long" -> ed.putLong(k, a.getLong(1))
                "Float" -> ed.putFloat(k, a.getDouble(1).toFloat())
                "String" -> ed.putString(k, a.getString(1))
            }
        }
        ed.commit()
    }

    fun cacheBytes(): Long = context.cacheDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    fun clearCache() { context.cacheDir.listFiles()?.forEach { it.deleteRecursively() } }

    fun clearLibrary() {
        publish(LibraryData())
    }

    private fun publish(d: LibraryData) {
        _data.value = d
        _visible.value = visible(d)
        store("library.json", d)
    }

    // ---- storage ----

    private inline fun <reified T> load(name: String, default: () -> T): T = runCatching {
        json.decodeFromString<T>(File(dir, name).readText())
    }.getOrElse { default() }

    private inline fun <reified T> store(name: String, value: T) {
        val text = json.encodeToString(value)
        synchronized(this) {
            val tmp = File(dir, "$name.tmp")
            tmp.writeText(text)
            tmp.renameTo(File(dir, name))
        }
    }

    private val pending = HashSet<String>()

    /** Coalesces frequent writes (progress every few seconds) into one write a moment later. */
    private inline fun <reified T> storeLater(name: String, crossinline value: () -> T) {
        synchronized(pending) { if (!pending.add(name)) return }
        scope.launch {
            kotlinx.coroutines.delay(2000)
            synchronized(pending) { pending.remove(name) }
            store(name, value())
        }
    }

    fun flush() = store(progressFile, _progress.value)

    fun switchProfile(id: String) {
        val p = _profiles.value.firstOrNull { it.id == id } ?: return
        flush()
        _profile.value = p
        settings.profileId = id
        _progress.value = load(progressFile) { emptyMap() }
        _user.value = load(userFile) { UserData() }
        _visible.value = visible(_data.value)
    }

    fun saveProfile(p: Profile): Profile {
        val prof = if (p.id.isEmpty()) p.copy(id = UUID.randomUUID().toString().take(8)) else p
        _profiles.value = _profiles.value.filter { it.id != prof.id } + prof
        store("profiles.json", _profiles.value)
        if (prof.id == _profile.value.id) { _profile.value = prof; _visible.value = visible(_data.value) }
        return prof
    }

    fun removeProfile(id: String) {
        if (id == "main") return
        _profiles.value = _profiles.value.filter { it.id != id }
        store("profiles.json", _profiles.value)
        if (_profile.value.id == id) switchProfile("main")
    }

    private fun visible(d: LibraryData): LibraryData {
        val p = _profile.value
        if (!p.restricted) return d
        return d.copy(movies = d.movies.filter { ageOf(it.certification) <= p.maxAge }, shows = d.shows.filter { ageOf(it.certification) <= p.tvAge })
    }

    // When the PIN was last entered; the profile's "ask again" setting decides how long that holds.
    private var unlockedAt = 0L
    fun markUnlocked() { unlockedAt = System.currentTimeMillis() }
    fun stillUnlocked(): Boolean {
        val window = when (_profile.value.askAgain) { 1 -> 15 * 60_000L; 2 -> 60 * 60_000L; else -> 0L }
        return System.currentTimeMillis() - unlockedAt < window
    }

    /** Minimum age for a TMDB certification (Turkish and US systems; Turkey's "Genel İzleyici Kitlesi" in any spelling is for all); unknown counts as for adults. */
    fun ageOf(cert: String): Int = if (cert.trim().startsWith("Genel", ignoreCase = true)) 0 else when (cert.trim().uppercase(Locale.ROOT)) {
        "G", "TV-Y", "TV-G", "0+", "A" -> 0
        "TV-Y7", "7+", "6+", "PG", "TV-PG" -> 7
        "10+" -> 10
        "PG-13", "13+", "12+", "TV-14", "15+" -> 13
        "16+" -> 16
        else -> 18
    }

    companion object {
        private const val TAG = "Library"
        private val DVD_PART = Regex("""(?i)VTS_(\d\d)_(\d)\.VOB""")
        private val SKIP_FOLDERS = setOf("video_ts", "audio_ts", "bdmv", "certificate", "sample", "samples", "extras", "featurettes", "behind the scenes", "trailers", "deleted scenes", "interviews", "scenes", "shorts", "other", "@eadir", ".@__thumb")
    }
}

fun Show.sortedEpisodes() = episodes.sortedWith(compareBy({ if (it.season == 0) 999 else it.season }, { it.number }))
