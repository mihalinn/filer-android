libmedia3ext.so(app/src/main/jniLibs/arm64-v8a)は、NextLib 1.7.1-0.9.0 の ffvideo.cpp に「インターレース解除」を足して作り直したもの。
  - 元: https://github.com/anilbeesetti/nextlib (タグ 1.7.1-0.9.0)。FFmpeg 6.0 の .so は Maven の AAR にあるものをそのまま使う。
  - 変更: ffvideo.cpp の ffmpegReceiveFrame の最後に、インターレースのフレームだけ縞を消す処理(deinterlacePlane)。patch_ffvideo.py がその差分。
  - 作り方: Linux(Debian)で prep_media3ext.sh → build_media3ext.sh(NDK r25c、FFmpeg 6.0 のヘッダー)。出来た libmedia3ext.so を jniLibs に置く。
  - ffvideo.cpp は、パッチ適用後の全文。

【GPL-3.0 の対応ソース】
  - nextlib/ は、元の NextLib 1.7.1-0.9.0(GPL-3.0、タグ 1.7.1-0.9.0)の native ソース一式の写し(ffmain.cpp・ffcommon.cpp/.h・ffaudio.cpp・CMakeLists.txt・LICENSE)。変更していない。
  - nextlib/ffvideo.original.cpp は元の ffvideo.cpp。native/ffvideo.cpp が、インターレース解除を足した変更後の全文(差分は patch_ffvideo.py)。
  - 作り直した libmedia3ext.so は、nextlib/ の 4 つの .cpp(ffvideo.cpp だけ native/ のもの)を build_media3ext.sh でコンパイルしたもの。
  - FFmpeg 6.0 の .so(libavcodec・libavutil・libswresample・libswscale)は NextLib の AAR にあるものをそのまま使っている(LGPL v3 以降。libvpx 1.13.0 を静的に含む)。ソースは ffmpeg.org の 6.0 と libvpx 1.13.0。
