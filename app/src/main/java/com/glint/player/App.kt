package com.glint.player

import android.app.Application
import coil.ImageLoader
import kotlinx.coroutines.sync.withPermit
import coil.ImageLoaderFactory
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.disk.DiskCache
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.key.Keyer
import coil.memory.MemoryCache
import coil.request.Options
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import java.io.File
import java.security.MessageDigest

class App : Application(), ImageLoaderFactory {
    lateinit var library: Library
        private set

    override fun onCreate() {
        super.onCreate()
        AppLanguage.apply(this)
        instance = this
        library = Library(this)
        Palette.light = library.settings.theme == 2
        Palette.black = library.settings.theme == 0
        Palette.accent = library.settings.accent
        Thread { SizedPosterFetcher.trim(this); ImdbRatings.refresh(this) }.start()
    }

    override fun newImageLoader() = ImageLoader.Builder(this)
        .components {
            add(NasImageFetcher.Factory())
            add(Keyer<NasImage> { data, _ -> "nas:${data.source}/${data.share}/${data.path}" })
            add(SizedPosterFetcher.Factory())
            add(Keyer<SizedPoster> { data, _ -> "sized:${data.model}|${data.w}x${data.h}" })
        }
        .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.3).build() }
        .diskCache { DiskCache.Builder().directory(cacheDir.resolve("images")).maxSizeBytes(250L shl 20).build() }
        .crossfade(200)
        .build()

    companion object {
        lateinit var instance: App
            private set
        val library get() = instance.library
    }
}

/**
 * Loads posters and fanart that sit next to the videos on the NAS. They're 2000×3000 originals,
 * kept byte for byte (never re-encoded) in the cache, so later loads skip the network.
 */
class NasImageFetcher(private val data: NasImage, private val options: Options) : Fetcher {
    override suspend fun fetch(): FetchResult {
        // Earlier versions kept shrunk, recompressed copies; those are dropped for the originals.
        for (old in listOf("nas-images", "nas-images-hq")) File(options.context.cacheDir, old).takeIf { it.exists() }?.deleteRecursively()
        val dir = File(options.context.cacheDir, "nas-images-original").apply { mkdirs() }
        val cached = File(dir, hash("${data.source}/${data.share}/${data.path}"))
        if (!cached.exists()) {
            val src = App.library.source(data.source) ?: error("Source ${data.source} is gone")
            // A few at a time: a full grid asking at once (on top of a scan) makes the NAS stop answering.
            val bytes = reads.withPermit {
                if (src.type == "smb") SmbImages.readBytes(src, data.share, data.path) else Fs.readBytes(src, data.share, data.path)
            }
            val tmp = File(dir, cached.name + ".tmp")
            tmp.writeBytes(bytes)
            tmp.renameTo(cached)
        }
        return SourceResult(
            source = ImageSource(cached.toOkioPath(), FileSystem.SYSTEM),
            mimeType = null,   // JPEG, PNG or WebP as found on the NAS
            dataSource = DataSource.DISK,
        )
    }

    private fun hash(s: String) = MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        private val reads = kotlinx.coroutines.sync.Semaphore(3)
    }

    class Factory : Fetcher.Factory<NasImage> {
        override fun create(data: NasImage, options: Options, imageLoader: ImageLoader) = NasImageFetcher(data, options)
    }
}
