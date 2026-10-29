# Contributing to Glint

Thanks for helping. Bug reports, fixes and ideas are all welcome. Please follow the
[code of conduct](CODE_OF_CONDUCT.md), and report security problems privately as described in
[SECURITY.md](SECURITY.md), not in an issue.

## Reporting a bug

Open an issue with the **Bug report** form. The most useful details are what you did, what happened,
your TV or box and its Android version, and for playback problems the file's video and audio format
(the playback menu's **Info** tab shows them). Please don't attach films or other copyrighted files.

## Setting up

Glint is an Android TV app in Kotlin with Jetpack Compose. You need the Android SDK and JDK 17 (Android
Studio brings both).

1. Copy `local.properties.example` to `local.properties` and set `sdk.dir`. A free
   [TMDB key](https://www.themoviedb.org/settings/api) in `tmdb.apiKey` lets films match their details.
2. Build and install the release build on a TV or box:

   ```
   ./gradlew assembleRelease
   adb install -r app/build/outputs/apk/release/app-release.apk
   ```

   Use the release build even for testing: debug builds of Compose are slow on TV boxes.
3. Run the unit tests with `./gradlew testReleaseUnitTest`.

The README's **Code** table says what each file does.

## How the app is meant to work

- **Made for the remote.** Every screen works with the arrow keys, OK and Back alone. Check where focus
  goes when a screen opens, when you come back to it, and at the edges of every row.
- **Big screen, sofa distance.** Sizes are laid out on a 1280dp-wide canvas; text has to read from across
  the room on a 1080p TV.
- **Your files, your network.** Nothing leaves the user's network except the film details (TMDB), the
  rating lookups and trailers. No accounts, analytics or tracking.
- **Every language.** Text shown to the user goes in `res/values/strings.xml` and in each translation
  under `res/values-*/`.

## Making a change

1. Fork the repository and create a branch.
2. Match the style of the surrounding code: its naming, comment density and idioms.
3. Test on a real TV or box with real files: open the screens you changed with the remote, and play
   something if playback is involved.
4. Never commit `local.properties`, signing keys, logs or media files (`.gitignore` covers them).
5. Open a pull request and fill in its template.

By contributing, you agree that your contributions are licensed under the project's
[GNU General Public License v3.0](LICENSE).
