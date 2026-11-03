# ファイラー(Filer)

広告のない、Android 用のファイルマネージャーです。端末のファイルと NAS(SMB 共有)を、同じ操作で扱えます。

- 動作環境: Android 11 以上(minSdk 30)、arm64 の端末
- ライセンス: GPL-3.0(下の「ライセンス」を参照)
- 無保証です。自己責任で使ってください

## できること

- **ファイルの操作**: 一覧・検索・並べ替え・コピー・移動・名前の変更・削除(ごみ箱つき)・ZIP の圧縮と展開・容量の分析
- **NAS(SMB)**: 接続先を保存して、端末のフォルダと同じように操作できます。コピー・移動はアプリを閉じても続きます
- **中身を見る**: ファイルを開くと、その場でプレビューします
  - 文字・Markdown・CSV・JSON・XML・HTML・EPUB・マンガ(cbz など)
  - 画像(HEIC・SVG を含む)・PDF
  - 動画・音声・MIDI(ExoPlayer と FFmpeg)
  - Word・Excel・PowerPoint(旧形式を含む)・ODF・RTF
  - 図面(DXF・JW_CAD・draw.io)と 3D(STL・OBJ・PLY・OFF・3MF)
  - 書庫(zip・7z・tar 系・rar・lzh)・APK・.xapk などのまとめインストール
  - iPhone 系(plist・pkpass・iWork・ipa)・SQLite・フォント・連絡先(vCard)
- **見た目**: ホーム画面の並べ替え、ライト/ダーク配色

## ビルド

必要なもの: JDK 17、Android SDK(compileSdk 35)

```
echo "sdk.dir=/path/to/Android/sdk" > local.properties
./gradlew assembleRelease
```

- APK は `app/build/outputs/apk/release/app-release.apk` にできます
- 署名は debug 鍵です(自分で使うための設定です。配るときは自分の鍵に変えてください)
- 単体テストは `./gradlew testDebugUnitTest` で実行します。実際の NAS を使う 2 本は、環境変数 `FILER_SMB_HOST` `FILER_SMB_SHARE` `FILER_SMB_USER` `FILER_SMB_PASS` が無いと飛ばします
- 試験に使う見本ファイル(`app/src/test/resources`)は、出どころを確認できないため公開していません。これを使う試験は、手元に同じ形式のファイルを置かないと失敗します
- コードの地図は [CODEMAP.md](CODEMAP.md) にあります

## 不具合・要望

[Issues](https://github.com/mihalinn/filer-android/issues) で受け付けています。アプリの「設定」→「不具合・要望の報告」からも、バージョンや端末の情報を入れた状態で、報告のページを開けます。

- 不具合: [報告する](https://github.com/mihalinn/filer-android/issues/new?template=bug_report.yml)
- 要望: [送る](https://github.com/mihalinn/filer-android/issues/new?template=feature_request.yml)
- 内容は誰でも読めます。パスワード、NAS の名前や IP アドレス、個人のファイル名は書かないでください
## ライセンス

このアプリは **GNU GPL v3** で公開しています([LICENSE](LICENSE))。動画・音声の拡張に使っている [NextLib](https://github.com/anilbeesetti/nextlib)(GPL-3.0)に合わせたものです。

- 同梱の `libmedia3ext.so` は、NextLib 1.7.1-0.9.0 の `ffvideo.cpp` にインターレース解除を加えて作り直したものです。変更後のソースと作り方は [native/](native/) にあります
- FFmpeg 6.0(LGPL v3 以降)と libvpx 1.13.0(BSD 3-Clause)は、NextLib の AAR に入っているものをそのまま使っています
- そのほか、使っているライブラリとライセンスの一覧は [THIRD_PARTY.md](THIRD_PARTY.md) にあります(アプリの「設定」→「ライセンス」でも見られます)。全文は `app/src/main/assets/licenses/` にあります

Copyright (C) 2026 mihalin

---

## English (summary)

**Filer** is an ad-free file manager for Android (arm64, Android 11+). It treats local storage and SMB shares (NAS) the same way, and previews many formats in-app (text, images, PDF, audio/video, Office, CAD/3D, archives, Apple formats, SQLite, fonts…).

Build: JDK 17 and the Android SDK, then `./gradlew assembleRelease`. Licensed under GPL-3.0 (see `LICENSE` and `THIRD_PARTY.md`). No warranty.