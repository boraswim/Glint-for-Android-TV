# Security policy

## Supported versions

Security fixes go into the latest release. If you're on an older version, update by installing the newest
release's APK over it.

| Version | Supported |
|---|---|
| Latest release | Yes |
| Older releases | No |

## Reporting a vulnerability

Please report security problems privately, not in a public issue:

1. Go to the repository's **Security** tab.
2. Click **Report a vulnerability** and describe the problem: what's affected, how to reproduce it, and
   what an attacker could do with it.

Only the maintainer sees the report. You can expect a first reply within a week. Once a fix is
released, the report can be published with credit to you, if you'd like.

## What's in scope

Glint runs on your own TV, but a few parts deal with things from outside it, and problems there are the
most useful to report:

- **Shares:** what Glint reads from SMB, WebDAV, FTP, SFTP, NFS and UPnP/DLNA servers, and from USB
  disks: file names, `.nfo` files, subtitles and artwork.
- **Saved logins:** share user names and passwords are kept in the app's private storage on the TV,
  and in the NAS backup (`Glint/backup.json`).
- **The local relay:** for a share with a fixed SMB version, Glint serves the film to its player through
  an HTTP server on `127.0.0.1` only.
- **Online lookups:** film details from TMDB, ratings from IMDb's datasets, Wikidata and Rotten
  Tomatoes, and trailers through YouTube.

Bugs in the libraries themselves (libVLC, Media3, smbj and others) belong with those projects. Do tell us
if Glint uses one of them in an unsafe way.
