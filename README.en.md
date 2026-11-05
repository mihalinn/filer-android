# Filer

[日本語](README.md) | **English**

An ad-free file manager for Android. It handles files on your device and on a NAS (SMB share) in the same way.

- Requirements: Android 11 or later (minSdk 30), arm64 device
- License: GPL-3.0 (see “License” below)
- No warranty. Use it at your own risk

## Features

- **File operations**: list, search, sort, copy, move, rename, delete (with a Trash), ZIP compress and extract, storage analysis
- **NAS (SMB)**: save connections and use them just like folders on your device. Copy and move keep running even if you close the app
- **Preview**: open a file and it is shown right away
  - Text, Markdown, CSV, JSON, XML, HTML, EPUB, comics (cbz and others)
  - Images (including HEIC and SVG) and PDF
  - Video, audio and MIDI (ExoPlayer and FFmpeg). Video and audio keep playing with the screen off, and you can control them from the notification
  - Word, Excel, PowerPoint (including old formats), ODF and RTF
  - Drawings (DXF, JW_CAD, draw.io) and 3D models (STL, OBJ, PLY, OFF, 3MF)
  - Touch and hold to select text and copy it (text files, Word, Excel, PowerPoint, PDF and more)
  - Tables such as CSV and Excel can be pinched to zoom and moved smoothly in any direction, including diagonally
  - Archives (zip, 7z, tar family, rar, lzh), APK, and bundles such as .xapk (install)
  - iPhone formats (plist, pkpass, iWork, ipa), SQLite, fonts and contacts (vCard)
- **Editing**: edit text files (find and replace, choice of text encoding and line endings). CSV and TSV can be edited like a spreadsheet by selecting cells
- **Appearance**: reorderable Home screen, light and dark colors, theme colors (indigo, teal, wine red)
- **Settings backup**: export your settings, bookmarks and NAS connections to a file in a custom format (.filerbak) and restore them later. If you set a password the file is strongly encrypted (scrypt + AES-256-GCM); without one it still gets simple encryption only. You can save it anywhere: device, NAS or cloud
- **Languages**: English and Japanese. By default the app follows the device language, and you can choose the language inside the app under Settings → Display → Language. Other languages are welcome (see “Adding a translation” below)

## Build

Requirements: JDK 17 and the Android SDK (compileSdk 35)

```
echo "sdk.dir=/path/to/Android/sdk" > local.properties
./gradlew assembleRelease
```

- The APK is created at `app/build/outputs/apk/release/app-release.apk`
- It is signed with the debug key (this is for personal use; use your own key if you distribute it)
- Run the unit tests with `./gradlew testDebugUnitTest`. The two tests that use a real NAS are skipped unless the environment variables `FILER_SMB_HOST`, `FILER_SMB_SHARE`, `FILER_SMB_USER` and `FILER_SMB_PASS` are set
- The sample files used by some tests (`app/src/test/resources`) are not published because their origin cannot be confirmed. Tests that use them fail unless you put files of the same formats there

## Adding a translation

All on-screen text is in `app/src/main/res/values*/strings.xml`: `values/` is English (also used for languages without a translation) and `values-ja/` is Japanese.

1. Copy `app/src/main/res/values/strings.xml` to `app/src/main/res/values-xx/strings.xml` (`xx` is the language code, for example `fr` or `ko`) and translate it. Keep placeholders such as `%1$s`, and the `\n` and `\'` notations, as they are. For sentences that depend on a count (`plurals`), write the forms your language needs (one, few, many and so on).
2. Add `<locale android:name="xx" />` to `app/src/main/res/xml/locales_config.xml`.
3. Add the language to `supported` in `app/src/main/java/local/filemanager/app/Languages.kt`, with its name written in that language (for example `Lang("fr", "Français")`).
4. Run `./gradlew testDebugUnitTest`. `StringsParityTest` finds missing translations and mismatched placeholders.

Pull requests with translations are welcome.

## Bugs and requests

Please use [Issues](https://github.com/mihalinn/filer-android/issues). In the app, Settings → “Bugs, requests and logs” opens the report page with the version and device information filled in. You can write in English or Japanese.

- Bugs: [Report](https://github.com/mihalinn/filer-android/issues/new?template=bug_report.yml)
- Requests: [Send](https://github.com/mihalinn/filer-android/issues/new?template=feature_request.yml)
- Anyone can read what you write. Do not include passwords, NAS names or IP addresses, or personal file names

## License

This app is released under the **GNU GPL v3** ([LICENSE](LICENSE)), to match [NextLib](https://github.com/anilbeesetti/nextlib) (GPL-3.0), which is used for the video and audio extension.

- The bundled `libmedia3ext.so` is NextLib 1.7.1-0.9.0 `ffvideo.cpp` rebuilt with deinterlacing added. The modified source and build steps are in [native/](native/)
- FFmpeg 6.0 (LGPL v3 or later) and libvpx 1.13.0 (BSD 3-Clause) are used as they are in NextLib's AAR
- The other libraries and their licenses are listed in [THIRD_PARTY.md](THIRD_PARTY.md) (also in the app under Settings → Licenses). The full texts are in `app/src/main/assets/licenses/`

Copyright (C) 2026 mihalin
