package com.glint.player

import kotlinx.serialization.Serializable

/** A network share the library is built from. An empty [share] means every share on the host. */
@Serializable
data class Source(
    val id: String,
    val name: String,
    val host: String,
    val share: String = "",
    val folder: String = "",
    val user: String = "",
    val password: String = "",
    val domain: String = "",
    /** smb, webdav, ftp, ftps, sftp, nfs or upnp. */
    val type: String = "smb",
    // Per-share options.
    val autoScan: Boolean = true,          // rescan when the app starts
    val precacheDetails: Boolean = true,   // look titles up on TMDB while scanning, not when first opened
    val precacheArtwork: Boolean = false,  // download posters and backdrops while scanning
    val smartFolders: Boolean = true,      // a folder holding one film opens that film
    /** SMB only: "" lets client and server agree (newest both speak); "2.0", "2.1", "3.0" fix the version. */
    val smbVersion: String = "",
)

/** One video file on a source. [path] is relative to the share, with "/" separators. */
@Serializable
data class VideoFile(
    val source: String,
    val share: String,
    val path: String,
    val size: Long,
    val modified: Long,
    val added: Long,
    // Sidecar files found next to the video, as paths on the same share.
    val subtitles: List<String> = emptyList(),
    val localPoster: String? = null,
    val localBackdrop: String? = null,
    // Dub audio tracks for this film found elsewhere on the NAS ("Film sesleri").
    val dubs: List<DubFile> = emptyList(),
    val localLogo: String? = null,
    val nfo: String? = null,              // metadata file next to the video
    val edition: String = "",             // "Extended", "Director's Cut", "IMAX"…
    val strmUrl: String? = null,          // .strm files point at a video elsewhere
    val extras: List<Extra> = emptyList(),  // trailers, featurettes… found next to the film
    // A DVD (VIDEO_TS) or Blu-ray (BDMV) folder: [path] is the folder, [parts] the files of the main title in order.
    val disc: String = "",
    val parts: List<String> = emptyList(),
) {
    val key get() = "$source|$share|$path"
    val name get() = path.substringAfterLast('/')
}

/** A separate audio file (usually a Turkish dub), plus subtitles that came in the same folder. */
@Serializable
data class DubFile(val source: String, val share: String, val path: String, val subtitles: List<String> = emptyList())

/** Bonus video for a film: kind is trailer, featurette, behind the scenes, deleted, interview, scene, short or other. */
@Serializable
data class Extra(val path: String, val kind: String, val title: String)

@Serializable
data class Person(val name: String, val role: String, val photo: String? = null)

@Serializable
data class Movie(
    val file: VideoFile,
    val title: String,
    val year: String = "",
    val tmdbId: Int = 0,
    val overview: String = "",
    val tagline: String = "",
    val poster: String? = null,
    val backdrop: String? = null,
    val logo: String? = null,
    val rating: Double = 0.0,
    val runtime: Int = 0,
    val genres: List<String> = emptyList(),
    val certification: String = "",
    val director: String = "",
    val cast: List<Person> = emptyList(),
    val collectionId: Int = 0,
    val collectionName: String = "",
    val collectionPoster: String? = null,
    val collectionBackdrop: String? = null,
    val releaseDate: String = "",
) {
    val id get() = file.key

    /** Average bitrate in Mbps from size and running time, or 0 when the runtime is unknown. */
    val mbps get() = if (runtime > 0) file.size * 8 / 1e6 / (runtime * 60) else 0.0

    /** Matched on TMDB or has its own poster: a film. Otherwise it's a home video or clip. */
    val catalogued get() = tmdbId != 0 || file.localPoster != null
}

@Serializable
data class Episode(
    val file: VideoFile,
    val season: Int,
    val number: Int,
    val title: String = "",
    val overview: String = "",
    val still: String? = null,
    val airDate: String = "",
    val runtime: Int = 0,
    val rating: Double = 0.0,
) {
    val id get() = file.key
    val code get() = "S%02dE%02d".format(season, number)
}

@Serializable
data class Show(
    val key: String,          // "tmdb:<id>" when matched, else "name:<normalised title>"
    val title: String,
    val year: String = "",
    val tmdbId: Int = 0,
    val overview: String = "",
    val poster: String? = null,
    val backdrop: String? = null,
    val logo: String? = null,
    val rating: Double = 0.0,
    val genres: List<String> = emptyList(),
    val certification: String = "",
    val network: String = "",
    val status: String = "",
    val cast: List<Person> = emptyList(),
    val seasonPosters: Map<Int, String> = emptyMap(),
    val episodes: List<Episode> = emptyList(),
) {
    val seasons get() = episodes.map { it.season }.distinct().sorted()
}

/** Someone who watches: their own history, lists and, for children, an age limit behind a PIN. */
@Serializable
data class Profile(
    val id: String,
    val name: String,
    val color: Int = 0,
    val restricted: Boolean = false,
    val maxAge: Int = 18,
    val pin: String = "",
    val maxTvAge: Int = -1,              // -1: same limit as films
    val lockSearch: Boolean = false,     // Search needs the PIN
    val lockSettings: Boolean = false,   // Settings need the PIN
    val askAgain: Int = 0,               // after a correct PIN, ask again: 0 every time, 1 after 15 minutes, 2 after an hour
) {
    val tvAge get() = if (maxTvAge < 0) maxAge else maxTvAge
}

/** Things the viewer decides: watchlist and personal star ratings (1–5). */
@Serializable
data class UserData(
    val watchlist: List<String> = emptyList(),
    val ratings: Map<String, Int> = emptyMap(),
    val playlists: Map<String, List<String>> = emptyMap(),        // name → items in play order
    val collections: Map<String, List<String>> = emptyMap(),      // the viewer's own collections: films, series ("tmdb:1") and seasons ("season:tmdb:1#2")
    val pins: List<Pin> = emptyList(),                            // categories pinned to Home
    val hiddenTiles: List<Int> = emptyList(),                     // built-in Favorites tiles the viewer hid
    val tileOrder: List<String> = emptyList(),                    // Favorites order the viewer set ("tile:1", "pin:genre:Drama"); others follow
)

/**
 * A library category on Home: as a Favorites tile or as a row of its titles.
 * key: "genre:Name", "playlist:Name", "collection:Id", "actor:Name" or "folder:source|share|path".
 */
@Serializable
data class Pin(val key: String, val label: String, val row: Boolean)

@Serializable
data class LibraryData(
    val movies: List<Movie> = emptyList(),
    val shows: List<Show> = emptyList(),
    val scannedAt: Long = 0,
)

/** How far into a file the viewer got. */
@Serializable
data class Progress(
    val position: Long,
    val duration: Long,
    val updated: Long,
    val watched: Boolean = false,
    val audioTrack: Int = -2,     // libVLC ids; -2 = not chosen by the viewer
    val subtitleTrack: Int = -2,
) {
    val fraction get() = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    /** Has a place to resume from, however early: anything in Watching asks whether to resume or start over. */
    val inProgress get() = !watched && position > 0 && duration > 0
    /**
     * Opened and not finished, however briefly: enough to sit in Watching (resuming needs [inProgress]).
     * The player saves at least 1 ms; "mark unwatched" saves 0, which keeps the title out.
     */
    val started get() = !watched && position > 0 && duration > 0
}

/** Anything that can sit on a shelf or in a grid. */
sealed interface Item {
    val id: String
    val title: String
    val poster: Any?
    val backdrop: Any?

    data class MovieItem(val movie: Movie) : Item {
        override val id get() = movie.id
        override val title get() = movie.title
        // The NAS poster wins: it's the one the owner picked. TMDB fills in when a file has none.
        override val poster: Any? get() = movie.file.localPoster?.let { nasUri(movie.file, it) } ?: movie.poster
        override val backdrop: Any? get() = movie.backdrop ?: movie.file.localBackdrop?.let { nasUri(movie.file, it) }
        val logo: Any? get() = movie.file.localLogo?.let { nasUri(movie.file, it) } ?: movie.logo
    }

    /** A series, or one [season] of it (then [show] holds only that season's episodes). */
    data class ShowItem(val show: Show, val season: Int? = null) : Item {
        override val id get() = if (season == null) show.key else "season:${show.key}#$season"
        override val title get() = show.title
        override val poster: Any? get() = season?.let { show.seasonPosters[it] } ?: show.poster ?: show.episodes.firstNotNullOfOrNull { e -> e.file.localPoster?.let { nasUri(e.file, it) } }
        override val backdrop: Any? get() = show.backdrop ?: show.episodes.firstNotNullOfOrNull { e -> e.file.localBackdrop?.let { nasUri(e.file, it) } }
    }

    data class EpisodeItem(val show: Show, val episode: Episode) : Item {
        override val id get() = episode.id
        override val title get() = show.title
        override val poster: Any? get() = ShowItem(show).poster
        override val backdrop: Any? get() = episode.still ?: show.backdrop ?: ShowItem(show).backdrop
    }

    /** A TMDB collection of films, or one of the viewer's own, which may also hold series and seasons ([others]). */
    data class CollectionItem(val collectionId: Int, val name: String, val movies: List<Movie>, val others: List<ShowItem> = emptyList()) : Item {
        override val id get() = "collection:$collectionId"
        override val title get() = name
        val all: List<Item> get() = movies.map { MovieItem(it) } + others
        val size get() = movies.size + others.size
        override val poster: Any? get() = movies.firstOrNull()?.let { it.collectionPoster ?: MovieItem(it).poster } ?: others.first().poster
        override val backdrop: Any? get() = movies.firstOrNull()?.let { it.collectionBackdrop ?: MovieItem(it).backdrop } ?: others.first().backdrop
    }
}

/** Coil model for an image stored on the NAS next to a video, served by [NasImageFetcher]. */
data class NasImage(val source: String, val share: String, val path: String)

fun nasUri(file: VideoFile, path: String) = NasImage(file.source, file.share, path)
