# Glint privacy policy

*Last updated: 30 October 2026*

Glint is a media player for Android TV that plays films and series from your own network shares and USB disks.
It has no accounts, no ads, no analytics and no tracking, and the developer runs no server: nothing you do in
Glint is sent to the developer.

## What stays on your TV

Glint keeps these in its private storage on the TV, where other apps can't read them:

- **Your shares:** their addresses, and the user names and passwords you enter for them.
- **Your library:** the films and series found on your shares, with their details and artwork.
- **Your activity:** profiles (with their PINs), watch progress and history, watchlist, playlists, collections,
  ratings you give, and your settings.
- **Your API keys:** the TMDB and OMDb keys you enter.

## What goes over your home network

To play and scan your films, Glint connects to the shares you added (SMB, WebDAV, FTP, FTPS, SFTP, NFS or
UPnP/DLNA) with the details you entered.

If you use **Settings → Library → Back up to NAS**, Glint writes a backup file (`Glint/backup.json`) to your own NAS.
It holds your profiles, watch history, lists and settings, including your shares' passwords and your API keys,
so keep access to that share private.

## What goes to the internet

Glint contacts three services, and only for the reasons below:

- **TMDB** (The Movie Database, themoviedb.org), with the API key you enter: Glint sends film and series titles and
  years taken from your file names, and the TMDB id of a title, to get its details, artwork, cast and trailers.
  Posters and pictures are downloaded from TMDB's image server. TMDB's privacy policy:
  https://www.themoviedb.org/privacy-policy
- **OMDb** (omdbapi.com), with the API key you enter: Glint sends a title's IMDb id to get its IMDb and Rotten
  Tomatoes scores. OMDb's privacy policy: https://www.omdbapi.com/legal.htm
- **YouTube:** when you open a trailer, Glint hands its link to the YouTube app on your TV. What YouTube does
  then is covered by Google's privacy policy: https://policies.google.com/privacy

These services see your TV's internet address, as any website does. Glint sends them no names, file paths,
passwords or anything else about you.

## Permissions

- **Network access:** to reach your shares and the services above.
- **Photos and videos (media) access,** only if you add a USB disk: to read the videos, pictures and audio on it.

## Deleting your data

- **Settings → Delete all data** removes everything Glint keeps on the TV, and Glint starts again as new.
- Uninstalling Glint removes it too.
- A backup you made on your NAS stays there until you delete `Glint/backup.json` yourself.

## Children

Glint isn't aimed at children and collects no personal information from anyone. Profiles can have age limits
and a PIN, so a family can choose what each profile shows.

## Changes and contact

If this policy changes, the new version will be published here with a new date. For questions, open an issue at
https://github.com/boraswim/Glint-for-Android-TV/issues.
