# 使っているもの(サードパーティ)

このアプリが使っているライブラリと、そのライセンスです。同じ内容は、アプリの「設定」→「ライセンス」にもあります。ライセンスの全文は `app/src/main/assets/licenses/` にあります。

| 名前 | ライセンス | 補足 |
|---|---|---|
| ファイラー(このアプリ) | GNU GPL v3 | Copyright (C) 2026 mihalin。NextLib(GPL v3)を使っているため、アプリ全体も GPL v3 で提供しています。無保証です |
| NextLib 1.7.1-0.9.0(動画・音声の拡張) | GNU GPL v3 | `libmedia3ext.so` は、インターレース解除を加えて作り直したものです。変更内容とソースは `native/` にあります。元: https://github.com/anilbeesetti/nextlib |
| FFmpeg 6.0(NextLib に同梱) | GNU LGPL v3 以降 | 動的ライブラリ(.so)として同梱しています。差し替えもできます。ソース: https://ffmpeg.org |
| libvpx 1.13.0(VP8・VP9 の読み取り用。FFmpeg に組み込まれています) | BSD 3-Clause | 著作権: The WebM Project authors |
| AndroidX Media3 1.7.1(ExoPlayer) | Apache License 2.0 | |
| Jetpack Compose・Material 3・AndroidX | Apache License 2.0 | Activity、Core、Lifecycle、ExifInterface を含みます。画面のアイコンは Material Icons(Google)です |
| Kotlin 標準ライブラリ 1.9.22・kotlinx.coroutines 1.7.1 | Apache License 2.0 | JetBrains annotations(Apache 2.0)を含みます |
| Guava 33.3.1(Android 用) | Apache License 2.0 | Media3 が使っています。failureaccess 1.0.2・listenablefuture 1.0・Error Prone annotations 2.38.0(いずれも Apache 2.0)を含みます |
| smbj 0.13.0(NAS への接続) | Apache License 2.0 | 同じ作者の asn-one 0.6.0(Apache 2.0)も使っています |
| Mbassador 1.3.0 | MIT | smbj が使っています。作者: Benjamin Diedrichsen |
| Apache Commons Compress 1.27.1 | Apache License 2.0 | 7z・tar などを読むために使っています。Commons Codec 1.17.1・IO 2.16.1・Lang 3.16.0 も含みます。著作権表示(NOTICE)は `app/src/main/assets/licenses/commons-notice.txt` |
| AndroidSVG 1.4 | Apache License 2.0 | |
| Bouncy Castle 1.79 | MIT | |
| SLF4J 2.0.17(API)・2.0.9(nop) | MIT | |
| XZ for Java 1.10 | 0BSD | ほぼパブリックドメイン |
| junrar 8.1.2(RAR の読み取り) | UnRAR ライセンス | RAR を読むためだけに使っています。RAR と互換の圧縮ソフトを作る目的には使えません |
| ezjww(JW_CAD の形式の参考) | MIT | `.jww` の構造は、公開されている解析(https://github.com/monozukuri-ai/ezjww)を参考にしました。読み込みの処理は自作です |