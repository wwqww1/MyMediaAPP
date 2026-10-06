#!/bin/bash
# ============================================================================
#  FFmpeg 7.0 (arm64-v8a) + libmp3lame 交叉编译脚本
#  用于「我的多媒体」Android App
#
#  用法：改下面 4 个路径 → bash build_ffmpeg_arm64.sh
#
#  ⚠️ 许可证明细
#  本脚本保留 --enable-gpl，编出来的 FFmpeg 是【GPL 版】
#  （6 个 .so 都会自报 "GPL version 2 or later"）。
#  用了 GPL 版 FFmpeg，整个 App 就必须以 GPL 兼容协议发布。
#
#  想让 App 保持 MIT 且不触发 GPL 传染：删掉 --enable-gpl 这一行重编。
#  当前启用的编码器/解码器/滤镜全部在 LGPL 下可用，唯一的第三方库
#  libmp3lame 本身也是 LGPL，所以去掉 --enable-gpl 后功能不受影响。
#  （注意：LGPL 下 libmp3lame 若静态链接，还需提供可重链接手段，
#    最省事的做法是把它也编成 .so 动态链接。）
# ============================================================================
set -e

NDK=/opt/android-sdk/ndk/25.1.8937393    # ← 改成你的 NDK 路径
SRC=/tmp/ffmpeg-7.0                      # ← FFmpeg 7.0 源码目录
LAME=/tmp/lame-3.100                     # ← libmp3lame 3.100 源码目录
OUT="$(cd "$(dirname "$0")" && pwd)/output"
JOBS=$(nproc)

HOST_TAG=linux-x86_64
PREBUILT=$NDK/toolchains/llvm/prebuilt/$HOST_TAG
SYS=$PREBUILT/sysroot
export PATH=$PREBUILT/bin:$PATH

mkdir -p $OUT

build_arch() {
  local arch=$1 ffarch=$2 cpu=$3
  local PREFIX=$OUT/$arch
  local arch_dir=$PREFIX/ffmpeg
  mkdir -p $PREFIX/lame $arch_dir

  local cc=$PREBUILT/bin/aarch64-linux-android24-clang
  local cxx=$PREBUILT/bin/aarch64-linux-android24-clang++
  local ar=$PREBUILT/bin/aarch64-linux-android-ar
  local ranlib=$PREBUILT/bin/aarch64-linux-android-ranlib

  echo "=== [$arch] libmp3lame ==="
  cd $LAME
  make distclean > /dev/null 2>&1 || true
  CC=$cc AR="$ar" RANLIB=$ranlib \
  ./configure --host=aarch64-linux-android --prefix=$PREFIX/lame \
    CFLAGS="-O2 -fPIC" --disable-shared --enable-static \
    --disable-frontend --disable-decoder > /dev/null
  make -j$JOBS > /dev/null && make install > /dev/null

  echo "=== [$arch] FFmpeg ==="
  cd $SRC
  make distclean > /dev/null 2>&1 || true

  $SRC/configure \
    --prefix=$arch_dir \
    --cross-prefix=$PREBUILT/bin/aarch64-linux-android- \
    --sysroot=$SYS --target-os=android --arch=$ffarch --cpu=$cpu \
    --cc=$cc --cxx=$cxx \
    --extra-cflags="-O2 -fPIC -I$PREFIX/lame/include" \
    --extra-ldflags="-L$PREFIX/lame/lib -lm" \
    --enable-shared --disable-static \
    --enable-gpl --disable-nonfree \
    --disable-network \
    --enable-small --disable-debug --disable-doc --disable-programs \
    --enable-avcodec --enable-avformat --enable-avutil --disable-avdevice \
    --enable-avfilter --disable-postproc \
    --enable-libmp3lame \
    --enable-demuxer=mov,matroska,webm_dash_manifest,live_flv,flv,wav,mp3,ogg,aac,image2,flac,aiff,asf,ape,amr,ac3,mp2,au,caf,avi,mpegps,mpegts,rm,gif \
    --enable-muxer=mp4,matroska,webm,wav,mp3,ogg,gif,image2,null,flac,aiff,avi,mpegts,mpeg1system \
    --enable-encoder=aac,libmp3lame,wav,pcm_s16le,mjpeg,vorbis,flac,gif,png,mpeg4,alac,pcm_u8,pcm_s16be,pcm_s24le,pcm_s32le,pcm_f32le,pcm_alaw,pcm_mulaw,mpeg2video \
    --enable-decoder=aac,h264,hevc,vp8,vp9,mp3,vorbis,opus,pcm_s16le,mjpeg,flac,alac,wmav2,ape,amrnb,amrwb,ac3,mp2,pcm_u8,pcm_s16be,pcm_s24le,pcm_s32le,pcm_f32le,pcm_f64le,pcm_alaw,pcm_mulaw,pcm_s24be,pcm_s32be,mpeg4,mpeg2video,wmv2,wmv3,rv10,rv20,rv30,rv40,msmpeg4v3,h263,gif,cook \
    --enable-parser=aac,h264,hevc,vp8,vp9,opus,vorbis,mpegaudio,flac,ac3,mp2,mpeg4video,mpegvideo,rv40,h263 \
    --enable-bsf=aac_adtstoasc,h264_mp4toannexb,hevc_mp4toannexb \
    --enable-filter=atempo,abuffer,abuffersink,anull,aresample,volume,format \
    --disable-x86asm --disable-inline-asm \
    --enable-protocol=file

  make -j$JOBS
  make install
  echo "=== [$arch] DONE ==="
  ls $arch_dir/lib/
}

build_arch arm64 aarch64 cortex-a53
echo "===== ALL DONE ====="
