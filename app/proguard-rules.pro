# SMB(smbj)と暗号ライブラリはリフレクションで中身を引くため、縮小の対象から外す
-keep class com.hierynomus.** { *; }
-keep class net.engio.mbassy.** { *; }
-keep class org.bouncycastle.** { *; }

# Android に無い Kerberos 関連などの参照は無視する
-dontwarn org.bouncycastle.**
-dontwarn javax.el.**
-dontwarn org.ietf.jgss.**
-dontwarn javax.security.auth.kerberos.**
-dontwarn sun.security.x509.**
-dontwarn org.slf4j.**
-dontwarn java.lang.management.**

# 書庫の読み取り(commons-compress / junrar)。使わない圧縮方式の部品(zstd・brotli など)が無くても警告にしない
-dontwarn org.apache.commons.compress.**
-dontwarn com.github.luben.**
-dontwarn org.brotli.**
-dontwarn com.github.junrar.**
-dontwarn org.osgi.**
-dontwarn org.objectweb.asm.**
-keep class org.apache.commons.compress.archivers.** { *; }
-keep class org.apache.commons.compress.compressors.** { *; }
-keep class com.github.junrar.** { *; }
-keep class com.caverock.androidsvg.** { *; }
-dontwarn com.caverock.androidsvg.**

# PDF の表示と文字の位置(PDFium)。ネイティブの部品から呼ばれるので、名前を変えない
-keep class io.legere.pdfiumandroid.** { *; }
-dontwarn io.legere.pdfiumandroid.suspend.**
