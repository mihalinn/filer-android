# ファイラー(local.filemanager) コードの地図

広告なしの自作ファイルマネージャー(Android / Kotlin / Jetpack Compose)。ここは「どこに何があるか」と「作業の決まり」をまとめたもの。

## 1. ビルドと試験

- 環境: Kotlin 1.9.22 / Compose BOM 2024.06 / AGP 8.2.2 / Gradle 8.5 / JDK 17(`C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot`)。minSdk 30、targetSdk 34。
- 配布のビルド(約 9 分。メモリが足りなくなるので `-Xmx2g`・workers 3 まで)。
  ```
  $env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot'
  .\gradlew.bat cleanTestDebugUnitTest testDebugUnitTest assembleRelease --no-daemon '-Pkotlin.compiler.execution.strategy=in-process' --max-workers=3
  ```
  PowerShell では `-P…` を必ず引用符で囲む。APK は `app\build\outputs\apk\release\app-release.apk`(R8 あり。署名は debug 鍵なので上書きインストールできる)。
- 試験だけ: `testDebugUnitTest`(1 本だけなら `--tests 'local.filemanager.NavTest'`)。環境変数が変わっても Gradle が UP-TO-DATE にするので、`cleanTestDebugUnitTest` を付ける。
- コンパイルだけ確かめる: `compileDebugKotlin compileDebugUnitTestKotlin`(約 5 分)。
- 実際の NAS を使う試験(`SmbSmokeTest` `SmbSpeedTest`)は、環境変数 `FILER_SMB_HOST/SHARE/USER/PASS` が無いと飛ばす。
- `versionCode` / `versionName`(`app/build.gradle`)は、**ビルドを始める前に**上げる。

## 2. 作業の決まり

- **画面に出す文字は場面で使い分ける**。ボタン・見出し・メニューは短い言葉(「コピー」「削除」)。お知らせ・エラー・説明・空の画面は、ていねいな言い方(「〜しました」「〜できませんでした」「〜してください」)。「〜である」や体言止めの文は使わない。1 文だけの短い文は、最後の「。」を付けない。
- **画面に出す文字は、ソースに直接書かず `res/values/strings.xml` に置く**(言語を増やせるように)。取り出すのは `Txt` の 1 か所だけ:
  `Txt(R.string.名前)` / `Txt(R.string.名前, 値…)`(`%1$s` に入る) / `Txt.n(R.plurals.名前, 件数, 値…)`(件数で言い方が変わる文。件数は `%1$d`、続く値は `%2$s` から)。Compose の中でも、読み取り処理の失敗メッセージ(`OpException(Txt(…))`)でも、同じ書き方でよい。
  - 文を部品でつなぎ合わせない(言語によって語順が変わる)。「〜を移動しました」と「〜をコピーしました」は別の文にする。
  - 名前は、場所が分かるつけ方にする(`op_moved`・`detail_count` など)。同じ日本語でも、場面が違えば別の名前にする(訳し分けるため)。
  - 実行時に言語が変わっても追従するよう、`val x = Txt(…)` を object やトップレベルに置かない(関数か `get()` にする)。列挙型の名前は `@StringRes val labelRes` を持たせ、`val label get() = Txt(labelRes)` にする。
  - 日本語のまま残すもの: 動作の記録・異常終了の記録の中身(開発者向けの診断)、GitHub の報告テンプレート、ファイルの中身との照合に使う語(Word の「見出し」、フォント名の判定など)。
  - PC の単体試験では Context が無いので、`TestStrings`(試験側)が `values-ja/strings.xml`(日本語)を読んで同じ文を返す。試験が日本語の文面を確かめているのは、このため。
  - **言語の置き場所**: `res/values/strings.xml` = 英語(どの言語にも当てはまらない端末が使う予備)、`res/values-ja/strings.xml` = 日本語。新しい文字を足すときは、**両方**に同じ名前で足す。
  - **言語を足すとき**: ① `res/values-xx/strings.xml` を作る(`values/` をコピーして訳す。`%1$s` などの記号と、`\n`・`\'` の書き方はそのまま。複数形 `plurals` は、その言語に必要な区分(one・few・many…)を書く) ② `res/xml/locales_config.xml` に `<locale android:name="xx" />` を足し、`app/Languages.kt` の `supported` にも足す(設定の「言語」の選択肢になる。名前はその言語自身の書き方) ③ `StringsParityTest` が、足りない名前・記号の食い違い・フォルダと一覧の食い違いを見つける。
  - 言語は、設定の「表示」の「言語」(端末に合わせる / 日本語 / English)で、アプリの中で選ぶ。`Languages` が選びを SharedPreferences に残し、`MainActivity.attachBaseContext`(画面)と `Txt.useContext`(通知など)に反映する。選んだら `Activity.recreate()` で、すぐ切り替わる。「端末に合わせる」のときは、端末(Android 13 以上は端末の「アプリの言語」)に従う。
- ソースは UTF-8・BOM なし・LF。PowerShell 5.1 の `Get-Content -Raw` / `Set-Content` は日本語を壊すので使わない(編集は Edit / Write、まとめて変えるときは `[IO.File]::ReadAllText/WriteAllText` に `UTF8Encoding($false)`)。`proguard-rules.pro` と `build.gradle` も同じ。
- クラス名は、大文字小文字だけが違うものを作らない(Windows で `.class` が衝突する)。
- Android に依存しない読み取り処理(形式の解析)は、PC の単体試験で確かめる。`android.jar` だけの JVM 試験では `java.awt` と `org.json` が使えない(`Json.kt` は自前)。
- 見本ファイルは、その形式の「本物」を使って試験する(手作りだけでは見落とす)。

## 3. フォルダの地図

すべて同じパッケージ `local.filemanager`(フォルダは探しやすさのための分け方。`import` は要らない)。試験は `app/src/test/java/local/filemanager/` に同じフォルダ構成で置く。

| フォルダ | 役割 | おもなファイル |
|---|---|---|
| `app/` | 入口と全体の状態 | `FilerApp`(起動時に `Txt` を使えるようにする)、`Txt`(画面に出す文字の窓口。文字は `res/values*/strings.xml`)、`MainActivity`(起動・他アプリへの受け渡し)、`FmViewModel`(画面の状態と操作。節ごとにまとめてある)、`AppModels`(`Screen` `Place` `Clip` などの型)、`Screens`(画面の組み立て)、`Nav`(戻る・上への判断)、`Theme`(色。テーマカラー `ThemeColor` は藍・ティール・ワイン赤。色を足すときは `palOf` と `ThemeColorTest`)、`Incoming`(共有で受け取るもの)、`CrashLog`(異常終了の記録)、`PerfLog`(動作の記録。開発者向けオプションでオンにしたときだけ取る)、`DevTap`(バージョンを続けて押して開発者向けオプションを出す数え方)、`Report`(不具合・要望の報告の URL を作る。画面は `browser/ReportUi`) |
| `browser/` | 一覧とその周りの画面 | `ListUi` `Home` `HomeLayout` `Drawer` `Dialogs` `FileIcon` `SettingsUi`(開発者向けオプションの画面を含む) `AboutUi`(ライセンス) `AnalyzeUi` `ProgressUi` `PerfLogUi`(動作の記録の窓) |
| `storage/` | 場所(端末・NAS)の読み書き | `Fs`(抽象と端末内)、`SmbFs`(NAS。smbj)、`Remotes`(接続先の保存。暗号化)、`Trash`、`FileOps`(端末内の操作・拡張子の分類)、`Media`(MediaStore の分類)、`Analyzer`(容量の分析)、`ReadAhead` `WriteBehind` `ResumeStreams`(NAS を速く・切れにくくする)、`ReadProgress`、`BackgroundPool`(サムネイルなどを作る低優先度のスレッド) |
| `transfer/` | コピー・移動・圧縮の実行 | `Transfer`(Fs 間の操作)、`Progress`、`Ops`(実行中の操作の状態。アプリ全体で 1 つ)、`TransferService`(アプリを閉じても続けるサービス。コピー・移動と、`BgTasks` の通知を出す)、`BgTasks`(画面を消しても続けるダウンロード。アプリの更新とプレビューの取り込み。通知・中止つき)、`Conflict`(同名の扱い) |
| `viewer/` | プレビューの入口と文字・画像・PDF | `Viewer`(入口。種類で振り分け)、`LoadingPanel`(NAS から取り込む間の読み込み画面)、`ViewerMenu`、`Highlight`(`PreviewKind`)、`PreviewUi`(文書・Markdown・表・配色 `Pv`)、`PdfUi`(PDF。表示は PDFium) `PdfiumDoc`(PDFium の窓口) `PdfLayout`(文字の位置の計算) `PdfSelect`(文字の選択) `UnknownUi`(種類の分からないファイル) `ImageUi`、`TextEdit` `EditorUi`(文字ファイルの編集)、`CsvSheet`(CSV の表の編集の中身。元に戻す・行列の挿入削除。純 Kotlin) `CsvEditorUi`(表で編集する画面。保存は `EditorUi` の `writeBack` を使う)、`Sniff`(種類の推測)、`TextDecode`、`Csv` `Pretty` `Markdown` `Json` `Svg` `ImageDecode` `PhotoInfo` `ExifFormat` `Vcard` `ContactUi` |
| `media/` | 動画・音声・MIDI | `Player` `PlayerSetup`(動画・音声。Media3/ExoPlayer + FFmpeg)、`PlaybackService`(再生エンジンの持ち主 `PlaybackHolder` と、画面を消しても再生を続けて通知を出すサービス。画面を閉じるときは `PlaybackHolder.stop`)、`PlayerChrome`(上のバー・再生ボタン・シークバー。音声と MIDI で共通)、`AudioUi`、`MediaSupport`(NAS 上のファイルを ExoPlayer に読ませる)、`Shims`(読めない形を読める形に見せかける。BDAV・AIFF)、`Caf`、`MusicInfo`、`MidiPlay`(MIDI は端末の MediaPlayer で鳴らすだけ。画面は音声と同じ部品)、`Thumbs` |
| `office/` | Office・ODF・RTF | `Office` `OfficeUi` `OfficeTheme`、`Docx` `Doc`(旧 Word)、`Xlsx` `Xls` `SheetUi`(Excel 風の表。`GridModel` で CSV と共通)、`SheetGridFast`(`FastGrid`。CSV・Excel・SQLite の結果の表の描き方。1 行を 1 つの部品にして、セルの文字・色・線を直接描く。横スクロールは描き直しだけ。結合・折り返しの行にも対応)、`SheetZoom`(`SheetGrid` の入り口。つまんで拡大・縮小、縦横いっしょの移動と流れ、拡大のあとのスクロール位置合わせ)、`SlideDeck` `SlideStyle` `SlideGeom` `SlideUi`(PowerPoint を絵にする)、`Ppt` `PptDeck`(旧 PowerPoint)、`Odf` `Odt` `Ods` `Odp`、`Rtf`、`Ole2`(旧形式の入れ物) |
| `archive/` | 書庫・APK | `Archives`(zip/7z/rar/tar 系の窓口)、`ArchiveUi`(ZIP の画面)、`ApkUi` `BundleUi`、`Lzh`、`ZipReader` `ZipTree`、`ApkFormat` `ApkBundle` `BundleInstall`(.xapk などのまとめインストール) |
| `cad/` | 図面・3D | `Cad`(2D の共通モデル)、`CadUi`、`Dxf` `Jww` `Jwc`(JW_CAD)、`Drawio` `DiagramUi`、`Stl` `MeshFormats`(OBJ/PLY/OFF/3MF)、`ModelUi`(OpenGL) |
| `web/` | HTML・電子書籍 | `HtmlUi` `HtmlRules`(外部通信を止める規則)、`Mhtml` `MhtmlUi`、`Epub` `EpubUi`、`Comic` `ComicUi` |
| `apple/` | iPhone 系 | `Plist`、`Pkpass` `PkpassUi`、`Iwork`(.pages/.numbers/.key)、`AppleUi`(.ipa も) |
| `data/` | データベース・フォント | `SqliteUi` `SqlFormat`、`FontInfo` `FontUi` |
| `update/` | アプリの更新(GitHub のリリース) | `UpdateLogic`(応答の読み取り・バージョンの比べ方。Android に依存しない)、`UpdateNet`(確認とダウンロード)、`UpdateManager`(状態・検証・インストールの画面への受け渡し)、`UpdateUi`(窓と設定) |

## 4. 流れ

- 一覧のファイルを押す → `FmViewModel.openOrView` → `ViewerRequest` → `viewer/Viewer.kt` の `ViewerScreen` が `PreviewKind.of(名前)` で種類を決めて、各フォルダの画面(`xxxPane`)を出す。動画・音声は `media/Player.kt` の `PlayerScreen`、MIDI は `media/MidiPlay.kt`。
- NAS のファイルは `Fs.open` / `openRandom` で読む。ビューアに渡すときは `TrackedFs` で包んで、読んだ量を数える。
- コピー・移動などは `FmViewModel.runOp` → `Ops` → `Transfer`。アプリを閉じても `TransferService` が続ける。
- 動画・音声の再生エンジン(ExoPlayer)は `PlaybackHolder` が持つ。画面(`Player.kt`)は `acquire` でつなぎ、閉じるときだけ `stop` する。見えている間は `attachUi`、消えたら `detachUi`。MIDI は対象外。
- NAS のファイルを端末に取り込んでから開く形式(PDF・APK・SQLite・フォント・RAR)は、`BgTasks.run` で取り込む。大きいものは `TransferService` が通知を出し、画面を消しても続く。アプリの更新のダウンロードも同じ(`UpdateManager`)。
- 形式の読み取り(`Docx` `Dxf` など)は、Android に依存しない純 Kotlin。画面側(`xxxUi.kt`)が結果を描く。
- 新しい形式を足すとき: ① `viewer/Highlight.kt` の `PreviewKind` に種類と拡張子を足す ② 読み取りを書いて単体試験 ③ `xxxUi.kt` に画面 ④ `viewer/Viewer.kt` の振り分けと `ViewerMenu` に足す ⑤ 出どころ・ライセンスがあれば `browser/AboutUi.kt` の `Credits` と `assets/licenses/` に足す(`CreditsTest` が食い違いを見張る)。

## 5. 別に置いてあるもの

- `native/`: 同梱の FFmpeg 拡張(`libmedia3ext.so`。インターレース解除を足して作り直したもの)のソースと作り方(`native/README.txt`)。
- `app/src/main/assets/licenses/`: ライセンス全文。アプリ全体は GPL-3.0(NextLib が GPL-3.0 のため)。
- `app/src/test/resources/`: 試験に使う本物の旧 Office・iPhone 系などの見本。出どころを確認できないため、公開リポジトリには含めない(これを使う試験は、手元にファイルが無いと失敗する)。
- 旧 MIDI 内蔵音源(ピアノロール・パート・巨大 MIDI 対応)は v0.37.0 で外した。
