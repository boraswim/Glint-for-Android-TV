# Third-party notices

Glint is licensed under the [GNU General Public License v3.0](LICENSE). It is built on the open-source
libraries below, each under its own license. The licenses are compatible with GPL-3.0; the full text of
each is in the library's own repository.

| Library | Used for | License |
|---|---|---|
| [LibVLC for Android](https://code.videolan.org/videolan/vlc-android) (`org.videolan.android:libvlc-all`) | Main video and audio playback | LGPL-2.1-or-later |
| [AndroidX Media3](https://github.com/androidx/media) (`media3-exoplayer`, `media3-ui`) | Dolby Vision playback | Apache-2.0 |
| [Jellyfin Media3 FFmpeg decoder](https://github.com/jellyfin/jellyfin-androidx-media) (`org.jellyfin.media3:media3-ffmpeg-decoder`) | Audio formats the TV can't decode itself, in the Dolby Vision player | GPL-3.0 |
| [smbj](https://github.com/hierynomus/smbj) | SMB shares | Apache-2.0 |
| [dcerpc](https://github.com/rapid7/smb-dcerpc) (Rapid7) | Listing the shares on a NAS | BSD-3-Clause |
| [Bouncy Castle](https://www.bouncycastle.org/) (via smbj) | NTLM sign-in to SMB shares | MIT (Bouncy Castle License) |
| [OkHttp](https://github.com/square/okhttp) | WebDAV and web requests | Apache-2.0 |
| [Apache Commons Net](https://commons.apache.org/proper/commons-net/) | FTP and FTPS | Apache-2.0 |
| [JSch](https://github.com/mwiede/jsch) (mwiede fork) | SFTP | BSD-3-Clause |
| [nfs-client-java](https://github.com/EMCECS/nfs-client-java) | NFS v3 | Apache-2.0 |
| [Coil](https://github.com/coil-kt/coil) | Loading posters and artwork | Apache-2.0 |
| [Jetpack Compose](https://developer.android.com/jetpack/compose), AndroidX Activity, Core, Lifecycle | The interface | Apache-2.0 |
| [kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines), [kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization) | Background work, saving data | Apache-2.0 |

## LibVLC (LGPL-2.1)

Glint uses LibVLC unmodified, as the prebuilt `libvlc-all` package from Maven Central. Its source code is
available from the [VideoLAN project](https://code.videolan.org/videolan/vlc-android); you may replace the
LibVLC in Glint with your own build by changing the dependency in `app/build.gradle.kts` and building the
app from this repository.

## Data and services

- **TMDB:** titles, plots, cast, posters and artwork come from [The Movie Database](https://www.themoviedb.org/).
  This product uses the TMDB API but is not endorsed or certified by TMDB.
- **IMDb:** ratings come from the IMDb non-commercial datasets. Information courtesy of IMDb
  (https://www.imdb.com). Used with permission for personal, non-commercial use.
- **Rotten Tomatoes:** critic and audience scores are read from rottentomatoes.com pages, linked through
  Wikidata. Rotten Tomatoes is a trademark of Fandango; Glint is not affiliated with it.
- **YouTube:** trailers open in the YouTube app.

Film and series artwork shown in the app and in the screenshots belongs to its respective owners.
