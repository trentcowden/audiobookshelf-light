# Audiobookshelf for Light Phone

A dead-simple [Audiobookshelf](https://www.audiobookshelf.org) client for the Light Phone III, built with the [light-sdk](https://github.com/lightphone/light-sdk). Download books from your own server, listen without worrying about signal, and keep your place in sync.

## Features

- Log in to your Audiobookshelf server (server address, username, password)
- Library: **Continue** (books in progress) and **All Books**, with your progress and download status; works offline
- Search by title, author, series or narrator
- Books are **downloaded, then played from the phone**, so bad signal never interrupts listening. Downloads resume if interrupted
- Player: play/pause, back/forward 15 seconds, a seek bar for the whole book (tap the times to switch it to the current chapter), "38% · 14h 2m left", chapter list, speed (0.75x–2x)
- Keeps playing in the background
- Progress is saved on the phone every few seconds and synced to the server every 15 seconds and when you pause. Offline listening is sent when you're back online. When phone and server disagree, the newer position wins.
- After a pause longer than 5 minutes, resumes 15 seconds back so you can pick the thread up again
- Settings: server, downloads storage, **Reset Player**, **Log Out**

## Using it

1. Open the tool and enter your server address (e.g. `audiobooks.example.com`; `https://` is added for you, and plain `http://` is tried if that fails), your username and password.
2. Tap a book, download it (keep the tool open while it downloads; the Light SDK can't download in the background yet), then play.
3. Remove a download with the trash icon on the player. Your progress is kept.

The password is only used to log in and is never stored. The tool keeps the tokens the server returns, in its private storage. The Light keyboard can't hide what you type, so the password is visible while you enter it.

Offline listening updates your position but isn't counted in Audiobookshelf's listening stats.

## If playback gets stuck

Settings → **Reset Player** stops playback and rebuilds the player. If the player still can't start, the tool closes itself so the next launch starts fresh (LightOS has no force-stop).

Why this exists: in a Light SDK tool, if Android destroys the tool's screen while audio is playing, a player owned by a screen is never released. The SDK only allows one player per tool, so every later tap does nothing until the phone restarts. This tool owns its single player outside any screen, releases it when the screen goes away, checks that Play actually started (rebuilding the player and retrying once if it didn't), and shows an error instead of failing silently.

## Building

Needs JDK 17 and 21 and the Android SDK (API 36). This repo is a fork of light-sdk; the tool is the `:tool` module.

```bash
./gradlew :tool:assembleDebug -DlightSdk.toolOnly=true      # build
./gradlew :tool:testDebugUnitTest -DlightSdk.toolOnly=true  # unit tests
adb install -r tool/build/outputs/apk/debug/tool-debug.apk   # sideload
```

For the LightOS emulator, switch `serverPackage` in `tool/lighttool.toml` and build with `-DlightSdk.allowAltServerPackage=true`.

## Credits

The player design (detached audio, chapter seek bar, speed picker, rewind after a long pause) is adapted from [fenleon/audiobooks](https://github.com/fenleon/audiobooks) (MIT). The API usage follows the official [audiobookshelf-app](https://github.com/advplyr/audiobookshelf-app).

Unofficial, not affiliated with Audiobookshelf or The Light Phone, Inc. The Light SDK's original README is [here](https://github.com/lightphone/light-sdk#readme).
