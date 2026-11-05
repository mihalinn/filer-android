# 使っているもの(サードパーティ)

このアプリが使っているライブラリと、そのライセンスです。同じ内容は、アプリの「設定」→「ライセンス」にもあります。ライセンスの全文は `app/src/main/assets/licenses/` にあります。

| 名前 | ライセンス | 補足 |
|---|---|---|
| ファイラー(このアプリ) | GNU GPL v3 | Copyright (C) 2026 mihalin。NextLib(GPL v3)を使っているため、アプリ全体も GPL v3 で提供しています。無保証です |
| NextLib 1.7.1-0.9.0(動画・音声の拡張) | GNU GPL v3 | `libmedia3ext.so` は、インターレース解除を加えて作り直したものです。変更内容とソースは `native/` にあります。元: https://github.com/anilbeesetti/nextlib |
| FFmpeg 6.0(NextLib に同梱) | GNU LGPL v3 以降 | 動的ライブラリ(.so)として同梱しています。差し替えもできます。ソース: https://ffmpeg.org |
| libvpx 1.13.0(VP8・VP9 の読み取り用。FFmpeg に組み込まれています) | BSD 3-Clause | 著作権: The WebM Project authors |
| AndroidX Media3 1.7.1(ExoPlayer・Session) | Apache License 2.0 | |
| Jetpack Compose・Material 3・AndroidX | Apache License 2.0 | Activity、Core、Lifecycle、ExifInterface を含みます。画面のアイコンは Material Icons(Google)です |
| Kotlin 標準ライブラリ 1.9.22・kotlinx.coroutines 1.7.1 | Apache License 2.0 | JetBrains annotations(Apache 2.0)を含みます |
| Guava 33.3.1(Android 用) | Apache License 2.0 | Media3 が使っています。failureaccess 1.0.2・listenablefuture 1.0・Error Prone annotations 2.38.0(いずれも Apache 2.0)を含みます |
| smbj 0.13.0(NAS への接続) | Apache License 2.0 | 同じ作者の asn-one 0.6.0(Apache 2.0)も使っています |
| Mbassador 1.3.0 | MIT | smbj が使っています。作者: Benjamin Diedrichsen |
| Apache Commons Compress 1.27.1 | Apache License 2.0 | 7z・tar などを読むために使っています。Commons Codec 1.17.1・IO 2.16.1・Lang 3.16.0 も含みます。著作権表示(NOTICE)は `app/src/main/assets/licenses/commons-notice.txt` |
| PDFium(PDF の表示と文字の選択)・PdfiumAndroidKt 1.0.35 | Apache License 2.0 | PDF を絵にして、文字の位置を調べるために使っています。PDFium が使う FreeType・ICU・libjpeg-turbo・libpng・zlib・HarfBuzz などの著作権表示とライセンスは `app/src/main/assets/licenses/pdfium-notice.txt` |
| AndroidSVG 1.4 | Apache License 2.0 | |
| Bouncy Castle 1.79 | MIT | |
| SLF4J 2.0.17(API)・2.0.9(nop) | MIT | |
| XZ for Java 1.10 | 0BSD | ほぼパブリックドメイン |
| junrar 8.1.2(RAR の読み取り) | UnRAR ライセンス | RAR を読むためだけに使っています。RAR と互換の圧縮ソフトを作る目的には使えません |
| ezjww(JW_CAD の形式の参考) | MIT | `.jww` の構造は、公開されている解析(https://github.com/monozukuri-ai/ezjww)を参考にしました。読み込みの処理は自作です |

---

# Third-party components (English)

The libraries this app uses and their licenses. The same list is shown in the app under Settings → Licenses. The full license texts are in `app/src/main/assets/licenses/`.

| Name | License | Notes |
|---|---|---|
| Filer (this app) | GNU GPL v3 | Copyright (C) 2026 mihalin. Because it uses NextLib (GPL v3), the whole app is also provided under GPL v3. No warranty |
| NextLib 1.7.1-0.9.0 (video and audio extension) | GNU GPL v3 | `libmedia3ext.so` was rebuilt with deinterlacing added. The changes and the source are in `native/`. Original: https://github.com/anilbeesetti/nextlib |
| FFmpeg 6.0 (bundled with NextLib) | GNU LGPL v3 or later | Bundled as a dynamic library (.so), so it can be replaced. Source: https://ffmpeg.org |
| libvpx 1.13.0 (for reading VP8 and VP9; built into FFmpeg) | BSD 3-Clause | Copyright: The WebM Project authors |
| AndroidX Media3 1.7.1 (ExoPlayer and Session) | Apache License 2.0 | |
| Jetpack Compose, Material 3 and AndroidX | Apache License 2.0 | Includes Activity, Core, Lifecycle and ExifInterface. The icons on screen are Material Icons (Google) |
| Kotlin standard library 1.9.22 and kotlinx.coroutines 1.7.1 | Apache License 2.0 | Includes JetBrains annotations (Apache 2.0) |
| Guava 33.3.1 (for Android) | Apache License 2.0 | Used by Media3. Includes failureaccess 1.0.2, listenablefuture 1.0 and Error Prone annotations 2.38.0 (all Apache 2.0) |
| smbj 0.13.0 (connecting to a NAS) | Apache License 2.0 | Also uses asn-one 0.6.0 (Apache 2.0) by the same author |
| Mbassador 1.3.0 | MIT | Used by smbj. Author: Benjamin Diedrichsen |
| Apache Commons Compress 1.27.1 | Apache License 2.0 | Used to read 7z, tar and similar files. Also includes Commons Codec 1.17.1, IO 2.16.1 and Lang 3.16.0. The copyright notice (NOTICE) is in `app/src/main/assets/licenses/commons-notice.txt` |
| PDFium (PDF display and text selection) and PdfiumAndroidKt 1.0.35 | Apache License 2.0 | Used to render PDFs and find the position of text. The copyright notices and licenses of FreeType, ICU, libjpeg-turbo, libpng, zlib, HarfBuzz and others used by PDFium are in `app/src/main/assets/licenses/pdfium-notice.txt` |
| AndroidSVG 1.4 | Apache License 2.0 | |
| Bouncy Castle 1.79 | MIT | |
| SLF4J 2.0.17 (API) and 2.0.9 (nop) | MIT | |
| XZ for Java 1.10 | 0BSD | Almost public domain |
| junrar 8.1.2 (reading RAR) | UnRAR License | Used only to read RAR. It cannot be used to develop RAR-compatible compression software |
| ezjww (reference for the JW_CAD format) | MIT | The structure of `.jww` follows a published analysis (https://github.com/monozukuri-ai/ezjww). The loading code is original |
