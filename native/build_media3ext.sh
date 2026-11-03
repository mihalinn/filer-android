set -e
cd /root
mkdir -p orig out && cd orig
[ -f nl.aar ] || curl -sfL -o nl.aar https://repo1.maven.org/maven2/io/github/anilbeesetti/nextlib-media3ext/1.7.1-0.9.0/nextlib-media3ext-1.7.1-0.9.0.aar
unzip -q -o nl.aar 'jni/arm64-v8a/*'
cd /root
NDK=/root/tools/android-ndk-r25c
TC=$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin
echo "--- original NEEDED:"; $TC/llvm-readelf -d orig/jni/arm64-v8a/libmedia3ext.so | grep -E "NEEDED|SONAME"
echo "--- libavcodec soname:"; $TC/llvm-readelf -d orig/jni/arm64-v8a/libavcodec.so | grep -E "SONAME|NEEDED"
SRC=/root/nextlib/media3ext/src/main/cpp
$TC/aarch64-linux-android21-clang++ -std=c++17 -O2 -fPIC -shared -fvisibility=default -static-libstdc++ \
  -I/root/tools/ffmpeg-6.0 $SRC/ffmain.cpp $SRC/ffcommon.cpp $SRC/ffaudio.cpp $SRC/ffvideo.cpp \
  -o out/libmedia3ext.so -Wl,-z,max-page-size=16384 -Wl,--build-id=none \
  -Lorig/jni/arm64-v8a -l:libavutil.so -l:libavcodec.so -l:libswresample.so -l:libswscale.so -llog -landroid
$TC/llvm-strip --strip-unneeded out/libmedia3ext.so
echo "--- new NEEDED:"; $TC/llvm-readelf -d out/libmedia3ext.so | grep -E "NEEDED|SONAME"
ls -l out/libmedia3ext.so orig/jni/arm64-v8a/libmedia3ext.so
echo "--- JNI symbols new vs orig:"
$TC/llvm-nm -D --defined-only out/libmedia3ext.so | grep -c Java_
$TC/llvm-nm -D --defined-only orig/jni/arm64-v8a/libmedia3ext.so | grep -c Java_