set -e
cd /root
python3 /root/patch.py
mkdir -p tools && cd tools
if [ ! -d android-ndk-r25c ]; then
  curl -sfL -o ndk.zip https://dl.google.com/android/repository/android-ndk-r25c-linux.zip
  unzip -q ndk.zip && rm ndk.zip
fi
if [ ! -d ffmpeg-6.0 ]; then
  curl -sfL -o ff.tgz https://ffmpeg.org/releases/ffmpeg-6.0.tar.gz
  tar xzf ff.tgz && rm ff.tgz
fi
cd ffmpeg-6.0
[ -f libavutil/avconfig.h ] || ./configure --disable-everything --disable-programs --disable-doc --disable-avdevice --disable-avformat --disable-avfilter --disable-network >/dev/null 2>&1
ls libavutil/avconfig.h config.h