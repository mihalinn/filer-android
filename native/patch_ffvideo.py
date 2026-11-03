import re
p='/root/nextlib/media3ext/src/main/cpp/ffvideo.cpp'
s=open(p,encoding='utf-8').read()

func = r'''
// インターレース(縞)の解除。インターレースの映像は、偶数行と奇数行が別々の時刻のため、動く部分に
// くし状のずれが出る。偶数行(上のフィールド)はそのまま残し、奇数行のうち「上下の行から見て
// 外れている(=くし状にずれている)」画素だけを、上下の行の平均に置き換える。動かない部分は元のまま。
static void deinterlacePlane(uint8_t *plane, int stride, int width, int height) {
    const int threshold = 8;
    for (int y = 1; y < height; y += 2) {
        uint8_t *cur = plane + (size_t) y * stride;
        const uint8_t *up = cur - stride;
        const uint8_t *down = (y + 1 < height) ? cur + stride : up;
        for (int x = 0; x < width; x++) {
            const int a = up[x];
            const int b = down[x];
            const int c = cur[x];
            const int lo = a < b ? a : b;
            const int hi = a < b ? b : a;
            if (c > hi + threshold || c < lo - threshold) {
                cur[x] = (uint8_t) ((a + b + 1) >> 1);
            }
        }
    }
}

'''
marker = 'extern "C"\nJNIEXPORT jlong JNICALL\nJava_io_github_anilbeesetti_nextlib_media3ext_ffdecoder_FfmpegVideoDecoder_ffmpegInitialize'
assert marker in s
s = s.replace(marker, func + marker, 1)

old = '    memcpy(data + yLength + uvLength, frame->data[2], uvLength);\n'
assert old in s
new = old + '''
    // インターレースの映像(放送録画など)は、縞を消す
    if (frame->interlaced_frame &&
        (frame->format == AV_PIX_FMT_YUV420P || frame->format == AV_PIX_FMT_YUVJ420P)) {
        auto *yPlane = reinterpret_cast<uint8_t *>(data);
        auto *uPlane = yPlane + yLength;
        auto *vPlane = uPlane + uvLength;
        const int uvWidth = (frame->width + 1) / 2;
        deinterlacePlane(yPlane, frame->linesize[0], frame->width, frame->height);
        deinterlacePlane(uPlane, frame->linesize[1], uvWidth, uvHeight);
        deinterlacePlane(vPlane, frame->linesize[1], uvWidth, uvHeight);
    }
'''
s = s.replace(old, new, 1)
open(p,'w',encoding='utf-8').write(s)
print("patched")