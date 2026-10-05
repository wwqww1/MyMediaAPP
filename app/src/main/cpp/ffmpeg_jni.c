// ffmpeg_jni.c - JNI bridge for FFmpeg 7.0 (v3.2)
// Audio: AAC/MP3/OGG/WAV/FLAC conversion (trim/speed/volume)
// Video: H264/H265/MPEG4/GIF conversion (trim/speed/crf/bitrate/resize)
// Concat: multi-input video merge; Snapshot: frame extraction
// Task control: per-task pause/cancel via handle
#include <jni.h>
#include <string.h>
#include <stdlib.h>
#include <stdarg.h>
#include <stdio.h>
#include <time.h>
#include <math.h>
#include <unistd.h>
#include <pthread.h>
#include <android/log.h>

#define TAG "FFmpegJNI"

/* ===== 日志：logcat + 文件双写（文件用于无 adb 环境排查） ===== */
static char g_log_path[1024] = {0};
static pthread_mutex_t g_log_mutex = PTHREAD_MUTEX_INITIALIZER;

static void log_write(int prio, const char *fmt, ...) {
    va_list args, args2;
    va_start(args, fmt);
    va_copy(args2, args);
    __android_log_vprint(prio, TAG, fmt, args);
    if (g_log_path[0]) {
        /* 并发任务会同时写日志，必须加锁防止 fopen/fclose 竞争崩溃 */
        pthread_mutex_lock(&g_log_mutex);
        FILE *f = fopen(g_log_path, "a");
        if (f) {
            time_t t = time(NULL);
            struct tm *tm_info = localtime(&t);
            char buf[2048];
            vsnprintf(buf, sizeof(buf), fmt, args2);
            if (tm_info)
                fprintf(f, "[%02d:%02d:%02d] %s\n", tm_info->tm_hour, tm_info->tm_min, tm_info->tm_sec, buf);
            else
                fprintf(f, "[?] %s\n", buf);
            fclose(f);
        }
        pthread_mutex_unlock(&g_log_mutex);
    }
    va_end(args2);
    va_end(args);
}

#define LOGI(...) log_write(ANDROID_LOG_INFO, __VA_ARGS__)
#define LOGE(...) log_write(ANDROID_LOG_ERROR, __VA_ARGS__)

#define TASK_CANCELLED (-1001)
#define MAX_TASKS 128

#include <libavformat/avformat.h>
#include <libavcodec/avcodec.h>
#include <libavcodec/bsf.h>
#include <libavutil/avutil.h>
#include <libavutil/opt.h>
#include <libavutil/imgutils.h>
#include <libavutil/display.h>
#include <libavutil/fifo.h>
#include <libavutil/audio_fifo.h>
#include <libswresample/swresample.h>
#include <libswscale/swscale.h>

/* ===== Task control (pause/cancel) ===== */
static volatile int g_cancel[MAX_TASKS];
static volatile int g_pause[MAX_TASKS];

/* ===== JNI progress callback ===== */
static JavaVM *g_vm = NULL;

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    g_vm = vm;
    return JNI_VERSION_1_6;
}

/* 回调 Kotlin: TaskManager.nativeProgress(handle, percent) */
static void report_progress(int handle, int percent) {
    if (handle < 0 || handle >= MAX_TASKS || !g_vm) return;
    JNIEnv *env = NULL;
    int attached = 0;
    if ((*g_vm)->GetEnv(g_vm, (void **)&env, JNI_VERSION_1_6) != JNI_OK) {
        if ((*g_vm)->AttachCurrentThread(g_vm, (void **)&env, NULL) != JNI_OK) return;
        attached = 1;
    }
    static jclass cls = NULL;
    static jmethodID mid = NULL;
    if (cls == NULL) {
        jclass tmp = (*env)->FindClass(env, "com/mymedia/app/utils/TaskManager");
        if (tmp) {
            cls = (jclass)(*env)->NewGlobalRef(env, tmp);
            mid = (*env)->GetStaticMethodID(env, cls, "nativeProgress", "(II)V");
        }
    }
    if (cls && mid) (*env)->CallStaticVoidMethod(env, cls, mid, handle, percent);
    if (attached) (*g_vm)->DetachCurrentThread(g_vm);
}

/* 0=继续, TASK_CANCELLED=取消；暂停时阻塞等待恢复 */
static int task_check(int handle) {
    if (handle >= 0 && handle < MAX_TASKS) {
        if (g_cancel[handle]) return TASK_CANCELLED;
        while (g_pause[handle]) {
            if (g_cancel[handle]) return TASK_CANCELLED;
            usleep(100000);
        }
    }
    return 0;
}

static void task_reset(int handle) {
    if (handle >= 0 && handle < MAX_TASKS) { g_cancel[handle] = 0; g_pause[handle] = 0; }
}

/* =========================================================
 * 自定义 avio：绕过被禁用的 file 协议（修复 "Protocol not found"）
 * 根因：build_ffmpeg.sh 里 --enable-protocol=file 写在 --disable-everything 之前，
 *       configure 的 --disable-everything 会 unset 所有组件列表（含 protocols），
 *       且之后没有重新 enable 任何协议 → CONFIG_FILE_PROTOCOL=0，
 *       avformat_open_input 打开任何本地文件都返回 AVERROR_PROTOCOL_NOT_FOUND。
 * 解法：avio_alloc_context + 自定义 read/seek 回调直接读文件，
 *       avformat_open_input 的 filename 传 NULL，完全绕过协议表。
 * ========================================================= */
typedef struct {
    FILE *fp;
} AvioFileCtx;

static int avio_file_read(void *opaque, uint8_t *buf, int buf_size) {
    AvioFileCtx *fc = (AvioFileCtx *)opaque;
    size_t n = fread(buf, 1, buf_size, fc->fp);
    if (n == 0) return AVERROR_EOF;
    return (int)n;
}

static int64_t avio_file_seek(void *opaque, int64_t offset, int whence) {
    AvioFileCtx *fc = (AvioFileCtx *)opaque;
    if (whence == AVSEEK_SIZE) {
        long cur = ftell(fc->fp);
        fseek(fc->fp, 0, SEEK_END);
        long size = ftell(fc->fp);
        fseek(fc->fp, cur, SEEK_SET);
        return size;
    }
    if (fseek(fc->fp, (long)offset, whence) < 0) return -1;
    return ftell(fc->fp);
}

/* 打开输入文件（替代 avformat_open_input），成功写 *pfmt，失败保持 *pfmt 不变 */
static int open_input_avio(AVFormatContext **pfmt, const char *path) {
    FILE *fp = fopen(path, "rb");
    if (!fp) {
        LOGE("Cannot open file: %s", path);
        return AVERROR(ENOENT);
    }
    AvioFileCtx *fc = av_malloc(sizeof(*fc));
    unsigned char *buf = av_malloc(1 << 16);
    if (!fc || !buf) {
        LOGE("OOM while opening %s", path);
        if (fc) av_free(fc);
        if (buf) av_free(buf);
        fclose(fp);
        return AVERROR(ENOMEM);
    }
    fc->fp = fp;
    AVIOContext *pb = avio_alloc_context(buf, 1 << 16, 0, fc, avio_file_read, NULL, avio_file_seek);
    if (!pb) {
        av_free(fc);
        av_free(buf);
        fclose(fp);
        return AVERROR(ENOMEM);
    }
    AVFormatContext *fmt = avformat_alloc_context();
    if (!fmt) {
        av_free(fc);
        av_free(buf);
        fclose(fp);
        avio_context_free(&pb);
        return AVERROR(ENOMEM);
    }
    fmt->pb = pb;
    fmt->flags |= AVFMT_FLAG_CUSTOM_IO;
    int ret = avformat_open_input(&fmt, NULL, NULL, NULL);
    if (ret < 0) {
        LOGE("Cannot open input: %s", av_err2str(ret));
        /* avformat_open_input 失败：s 已释放，CUSTOM_IO 的 pb 仍存活，须手动回收。
         * 注意：probe 成功后 ffio_rewind_with_probe_data 会把 pb->buffer 替换成探测
         * buffer（旧 buffer 已被它释放）——所以必须读 pb 当前的 buffer，
         * 绝不能使用进入 avformat_open_input 之前保存的指针（会 double free）。 */
        AvioFileCtx *f = pb ? (AvioFileCtx *)pb->opaque : NULL;
        unsigned char *cur_buf = pb ? pb->buffer : NULL;
        avio_context_free(&pb);
        if (f) { if (f->fp) fclose(f->fp); av_free(f); }
        av_freep(&cur_buf);
        return ret;
    }
    *pfmt = fmt;
    LOGI("open ok: %s", path);
    return 0;
}

/* 关闭输入：释放 CUSTOM_IO 的 pb + buffer + FileCtx。
 * 注意：绝不能用 avio_closep/avio_close —— avio_close 会 av_freep(s->buffer) 并把
 * s->opaque 当 URLContext 调 ffurl_close（avio_alloc_context 的自定义 IO 会 double-free
 * + 野指针 SIGSEGV）。正确做法是 avio_context_free 只释放结构，buffer/opaque 手动回收。 */
static void close_input_avio(AVFormatContext **pfmt) {
    if (!pfmt || !*pfmt) return;
    AVFormatContext *fmt = *pfmt;
    AVIOContext *pb = fmt->pb;
    fmt->pb = NULL;  /* 防 avformat_free_context 再次释放 */
    if (pb) {
        AvioFileCtx *f = (AvioFileCtx *)pb->opaque;
        unsigned char *bf = pb->buffer;
        avio_context_free(&pb);
        if (f) { if (f->fp) fclose(f->fp); av_free(f); }
        av_freep(&bf);
    }
    avformat_free_context(fmt);
    *pfmt = NULL;
}

/* =========================================================
 * 自定义 avio（输出）：同样绕过被禁的 file 协议。
 * 根因：库内 CONFIG_FILE_PROTOCOL=0，avio_open 打开输出文件也报
 * "Protocol not found"（之前所有 FFmpeg 功能失败的完整真相）。
 * ========================================================= */
typedef struct {
    FILE *fp;
} AvioOutCtx;

static int avio_file_write(void *opaque, const uint8_t *buf, int buf_size) {
    AvioOutCtx *oc = (AvioOutCtx *)opaque;
    size_t n = fwrite(buf, 1, buf_size, oc->fp);
    return (int)n;
}

static int64_t avio_out_seek(void *opaque, int64_t offset, int whence) {
    AvioOutCtx *oc = (AvioOutCtx *)opaque;
    if (whence == AVSEEK_SIZE) {
        long cur = ftell(oc->fp);
        fseek(oc->fp, 0, SEEK_END);
        long size = ftell(oc->fp);
        fseek(oc->fp, cur, SEEK_SET);
        return size;
    }
    if (fseek(oc->fp, (long)offset, whence) < 0) return -1;
    return ftell(oc->fp);
}

/* 打开输出文件，返回自定义 pb；失败返回 NULL（错误已记日志） */
static AVIOContext *open_output_avio_pb(const char *path) {
    FILE *fp = fopen(path, "wb");
    if (!fp) {
        LOGE("Cannot create output: %s", path);
        return NULL;
    }
    AvioOutCtx *oc = av_malloc(sizeof(*oc));
    unsigned char *buf = av_malloc(1 << 16);
    if (!oc || !buf) {
        LOGE("OOM creating output %s", path);
        if (oc) av_free(oc);
        if (buf) av_free(buf);
        fclose(fp);
        return NULL;
    }
    oc->fp = fp;
    AVIOContext *pb = avio_alloc_context(buf, 1 << 16, 1, oc, NULL, avio_file_write, avio_out_seek);
    if (!pb) {
        av_free(oc);
        av_free(buf);
        fclose(fp);
        return NULL;
    }
    return pb;
}

/* 关闭输出 pb（不能 avio_close：opaque 不是 URLContext；先 flush 写缓冲） */
static void close_output_avio_pb(AVIOContext **ppb) {
    if (!ppb || !*ppb) return;
    AVIOContext *pb = *ppb;
    avio_flush(pb);
    AvioOutCtx *oc = (AvioOutCtx *)pb->opaque;
    unsigned char *bf = pb->buffer;
    avio_context_free(&pb);
    if (oc) { if (oc->fp) fclose(oc->fp); av_free(oc); }
    av_freep(&bf);
    *ppb = NULL;
}

/* 关闭输出容器（fmt 级：先关 pb 再释放 fmt） */
static void close_output_avio(AVFormatContext **pfmt) {
    if (!pfmt || !*pfmt) return;
    AVFormatContext *fmt = *pfmt;
    AVIOContext *pb = fmt->pb;
    fmt->pb = NULL;
    if (pb) {
        avio_flush(pb);
        AvioOutCtx *oc = (AvioOutCtx *)pb->opaque;
        unsigned char *bf = pb->buffer;
        avio_context_free(&pb);
        if (oc) { if (oc->fp) fclose(oc->fp); av_free(oc); }
        av_freep(&bf);
    }
    avformat_free_context(fmt);
    *pfmt = NULL;
}

/* ===== KV options ===== */
typedef struct {
    int64_t ss_us, to_us;           /* 单区间（兼容） */
    int64_t ss_list[8], to_list[8]; /* 多裁剪区间 */
    int64_t win_offsets[8];         /* 区间输出时间偏移（视频 pts 重映射） */
    int n_windows;
    double speed, volume;
    int crf;
    int64_t bitrate;
    int maxw, maxh;
    int gif, fps;
    int audio_mode;   /* 0=流复制（默认），1=重编码 aac */
} Opts;

static void opts_init(Opts *o) {
    o->ss_us = 0; o->to_us = 0;
    o->n_windows = 0;
    for (int i = 0; i < 8; i++) { o->ss_list[i] = 0; o->to_list[i] = 0; o->win_offsets[i] = 0; }
    o->speed = 1.0; o->volume = 1.0;
    o->crf = 0; o->bitrate = 0;
    o->maxw = 0; o->maxh = 0;
    o->gif = 0; o->fps = 0;
    o->audio_mode = 0;
}

static void opts_parse(Opts *o, const char *kv) {
    opts_init(o);
    if (!kv || !*kv) return;
    char *buf = strdup(kv);
    char *save = NULL;
    for (char *tok = strtok_r(buf, ";", &save); tok; tok = strtok_r(NULL, ";", &save)) {
        char *eq = strchr(tok, '=');
        if (!eq) continue;
        *eq = 0;
        const char *k = tok, *v = eq + 1;
        if (!strcmp(k, "ss")) {
            /* 多区间：重复 ss=/to= 按顺序配对 */
            if (o->n_windows < 8) { o->ss_list[o->n_windows] = atoll(v); o->to_list[o->n_windows] = 0; o->n_windows++; }
        }
        else if (!strcmp(k, "to")) {
            if (o->n_windows > 0) o->to_list[o->n_windows - 1] = atoll(v);
            else { o->ss_list[0] = 0; o->to_list[0] = atoll(v); o->n_windows = 1; }
        }
        else if (!strcmp(k, "speed"))   o->speed = atof(v);
        else if (!strcmp(k, "volume"))  o->volume = atof(v);
        else if (!strcmp(k, "crf"))     o->crf = atoi(v);
        else if (!strcmp(k, "bitrate")) o->bitrate = atoll(v);
        else if (!strcmp(k, "maxw"))    o->maxw = atoi(v);
        else if (!strcmp(k, "maxh"))    o->maxh = atoi(v);
        else if (!strcmp(k, "gif"))     o->gif = atoi(v);
        else if (!strcmp(k, "fps"))     o->fps = atoi(v);
        else if (!strcmp(k, "audio_mode")) o->audio_mode = atoi(v);  /* */
    }
    free(buf);
    /* 兼容单区间 + 计算输出偏移 */
    if (o->n_windows == 1) { o->ss_us = o->ss_list[0]; o->to_us = o->to_list[0]; }
    int64_t acc = 0;
    for (int i = 0; i < o->n_windows; i++) {
        o->win_offsets[i] = acc;
        if (o->to_list[i] > o->ss_list[i]) acc += o->to_list[i] - o->ss_list[i];
    }
}

/* 裁剪窗口判断：多区间任一命中即通过；越过最后区间结束点置 past_end */
static int frame_in_window(int64_t pts_us, const Opts *o, int *past_end) {
    if (o->n_windows == 0) return 1;   /* 无裁剪 */
    for (int i = 0; i < o->n_windows; i++) {
        int64_t ss = o->ss_list[i], to = o->to_list[i];
        if (ss > 0 && pts_us < ss) continue;
        if (to > 0 && pts_us > to) continue;
        return 1;
    }
    if (o->n_windows > 0 && o->to_list[o->n_windows - 1] > 0 &&
        pts_us > o->to_list[o->n_windows - 1]) *past_end = 1;
    return 0;
}

/* YUV420P 平面旋转：cw=1 顺时针 90°，cw=0 逆时针 90°（等价 270°）。
 * 用于竖屏视频物理旋转（输出不依赖播放器读取 rotate metadata）。 */
static void rotate_plane(uint8_t *dst, const uint8_t *src, int w, int h,
                         int dst_linesize, int src_linesize, int cw) {
    if (cw) {
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                dst[(w - 1 - x) * dst_linesize + y] = src[y * src_linesize + x];
    } else {
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                dst[x * dst_linesize + (h - 1 - y)] = src[y * src_linesize + x];
    }
}

/* RGB24 旋转（3 字节/像素）：GIF 用 RGB24 中间帧时的竖屏物理旋转 */
static void rotate_rgb24(uint8_t *dst, const uint8_t *src, int w, int h,
                         int dst_linesize, int src_linesize, int cw) {
    if (cw) {
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                memcpy(dst + (w - 1 - x) * dst_linesize + y * 3,
                       src + y * src_linesize + x * 3, 3);
    } else {
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                memcpy(dst + x * dst_linesize + (h - 1 - y) * 3,
                       src + y * src_linesize + x * 3, 3);
    }
}

/* =========================================================
 *  GIF 调色板
 *  gif 编码器只支持 PAL8/RGB8；sws_scale 不会为调色板格式生成 palette。
 *  这里用固定 6×6×6 色立方（216 色）+ 40 级灰度 组成 256 色调色板，
 *  RGB24 逐像素量化写入 PAL8 索引 —— 不依赖任何内部 API。
 * ========================================================= */
static void gif_build_palette(uint32_t *pal) {
    int i = 0;
    for (int r = 0; r < 6; r++)
        for (int g = 0; g < 6; g++)
            for (int b = 0; b < 6; b++) {
                int R = r * 51, G = g * 51, B = b * 51;
                pal[i++] = 0xFF000000u | ((uint32_t)R << 16) | ((uint32_t)G << 8) | (uint32_t)B;
            }
    for (; i < 256; i++) {
        int v = (i - 216) * 255 / 39;
        pal[i] = 0xFF000000u | ((uint32_t)v << 16) | ((uint32_t)v << 8) | (uint32_t)v;
    }
}

/* 分辨率限制（保持宽高比，偶数对齐） */
static void calc_output_size(int src_w, int src_h, const Opts *o, int *out_w, int *out_h) {
    int w = src_w, h = src_h;
    if (o->maxw > 0 && o->maxh > 0 && (w > o->maxw || h > o->maxh)) {
        double ar = (double)w / h;
        if (w > h) { w = o->maxw; h = (int)(o->maxw / ar); }
        else       { h = o->maxh; w = (int)(o->maxh * ar); }
        if (h > o->maxh) { h = o->maxh; w = (int)(o->maxh * ar); }
        if (w > o->maxw) { w = o->maxw; h = (int)(o->maxw / ar); }
    }
    *out_w = w & ~1;
    *out_h = h & ~1;
    if (*out_w < 2) *out_w = 2;
    if (*out_h < 2) *out_h = 2;
}

/* 音频变速（重写）：按累积比例取样本写入 FIFO。
 * 加速丢样本 / 减速复制样本（速度可任意 >0），音调由调用方通过
 * swr 采样率预补偿（out_rate = sr/speed）保持，不再变调（旧版电音根因）。 */
static void fifo_write_audio(AVAudioFifo *fifo, AVFrame *src, int nb, double speed,
                             int64_t *in_total, int64_t *out_written) {
    if (speed == 1.0) {
        av_audio_fifo_write(fifo, (void **)src->data, nb);
        return;
    }
    *in_total += nb;
    int64_t want = (int64_t)((double)*in_total / speed);
    int chs = src->ch_layout.nb_channels;
    if (chs < 1) chs = 2;
    int bps = av_get_bytes_per_sample(src->format);
    int64_t used = 0;
    while (*out_written < want && used < nb) {
        int64_t take = want - *out_written;
        if (take > nb - used) take = nb - used;
        if (take < 1) take = 1;
        if (av_sample_fmt_is_planar(src->format)) {
            void *ptrs[8];
            for (int ch = 0; ch < chs && ch < 8; ch++) ptrs[ch] = src->data[ch] + used * bps;
            av_audio_fifo_write(fifo, ptrs, (int)take);
        } else {
            void *ptr = src->data[0] + used * bps * chs;
            av_audio_fifo_write(fifo, &ptr, (int)take);
        }
        *out_written += take;
        used += take;
        /* 一帧用完但 want 未到：等下一帧继续补（减速时发生） */
        if (used >= nb) break;
    }
}

/* =========================================================
 *  atempo 变速不变调（HAVE_AVFILTER 时启用）
 * 旧版丢样本变速会升调 → 电音（用户实测）。atempo 是 WSOLA 时间
 * 拉伸算法：变时长不变音调，音质最好。speed>2 或 <0.5 时串联多个
 * atempo（每个只支持 0.5~2.0）。图结构：
 *   abuffer(dec参数) → aresample(转 enc 参数) → atempo×N → abuffersink
 * 输出帧格式 = enc 格式（采样率/声道/采样格式已对齐），可直接写 FIFO。
 * ========================================================= */
#ifdef HAVE_AVFILTER
#include <libavfilter/avfilter.h>
#include <libavfilter/buffersrc.h>
#include <libavfilter/buffersink.h>

typedef struct {
    AVFilterGraph *graph;
    AVFilterContext *src_ctx, *sink_ctx;
    int ok;
} TempoPipe;

static void tempo_free(TempoPipe *t);   /* 前向声明（tempo_init 失败路径使用） */

/* 构建 atempo 链：从 start 滤镜开始，speed 拆成多个 0.5~2.0 串联
 * （如 4x → 2x+2x，0.25x → 0.5+0.5），末尾连到 sink */
static int tempo_build_chain(TempoPipe *t, AVFilterContext *start, double speed) {
    char args[64];
    AVFilterContext *prev = start;
    double rem = speed;
    int n = 0;
    /* 最多拆 6 段（支持 0.5^6~2^6 = 1/64 ~ 64 倍） */
    while (fabs(rem - 1.0) > 0.001 && n < 6) {
        double seg = rem;
        if (seg > 2.0) seg = 2.0;
        if (seg < 0.5) seg = 0.5;
        snprintf(args, sizeof(args), "tempo=%.3f", seg);
        const AVFilter *af = avfilter_get_by_name("atempo");
        if (!af) { LOGE("atempo: filter not found"); return -1; }
        AVFilterContext *cur = NULL;
        if (avfilter_graph_create_filter(&cur, af, "t", args, NULL, t->graph) < 0) {
            LOGE("atempo: create filter failed (speed=%.2f)", speed);
            return -1;
        }
        if (avfilter_link(prev, 0, cur, 0) < 0) { LOGE("atempo: link failed"); return -1; }
        prev = cur;
        rem /= seg;
        n++;
    }
    if (avfilter_link(prev, 0, t->sink_ctx, 0) < 0) { LOGE("atempo: link sink failed"); return -1; }
    return 0;
}

/* 初始化：dec 参数进，enc 参数出（内部 aresample 对齐格式）。
 * 图：abuffer(dec) → aresample(dec→enc) → atempo×N → abuffersink */
static int tempo_init(TempoPipe *t, double speed, int dec_sr, int dec_ch,
                      const AVChannelLayout *dec_layout, const char *dec_fmt,
                      int enc_sr, int enc_ch, const char *enc_fmt) {
    memset(t, 0, sizeof(*t));
    t->graph = avfilter_graph_alloc();
    if (!t->graph) { LOGE("atempo: graph alloc failed"); return -1; }
    t->graph->nb_threads = 1;

    /* abuffer 输入参数（dec 格式）：channel_layout 用 describe 字符串，
     * 兼容 5.1 等多声道（aresample 会转成 enc 的声道数） */
    char args[512];
    char chl_desc[128] = "stereo";
    if (dec_ch > 2 && dec_layout) {
        if (av_channel_layout_describe(dec_layout, chl_desc, sizeof(chl_desc)) < 0)
            snprintf(chl_desc, sizeof(chl_desc), "%d channels", dec_ch);
    }
    snprintf(args, sizeof(args),
             "time_base=1/%d:sample_rate=%d:sample_fmt=%s:channel_layout=%s",
             dec_sr, dec_sr, dec_fmt, chl_desc);
    const AVFilter *srcf = avfilter_get_by_name("abuffer");
    if (!srcf) { LOGE("atempo: abuffer not found"); goto fail; }
    if (avfilter_graph_create_filter(&t->src_ctx, srcf, "in", args, NULL, t->graph) < 0) {
        LOGE("atempo: abuffer create failed"); goto fail;
    }

    /* aresample：dec 格式 → enc 格式 */
    const AVFilter *arf = avfilter_get_by_name("aresample");
    if (!arf) { LOGE("atempo: aresample not found"); goto fail; }
    AVFilterContext *ares = NULL;
    snprintf(args, sizeof(args),
             "osr=%d:ochl=%d:osf=%s",
             enc_sr, enc_ch == 1 ? AV_CH_LAYOUT_MONO : AV_CH_LAYOUT_STEREO, enc_fmt);
    if (avfilter_graph_create_filter(&ares, arf, "ar", args, NULL, t->graph) < 0) {
        LOGE("atempo: aresample create failed"); goto fail;
    }
    if (avfilter_link(t->src_ctx, 0, ares, 0) < 0) { LOGE("atempo: link ares failed"); goto fail; }

    /* abuffersink */
    const AVFilter *sinkf = avfilter_get_by_name("abuffersink");
    if (!sinkf) { LOGE("atempo: abuffersink not found"); goto fail; }
    if (avfilter_graph_create_filter(&t->sink_ctx, sinkf, "out", NULL, NULL, t->graph) < 0) {
        LOGE("atempo: sink create failed"); goto fail;
    }

    if (tempo_build_chain(t, ares, speed) < 0) goto fail;
    if (avfilter_graph_config(t->graph, NULL) < 0) { LOGE("atempo: graph config failed"); goto fail; }
    t->ok = 1;
    LOGI("atempo: ready speed=%.3f dec=%dHz/%dch/%s enc=%dHz/%dch/%s",
         speed, dec_sr, dec_ch, dec_fmt, enc_sr, enc_ch, enc_fmt);
    return 0;
fail:
    tempo_free(t);
    return -1;
}

/* 送一帧进图；返回 0 或 EAGAIN（缓冲满，需先 recv） */
static int tempo_send(TempoPipe *t, AVFrame *frame) {
    if (!t->ok) return AVERROR(EINVAL);
    int r = av_buffersrc_add_frame(t->src_ctx, frame);
    return r;
}

/* 取一帧输出：out 为调用方预分配帧（FFmpeg 7.0 签名），
 * 返回 0（帧已写入，调用方 av_frame_unref）或 EAGAIN/EOF */
static int tempo_recv(TempoPipe *t, AVFrame *out) {
    if (!t->ok) return AVERROR(EINVAL);
    return av_buffersink_get_frame(t->sink_ctx, out);
}

static void tempo_free(TempoPipe *t) {
    if (t->graph) avfilter_graph_free(&t->graph);
    memset(t, 0, sizeof(*t));
}
#endif /* HAVE_AVFILTER */

/* 音量增益（PCM 层手动缩放，不依赖 avfilter） */
static void apply_volume(AVFrame *f, double volume) {
    if (volume == 1.0 || !f->data[0]) return;
    int chs = f->ch_layout.nb_channels;
    int n = f->nb_samples;
    if (f->format == AV_SAMPLE_FMT_FLTP || f->format == AV_SAMPLE_FMT_FLT) {
        int planes = (f->format == AV_SAMPLE_FMT_FLTP) ? chs : 1;
        int per = (f->format == AV_SAMPLE_FMT_FLTP) ? n : n * chs;
        for (int p = 0; p < planes; p++) {
            float *d = (float *)f->data[p];
            for (int i = 0; i < per; i++) d[i] *= (float)volume;
        }
    } else if (f->format == AV_SAMPLE_FMT_S16 || f->format == AV_SAMPLE_FMT_S16P) {
        if (f->format == AV_SAMPLE_FMT_S16) {
            int16_t *d = (int16_t *)f->data[0];
            for (int i = 0; i < n * chs; i++)
                d[i] = av_clip_int16((int32_t)llround(d[i] * volume));
        } else {
            for (int p = 0; p < chs; p++) {
                int16_t *d = (int16_t *)f->data[p];
                for (int i = 0; i < n; i++)
                    d[i] = av_clip_int16((int32_t)llround(d[i] * volume));
            }
        }
    }
}

static int find_stream(AVFormatContext *fmt_ctx, enum AVMediaType type) {
    for (int i = 0; i < (int)fmt_ctx->nb_streams; i++) {
        AVStream *st = fmt_ctx->streams[i];
        if (st->codecpar->codec_type != type) continue;
        /* 关键：跳过封面图等附件流。FLAC/MP3 内嵌封面会被 FFmpeg 作为
         * AVMEDIA_TYPE_VIDEO 流（AV_DISPOSITION_ATTACHED_PIC），
         * 不排除会导致纯音频文件被误判为"有视频"（修复：
         * 音频拼接失败 -1 / 音频任务输出成视频 的根因之一） */
        if (type == AVMEDIA_TYPE_VIDEO && (st->disposition & AV_DISPOSITION_ATTACHED_PIC)) continue;
        return i;
    }
    return -1;
}

/* 音频样本拷贝（无 swr 直通时）：planar 格式必须逐声道拷，直接 memcpy(data[0])
 * 会把全部声道数据写进第 1 声道 → 缓冲区溢出（修复：视频 AAC→AAC 失败 -22） */
static void copy_audio_samples(AVFrame *dst, const AVFrame *src) {
    int bytes = av_get_bytes_per_sample(dst->format);
    if (av_sample_fmt_is_planar(dst->format)) {
        for (int ch = 0; ch < dst->ch_layout.nb_channels; ch++)
            memcpy(dst->data[ch], src->data[ch], dst->nb_samples * bytes);
    } else {
        memcpy(dst->data[0], src->data[0],
               dst->nb_samples * bytes * dst->ch_layout.nb_channels);
    }
}

static const char *audio_enc_name(const char *codec) {
    if (strcmp(codec, "mp3") == 0) return "libmp3lame";
    if (strcmp(codec, "aac") == 0) return "aac";
    if (strcmp(codec, "vorbis") == 0) return "vorbis";
    if (strcmp(codec, "wav") == 0) return "pcm_s16le";
    if (strcmp(codec, "flac") == 0) return "flac";
    return codec;
}

static const char *audio_fmt_name(const char *codec) {
    if (strcmp(codec, "mp3") == 0) return "mp3";
    if (strcmp(codec, "aac") == 0) return "mp4";
    if (strcmp(codec, "vorbis") == 0) return "ogg";
    if (strcmp(codec, "wav") == 0) return "wav";
    if (strcmp(codec, "flac") == 0) return "flac";
    return "mp4";
}

static const char *video_enc_name(const char *codec) {
    if (strcmp(codec, "h264") == 0) return "libx264";
    if (strcmp(codec, "h265") == 0) return "libx265";
    if (strcmp(codec, "mpeg4") == 0) return "mpeg4";
    if (strcmp(codec, "gif") == 0) return "gif";
    return NULL;
}

static const char *video_fmt_name(const char *codec, const char *output) {
    if (strcmp(codec, "gif") == 0) return "gif";
    /* 按输出扩展名选容器（MKV 已弃用，新增 AVI/TS/WebM/MPG） */
    if (strstr(output, ".mkv")) return "matroska";
    if (strstr(output, ".avi")) return "avi";
    if (strstr(output, ".ts")) return "mpegts";
    if (strstr(output, ".webm")) return "webm";
    if (strstr(output, ".mpg") || strstr(output, ".mpeg")) return "mpeg";
    return "mp4";
}

/* =========================================================
 *  AUDIO CONVERSION (trim / speed / volume)
 * ========================================================= */
static int do_audio_convert(const char *input, const char *output, const char *codec_name,
                            const Opts *o, int handle) {
    AVFormatContext *in_fmt = NULL, *out_fmt = NULL;
    AVCodecContext *dec_ctx = NULL, *enc_ctx = NULL;
    SwrContext *swr = NULL;
    AVAudioFifo *fifo = NULL;
    AVPacket *pkt = NULL;
    AVFrame *frame = NULL;
    int ret = 0, result = 0, stream_idx = -1;
    int64_t out_pts = 0;
    int64_t speed_in_total = 0, speed_out_written = 0;  /* 音频倍速累积器 */
    int out_frame_size = 1024;
    AVStream *out_stream = NULL;
    const AVCodec *encoder = NULL;
#ifdef HAVE_AVFILTER
    TempoPipe tempo;          /* atempo 变速（音调不变，替代丢样本） */
    memset(&tempo, 0, sizeof(tempo));
    int use_tempo = 0;
#endif

    if ((ret = open_input_avio(&in_fmt, input)) < 0) {
        return ret;
    }
    if ((ret = avformat_find_stream_info(in_fmt, NULL)) < 0) goto fail;
    LOGI("audio: find_stream_info ok, %d streams, duration=%lldus", (int)in_fmt->nb_streams, (long long)in_fmt->duration);
    stream_idx = find_stream(in_fmt, AVMEDIA_TYPE_AUDIO);
    if (stream_idx < 0) {
        /* 无音轨输入（如分离视频）给出明确错误，替代裸 ret=-1 */
        LOGE("audio: no audio stream found (nb_streams=%d) - 输入无音轨，无法转换音频",
             (int)in_fmt->nb_streams);
        ret = AVERROR_STREAM_NOT_FOUND;
        goto fail;
    }

    AVCodecParameters *in_par = in_fmt->streams[stream_idx]->codecpar;
    const AVCodec *decoder = avcodec_find_decoder(in_par->codec_id);
    if (!decoder) { ret = -1; goto fail; }
    dec_ctx = avcodec_alloc_context3(decoder);
    avcodec_parameters_to_context(dec_ctx, in_par);
    if ((ret = avcodec_open2(dec_ctx, decoder, NULL)) < 0) goto fail;
    LOGI("audio: decoder opened (%s, %dHz %dch)", decoder->name, dec_ctx->sample_rate, dec_ctx->ch_layout.nb_channels);

    const char *enc_name = audio_enc_name(codec_name);
    const char *fmt_name = audio_fmt_name(codec_name);
    encoder = avcodec_find_encoder_by_name(enc_name);
    if (!encoder) { LOGE("Encoder %s not found", enc_name); ret = AVERROR_ENCODER_NOT_FOUND; goto fail; }

    AVOutputFormat *oformat = av_guess_format(fmt_name, output, NULL);
    if (!oformat) { ret = -1; goto fail; }
    avformat_alloc_output_context2(&out_fmt, oformat, NULL, output);
    if (!out_fmt) { ret = -1; goto fail; }
    out_stream = avformat_new_stream(out_fmt, encoder);

    enc_ctx = avcodec_alloc_context3(encoder);
    /* 采样率保持原值。变速不变调只能靠 atempo（WSOLA，HAVE_AVFILTER）——
     * 丢样本/重采样变速必然变调（电音），无 avfilter 时无法同时保持音调和时长。
     * 未重编 FFmpeg 的用户会看到 nativeIsAvailable 日志 atempo=DISABLED，
     * 此时倍速会变调（功能可用，音调略高），重编后自动消除。 */
    enc_ctx->sample_rate = dec_ctx->sample_rate ? dec_ctx->sample_rate : 44100;
    enc_ctx->ch_layout = dec_ctx->ch_layout;
    if (enc_ctx->ch_layout.nb_channels == 0)
        enc_ctx->ch_layout = (AVChannelLayout)AV_CHANNEL_LAYOUT_STEREO;
    /* 无具体声道布局（AV_CHANNEL_ORDER_UNSPEC）时补默认布局：
     * AAC 等编码器要求明确布局（如 stereo=FL+FR），否则 avcodec_open2 报
     * "Unsupported channel layout"（修复 WAV→AAC 失败 -22） */
    if (enc_ctx->ch_layout.order == AV_CHANNEL_ORDER_UNSPEC) {
        enc_ctx->ch_layout = enc_ctx->ch_layout.nb_channels == 1
            ? (AVChannelLayout)AV_CHANNEL_LAYOUT_MONO
            : (AVChannelLayout)AV_CHANNEL_LAYOUT_STEREO;
    }
    if (enc_ctx->ch_layout.nb_channels > 2) {
        LOGE("Source has %d channels, forcing stereo", enc_ctx->ch_layout.nb_channels);
        enc_ctx->ch_layout = (AVChannelLayout)AV_CHANNEL_LAYOUT_STEREO;
    }
    enc_ctx->bit_rate = o->bitrate > 0 ? o->bitrate : 128000;
    enc_ctx->sample_fmt = encoder->sample_fmts[0];
    enc_ctx->time_base = (AVRational){1, enc_ctx->sample_rate};
    /* vorbis 编码器标记 experimental，需放开合规性检查才能打开（修复 OGG 输出失败） */
    enc_ctx->strict_std_compliance = FF_COMPLIANCE_EXPERIMENTAL;
    if ((ret = avcodec_open2(enc_ctx, encoder, NULL)) < 0) {
        LOGE("Cannot open encoder %s: %s", enc_name, av_err2str(ret));
        goto fail;
    }
    avcodec_parameters_from_context(out_stream->codecpar, enc_ctx);
    out_stream->time_base = enc_ctx->time_base;
    LOGI("audio: encoder opened (%s)", enc_name);

    /* 音频 FIFO：解码帧 nb_samples 未必等于编码器 frame_size（mp3=1152/aac=1024），
     * 直接 send_frame 会 EINVAL (-22)。先累积到 FIFO，凑够 frame_size 再分块送。 */
    if (enc_ctx->frame_size > 0) out_frame_size = enc_ctx->frame_size;
    fifo = av_audio_fifo_alloc(enc_ctx->sample_fmt, enc_ctx->ch_layout.nb_channels, 1);
    if (!fifo) { ret = AVERROR(ENOMEM); goto fail; }

    if (dec_ctx->sample_fmt != enc_ctx->sample_fmt ||
        dec_ctx->sample_rate != enc_ctx->sample_rate ||
        dec_ctx->ch_layout.nb_channels != enc_ctx->ch_layout.nb_channels) {
        swr = swr_alloc();
        av_opt_set_chlayout(swr, "in_chlayout", &dec_ctx->ch_layout, 0);
        av_opt_set_int(swr, "in_sample_rate", dec_ctx->sample_rate, 0);
        av_opt_set_sample_fmt(swr, "in_sample_fmt", dec_ctx->sample_fmt, 0);
        av_opt_set_chlayout(swr, "out_chlayout", &enc_ctx->ch_layout, 0);
        av_opt_set_int(swr, "out_sample_rate", enc_ctx->sample_rate, 0);
        av_opt_set_sample_fmt(swr, "out_sample_fmt", enc_ctx->sample_fmt, 0);
        if ((ret = swr_init(swr)) < 0) goto fail;
    }

#ifdef HAVE_AVFILTER
    /* 变速走 atempo（WSOLA，音调不变）。丢样本变速会升调→电音。
     * 用 abuffer(dec) → aresample(enc) → atempo → sink 的图结构：
     * 输入帧格式 dec，输出已转成 enc 格式，直接写 FIFO。 */
    if (o->speed != 1.0 && o->speed > 0.01) {
        const char *dfmt = av_get_sample_fmt_name(dec_ctx->sample_fmt);
        const char *efmt = av_get_sample_fmt_name(enc_ctx->sample_fmt);
        if (dfmt && efmt &&
            tempo_init(&tempo, o->speed, dec_ctx->sample_rate,
                       dec_ctx->ch_layout.nb_channels, &dec_ctx->ch_layout, dfmt,
                       enc_ctx->sample_rate, enc_ctx->ch_layout.nb_channels, efmt) == 0) {
            use_tempo = 1;
            LOGI("audio: use atempo speed=%.3f", o->speed);
        }
    }
#endif

    out_fmt->pb = open_output_avio_pb(output);
    if (!out_fmt->pb) { ret = AVERROR(ENOENT); goto fail; }
    LOGI("audio: output opened");
    if ((ret = avformat_write_header(out_fmt, NULL)) < 0) goto fail;
    LOGI("audio: header written");

    pkt = av_packet_alloc();
    frame = av_frame_alloc();
    AVRational tb = in_fmt->streams[stream_idx]->time_base;

    while (av_read_frame(in_fmt, pkt) >= 0) {
        if ((ret = task_check(handle)) < 0) { result = ret; break; }
        if (pkt->stream_index != stream_idx) { av_packet_unref(pkt); continue; }
        ret = avcodec_send_packet(dec_ctx, pkt);
        while (ret >= 0) {
            ret = avcodec_receive_frame(dec_ctx, frame);
            if (ret == AVERROR(EAGAIN) || ret == AVERROR_EOF) break;
            if (ret < 0) { result = ret; break; }

            /* 裁剪窗口 */
            int past_end = 0;
            int64_t pts_us = av_rescale_q(frame->pts, tb, AV_TIME_BASE_Q);
            if (!frame_in_window(pts_us, o, &past_end)) {
                if (past_end) { result = 1; break; }  /* 1 = 正常结束 */
                continue;
            }

            /* 重采样到编码格式，写入 FIFO */
            /* 直通时 frame 格式与 enc 一致，apply_volume 直接对 frame 处理（避免 tmp->format 错乱导致杂音） */
#ifdef HAVE_AVFILTER
            if (use_tempo) {
                /* atempo 路径：帧原样送入变速图（内部处理格式转换+变速），
                 * 输出帧已对齐 enc 格式，apply_volume 后直接写 FIFO（不再丢样本） */
                ret = tempo_send(&tempo, frame);
                if (ret < 0 && ret != AVERROR(EAGAIN)) { result = ret; break; }
                AVFrame *tf = av_frame_alloc();
                if (!tf) { result = AVERROR(ENOMEM); break; }
                while (tempo_recv(&tempo, tf) == 0) {
                    apply_volume(tf, o->volume);
                    av_audio_fifo_write(fifo, (void **)tf->data, tf->nb_samples);
                    av_frame_unref(tf);
                }
                av_frame_free(&tf);
            } else
#endif
            if (swr) {
                AVFrame *tmp = av_frame_alloc();
                if (!tmp) { result = AVERROR(ENOMEM); break; }
                tmp->format = enc_ctx->sample_fmt;
                tmp->sample_rate = enc_ctx->sample_rate;
                av_channel_layout_copy(&tmp->ch_layout, &enc_ctx->ch_layout);
                tmp->nb_samples = (int)av_rescale_rnd(
                    swr_get_delay(swr, dec_ctx->sample_rate) + frame->nb_samples,
                    enc_ctx->sample_rate, dec_ctx->sample_rate, AV_ROUND_UP);
                av_frame_get_buffer(tmp, 0);
                int converted = swr_convert(swr, tmp->data, tmp->nb_samples,
                                            (const uint8_t **)frame->data, frame->nb_samples);
                if (converted < 0) { result = converted; av_frame_free(&tmp); break; }
                tmp->nb_samples = converted;
                apply_volume(tmp, o->volume);
                fifo_write_audio(fifo, tmp, tmp->nb_samples, o->speed, &speed_in_total, &speed_out_written);
                av_frame_free(&tmp);
            } else {
                apply_volume(frame, o->volume);
                fifo_write_audio(fifo, frame, frame->nb_samples, o->speed, &speed_in_total, &speed_out_written);
            }

            /* 凑够 frame_size 分块送编码器 */
            AVFrame *out_frame = av_frame_alloc();
            out_frame->format = enc_ctx->sample_fmt;
            out_frame->sample_rate = enc_ctx->sample_rate;
            av_channel_layout_copy(&out_frame->ch_layout, &enc_ctx->ch_layout);
            out_frame->nb_samples = out_frame_size;
            av_frame_get_buffer(out_frame, 0);
            while (av_audio_fifo_size(fifo) >= out_frame_size) {
                if ((ret = task_check(handle)) < 0) { result = ret; break; }
                av_audio_fifo_read(fifo, (void **)out_frame->data, out_frame_size);
                out_frame->pts = out_pts;
                out_pts += out_frame_size;
                ret = avcodec_send_frame(enc_ctx, out_frame);
                if (ret == AVERROR(EAGAIN)) {
                    while (avcodec_receive_packet(enc_ctx, pkt) >= 0) {
                        av_packet_rescale_ts(pkt, enc_ctx->time_base, out_stream->time_base);
                        av_interleaved_write_frame(out_fmt, pkt);
                        av_packet_unref(pkt);
                    }
                    ret = avcodec_send_frame(enc_ctx, out_frame);
                }
                if (ret < 0) { result = ret; break; }
                while ((ret = avcodec_receive_packet(enc_ctx, pkt)) >= 0) {
                    av_packet_rescale_ts(pkt, enc_ctx->time_base, out_stream->time_base);
                    av_interleaved_write_frame(out_fmt, pkt);
                    av_packet_unref(pkt);
                }
            }
            av_frame_free(&out_frame);
            if (result != 0) break;
            /* 进度：按原始帧时间 / 总时长 */
            if (in_fmt->duration > 0) {
                int pct = (int)((double)pts_us / in_fmt->duration * 100);
                report_progress(handle, pct < 0 ? 0 : (pct > 99 ? 99 : pct));
            }
        }
        if (result != 0) break;
        av_packet_unref(pkt);
    }
    if (result == 1) result = 0;  /* 正常到裁剪终点 */
    if (result >= 0) {
        /* flush 解码器剩余帧 → FIFO */
        avcodec_send_packet(dec_ctx, NULL);
        while (avcodec_receive_frame(dec_ctx, frame) >= 0) {
            /* flush 阶段直通同样直接用 frame */
#ifdef HAVE_AVFILTER
            if (use_tempo) {
                ret = tempo_send(&tempo, frame);
                if (ret < 0 && ret != AVERROR(EAGAIN)) { result = ret; break; }
                AVFrame *tf = av_frame_alloc();
                if (!tf) { result = AVERROR(ENOMEM); break; }
                while (tempo_recv(&tempo, tf) == 0) {
                    apply_volume(tf, o->volume);
                    av_audio_fifo_write(fifo, (void **)tf->data, tf->nb_samples);
                    av_frame_unref(tf);
                }
                av_frame_free(&tf);
            } else
#endif
            if (swr) {
                AVFrame *tmp = av_frame_alloc();
                if (!tmp) { result = AVERROR(ENOMEM); break; }
                tmp->format = enc_ctx->sample_fmt;
                tmp->sample_rate = enc_ctx->sample_rate;
                av_channel_layout_copy(&tmp->ch_layout, &enc_ctx->ch_layout);
                tmp->nb_samples = (int)av_rescale_rnd(
                    swr_get_delay(swr, dec_ctx->sample_rate) + frame->nb_samples,
                    enc_ctx->sample_rate, dec_ctx->sample_rate, AV_ROUND_UP);
                av_frame_get_buffer(tmp, 0);
                int converted = swr_convert(swr, tmp->data, tmp->nb_samples,
                                            (const uint8_t **)frame->data, frame->nb_samples);
                if (converted < 0) { result = converted; av_frame_free(&tmp); break; }
                tmp->nb_samples = converted;
                apply_volume(tmp, o->volume);
                fifo_write_audio(fifo, tmp, tmp->nb_samples, o->speed, &speed_in_total, &speed_out_written);
                av_frame_free(&tmp);
            } else {
                apply_volume(frame, o->volume);
                fifo_write_audio(fifo, frame, frame->nb_samples, o->speed, &speed_in_total, &speed_out_written);
            }
        }
#ifdef HAVE_AVFILTER
        /* flush atempo 图：送 NULL 结束，拉取尾部帧 */
        if (use_tempo) {
            tempo_send(&tempo, NULL);
            AVFrame *tf = av_frame_alloc();
            if (tf) {
                while (tempo_recv(&tempo, tf) == 0) {
                    apply_volume(tf, o->volume);
                    av_audio_fifo_write(fifo, (void **)tf->data, tf->nb_samples);
                    av_frame_unref(tf);
                }
                av_frame_free(&tf);
            }
        }
#endif
        /* flush FIFO 剩余（不足 frame_size 的补零） */
        int bytes_ps = av_get_bytes_per_sample(enc_ctx->sample_fmt);
        while (result >= 0 && av_audio_fifo_size(fifo) > 0) {
            int rem = av_audio_fifo_size(fifo);
            int got = rem < out_frame_size ? rem : out_frame_size;
            AVFrame *out_frame = av_frame_alloc();
            out_frame->format = enc_ctx->sample_fmt;
            out_frame->sample_rate = enc_ctx->sample_rate;
            av_channel_layout_copy(&out_frame->ch_layout, &enc_ctx->ch_layout);
            out_frame->nb_samples = out_frame_size;
            av_frame_get_buffer(out_frame, 0);
            av_audio_fifo_read(fifo, (void **)out_frame->data, got);
            /* 补零：packed 格式只有 data[0]（FLAC 是 S16 packed，按声道循环会访问
             * data[1] 空指针 → SEGV 闪退，修复的 FLAC 转换崩溃根因） */
            if (av_sample_fmt_is_planar(enc_ctx->sample_fmt)) {
                for (int ch = 0; ch < enc_ctx->ch_layout.nb_channels; ch++)
                    memset(out_frame->data[ch] + got * bytes_ps, 0, (out_frame_size - got) * bytes_ps);
            } else {
                memset(out_frame->data[0] + got * bytes_ps * enc_ctx->ch_layout.nb_channels, 0,
                       (out_frame_size - got) * bytes_ps * enc_ctx->ch_layout.nb_channels);
            }
            out_frame->pts = out_pts;
            out_pts += out_frame_size;
            ret = avcodec_send_frame(enc_ctx, out_frame);
            if (ret >= 0) {
                while (avcodec_receive_packet(enc_ctx, pkt) >= 0) {
                    av_packet_rescale_ts(pkt, enc_ctx->time_base, out_stream->time_base);
                    av_interleaved_write_frame(out_fmt, pkt);
                    av_packet_unref(pkt);
                }
            } else {
                result = ret;
            }
            av_frame_free(&out_frame);
        }
        /* flush 编码器 */
        avcodec_send_frame(enc_ctx, NULL);
        while (avcodec_receive_packet(enc_ctx, pkt) >= 0) {
            av_packet_rescale_ts(pkt, enc_ctx->time_base, out_stream->time_base);
            av_interleaved_write_frame(out_fmt, pkt);
            av_packet_unref(pkt);
        }
    }
    av_write_trailer(out_fmt);

fail:
    if (result < 0 || (ret < 0 && ret != AVERROR(EAGAIN) && ret != AVERROR(EOF)))
        LOGE("%s failed: ret=%d (%s) result=%d", __func__, ret, av_err2str(ret), result);
    else
        LOGI("%s done: result=%d", __func__, result);
    close_output_avio(&out_fmt);
    close_input_avio(&in_fmt);
    if (fifo) av_audio_fifo_free(fifo);
    if (dec_ctx) avcodec_free_context(&dec_ctx);
    if (enc_ctx) avcodec_free_context(&enc_ctx);
    if (swr) swr_free(&swr);
#ifdef HAVE_AVFILTER
    if (use_tempo) tempo_free(&tempo);
#endif
    if (pkt) av_packet_free(&pkt);
    if (frame) av_frame_free(&frame);
    task_reset(handle);
    return result < 0 ? result : (ret < 0 && ret != AVERROR(EAGAIN) && ret != AVERROR(EOF) ? ret : 0);
}

/* =========================================================
 *  VIDEO CONVERSION (trim / speed / crf / bitrate / resize)
 * ========================================================= */
static int do_video_convert(const char *input, const char *output, const char *codec_name,
                            const Opts *o, int handle) {
    AVFormatContext *in_fmt = NULL, *out_fmt = NULL;
    AVCodecContext *vdec_ctx = NULL, *venc_ctx = NULL, *adec_ctx = NULL;
    AVPacket *pkt = NULL;
    AVFrame *frame = NULL;
    AVFrame *out_frame = NULL;
    struct SwsContext *sws = NULL;
    int ret = 0, result = 0, vidx = -1, aidx = -1;
    AVStream *vout = NULL, *aout = NULL;
    int has_audio = 0, out_w = 0, out_h = 0;
    AVBSFContext *aac_bsf = NULL;   /* ADTS→ASC 位流过滤器（微信等视频的 aac 常带 ADTS 头） */
    /* 视频音量重编码管线（volume != 1.0 时：解码→调音量→重编码 aac)  */
    AVCodecContext *vadec = NULL, *vaenc = NULL;
    SwrContext *vaswr = NULL;
    AVAudioFifo *vafifo = NULL;
    int vaout_frame_size = 1024;
    int64_t va_pts = 0, va_speed_in = 0, va_speed_out = 0;
#ifdef HAVE_AVFILTER
    TempoPipe vatempo;        /* 视频任务音频变速（atempo，音调不变） */
    memset(&vatempo, 0, sizeof(vatempo));
    int vuse_tempo = 0;
#endif

    if ((ret = open_input_avio(&in_fmt, input)) < 0) {
        return ret;
    }
    avformat_find_stream_info(in_fmt, NULL);
    LOGI("video: find_stream_info done, %d streams, duration=%lldus", (int)in_fmt->nb_streams, (long long)in_fmt->duration);
    vidx = find_stream(in_fmt, AVMEDIA_TYPE_VIDEO);
    aidx = find_stream(in_fmt, AVMEDIA_TYPE_AUDIO);
    if (vidx < 0) { ret = -1; goto fail; }

    AVCodecParameters *vin_par = in_fmt->streams[vidx]->codecpar;
    const AVCodec *vdecoder = avcodec_find_decoder(vin_par->codec_id);
    if (!vdecoder) { ret = -1; goto fail; }
    vdec_ctx = avcodec_alloc_context3(vdecoder);
    avcodec_parameters_to_context(vdec_ctx, vin_par);
    if ((ret = avcodec_open2(vdec_ctx, vdecoder, NULL)) < 0) goto fail;
    LOGI("video: video decoder opened (%s %dx%d)", vdecoder->name, vdec_ctx->width, vdec_ctx->height);

    if (aidx >= 0) {
        AVCodecParameters *ain_par = in_fmt->streams[aidx]->codecpar;
        LOGI("video: audio track codec=%s rate=%d ch=%d",
             avcodec_get_name(ain_par->codec_id), ain_par->sample_rate, ain_par->ch_layout.nb_channels);
        const AVCodec *adecoder = avcodec_find_decoder(ain_par->codec_id);
        if (adecoder) {
            adec_ctx = avcodec_alloc_context3(adecoder);
            avcodec_parameters_to_context(adec_ctx, ain_par);
            if (avcodec_open2(adec_ctx, adecoder, NULL) < 0) {
                avcodec_free_context(&adec_ctx); adec_ctx = NULL;
            } else {
                has_audio = 1;
            }
        }
    }

    const char *venc_name = video_enc_name(codec_name);
    if (!venc_name) { ret = AVERROR_ENCODER_NOT_FOUND; goto fail; }
    const AVCodec *vencoder = avcodec_find_encoder_by_name(venc_name);
    if (!vencoder) { LOGE("Video encoder %s not found", venc_name); ret = AVERROR_ENCODER_NOT_FOUND; goto fail; }

    double rot = 0.0;
    uint8_t *disp = av_stream_get_side_data(in_fmt->streams[vidx], AV_PKT_DATA_DISPLAYMATRIX, NULL);
    if (disp) rot = av_display_rotation_get((const int32_t *)disp);
    else {
        AVDictionaryEntry *de = av_dict_get(in_fmt->streams[vidx]->metadata, "rotate", NULL, 0);
        if (de) rot = atoi(de->value);
    }
    int rotation = ((int)rot) % 360;
    if (rotation < 0) rotation += 360;

    calc_output_size(vin_par->width, vin_par->height, o, &out_w, &out_h);
    /* 竖屏修正：90°/270° 时输出宽高交换 + 像素物理旋转（不依赖播放器读 rotate） */
    int phys_rotate = (rotation == 90 || rotation == 270);
    int rot_w = out_w, rot_h = out_h;   /* sws 缩放目标（旋转前方向） */
    if (phys_rotate) { int t = out_w; out_w = out_h; out_h = t; }
    LOGI("video: rotation=%d out=%dx%d%s", rotation, out_w, out_h, phys_rotate ? " (phys rotate)" : "");

    AVOutputFormat *oformat = av_guess_format(video_fmt_name(codec_name, output), output, NULL);
    if (!oformat) { ret = -1; goto fail; }
    avformat_alloc_output_context2(&out_fmt, oformat, NULL, output);
    if (!out_fmt) { ret = -1; goto fail; }

    vout = avformat_new_stream(out_fmt, vencoder);
    venc_ctx = avcodec_alloc_context3(vencoder);
    venc_ctx->width = out_w;
    venc_ctx->height = out_h;
    venc_ctx->pix_fmt = AV_PIX_FMT_YUV420P;
    venc_ctx->bit_rate = o->bitrate > 0 ? o->bitrate
                         : (vin_par->bit_rate ? vin_par->bit_rate : 2000000);
    venc_ctx->framerate = in_fmt->streams[vidx]->avg_frame_rate;
    venc_ctx->time_base = in_fmt->streams[vidx]->time_base;
    /* 容错：异常流可能无帧率/time_base，mpeg4 编码器会报 EINVAL */
    if (venc_ctx->framerate.num <= 0 || venc_ctx->framerate.den <= 0)
        venc_ctx->framerate = in_fmt->streams[vidx]->r_frame_rate;  /* try r_frame_rate */
    if (venc_ctx->framerate.num <= 0 || venc_ctx->framerate.den <= 0)
        venc_ctx->framerate = (AVRational){25, 1};
    if (venc_ctx->time_base.num <= 0 || venc_ctx->time_base.den <= 0)
        venc_ctx->time_base = (AVRational){1, 90000};
    /* mpeg4 编码器 time_base 分母上限 65535（MPEG-4 标准），超限 avcodec_open2 返回
     * EINVAL (-22)。视频流常见 time_base 1/90000，必须缩放（用 15360，帧精度足够）。 */
    if (venc_ctx->time_base.den > 65535)
        venc_ctx->time_base = (AVRational){1, 15360};
    if (strcmp(venc_name, "libx264") == 0) {
        av_opt_set(venc_ctx->priv_data, "preset", "fast", 0);
        if (o->crf > 0) {
            char crf_s[8];
            snprintf(crf_s, sizeof(crf_s), "%d", o->crf);
            av_opt_set(venc_ctx->priv_data, "crf", crf_s, 0);
        }
    } else if (strcmp(venc_name, "libx265") == 0) {
        av_opt_set(venc_ctx->priv_data, "preset", "fast", 0);
        if (o->crf > 0) {
            char crf_s[8];
            snprintf(crf_s, sizeof(crf_s), "%d", o->crf);
            av_opt_set(venc_ctx->priv_data, "crf", crf_s, 0);
        }
        venc_ctx->pix_fmt = AV_PIX_FMT_YUV420P;
    } else if (strcmp(venc_name, "mpeg4") == 0) {
        /* 修复压缩变大：mpeg4 不支持 crf 私有选项（libx264 专属），
         * crf=23 静默失效 → 输出默认高码率 → 43MB 变 183MB。
         * 改用 QSCALE + global_quality（qscale 等价，越小质量越高）。
         * 再修复：QSCALE 模式下 bit_rate 被忽略（固定质量），
         * hevc 转 mpeg4 效率倒挂 → 压缩反而变大。压缩任务（bitrate>0）
         * 改走码率控制模式（ABR），输出码率受 bitrate 硬约束 → 保证变小。 */
        if (o->bitrate > 0) {
            /* 压缩：ABR 码率控制，输出码率 ≈ bitrate（低码率下 mpeg4 质量可接受） */
            venc_ctx->bit_rate = o->bitrate;
            venc_ctx->rc_max_rate = o->bitrate;
            venc_ctx->rc_buffer_size = o->bitrate / 2;
            venc_ctx->flags &= ~AV_CODEC_FLAG_QSCALE;
        } else if (o->crf > 0) {
            /* 转码/裁剪等无码率要求：QSCALE 固定质量 */
            venc_ctx->flags |= AV_CODEC_FLAG_QSCALE;
            venc_ctx->global_quality = FF_QP2LAMBDA * o->crf;
        }
    }
    if ((ret = avcodec_open2(venc_ctx, vencoder, NULL)) < 0) goto fail;
    avcodec_parameters_from_context(vout->codecpar, venc_ctx);
    vout->time_base = venc_ctx->time_base;
    LOGI("video: video encoder opened (%s %dx%d)", venc_name, venc_ctx->width, venc_ctx->height);
    if (rotation != 0 && !phys_rotate) {
        char rot_buf[8];
        snprintf(rot_buf, sizeof(rot_buf), "%d", rotation);
        av_dict_set(&vout->metadata, "rotate", rot_buf, 0);
    }

    if (has_audio) {
        /* speed≠1.0 也必须走重编码管线！
         * 此前条件漏了 speed：倍速且音量=1.0 时音频走直通 bsf，只改了 pts 没改内容
         * → 音频没倍速、音画时长对不上（task#32 speed=2.25 volume=1.0 实测）。
         * 音频倍速只能靠解码→重采样变速→重编码实现。 */
        if (o->volume != 1.0 || o->audio_mode != 0 || o->speed != 1.0) {
            /* 音频解码 → 调音量 → 重编码 AAC（视频音量；压缩重编码） */
            AVCodecParameters *ain_par = in_fmt->streams[aidx]->codecpar;
            const AVCodec *vad = avcodec_find_decoder(ain_par->codec_id);
            if (!vad) { ret = AVERROR_DECODER_NOT_FOUND; goto fail; }
            vadec = avcodec_alloc_context3(vad);
            avcodec_parameters_to_context(vadec, ain_par);
            if ((ret = avcodec_open2(vadec, vad, NULL)) < 0) { LOGE("video volume: adec open fail %d", ret); goto fail; }
            const AVCodec *vae = avcodec_find_encoder_by_name("aac");
            if (!vae) { LOGE("video volume: aac encoder not found"); ret = AVERROR_ENCODER_NOT_FOUND; goto fail; }
            aout = avformat_new_stream(out_fmt, vae);
            vaenc = avcodec_alloc_context3(vae);
            vaenc->sample_rate = vadec->sample_rate ? vadec->sample_rate : 44100;
            vaenc->ch_layout = vadec->ch_layout;
            if (vaenc->ch_layout.nb_channels == 0 || vaenc->ch_layout.order == AV_CHANNEL_ORDER_UNSPEC)
                vaenc->ch_layout = vaenc->ch_layout.nb_channels == 1
                    ? (AVChannelLayout)AV_CHANNEL_LAYOUT_MONO : (AVChannelLayout)AV_CHANNEL_LAYOUT_STEREO;
            if (vaenc->ch_layout.nb_channels > 2) vaenc->ch_layout = (AVChannelLayout)AV_CHANNEL_LAYOUT_STEREO;
            /* 保留原音频码率；audio_mode=1 时强制 192k（用户显式选重编码） */
            vaenc->bit_rate = (o->audio_mode != 0) ? 192000 : (ain_par->bit_rate > 0 ? ain_par->bit_rate : 128000);
            vaenc->sample_fmt = vae->sample_fmts[0];
            vaenc->time_base = (AVRational){1, vaenc->sample_rate};
            vaenc->strict_std_compliance = FF_COMPLIANCE_EXPERIMENTAL;
            if ((ret = avcodec_open2(vaenc, vae, NULL)) < 0) { LOGE("video volume: aenc open fail %d", ret); goto fail; }
            avcodec_parameters_from_context(aout->codecpar, vaenc);
            aout->time_base = vaenc->time_base;
            if (vaenc->frame_size > 0) vaout_frame_size = vaenc->frame_size;
            vafifo = av_audio_fifo_alloc(vaenc->sample_fmt, vaenc->ch_layout.nb_channels, 1);
            if (!vafifo) { ret = AVERROR(ENOMEM); goto fail; }
            if (vadec->sample_fmt != vaenc->sample_fmt || vadec->sample_rate != vaenc->sample_rate ||
                vadec->ch_layout.nb_channels != vaenc->ch_layout.nb_channels) {
                vaswr = swr_alloc();
                av_opt_set_chlayout(vaswr, "in_chlayout", &vadec->ch_layout, 0);
                av_opt_set_int(vaswr, "in_sample_rate", vadec->sample_rate, 0);
                av_opt_set_sample_fmt(vaswr, "in_sample_fmt", vadec->sample_fmt, 0);
                av_opt_set_chlayout(vaswr, "out_chlayout", &vaenc->ch_layout, 0);
                av_opt_set_int(vaswr, "out_sample_rate", vaenc->sample_rate, 0);
                av_opt_set_sample_fmt(vaswr, "out_sample_fmt", vaenc->sample_fmt, 0);
                if ((ret = swr_init(vaswr)) < 0) goto fail;
            }
#ifdef HAVE_AVFILTER
            /* 视频任务变速同样走 atempo（音调不变，替代丢样本） */
            if (o->speed != 1.0 && o->speed > 0.01) {
                const char *dfmt = av_get_sample_fmt_name(vadec->sample_fmt);
                const char *efmt = av_get_sample_fmt_name(vaenc->sample_fmt);
                if (dfmt && efmt &&
                    tempo_init(&vatempo, o->speed, vadec->sample_rate,
                               vadec->ch_layout.nb_channels, &vadec->ch_layout, dfmt,
                               vaenc->sample_rate, vaenc->ch_layout.nb_channels, efmt) == 0) {
                    vuse_tempo = 1;
                    LOGI("video: use atempo speed=%.3f", o->speed);
                }
            }
#endif
            LOGI("video: audio re-encode for volume=%f sr=%d ch=%d", o->volume, vaenc->sample_rate, vaenc->ch_layout.nb_channels);
        } else {
            aout = avformat_new_stream(out_fmt, NULL);
            avcodec_parameters_copy(aout->codecpar, in_fmt->streams[aidx]->codecpar);
            aout->time_base = in_fmt->streams[aidx]->time_base;
            /* aac 音频可能带 ADTS 头（微信/部分手机视频），mp4/mkv 需要 raw AAC + ASC。
             * 初始化 aac_adtstoasc BSF，写包时对 ADTS 包自动转换。 */
            if (aout->codecpar->codec_id == AV_CODEC_ID_AAC) {
                const AVBitStreamFilter *f = av_bsf_get_by_name("aac_adtstoasc");
                if (f && av_bsf_alloc(f, &aac_bsf) == 0) {
                    avcodec_parameters_copy(aac_bsf->par_in, aout->codecpar);
                    if (av_bsf_init(aac_bsf) == 0) {
                        avcodec_parameters_copy(aout->codecpar, aac_bsf->par_out);
                    } else {
                        av_bsf_free(&aac_bsf);
                    }
                }
                LOGI("video: aac bsf %s", aac_bsf ? "ready" : "unavailable");
            }
        }
    }

    out_fmt->pb = open_output_avio_pb(output);
    if (!out_fmt->pb) { ret = AVERROR(ENOENT); goto fail; }
    LOGI("video: output opened");
    if ((ret = avformat_write_header(out_fmt, NULL)) < 0) {
        /* matroska/avi 对部分构建的 mpeg4 extradata 解析失败（Invalid data）。
         * 清空 extradata 重试一次（matroska 的 mpeg4 不强制 VOL；avi 的 mpeg4 常见无 VOL）。 */
        if ((strstr(output, ".mkv") || strstr(output, ".avi")) && vout->codecpar->codec_id == AV_CODEC_ID_MPEG4 &&
            vout->codecpar->extradata_size > 0) {
            av_freep(&vout->codecpar->extradata);
            vout->codecpar->extradata_size = 0;
            LOGI("video: retry write_header without mpeg4 extradata");
            ret = avformat_write_header(out_fmt, NULL);
        }
        if (ret < 0) {
            LOGE("video: write_header failed %s (vcodec=%s vtag=%d aout=%s)",
                 av_err2str(ret),
                 vout->codecpar->codec_id ? avcodec_get_name(vout->codecpar->codec_id) : "none",
                 vout->codecpar->codec_tag,
                 aout ? avcodec_get_name(aout->codecpar->codec_id) : "none");
            goto fail;
        }
    }
    LOGI("video: header written");

    sws = sws_getContext(vdec_ctx->width, vdec_ctx->height, vdec_ctx->pix_fmt,
                         rot_w, rot_h, AV_PIX_FMT_YUV420P,
                         SWS_BILINEAR, NULL, NULL, NULL);
    if (!sws) { ret = -1; goto fail; }

    pkt = av_packet_alloc();
    frame = av_frame_alloc();
    out_frame = av_frame_alloc();
    out_frame->format = AV_PIX_FMT_YUV420P;
    out_frame->width = venc_ctx->width;
    out_frame->height = venc_ctx->height;
    av_frame_get_buffer(out_frame, 0);
    AVFrame *rot_src = NULL;
    if (phys_rotate) {
        /* 旋转中间帧：sws 先缩放到原始方向，再旋转到输出方向 */
        rot_src = av_frame_alloc();
        rot_src->format = AV_PIX_FMT_YUV420P;
        rot_src->width = rot_w;
        rot_src->height = rot_h;
        av_frame_get_buffer(rot_src, 0);
    }

    AVRational in_tb = in_fmt->streams[vidx]->time_base;
    int audio_done = !has_audio;
    int64_t vpts_cnt = 0;   /* 帧计数：pts 为 NOPTS 时兜底 */

    while (av_read_frame(in_fmt, pkt) >= 0) {
        if ((ret = task_check(handle)) < 0) { result = ret; break; }
        if (pkt->stream_index == vidx) {
            ret = avcodec_send_packet(vdec_ctx, pkt);
            while (ret >= 0) {
                ret = avcodec_receive_frame(vdec_ctx, frame);
                if (ret == AVERROR(EAGAIN) || ret == AVERROR_EOF) break;
                if (ret < 0) { result = ret; break; }
                int past_end = 0;
                int64_t pts_us = av_rescale_q(frame->pts, in_tb, AV_TIME_BASE_Q);
                if (!frame_in_window(pts_us, o, &past_end)) {
                    if (past_end) { result = 1; break; }
                    continue;
                }
                if (phys_rotate) {
                    sws_scale(sws, (const uint8_t *const *)frame->data, frame->linesize,
                              0, vdec_ctx->height, rot_src->data, rot_src->linesize);
                    int cw = (rotation == 90);
                    rotate_plane(out_frame->data[0], rot_src->data[0], rot_w, rot_h,
                                 out_frame->linesize[0], rot_src->linesize[0], cw);
                    rotate_plane(out_frame->data[1], rot_src->data[1], rot_w / 2, rot_h / 2,
                                 out_frame->linesize[1], rot_src->linesize[1], cw);
                    rotate_plane(out_frame->data[2], rot_src->data[2], rot_w / 2, rot_h / 2,
                                 out_frame->linesize[2], rot_src->linesize[2], cw);
                } else {
                    sws_scale(sws, (const uint8_t *const *)frame->data, frame->linesize,
                              0, vdec_ctx->height, out_frame->data, out_frame->linesize);
                }
                int64_t pts = frame->pts;
                if (pts == AV_NOPTS_VALUE) pts = vpts_cnt;
                /* 修复慢放/时间不对位：mpeg4 编码器 time_base 被缩放到 1/15360
                 * （输入流常见 1/90000），pts 必须从输入 tb rescale 到编码器 tb，
                 * 否则每帧时间被放大 90000/15360≈5.86 倍 → 输出慢放、时长对不上。
                 * 此前只有裁剪窗口分支做了 rescale，无裁剪/仅倍速路径直接用了原始 pts。 */
                if (o->n_windows > 0) {
                    /* 多区间裁剪：输出时间重映射为连续（区间内偏移 + 前面区间总长） */
                    int64_t f_us = av_rescale_q(frame->pts, in_tb, AV_TIME_BASE_Q);
                    int64_t out_us = f_us;
                    for (int w = 0; w < o->n_windows; w++) {
                        int64_t ss = o->ss_list[w], to = o->to_list[w];
                        if ((ss <= 0 || f_us >= ss) && (to <= 0 || f_us <= to)) {
                            out_us = (f_us - (ss > 0 ? ss : 0)) + o->win_offsets[w];
                            break;
                        }
                    }
                    if (o->speed != 1.0) out_us = (int64_t)(out_us / o->speed);
                    pts = av_rescale_q(out_us, AV_TIME_BASE_Q, venc_ctx->time_base);
                } else {
                    pts = av_rescale_q(pts, in_tb, venc_ctx->time_base);
                    if (o->speed != 1.0) pts = (int64_t)(pts / o->speed);
                }
                out_frame->pts = pts;
                vpts_cnt++;
                ret = avcodec_send_frame(venc_ctx, out_frame);
                if (ret == AVERROR(EAGAIN)) {
                    while (avcodec_receive_packet(venc_ctx, pkt) >= 0) {
                        av_packet_rescale_ts(pkt, venc_ctx->time_base, vout->time_base);
                        pkt->stream_index = vout->index;
                        av_interleaved_write_frame(out_fmt, pkt);
                        av_packet_unref(pkt);
                    }
                    ret = avcodec_send_frame(venc_ctx, out_frame);
                }
                if (ret < 0) { LOGE("video: send_frame v=%d (0x%x)", ret, (unsigned)(-ret)); result = ret; break; }
                while ((ret = avcodec_receive_packet(venc_ctx, pkt)) >= 0) {
                    av_packet_rescale_ts(pkt, venc_ctx->time_base, vout->time_base);
                    pkt->stream_index = vout->index;
                    av_interleaved_write_frame(out_fmt, pkt);
                    av_packet_unref(pkt);
                }
                if (in_fmt->duration > 0) {
                    int pct = (int)((double)pts_us / in_fmt->duration * 100);
                    report_progress(handle, pct < 0 ? 0 : (pct > 99 ? 99 : pct));
                }
                if (ret < 0 && ret != AVERROR(EAGAIN)) { result = ret; break; }
            }
            if (result != 0) break;
        } else if (has_audio && pkt->stream_index == aidx) {
            if (o->volume != 1.0 || o->audio_mode != 0 || o->speed != 1.0) {
                /* 音量调整/变速/强制重编码：解码 → 裁剪窗口 → 调音量（含变速）→ 重编码 AAC */
                ret = avcodec_send_packet(vadec, pkt);
                while (ret >= 0) {
                    ret = avcodec_receive_frame(vadec, frame);
                    if (ret == AVERROR(EAGAIN) || ret == AVERROR_EOF) break;
                    if (ret < 0) { result = ret; break; }
                    int past_end = 0;
                    int64_t pts_us = av_rescale_q(frame->pts, in_fmt->streams[aidx]->time_base, AV_TIME_BASE_Q);
                    if (!frame_in_window(pts_us, o, &past_end)) { if (past_end) audio_done = 1; continue; }
#ifdef HAVE_AVFILTER
                    if (vuse_tempo) {
                        /* atempo 路径：原帧送入变速图，输出已对齐 vaenc 格式 */
                        ret = tempo_send(&vatempo, frame);
                        if (ret < 0 && ret != AVERROR(EAGAIN)) { result = ret; break; }
                        AVFrame *tf = av_frame_alloc();
                        if (!tf) { result = AVERROR(ENOMEM); break; }
                        while (tempo_recv(&vatempo, tf) == 0) {
                            apply_volume(tf, o->volume);
                            av_audio_fifo_write(vafifo, (void **)tf->data, tf->nb_samples);
                            av_frame_unref(tf);
                        }
                        av_frame_free(&tf);
                    } else
#endif
                    /* 直通直接用 frame，避免 vaenc 强转 sample_fmt 与 frame 内容不一致产生杂音 */
                    if (vaswr) {
                        AVFrame *tmp = av_frame_alloc();
                        tmp->format = vaenc->sample_fmt;
                        tmp->sample_rate = vaenc->sample_rate;
                        av_channel_layout_copy(&tmp->ch_layout, &vaenc->ch_layout);
                        tmp->nb_samples = (int)av_rescale_rnd(
                            swr_get_delay(vaswr, vadec->sample_rate) + frame->nb_samples,
                            vaenc->sample_rate, vadec->sample_rate, AV_ROUND_UP);
                        av_frame_get_buffer(tmp, 0);
                        int c = swr_convert(vaswr, tmp->data, tmp->nb_samples,
                                            (const uint8_t **)frame->data, frame->nb_samples);
                        if (c < 0) { result = c; av_frame_free(&tmp); break; }
                        tmp->nb_samples = c;
                        apply_volume(tmp, o->volume);
                        fifo_write_audio(vafifo, tmp, tmp->nb_samples, o->speed, &va_speed_in, &va_speed_out);
                        av_frame_free(&tmp);
                    } else {
                        apply_volume(frame, o->volume);
                        fifo_write_audio(vafifo, frame, frame->nb_samples, o->speed, &va_speed_in, &va_speed_out);
                    }
                    /* FIFO 分块送 AAC 编码器 */
                    AVFrame *aof = av_frame_alloc();
                    aof->format = vaenc->sample_fmt;
                    aof->sample_rate = vaenc->sample_rate;
                    av_channel_layout_copy(&aof->ch_layout, &vaenc->ch_layout);
                    aof->nb_samples = vaout_frame_size;
                    av_frame_get_buffer(aof, 0);
                    while (av_audio_fifo_size(vafifo) >= vaout_frame_size) {
                        if ((ret = task_check(handle)) < 0) { result = ret; break; }
                        av_audio_fifo_read(vafifo, (void **)aof->data, vaout_frame_size);
                        aof->pts = va_pts;
                        va_pts += vaout_frame_size;
                        ret = avcodec_send_frame(vaenc, aof);
                        if (ret == AVERROR(EAGAIN)) {
                            while (avcodec_receive_packet(vaenc, pkt) >= 0) {
                                pkt->stream_index = aout->index;
                                av_packet_rescale_ts(pkt, vaenc->time_base, aout->time_base);
                                av_interleaved_write_frame(out_fmt, pkt);
                                av_packet_unref(pkt);
                            }
                            ret = avcodec_send_frame(vaenc, aof);
                        }
                        if (ret < 0) { result = ret; break; }
                        while ((ret = avcodec_receive_packet(vaenc, pkt)) >= 0) {
                            pkt->stream_index = aout->index;
                            av_packet_rescale_ts(pkt, vaenc->time_base, aout->time_base);
                            av_interleaved_write_frame(out_fmt, pkt);
                            av_packet_unref(pkt);
                        }
                    }
                    av_frame_free(&aof);
                    if (result < 0) break;
                }
                if (result < 0) break;
            } else {
                /* 流拷贝路径（裁剪窗口 + speed pts + ADTS BSF） */
                AVPacket *cpkt = av_packet_clone(pkt);
                int64_t pkt_us = av_rescale_q(cpkt->pts, in_fmt->streams[aidx]->time_base, AV_TIME_BASE_Q);
                int past_end = 0;
                if (!frame_in_window(pkt_us, o, &past_end)) {
                    av_packet_free(&cpkt);
                    if (past_end) audio_done = 1;
                } else {
                    /* 修复音画不对位：视频帧 pts 已重映射为
                     * (f_us - ss) + win_offset（从 0 开始连续），但音频直通包
                     * pts 未做同样重映射 → 音频从 ss 开始、视频从 0 开始，
                     * 播放时音画错位（task#26 TRIM 11.9s~19.08s 实测）。
                     * 此处对音频包做同样的窗口偏移重映射。 */
                    int64_t out_us = pkt_us;
                    for (int w = 0; w < o->n_windows; w++) {
                        int64_t ss = o->ss_list[w], to = o->to_list[w];
                        if ((ss <= 0 || pkt_us >= ss) && (to <= 0 || pkt_us <= to)) {
                            out_us = (pkt_us - (ss > 0 ? ss : 0)) + o->win_offsets[w];
                            break;
                        }
                    }
                    int64_t pts = av_rescale_q(out_us, AV_TIME_BASE_Q,
                                               in_fmt->streams[aidx]->time_base);
                    if (o->speed != 1.0) pts = (int64_t)(pts / o->speed);
                    cpkt->pts = pts;
                    cpkt->dts = pts;
                    av_packet_rescale_ts(cpkt, in_fmt->streams[aidx]->time_base, aout->time_base);
                    cpkt->stream_index = aout->index;
                    /* ADTS 头检测（0xFFF 起始）：带头的 aac 必须经 BSF 转 raw，否则写 mp4/mkv 失败 */
                    int is_adts = (cpkt->size >= 2 && cpkt->data[0] == 0xFF && (cpkt->data[1] & 0xF0) == 0xF0);
                    if (is_adts && aac_bsf) {
                        if (av_bsf_send_packet(aac_bsf, cpkt) >= 0) {
                            av_packet_free(&cpkt);
                            while (av_bsf_receive_packet(aac_bsf, cpkt) >= 0) {
                                cpkt->stream_index = aout->index;
                                av_interleaved_write_frame(out_fmt, cpkt);
                                av_packet_unref(cpkt);
                            }
                            av_packet_free(&cpkt);
                            cpkt = NULL;
                        }
                    }
                    if (cpkt) {
                        av_interleaved_write_frame(out_fmt, cpkt);
                        av_packet_free(&cpkt);
                    }
                }
            }
            if (audio_done && result == 0) { /* 音频已到终点 */ }
        }
        av_packet_unref(pkt);
    }
    if (result == 1) result = 0;
    if (result >= 0) {
        avcodec_send_frame(venc_ctx, NULL);
        while (avcodec_receive_packet(venc_ctx, pkt) >= 0) {
            av_packet_rescale_ts(pkt, venc_ctx->time_base, vout->time_base);
            pkt->stream_index = vout->index;
            av_interleaved_write_frame(out_fmt, pkt);
            av_packet_unref(pkt);
        }
    }
    /* 音频重编码管线 flush（视频音量调整/变速)  */
    if (result >= 0 && (o->volume != 1.0 || o->audio_mode != 0 || o->speed != 1.0) && vafifo) {
        avcodec_send_packet(vadec, NULL);
        while (avcodec_receive_frame(vadec, frame) >= 0) {
            int past_end = 0;
            int64_t pts_us = av_rescale_q(frame->pts, in_fmt->streams[aidx]->time_base, AV_TIME_BASE_Q);
            if (!frame_in_window(pts_us, o, &past_end)) continue;
#ifdef HAVE_AVFILTER
            if (vuse_tempo) {
                ret = tempo_send(&vatempo, frame);
                if (ret < 0 && ret != AVERROR(EAGAIN)) { result = ret; break; }
                AVFrame *tf = av_frame_alloc();
                if (!tf) { result = AVERROR(ENOMEM); break; }
                while (tempo_recv(&vatempo, tf) == 0) {
                    apply_volume(tf, o->volume);
                    av_audio_fifo_write(vafifo, (void **)tf->data, tf->nb_samples);
                    av_frame_unref(tf);
                }
                av_frame_free(&tf);
            } else
#endif
            /* flush 同样分离 swr/直通 */
            if (vaswr) {
                AVFrame *tmp = av_frame_alloc();
                tmp->format = vaenc->sample_fmt;
                tmp->sample_rate = vaenc->sample_rate;
                av_channel_layout_copy(&tmp->ch_layout, &vaenc->ch_layout);
                tmp->nb_samples = (int)av_rescale_rnd(
                    swr_get_delay(vaswr, vadec->sample_rate) + frame->nb_samples,
                    vaenc->sample_rate, vadec->sample_rate, AV_ROUND_UP);
                av_frame_get_buffer(tmp, 0);
                int c = swr_convert(vaswr, tmp->data, tmp->nb_samples,
                                    (const uint8_t **)frame->data, frame->nb_samples);
                if (c < 0) { result = c; av_frame_free(&tmp); break; }
                tmp->nb_samples = c;
                apply_volume(tmp, o->volume);
                fifo_write_audio(vafifo, tmp, tmp->nb_samples, o->speed, &va_speed_in, &va_speed_out);
                av_frame_free(&tmp);
            } else {
                apply_volume(frame, o->volume);
                fifo_write_audio(vafifo, frame, frame->nb_samples, o->speed, &va_speed_in, &va_speed_out);
            }
        }
#ifdef HAVE_AVFILTER
        if (vuse_tempo) {
            tempo_send(&vatempo, NULL);
            AVFrame *tf = av_frame_alloc();
            if (tf) {
                while (tempo_recv(&vatempo, tf) == 0) {
                    apply_volume(tf, o->volume);
                    av_audio_fifo_write(vafifo, (void **)tf->data, tf->nb_samples);
                    av_frame_unref(tf);
                }
                av_frame_free(&tf);
            }
        }
#endif
        int bytes_ps = av_get_bytes_per_sample(vaenc->sample_fmt);
        while (result >= 0 && av_audio_fifo_size(vafifo) > 0) {
            int rem = av_audio_fifo_size(vafifo);
            int got = rem < vaout_frame_size ? rem : vaout_frame_size;
            AVFrame *aof = av_frame_alloc();
            aof->format = vaenc->sample_fmt;
            aof->sample_rate = vaenc->sample_rate;
            av_channel_layout_copy(&aof->ch_layout, &vaenc->ch_layout);
            aof->nb_samples = vaout_frame_size;
            av_frame_get_buffer(aof, 0);
            av_audio_fifo_read(vafifo, (void **)aof->data, got);
            if (av_sample_fmt_is_planar(vaenc->sample_fmt)) {
                for (int ch = 0; ch < vaenc->ch_layout.nb_channels; ch++)
                    memset(aof->data[ch] + got * bytes_ps, 0, (vaout_frame_size - got) * bytes_ps);
            } else {
                memset(aof->data[0] + got * bytes_ps * vaenc->ch_layout.nb_channels, 0,
                       (vaout_frame_size - got) * bytes_ps * vaenc->ch_layout.nb_channels);
            }
            aof->pts = va_pts;
            va_pts += vaout_frame_size;
            ret = avcodec_send_frame(vaenc, aof);
            if (ret >= 0) {
                while (avcodec_receive_packet(vaenc, pkt) >= 0) {
                    pkt->stream_index = aout->index;
                    av_packet_rescale_ts(pkt, vaenc->time_base, aout->time_base);
                    av_interleaved_write_frame(out_fmt, pkt);
                    av_packet_unref(pkt);
                }
            } else { result = ret; }
            av_frame_free(&aof);
        }
        if (result >= 0) {
            avcodec_send_frame(vaenc, NULL);
            while (avcodec_receive_packet(vaenc, pkt) >= 0) {
                pkt->stream_index = aout->index;
                av_packet_rescale_ts(pkt, vaenc->time_base, aout->time_base);
                av_interleaved_write_frame(out_fmt, pkt);
                av_packet_unref(pkt);
            }
        }
    }
    av_write_trailer(out_fmt);

fail:
    if (result < 0 || (ret < 0 && ret != AVERROR(EAGAIN) && ret != AVERROR(EOF)))
        LOGE("%s failed: ret=%d (%s) result=%d", __func__, ret, av_err2str(ret), result);
    else
        LOGI("%s done: result=%d", __func__, result);
    close_output_avio(&out_fmt);
    close_input_avio(&in_fmt);
    if (vdec_ctx) avcodec_free_context(&vdec_ctx);
    if (venc_ctx) avcodec_free_context(&venc_ctx);
    if (adec_ctx) avcodec_free_context(&adec_ctx);
    if (aac_bsf) av_bsf_free(&aac_bsf);
    if (vadec) avcodec_free_context(&vadec);
    if (vaenc) avcodec_free_context(&vaenc);
    if (vaswr) swr_free(&vaswr);
    if (vafifo) av_audio_fifo_free(vafifo);
#ifdef HAVE_AVFILTER
    if (vuse_tempo) tempo_free(&vatempo);
#endif
    if (sws) sws_freeContext(sws);
    if (pkt) av_packet_free(&pkt);
    if (frame) av_frame_free(&frame);
    if (out_frame) av_frame_free(&out_frame);
    if (rot_src) av_frame_free(&rot_src);
    task_reset(handle);
    return result < 0 ? result : (ret < 0 && ret != AVERROR(EAGAIN) && ret != AVERROR(EOF) ? ret : 0);
}

/* =========================================================
 *  GIF CONVERSION (fixed fps, palette)
 * ========================================================= */
/* =========================================================
 *  自适应 GIF 调色板
 *  固定 6x6x6 立方量化有 +-25 色阶误差（中间调明显偏色）。
 *  改为：先均匀采样视频帧统计 4-4-4 RGB 直方图，用 popularity
 *  取前 256 色作为调色板，再建 4096->256 最近色查找表。
 * ========================================================= */
static void gif_hist_add_rgb24(const uint8_t *src, int ls, int w, int h, uint32_t *hist) {
    for (int y = 0; y < h; y++) {
        const uint8_t *s = src + (size_t)y * ls;
        for (int x = 0; x < w; x++, s += 3) {
            int idx = ((s[0] >> 4) << 8) | ((s[1] >> 4) << 4) | (s[2] >> 4);
            hist[idx]++;
        }
    }
}

typedef struct { int idx; uint32_t cnt; } GifBucket;
static int gif_bucket_cmp(const void *a, const void *b) {
    const GifBucket *x = (const GifBucket *)a, *y = (const GifBucket *)b;
    if (x->cnt < y->cnt) return 1;
    if (x->cnt > y->cnt) return -1;
    return 0;
}

static void gif_lut_nearest(const uint32_t *pal, int m, uint8_t *lut) {
    for (int bi = 0; bi < 4096; bi++) {
        int br = ((bi >> 8) & 0xF) * 17, bg = ((bi >> 4) & 0xF) * 17, bb = (bi & 0xF) * 17;
        int best = 0; long bestd = -1;
        for (int q = 0; q < m; q++) {
            int pr = (pal[q] >> 16) & 0xFF, pg = (pal[q] >> 8) & 0xFF, pb = pal[q] & 0xFF;
            long d = (long)(br - pr) * (br - pr) + (long)(bg - pg) * (bg - pg) + (long)(bb - pb) * (bb - pb);
            if (bestd < 0 || d < bestd) { bestd = d; best = q; }
        }
        lut[bi] = (uint8_t)best;
    }
}

/* 由直方图生成 256 色调色板 + 查找表；返回实际颜色数（0=直方图为空） */
static int gif_build_adaptive_palette(uint32_t *pal, uint8_t *lut, const uint32_t *hist) {
    static GifBucket bk[4096];
    int n = 0;
    for (int i = 0; i < 4096; i++) if (hist[i]) { bk[n].idx = i; bk[n].cnt = hist[i]; n++; }
    if (n == 0) return 0;
    qsort(bk, n, sizeof(GifBucket), gif_bucket_cmp);
    int m = n < 256 ? n : 256, i;
    for (i = 0; i < m; i++) {
        int idx = bk[i].idx;
        int r = ((idx >> 8) & 0xF) * 17, g = ((idx >> 4) & 0xF) * 17, b = (idx & 0xF) * 17;
        pal[i] = 0xFF000000u | ((uint32_t)r << 16) | ((uint32_t)g << 8) | (uint32_t)b;
    }
    int rem = 256 - m;
    for (; i < 256; i++) {
        int v = rem > 0 ? (i - m) * 255 / rem : 0;
        pal[i] = 0xFF000000u | ((uint32_t)v << 16) | ((uint32_t)v << 8) | (uint32_t)v;
    }
    gif_lut_nearest(pal, m, lut);
    return m;
}

/* RGB24 -> PAL8 索引 + Floyd-Steinberg 误差扩散抖动。
 * 修复「平缓渐变被量化成一整块纯色」：4-4-4 桶每通道只有 16 级，
 * 跨度很小的渐变会整片落进同一个桶 -> 同一索引 -> 纯色块；
 * 误差扩散把量化残差撒给相邻像素，渐变成平滑抖动。 */
static void gif_quantize_pal8_dither(uint8_t *src, int src_ls,
                                     uint8_t *dst, int dst_ls, int w, int h,
                                     const uint8_t *lut, const uint32_t *pal) {
    static const int fx[3] = { 1, -1, 0 };
    static const int fy[3] = { 0, 1, 1 };
    static const int fw[3] = { 7, 3, 5 };
    for (int y = 0; y < h; y++) {
        uint8_t *s = src + (size_t)y * src_ls;
        uint8_t *d = dst + (size_t)y * dst_ls;
        for (int x = 0; x < w; x++) {
            uint8_t *px = s + (size_t)x * 3;
            int r = px[0], g = px[1], b = px[2];
            int bi = ((r >> 4) << 8) | ((g >> 4) << 4) | (b >> 4);
            int pi = lut[bi];
            d[x] = (uint8_t)pi;
            uint32_t c = pal[pi];
            int er = r - (int)((c >> 16) & 0xFF);
            int eg = g - (int)((c >> 8) & 0xFF);
            int eb = b - (int)(c & 0xFF);
            for (int k = 0; k < 3; k++) {
                int nx = x + fx[k], ny = y + fy[k];
                if (nx < 0 || nx >= w || ny >= h) continue;
                uint8_t *np = src + (size_t)ny * src_ls + (size_t)nx * 3;
                int nr = (int)np[0] + er * fw[k] / 16;
                int ng = (int)np[1] + eg * fw[k] / 16;
                int nb = (int)np[2] + eb * fw[k] / 16;
                np[0] = (uint8_t)(nr < 0 ? 0 : (nr > 255 ? 255 : nr));
                np[1] = (uint8_t)(ng < 0 ? 0 : (ng > 255 ? 255 : ng));
                np[2] = (uint8_t)(nb < 0 ? 0 : (nb > 255 ? 255 : nb));
            }
        }
    }
}

/* 第一趟：均匀采样视频帧累积直方图（小尺寸 160px，每 10 帧取 1 帧） */
static void gif_sample_histogram(const char *input, int handle, uint32_t *hist) {
    AVFormatContext *in_fmt = NULL;
    AVCodecContext *dec = NULL;
    AVFrame *frame = NULL, *rgb = NULL;
    AVPacket *pkt = NULL;
    struct SwsContext *sws = NULL;
    int sw = 0, sh = 0, src_fmt = -1, fi = 0;

    if (open_input_avio(&in_fmt, input) < 0) return;
    avformat_find_stream_info(in_fmt, NULL);
    int vidx = find_stream(in_fmt, AVMEDIA_TYPE_VIDEO);
    if (vidx < 0) goto samp_done;
    AVCodecParameters *par = in_fmt->streams[vidx]->codecpar;
    const AVCodec *d = avcodec_find_decoder(par->codec_id);
    if (!d) goto samp_done;
    dec = avcodec_alloc_context3(d);
    if (!dec || avcodec_parameters_to_context(dec, par) < 0) goto samp_done;
    if (avcodec_open2(dec, d, NULL) < 0) goto samp_done;
    if (dec->width <= 0 || dec->height <= 0) goto samp_done;
    if (dec->width >= dec->height) { sw = 160; sh = (int)((double)dec->height * 160.0 / dec->width); }
    else                           { sh = 160; sw = (int)((double)dec->width * 160.0 / dec->height); }
    sw &= ~1; sh &= ~1;
    if (sw < 2) sw = 2;
    if (sh < 2) sh = 2;

    frame = av_frame_alloc();
    rgb = av_frame_alloc();
    rgb->format = AV_PIX_FMT_RGB24;
    rgb->width = sw; rgb->height = sh;
    if (av_frame_get_buffer(rgb, 0) < 0) goto samp_done;
    pkt = av_packet_alloc();

    while (av_read_frame(in_fmt, pkt) >= 0) {
        if (task_check(handle) < 0) { av_packet_unref(pkt); break; }
        if (pkt->stream_index != vidx) { av_packet_unref(pkt); continue; }
        if (avcodec_send_packet(dec, pkt) >= 0) {
            while (avcodec_receive_frame(dec, frame) >= 0) {
                if (src_fmt != frame->format) {
                    if (sws) sws_freeContext(sws);
                    sws = sws_getContext(dec->width, dec->height, frame->format,
                                         sw, sh, AV_PIX_FMT_RGB24, SWS_BILINEAR, NULL, NULL, NULL);
                    src_fmt = frame->format;
                }
                if (sws && (fi % 10) == 0) {
                    sws_scale(sws, (const uint8_t *const *)frame->data, frame->linesize,
                              0, dec->height, rgb->data, rgb->linesize);
                    gif_hist_add_rgb24(rgb->data[0], rgb->linesize[0], sw, sh, hist);
                }
                fi++;
            }
        }
        av_packet_unref(pkt);
    }
    LOGI("gif: histogram sampled from %d frames", fi);

samp_done:
    if (sws) sws_freeContext(sws);
    if (frame) av_frame_free(&frame);
    if (rgb) av_frame_free(&rgb);
    if (pkt) av_packet_free(&pkt);
    if (dec) avcodec_free_context(&dec);
    close_input_avio(&in_fmt);
}

static int do_gif_convert(const char *input, const char *output, const Opts *o, int handle) {
    AVFormatContext *in_fmt = NULL, *out_fmt = NULL;
    AVCodecContext *vdec_ctx = NULL, *enc_ctx = NULL;
    AVPacket *pkt = NULL;
    AVFrame *frame = NULL, *out_frame = NULL;
    AVFrame *rgb_frame = NULL, *rot_src = NULL;   /* 提前声明（goto fail 安全） */
    struct SwsContext *sws = NULL;
    int ret = 0, result = 0, vidx = -1;
    AVStream *vout = NULL;
    const AVCodec *vencoder = NULL;

    if ((ret = open_input_avio(&in_fmt, input)) < 0) return ret;
    avformat_find_stream_info(in_fmt, NULL);
    vidx = find_stream(in_fmt, AVMEDIA_TYPE_VIDEO);
    if (vidx < 0) { ret = -1; goto fail; }

    AVCodecParameters *vin_par = in_fmt->streams[vidx]->codecpar;
    const AVCodec *vdecoder = avcodec_find_decoder(vin_par->codec_id);
    vdec_ctx = avcodec_alloc_context3(vdecoder);
    avcodec_parameters_to_context(vdec_ctx, vin_par);
    if ((ret = avcodec_open2(vdec_ctx, vdecoder, NULL)) < 0) goto fail;

    /* 修复 GIF 方向：读取旋转角，90°/270° 竖屏视频物理旋转输出
     * （此前 do_gif_convert 无 rotation 处理，竖屏视频转 GIF 是横的） */
    double rot = 0.0;
    uint8_t *disp = av_stream_get_side_data(in_fmt->streams[vidx], AV_PKT_DATA_DISPLAYMATRIX, NULL);
    if (disp) rot = av_display_rotation_get((const int32_t *)disp);
    else {
        AVDictionaryEntry *de = av_dict_get(in_fmt->streams[vidx]->metadata, "rotate", NULL, 0);
        if (de) rot = atoi(de->value);
    }
    int rotation = ((int)rot) % 360;
    if (rotation < 0) rotation += 360;
    int phys_rotate = (rotation == 90 || rotation == 270);
    /* GIF 尺寸限制（maxw/maxh，默认 480）——GIF 256 色无损格式，
     * 1080p 全分辨率体积巨大，图库渲染卡死像"不动"。
     * rot_w/rot_h = 缩放后的旋转前尺寸（sws 目标），out_w/out_h = 旋转后尺寸。 */
    int rot_w = vdec_ctx->width, rot_h = vdec_ctx->height;
    if (o->maxw > 0 && o->maxh > 0) calc_output_size(vdec_ctx->width, vdec_ctx->height, o, &rot_w, &rot_h);
    int out_w = rot_w, out_h = rot_h;
    if (phys_rotate) { int t = out_w; out_w = out_h; out_h = t; }
    LOGI("gif: rotation=%d out=%dx%d%s", rotation, out_w, out_h, phys_rotate ? " (phys rotate)" : "");

    vencoder = avcodec_find_encoder_by_name("gif");
    if (!vencoder) { LOGE("GIF encoder not found"); ret = AVERROR_ENCODER_NOT_FOUND; goto fail; }

    AVOutputFormat *oformat = av_guess_format("gif", output, NULL);
    avformat_alloc_output_context2(&out_fmt, oformat, NULL, output);
    vout = avformat_new_stream(out_fmt, vencoder);
    enc_ctx = avcodec_alloc_context3(vencoder);
    enc_ctx->width = out_w;
    enc_ctx->height = out_h;
    /* gif 编码器只接受 PAL8/RGB8，且 sws_scale 不支持输出调色板格式，
     * 故 sws 输出 RGB24 → 手动量化到 PAL8 索引，每帧随帧附带调色板。 */
    enc_ctx->pix_fmt = AV_PIX_FMT_PAL8;
    /* GIF time unit is fixed 1/100 s. Using {1, fps} made the gif
     * encoder interpret the pts gap in 1/100 units -> 100fps output. */
    enc_ctx->time_base = (AVRational){1, 100};
    enc_ctx->framerate = (AVRational){o->fps > 0 ? o->fps : 10, 1};
    if ((ret = avcodec_open2(enc_ctx, vencoder, NULL)) < 0) {
        LOGE("gif: encoder open failed: pix_fmt=%d w=%d h=%d tb=%d/%d err=%s",
            enc_ctx->pix_fmt, enc_ctx->width, enc_ctx->height,
            enc_ctx->time_base.num, enc_ctx->time_base.den, av_err2str(ret));
        goto fail;
    }
    avcodec_parameters_from_context(vout->codecpar, enc_ctx);
    vout->time_base = enc_ctx->time_base;

    out_fmt->pb = open_output_avio_pb(output);
    if (!out_fmt->pb) { ret = AVERROR(ENOENT); goto fail; }
    LOGI("video: output opened");
    if ((ret = avformat_write_header(out_fmt, NULL)) < 0) goto fail;
    LOGI("video: header written");

    /* sws 目标用 RGB24（标准支持），再量化到 PAL8（自建调色板） */
    sws = sws_getContext(vdec_ctx->width, vdec_ctx->height, vdec_ctx->pix_fmt,
                         rot_w, rot_h, AV_PIX_FMT_RGB24,
                         SWS_BILINEAR, NULL, NULL, NULL);
    if (!sws) { ret = -1; goto fail; }

    pkt = av_packet_alloc();
    frame = av_frame_alloc();
    out_frame = av_frame_alloc();
    out_frame->format = AV_PIX_FMT_PAL8;      /* 编码器输入：调色板索引 */
    out_frame->width = enc_ctx->width;
    out_frame->height = enc_ctx->height;
    av_frame_get_buffer(out_frame, 0);
    /* RGB24 中间帧（sws 输出 = 旋转前的缩放尺寸） */
    rgb_frame = av_frame_alloc();
    rgb_frame->format = AV_PIX_FMT_RGB24;
    rgb_frame->width = rot_w;
    rgb_frame->height = rot_h;
    av_frame_get_buffer(rgb_frame, 0);
    /* 旋转后 RGB24（尺寸 = 输出尺寸） */
    if (phys_rotate) {
        rot_src = av_frame_alloc();
        rot_src->format = AV_PIX_FMT_RGB24;
        rot_src->width = out_w;
        rot_src->height = out_h;
        av_frame_get_buffer(rot_src, 0);
    }
    /* adaptive palette (sample histogram first, fallback to fixed 6x6x6) */
    static uint32_t gif_palette[256];
    static uint8_t  gif_lut[4096];
    static uint32_t gif_hist[4096];
    memset(gif_hist, 0, sizeof(gif_hist));
    gif_sample_histogram(input, handle, gif_hist);
    if (gif_build_adaptive_palette(gif_palette, gif_lut, gif_hist) == 0) {
        gif_build_palette(gif_palette);
        gif_lut_nearest(gif_palette, 256, gif_lut);
        LOGI("gif: adaptive palette empty, fallback to fixed 6x6x6");
    }
    AVRational in_tb = in_fmt->streams[vidx]->time_base;
    int fps = o->fps > 0 ? o->fps : 10;
    if (fps < 1) fps = 1;
    if (fps > 60) fps = 60;
    /* sample by timestamp. The old integer skip=ceil(src_fps/fps) could
     * not reach arbitrary target fps (source 30fps picking 25fps became 15fps). */
    const double step_s = 1.0 / (double)fps;
    double next_take_s = 0.0;
    int64_t frame_idx = 0, out_idx = 0;

    while (av_read_frame(in_fmt, pkt) >= 0) {
        if ((ret = task_check(handle)) < 0) { result = ret; break; }
        if (pkt->stream_index != vidx) { av_packet_unref(pkt); continue; }
        ret = avcodec_send_packet(vdec_ctx, pkt);
        while (ret >= 0) {
            ret = avcodec_receive_frame(vdec_ctx, frame);
            if (ret == AVERROR(EAGAIN) || ret == AVERROR_EOF) break;
            if (ret < 0) { result = ret; break; }
            int past_end = 0;
            int64_t pts_us = av_rescale_q(frame->pts, in_tb, AV_TIME_BASE_Q);
            if (!frame_in_window(pts_us, o, &past_end)) {
                if (past_end) { result = 1; break; }
                frame_idx++;
                continue;
            }
            double cur_s = (double)pts_us / 1000000.0;
            if (cur_s + 1e-6 >= next_take_s) {
                next_take_s += step_s;
                /* sws → RGB24；旋转（可选）→ 量化到 PAL8 索引 */
                sws_scale(sws, (const uint8_t *const *)frame->data, frame->linesize,
                          0, vdec_ctx->height, rgb_frame->data, rgb_frame->linesize);
                /* must make_writable before encoding - the encoder refs
                 * the input frame for inter-frame delta; reusing one buffer makes
                 * every frame point at the same memory as 'last_frame', so the
                 * delta sees 'no change' -> only frame 1 survives (still GIF). */
                if (av_frame_make_writable(out_frame) < 0) { result = -1; break; }
                if (phys_rotate) {
                    rotate_rgb24(rot_src->data[0], rgb_frame->data[0], rot_w, rot_h,
                                 rot_src->linesize[0], rgb_frame->linesize[0], rotation == 90);
                    gif_quantize_pal8_dither(rot_src->data[0], rot_src->linesize[0],
                                          out_frame->data[0], out_frame->linesize[0],
                                          out_w, out_h, gif_lut, gif_palette);
                } else {
                    gif_quantize_pal8_dither(rgb_frame->data[0], rgb_frame->linesize[0],
                                          out_frame->data[0], out_frame->linesize[0],
                                          out_w, out_h, gif_lut, gif_palette);
                }
                /* PAL8 每帧需带调色板（data[1] = 256×32bit） */
                memcpy(out_frame->data[1], gif_palette, sizeof(gif_palette));
                /* pts in 1/100 s units */
                out_frame->pts = (int64_t)out_idx * 100 / fps;
                out_idx++;
                /* use a separate eret so the outer decode loop's ret
                 * is not clobbered (was causing premature loop exit / frame drop) */
                int eret = avcodec_send_frame(enc_ctx, out_frame);
                if (eret == AVERROR(EAGAIN)) {
                    while (avcodec_receive_packet(enc_ctx, pkt) >= 0) {
                        pkt->stream_index = vout->index;
                        av_interleaved_write_frame(out_fmt, pkt);
                        av_packet_unref(pkt);
                    }
                    eret = avcodec_send_frame(enc_ctx, out_frame);
                }
                if (eret < 0) { result = eret; break; }
                while (avcodec_receive_packet(enc_ctx, pkt) >= 0) {
                    pkt->stream_index = vout->index;
                    av_interleaved_write_frame(out_fmt, pkt);
                    av_packet_unref(pkt);
                }
                if (in_fmt->duration > 0) {
                    int pct = (int)((double)pts_us / in_fmt->duration * 100);
                    report_progress(handle, pct < 0 ? 0 : (pct > 99 ? 99 : pct));
                }
            }
            frame_idx++;
        }
        if (result != 0) break;
        av_packet_unref(pkt);
    }
    if (result == 1) result = 0;
    if (result >= 0) {
        avcodec_send_frame(enc_ctx, NULL);
        while (avcodec_receive_packet(enc_ctx, pkt) >= 0) {
            pkt->stream_index = vout->index;
            av_interleaved_write_frame(out_fmt, pkt);
            av_packet_unref(pkt);
        }
    }
    av_write_trailer(out_fmt);
    /* 输出帧数日志（验证 GIF 动画帧数，200KB≈单帧问题排查） */
    LOGI("gif: written %lld frames (fps=%d out=%dx%d)", (long long)out_idx, fps, out_w, out_h);

fail:
    if (result < 0 || (ret < 0 && ret != AVERROR(EAGAIN) && ret != AVERROR(EOF)))
        LOGE("%s failed: ret=%d (%s) result=%d", __func__, ret, av_err2str(ret), result);
    else
        LOGI("%s done: result=%d", __func__, result);
    close_output_avio(&out_fmt);
    close_input_avio(&in_fmt);
    if (vdec_ctx) avcodec_free_context(&vdec_ctx);
    if (enc_ctx) avcodec_free_context(&enc_ctx);
    if (sws) sws_freeContext(sws);
    if (pkt) av_packet_free(&pkt);
    if (frame) av_frame_free(&frame);
    if (out_frame) av_frame_free(&out_frame);
    if (rgb_frame) av_frame_free(&rgb_frame);
    if (rot_src) av_frame_free(&rot_src);
    task_reset(handle);
    return result < 0 ? result : (ret < 0 && ret != AVERROR(EAGAIN) && ret != AVERROR(EOF) ? ret : 0);
}

/* =========================================================
 *  CONCAT (multi-input → H.264 + AAC)
 * ========================================================= */
/* ===== 同格式拼接：按包 remux（零重编码 → 无损，体积 ≈ 各输入之和） =====
 * 仅在调用方确认「全部输入为 mp4/mov、视频编码与分辨率一致、音频编码一致或无音轨、
 * 且无旋转角」时使用；其余情况由调用方回退到重编码。
 * 返回 0 = 成功；<0 = 失败（调用方回退）。 */
static int concat_copy(const char **inputs, int n_inputs, const char *output, int handle) {
    AVFormatContext *out_fmt = NULL, *in_fmt = NULL, *cur = NULL;
    AVPacket *pkt = NULL;
    int ret = -1;

    AVOutputFormat *oformat = av_guess_format("mp4", output, NULL);
    if (!oformat) return -1;
    if (avformat_alloc_output_context2(&out_fmt, oformat, NULL, output) < 0 || !out_fmt) return -1;

    if (open_input_avio(&in_fmt, inputs[0]) < 0) { avformat_free_context(out_fmt); return -1; }
    avformat_find_stream_info(in_fmt, NULL);
    int fv = find_stream(in_fmt, AVMEDIA_TYPE_VIDEO);
    int fa = find_stream(in_fmt, AVMEDIA_TYPE_AUDIO);
    if (fv < 0) goto cc_out;

    AVStream *vout = avformat_new_stream(out_fmt, NULL);
    if (!vout) goto cc_out;
    if (avcodec_parameters_copy(vout->codecpar, in_fmt->streams[fv]->codecpar) < 0) goto cc_out;
    vout->codecpar->codec_tag = 0;
    vout->time_base = in_fmt->streams[fv]->time_base;
    if (in_fmt->streams[fv]->avg_frame_rate.num > 0)
        vout->avg_frame_rate = in_fmt->streams[fv]->avg_frame_rate;
    /* 旋转信息（display matrix）原样带到输出流：竖屏/180° 素材流复制后方向才不会丢 */
    {
        uint8_t *dsp = av_stream_get_side_data(in_fmt->streams[fv], AV_PKT_DATA_DISPLAYMATRIX, NULL);
        if (dsp) {
            uint8_t *nd = av_stream_new_side_data(vout, AV_PKT_DATA_DISPLAYMATRIX, 36);
            if (nd) memcpy(nd, dsp, 36);
        }
    }

    AVStream *aout = NULL;
    if (fa >= 0) {
        aout = avformat_new_stream(out_fmt, NULL);
        if (!aout) goto cc_out;
        if (avcodec_parameters_copy(aout->codecpar, in_fmt->streams[fa]->codecpar) < 0) goto cc_out;
        aout->codecpar->codec_tag = 0;
        aout->time_base = in_fmt->streams[fa]->time_base;
    }

    out_fmt->pb = open_output_avio_pb(output);
    if (!out_fmt->pb) goto cc_out;
    if (avformat_write_header(out_fmt, NULL) < 0) { LOGE("concat_copy: write_header failed"); goto cc_out; }

    pkt = av_packet_alloc();
    if (!pkt) goto cc_out;

    /* 时间戳：段内相对关系保留，段与段之间严格接续。
     * mp4 muxer 没有 AVFMT_TS_NONSTRICT —— dts 只要不严格递增就直接 EINVAL(-22)
     * （见 libavformat/mux.c prepare_input_packet）。之前用 vs->duration 预估段长
     * 会偏小（不含最后一帧时长），第二段首个包的 dts 撞上第一段末尾 → -22 →
     * 回退重编码 → 画面糊成块。现改为记录「实际写出的最后一个包的 dts + duration」。
     * v_next/a_next = 下一段起点；INT64_MIN 表示尚未确定（第一段保持原样不平移）。 */
    int64_t v_next = INT64_MIN, a_next = INT64_MIN;
    for (int i = 0; i < n_inputs; i++) {
        if (task_check(handle) < 0) goto cc_out;
        AVFormatContext *in;
        if (i == 0) {
            in = in_fmt;
        } else {
            if (open_input_avio(&in, inputs[i]) < 0) goto cc_out;
            avformat_find_stream_info(in, NULL);
            cur = in;
        }
        int vi = find_stream(in, AVMEDIA_TYPE_VIDEO);
        int ai = find_stream(in, AVMEDIA_TYPE_AUDIO);
        AVStream *vs = vi >= 0 ? in->streams[vi] : NULL;
        AVStream *as = ai >= 0 ? in->streams[ai] : NULL;
        int64_t v_base = INT64_MIN, a_base = INT64_MIN;  /* 本段首个包的 dts（缩放后） */
        int64_t v_end = INT64_MIN, a_end = INT64_MIN;    /* 本段结束点（下一段起点） */
        while (av_read_frame(in, pkt) >= 0) {
            if (task_check(handle) < 0) { av_packet_unref(pkt); goto cc_out; }
            int wr = 0;
            if (vs && pkt->stream_index == vi) {
                av_packet_rescale_ts(pkt, vs->time_base, vout->time_base);
                pkt->stream_index = vout->index;
                if (v_base == INT64_MIN) {
                    v_base = (pkt->dts != AV_NOPTS_VALUE) ? pkt->dts : 0;
                    if (v_next == INT64_MIN) v_next = v_base;   /* 第一段不平移 */
                }
                if (pkt->dts != AV_NOPTS_VALUE) pkt->dts += v_next - v_base;
                if (pkt->pts != AV_NOPTS_VALUE) pkt->pts += v_next - v_base;
                if (pkt->dts != AV_NOPTS_VALUE) {
                    int64_t e = pkt->dts + (pkt->duration > 0 ? pkt->duration : 1);
                    if (v_end == INT64_MIN || e > v_end) v_end = e;
                }
                wr = 1;
            } else if (as && aout && pkt->stream_index == ai) {
                av_packet_rescale_ts(pkt, as->time_base, aout->time_base);
                pkt->stream_index = aout->index;
                if (a_base == INT64_MIN) {
                    a_base = (pkt->dts != AV_NOPTS_VALUE) ? pkt->dts : 0;
                    if (a_next == INT64_MIN) a_next = a_base;
                }
                if (pkt->dts != AV_NOPTS_VALUE) pkt->dts += a_next - a_base;
                if (pkt->pts != AV_NOPTS_VALUE) pkt->pts += a_next - a_base;
                if (pkt->dts != AV_NOPTS_VALUE) {
                    int64_t e = pkt->dts + (pkt->duration > 0 ? pkt->duration : 1);
                    if (a_end == INT64_MIN || e > a_end) a_end = e;
                }
                wr = 1;
            }
            if (wr) {
                int w = av_interleaved_write_frame(out_fmt, pkt);
                if (w < 0) {
                    LOGE("concat_copy: write failed %d (seg=%d st=%d pts=%lld dts=%lld dur=%lld)",
                         w, i, pkt->stream_index, (long long)pkt->pts,
                         (long long)pkt->dts, (long long)pkt->duration);
                    av_packet_unref(pkt);
                    goto cc_out;
                }
            }
            av_packet_unref(pkt);
        }
        if (v_end != INT64_MIN) v_next = v_end;
        if (a_end != INT64_MIN) a_next = a_end;
        if (i > 0) close_input_avio(&cur);
    }
    if (av_write_trailer(out_fmt) < 0) goto cc_out;
    LOGI("concat_copy: remuxed %d inputs (zero re-encode)", n_inputs);
    ret = 0;

cc_out:
    if (pkt) av_packet_free(&pkt);
    if (cur) close_input_avio(&cur);
    if (in_fmt) close_input_avio(&in_fmt);
    if (out_fmt) close_output_avio(&out_fmt);
    return ret;
}

static int do_concat(const char **inputs, int n_inputs, const char *output, int handle) {
    AVFormatContext *out_fmt = NULL;
    AVCodecContext *venc_ctx = NULL, *aenc_ctx = NULL;
    const AVCodec *vencoder = NULL, *aencoder = NULL;
    AVStream *vout = NULL, *aout = NULL;
    struct SwsContext *sws = NULL;
    SwrContext *swr = NULL;
    int ret = 0, result = 0;
    int out_w = 0, out_h = 0;
    int phys_rotate = 0, rot_w = 0, rot_h = 0;  /* 竖屏物理旋转（同 do_video_convert） */
    int concat_rotation = 0;
    AVFrame *rot_src = NULL;
    int any_video = 0;
    int64_t concat_in_bitrate = 0;  /* 输入视频码率，用于输出码率匹配（防拼接体积暴涨） */
    int64_t video_offset_us = 0;
    AVAudioFifo *fifo = NULL;
    int64_t concat_apts = 0;
    int concat_frame_size = 1024;
    AVRational concat_fr = (AVRational){0, 1};  /* output framerate follows source */

    if (n_inputs < 1) return -1;

    /* 输出扩展名决定容器：.mp3 = 纯音频拼接；否则 mp4 */
    int is_mp3 = strstr(output, ".mp3") != NULL;
    AVOutputFormat *oformat = av_guess_format(is_mp3 ? "mp3" : "mp4", output, NULL);
    avformat_alloc_output_context2(&out_fmt, oformat, NULL, output);
    if (!out_fmt) return -1;

    /* 探测：是否有视频输入 / 是否有音频输入 / 输出分辨率 */
    int any_audio = 0;
    /* —— 流复制可行性探测：同格式输入直接 remux，避免 mpeg4 低码率重编码糊成色块 —— */
    int copy_ok = 1, copy_seen = 0, copy_hv = 0, copy_ha = 0, copy_mov = 1, copy_rot = 0;
    enum AVCodecID c_vid = AV_CODEC_ID_NONE, c_aid = AV_CODEC_ID_NONE;
    int c_w = 0, c_h = 0, c_sr = 0, c_ch = 0;
    int c_disp_size = -1;   /* 第一个输入的 display matrix 字节数（-1=未记录；0=无） */
    int32_t c_disp[9];      /* 第一个输入的 display matrix，用于比较一致性 */
    for (int i = 0; i < n_inputs; i++) {
        AVFormatContext *t = NULL;
        int r0 = open_input_avio(&t, inputs[i]);
        LOGI("concat probe[%d/%d] open=%d (%s)", i, n_inputs, r0, inputs[i]);
        if (r0 < 0) continue;
        avformat_find_stream_info(t, NULL);
        int v = find_stream(t, AVMEDIA_TYPE_VIDEO);
        int a = find_stream(t, AVMEDIA_TYPE_AUDIO);
        LOGI("concat probe[%d] v=%d a=%d dur=%lld iformat=%s", i, v, a,
             (long long)t->duration, t->iformat ? t->iformat->name : "?");
        /* 逐项比较：视频/音频编码、分辨率、采样率、声道、容器、旋转 全部一致才可流复制 */
        {
            int hv = (v >= 0), ha = (a >= 0);
            enum AVCodecID vid = hv ? t->streams[v]->codecpar->codec_id : AV_CODEC_ID_NONE;
            int vw = hv ? t->streams[v]->codecpar->width : 0;
            int vhh = hv ? t->streams[v]->codecpar->height : 0;
            enum AVCodecID aid = ha ? t->streams[a]->codecpar->codec_id : AV_CODEC_ID_NONE;
            int asr = ha ? t->streams[a]->codecpar->sample_rate : 0;
            int ach = ha ? t->streams[a]->codecpar->ch_layout.nb_channels : 0;
            if (!copy_seen) {
                copy_seen = 1; copy_hv = hv; copy_ha = ha;
                c_vid = vid; c_w = vw; c_h = vhh; c_aid = aid; c_sr = asr; c_ch = ach;
            } else if (hv != copy_hv || ha != copy_ha || vid != c_vid || vw != c_w ||
                       vhh != c_h || aid != c_aid || asr != c_sr || ach != c_ch) {
                copy_ok = 0;
            }
            if (t->iformat && !strstr(t->iformat->name, "mov")) copy_mov = 0;
            if (hv) {
                /* 旋转：只要各输入的 display matrix 一致（含「都没有」）就能流复制，
                 * 原先要求必须为 0° 会让 180°/竖屏视频白白退回重编码。
                 * 不一致（如一段竖屏一段横屏）才回退重编码。 */
                uint8_t *disp = av_stream_get_side_data(t->streams[v], AV_PKT_DATA_DISPLAYMATRIX, NULL);
                int dsize = disp ? 36 : 0;
                int32_t d[9];
                if (disp) memcpy(d, disp, 36);
                if (c_disp_size < 0) {
                    c_disp_size = dsize;
                    if (dsize) memcpy(c_disp, d, 36);
                } else if (dsize != c_disp_size || (dsize && memcmp(c_disp, d, 36) != 0)) {
                    copy_rot = 1;
                }
            }
        }
        if (v >= 0) {
            any_video = 1;
            /* 记录输入视频码率（拼接输出用同码率，防体积暴涨） */
            if (concat_in_bitrate <= 0) {
                concat_in_bitrate = t->streams[v]->codecpar->bit_rate;
                if (concat_in_bitrate <= 0) concat_in_bitrate = 0;
            }
            /* 记录源帧率（输出帧率跟随源，不再写死 30fps） */
            if (concat_fr.num <= 0 || concat_fr.den <= 0) {
                concat_fr = t->streams[v]->avg_frame_rate;
                if (concat_fr.num <= 0 || concat_fr.den <= 0)
                    concat_fr = t->streams[v]->r_frame_rate;
            }
            if (out_w <= 0) {
                out_w = t->streams[v]->codecpar->width & ~1;
                out_h = t->streams[v]->codecpar->height & ~1;
                /* 竖屏修正：90°/270° 物理旋转，避免拼接输出变横屏 */
                double rot = 0.0;
                uint8_t *disp = av_stream_get_side_data(t->streams[v], AV_PKT_DATA_DISPLAYMATRIX, NULL);
                if (disp) rot = av_display_rotation_get((const int32_t *)disp);
                else {
                    AVDictionaryEntry *de = av_dict_get(t->streams[v]->metadata, "rotate", NULL, 0);
                    if (de) rot = atoi(de->value);
                }
                int rotation = ((int)rot) % 360;
                if (rotation < 0) rotation += 360;
                concat_rotation = rotation;
                phys_rotate = (rotation == 90 || rotation == 270);
                rot_w = out_w; rot_h = out_h;
                if (phys_rotate) { int t2 = out_w; out_w = out_h; out_h = t2; }
                LOGI("concat: rotation=%d out=%dx%d%s", rotation, out_w, out_h, phys_rotate ? " (phys rotate)" : "");
            }
        }
        if (a >= 0) any_audio = 1;
        close_input_avio(&t);
    }
    LOGI("concat probe done: any_video=%d any_audio=%d out=%dx%d", any_video, any_audio, out_w, out_h);
    /* 同格式输入 → 直接流复制 remux（无损、更快、体积 ≈ 各输入之和）。
     * 此前一律用 mpeg4（MPEG-4 Part 2，libx264 未编译）按输入码率重编码，
     * 1080p 仅 0.9Mbps → 严重块状伪影（被误认为 GIF 调色板色块）。 */
    {
        int vc_ok = (c_vid == AV_CODEC_ID_H264 || c_vid == AV_CODEC_ID_HEVC || c_vid == AV_CODEC_ID_MPEG4);
        int ac_ok = (!copy_ha) || (c_aid == AV_CODEC_ID_AAC || c_aid == AV_CODEC_ID_MP3);
        if (copy_ok && copy_seen && copy_mov && !copy_rot && !is_mp3 && copy_hv && vc_ok && ac_ok) {
            int cr = concat_copy(inputs, n_inputs, output, handle);
            LOGI("concat: stream-copy remux result=%d", cr);
            if (cr == 0) { avformat_free_context(out_fmt); return 0; }
            LOGE("concat: stream-copy failed(%d), fallback to re-encode", cr);
        } else {
            LOGI("concat: stream-copy skipped (sampleOk=%d seen=%d mov=%d rotMismatch=%d isMp3=%d hasVideo=%d vcodecOk=%d acodecOk=%d)",
                 copy_ok, copy_seen, copy_mov, copy_rot, is_mp3, copy_hv, vc_ok, ac_ok);
        }
    }
    if (out_w <= 0) out_w = 1280;
    if (out_h <= 0) out_h = 720;

    /* 视频轨：仅当有视频输入（纯音频拼接不创建） */
    if (any_video) {
        /* mpeg4 编码（mp4 容器，全设备可用；libx264 未编译） */
        vencoder = avcodec_find_encoder_by_name("mpeg4");
        if (!vencoder) { LOGE("mpeg4 not found"); ret = AVERROR_ENCODER_NOT_FOUND; goto fail; }
        vout = avformat_new_stream(out_fmt, vencoder);
        venc_ctx = avcodec_alloc_context3(vencoder);
        venc_ctx->width = out_w;
        venc_ctx->height = out_h;
        venc_ctx->pix_fmt = AV_PIX_FMT_YUV420P;
        /* 拼接体积暴涨修复：固定 2Mbps 导致低码率输入拼接翻倍膨胀
         * （30MB→150MB 实测）。改用输入码率匹配：coerceIn 500k~6M，
         * 与视频转换 ABR 逻辑一致，保证输出体积 ≈ 输入 × 段数。 */
        int64_t concat_br = concat_in_bitrate > 0 ? concat_in_bitrate : 2000000;
        if (concat_br < 500000) concat_br = 500000;
        if (concat_br > 6000000) concat_br = 6000000;
        venc_ctx->bit_rate = concat_br;
        venc_ctx->rc_max_rate = concat_br;
        venc_ctx->rc_buffer_size = concat_br / 2;
        LOGI("concat: vbitrate=%lld (in=%lld)", (long long)concat_br, (long long)concat_in_bitrate);
        /* 帧率跟随源（此前写死 30，非 30fps 视频拼接后帧率被改写成 30） */
        venc_ctx->framerate = (concat_fr.num > 0 && concat_fr.den > 0) ? concat_fr : (AVRational){25, 1};
        LOGI("concat: framerate=%d/%d (follow source)", venc_ctx->framerate.num, venc_ctx->framerate.den);
        /* mpeg4 编码器 time_base 分母上限 65535（1/90000 会 EINVAL） */
        venc_ctx->time_base = (AVRational){1, 15360};
        if ((ret = avcodec_open2(venc_ctx, vencoder, NULL)) < 0) goto fail;
        avcodec_parameters_from_context(vout->codecpar, venc_ctx);
        vout->time_base = venc_ctx->time_base;
    }

    if (any_audio) {
        /* 音频编码器：mp3 输出用 libmp3lame（FFmpeg 7.0 无原生 mp3 编码器），兜底 mp3 */
        aencoder = avcodec_find_encoder_by_name(is_mp3 ? "libmp3lame" : "aac");
        if (!aencoder && is_mp3) aencoder = avcodec_find_encoder_by_name("mp3");
        if (!aencoder) { LOGE("audio encoder not found"); ret = AVERROR_ENCODER_NOT_FOUND; goto fail; }
        aout = avformat_new_stream(out_fmt, aencoder);
        aenc_ctx = avcodec_alloc_context3(aencoder);
        aenc_ctx->sample_rate = 44100;
        aenc_ctx->ch_layout = (AVChannelLayout)AV_CHANNEL_LAYOUT_STEREO;
        aenc_ctx->bit_rate = 128000;
        aenc_ctx->sample_fmt = aencoder->sample_fmts[0];
        aenc_ctx->time_base = (AVRational){1, aenc_ctx->sample_rate};
        if ((ret = avcodec_open2(aenc_ctx, aencoder, NULL)) < 0) { LOGE("concat aenc open fail %d (0x%x)", ret, (unsigned)(-ret)); goto fail; }
        avcodec_parameters_from_context(aout->codecpar, aenc_ctx);
        aout->time_base = aenc_ctx->time_base;
        LOGI("concat aenc opened %s fmt=%s sr=%d ch=%d frame_size=%d", aencoder->name,
             av_get_sample_fmt_name(aenc_ctx->sample_fmt), aenc_ctx->sample_rate,
             aenc_ctx->ch_layout.nb_channels, aenc_ctx->frame_size);
        /* 音频 FIFO：解码帧 nb_samples 未必等于编码器 frame_size，直接 send 会 EINVAL */
        if (aenc_ctx->frame_size > 0) concat_frame_size = aenc_ctx->frame_size;
        fifo = av_audio_fifo_alloc(aenc_ctx->sample_fmt, aenc_ctx->ch_layout.nb_channels, 1);
        if (!fifo) { ret = AVERROR(ENOMEM); goto fail; }
    }

    out_fmt->pb = open_output_avio_pb(output);
    if (!out_fmt->pb) { ret = AVERROR(ENOENT); goto fail; }
    if ((ret = avformat_write_header(out_fmt, NULL)) < 0) goto fail;

    AVPacket *pkt = av_packet_alloc();
    AVFrame *frame = av_frame_alloc();
    AVFrame *voframe = NULL;
    if (any_video) {
        voframe = av_frame_alloc();
        voframe->format = AV_PIX_FMT_YUV420P;
        voframe->width = out_w;
        voframe->height = out_h;
        av_frame_get_buffer(voframe, 0);
        if (phys_rotate) {
            /* 旋转前方向的缩放目标（sws 输出） */
            rot_src = av_frame_alloc();
            rot_src->format = AV_PIX_FMT_YUV420P;
            rot_src->width = rot_w;
            rot_src->height = rot_h;
            av_frame_get_buffer(rot_src, 0);
        }
    }

    for (int i = 0; i < n_inputs; i++) {
        if ((ret = task_check(handle)) < 0) { result = ret; break; }
        AVFormatContext *in_fmt = NULL;
        ret = open_input_avio(&in_fmt, inputs[i]);
        LOGI("concat in[%d/%d] open=%d (%s)", i, n_inputs, ret, inputs[i]);
        if (ret < 0) { result = ret; break; }
        avformat_find_stream_info(in_fmt, NULL);
        int vidx = find_stream(in_fmt, AVMEDIA_TYPE_VIDEO);
        int aidx = find_stream(in_fmt, AVMEDIA_TYPE_AUDIO);
        LOGI("concat in[%d] vidx=%d aidx=%d dur=%lld", i, vidx, aidx, (long long)in_fmt->duration);
        AVCodecContext *vdec = NULL, *adec = NULL;
        AVStream *vst = vidx >= 0 ? in_fmt->streams[vidx] : NULL;
        AVStream *ast = aidx >= 0 ? in_fmt->streams[aidx] : NULL;
        /* 不同比例输入：缩放适配 + 黑边（letterbox），防变形 */
        int lb_sw = 0, lb_sh = 0, lb_ox = 0, lb_oy = 0;
        AVFrame *lb_src = NULL;

        if (vst) {
            const AVCodec *vd = avcodec_find_decoder(vst->codecpar->codec_id);
            vdec = avcodec_alloc_context3(vd);
            avcodec_parameters_to_context(vdec, vst->codecpar);
            if ((ret = avcodec_open2(vdec, vd, NULL)) < 0) { LOGE("concat in[%d] vdec open fail %d", i, ret); }
            if (sws) sws_freeContext(sws);
            if (phys_rotate) {
                /* 旋转路径：缩放到旋转前方向 */
                sws = sws_getContext(vdec->width, vdec->height, vdec->pix_fmt,
                                     rot_w, rot_h, AV_PIX_FMT_YUV420P,
                                     SWS_BILINEAR, NULL, NULL, NULL);
            } else {
                /* letterbox：保持输入宽高比，缩放适配到输出画布内 */
                double ar_in = (double)vdec->width / vdec->height;
                double ar_out = (double)out_w / out_h;
                if (ar_in > ar_out) { lb_sh = out_h; lb_sw = (int)(out_h * ar_in + 0.5); }
                else { lb_sw = out_w; lb_sh = (int)(out_w / ar_in + 0.5); }
                if (lb_sw > out_w) { lb_sw = out_w; lb_sh = (int)(out_w / ar_in + 0.5); }
                if (lb_sh > out_h) { lb_sh = out_h; lb_sw = (int)(out_h * ar_in + 0.5); }
                lb_sw &= ~1; lb_sh &= ~1;
                lb_ox = (out_w - lb_sw) / 2; lb_oy = (out_h - lb_sh) / 2;
                sws = sws_getContext(vdec->width, vdec->height, vdec->pix_fmt,
                                     lb_sw, lb_sh, AV_PIX_FMT_YUV420P,
                                     SWS_BILINEAR, NULL, NULL, NULL);
                lb_src = av_frame_alloc();
                lb_src->format = AV_PIX_FMT_YUV420P;
                lb_src->width = lb_sw;
                lb_src->height = lb_sh;
                av_frame_get_buffer(lb_src, 0);
            }
            if (!sws) { LOGE("concat in[%d] sws_getContext FAIL", i); }
        }
        if (ast) {
            const AVCodec *ad = avcodec_find_decoder(ast->codecpar->codec_id);
            adec = avcodec_alloc_context3(ad);
            avcodec_parameters_to_context(adec, ast->codecpar);
            if ((ret = avcodec_open2(adec, ad, NULL)) < 0) { LOGE("concat in[%d] adec open fail %d", i, ret); }
            if (swr) swr_free(&swr);
            swr = NULL;
            if (adec->sample_fmt != aenc_ctx->sample_fmt ||
                adec->sample_rate != aenc_ctx->sample_rate ||
                adec->ch_layout.nb_channels != aenc_ctx->ch_layout.nb_channels) {
                swr = swr_alloc();
                av_opt_set_chlayout(swr, "in_chlayout", &adec->ch_layout, 0);
                av_opt_set_int(swr, "in_sample_rate", adec->sample_rate, 0);
                av_opt_set_sample_fmt(swr, "in_sample_fmt", adec->sample_fmt, 0);
                av_opt_set_chlayout(swr, "out_chlayout", &aenc_ctx->ch_layout, 0);
                av_opt_set_int(swr, "out_sample_rate", aenc_ctx->sample_rate, 0);
                av_opt_set_sample_fmt(swr, "out_sample_fmt", aenc_ctx->sample_fmt, 0);
                if (swr_init(swr) < 0) { LOGE("concat in[%d] swr_init FAIL", i); result = AVERROR(EINVAL); break; }
                LOGI("concat in[%d] swr %s %dHz %dch -> %s %dHz %dch", i,
                     av_get_sample_fmt_name(adec->sample_fmt), adec->sample_rate, adec->ch_layout.nb_channels,
                     av_get_sample_fmt_name(aenc_ctx->sample_fmt), aenc_ctx->sample_rate, aenc_ctx->ch_layout.nb_channels);
            } else {
                LOGI("concat in[%d] audio passthrough fmt=%s sr=%d", i,
                     av_get_sample_fmt_name(adec->sample_fmt), adec->sample_rate);
            }
        }
        int64_t in_dur_us = in_fmt->duration > 0 ? in_fmt->duration : 0;

        while (av_read_frame(in_fmt, pkt) >= 0) {
            if ((ret = task_check(handle)) < 0) { result = ret; break; }
            if (vst && pkt->stream_index == vidx) {
                ret = avcodec_send_packet(vdec, pkt);
                while (ret >= 0) {
                    ret = avcodec_receive_frame(vdec, frame);
                    if (ret == AVERROR(EAGAIN) || ret == AVERROR_EOF) break;
                    if (ret < 0) { result = ret; break; }
                    if (phys_rotate) {
                        /* 先缩放到旋转前方向，再像素旋转到输出方向 */
                        sws_scale(sws, (const uint8_t *const *)frame->data, frame->linesize,
                                  0, vdec->height, rot_src->data, rot_src->linesize);
                        int cw = (concat_rotation == 90);
                        rotate_plane(voframe->data[0], rot_src->data[0], rot_w, rot_h,
                                     voframe->linesize[0], rot_src->linesize[0], cw);
                        rotate_plane(voframe->data[1], rot_src->data[1], rot_w / 2, rot_h / 2,
                                     voframe->linesize[1], rot_src->linesize[1], cw);
                        rotate_plane(voframe->data[2], rot_src->data[2], rot_w / 2, rot_h / 2,
                                     voframe->linesize[2], rot_src->linesize[2], cw);
                    } else {
                        /* letterbox：缩放到适配尺寸 → 黑边 + 居中拷贝（不同比例不拉伸） */
                        sws_scale(sws, (const uint8_t *const *)frame->data, frame->linesize,
                                  0, vdec->height, lb_src->data, lb_src->linesize);
                        memset(voframe->data[0], 16, (size_t)voframe->linesize[0] * out_h);
                        memset(voframe->data[1], 128, (size_t)voframe->linesize[1] * (out_h / 2));
                        memset(voframe->data[2], 128, (size_t)voframe->linesize[2] * (out_h / 2));
                        for (int y = 0; y < lb_sh; y++)
                            memcpy(voframe->data[0] + (lb_oy + y) * voframe->linesize[0] + lb_ox,
                                   lb_src->data[0] + y * lb_src->linesize[0], lb_sw);
                        for (int y = 0; y < lb_sh / 2; y++) {
                            memcpy(voframe->data[1] + (lb_oy / 2 + y) * voframe->linesize[1] + lb_ox / 2,
                                   lb_src->data[1] + y * lb_src->linesize[1], lb_sw / 2);
                            memcpy(voframe->data[2] + (lb_oy / 2 + y) * voframe->linesize[2] + lb_ox / 2,
                                   lb_src->data[2] + y * lb_src->linesize[2], lb_sw / 2);
                        }
                    }
                    int64_t us = av_rescale_q(frame->pts, vst->time_base, AV_TIME_BASE_Q) + video_offset_us;
                    voframe->pts = av_rescale_q(us, AV_TIME_BASE_Q, venc_ctx->time_base);
                    ret = avcodec_send_frame(venc_ctx, voframe);
                    if (ret == AVERROR(EAGAIN)) {
                        while (avcodec_receive_packet(venc_ctx, pkt) >= 0) {
                            pkt->stream_index = vout->index;
                            av_interleaved_write_frame(out_fmt, pkt);
                            av_packet_unref(pkt);
                        }
                        ret = avcodec_send_frame(venc_ctx, voframe);
                    }
                    if (ret < 0) { result = ret; break; }
                    while ((ret = avcodec_receive_packet(venc_ctx, pkt)) >= 0) {
                        pkt->stream_index = vout->index;
                        av_interleaved_write_frame(out_fmt, pkt);
                        av_packet_unref(pkt);
                    }
                    /* 进度：输入级 + 输入内 */
                    if (in_dur_us > 0) {
                        int base = i * 100 / n_inputs;
                        int inner = (int)((double)av_rescale_q(frame->pts, vst->time_base, AV_TIME_BASE_Q) / in_dur_us * (100 / n_inputs));
                        report_progress(handle, base + (inner > (100 / n_inputs) ? (100 / n_inputs) : inner));
                    }
                }
                if (result < 0) break;
            } else if (aout && ast && pkt->stream_index == aidx) {
                ret = avcodec_send_packet(adec, pkt);
                while (ret >= 0) {
                    ret = avcodec_receive_frame(adec, frame);
                    if (ret == AVERROR(EAGAIN) || ret == AVERROR_EOF) break;
                    if (ret < 0) { result = ret; break; }
                    AVFrame *tmp = av_frame_alloc();
                    tmp->format = aenc_ctx->sample_fmt;
                    tmp->sample_rate = aenc_ctx->sample_rate;
                    av_channel_layout_copy(&tmp->ch_layout, &aenc_ctx->ch_layout);
                    if (swr) {
                        tmp->nb_samples = (int)av_rescale_rnd(
                            swr_get_delay(swr, adec->sample_rate) + frame->nb_samples,
                            aenc_ctx->sample_rate, adec->sample_rate, AV_ROUND_UP);
                    } else tmp->nb_samples = frame->nb_samples;
                    av_frame_get_buffer(tmp, 0);
                    if (swr) {
                        int c = swr_convert(swr, tmp->data, tmp->nb_samples,
                                            (const uint8_t **)frame->data, frame->nb_samples);
                        if (c < 0) { result = c; av_frame_free(&tmp); break; }
                        tmp->nb_samples = c;
                    } else {
                        copy_audio_samples(tmp, frame);
                    }
                    av_audio_fifo_write(fifo, (void **)tmp->data, tmp->nb_samples);
                    av_frame_free(&tmp);
                    /* 分块送编码器 */
                    AVFrame *aof = av_frame_alloc();
                    aof->format = aenc_ctx->sample_fmt;
                    aof->sample_rate = aenc_ctx->sample_rate;
                    av_channel_layout_copy(&aof->ch_layout, &aenc_ctx->ch_layout);
                    aof->nb_samples = concat_frame_size;
                    av_frame_get_buffer(aof, 0);
                    while (av_audio_fifo_size(fifo) >= concat_frame_size) {
                        if ((ret = task_check(handle)) < 0) { result = ret; break; }
                        av_audio_fifo_read(fifo, (void **)aof->data, concat_frame_size);
                        aof->pts = concat_apts;
                        concat_apts += concat_frame_size;
                        ret = avcodec_send_frame(aenc_ctx, aof);
                        if (ret == AVERROR(EAGAIN)) {
                            while (avcodec_receive_packet(aenc_ctx, pkt) >= 0) {
                                pkt->stream_index = aout->index;
                                av_interleaved_write_frame(out_fmt, pkt);
                                av_packet_unref(pkt);
                            }
                            ret = avcodec_send_frame(aenc_ctx, aof);
                        }
                        if (ret < 0) { LOGE("concat: aenc send_frame %d (0x%x)", ret, (unsigned)(-ret)); result = ret; break; }
                        while ((ret = avcodec_receive_packet(aenc_ctx, pkt)) >= 0) {
                            pkt->stream_index = aout->index;
                            av_interleaved_write_frame(out_fmt, pkt);
                            av_packet_unref(pkt);
                        }
                    }
                    av_frame_free(&aof);
                    if (result < 0) break;
                }
                if (result < 0) break;
            }
            av_packet_unref(pkt);
        }
        /* flush 本输入的解码器：视频帧丢弃；音频帧进 FIFO 并冲刷输出 */
        if (vst) { avcodec_send_packet(vdec, NULL); while (avcodec_receive_frame(vdec, frame) >= 0) {} }
        if (ast && result >= 0 && aout) {
            avcodec_send_packet(adec, NULL);
            while (avcodec_receive_frame(adec, frame) >= 0) {
                AVFrame *tmp = av_frame_alloc();
                if (!tmp) { result = AVERROR(ENOMEM); break; }
                tmp->format = aenc_ctx->sample_fmt;
                tmp->sample_rate = aenc_ctx->sample_rate;
                av_channel_layout_copy(&tmp->ch_layout, &aenc_ctx->ch_layout);
                tmp->nb_samples = swr ? (int)av_rescale_rnd(
                    swr_get_delay(swr, adec->sample_rate) + frame->nb_samples,
                    aenc_ctx->sample_rate, adec->sample_rate, AV_ROUND_UP) : frame->nb_samples;
                av_frame_get_buffer(tmp, 0);
                if (swr) {
                    int c = swr_convert(swr, tmp->data, tmp->nb_samples,
                                        (const uint8_t **)frame->data, frame->nb_samples);
                    if (c < 0) { result = c; av_frame_free(&tmp); break; }
                    tmp->nb_samples = c;
                } else {
                    copy_audio_samples(tmp, frame);
                }
                av_audio_fifo_write(fifo, (void **)tmp->data, tmp->nb_samples);
                av_frame_free(&tmp);
            }
            /* flush FIFO 剩余（不足 frame_size 补零）。注意：不能在此 send_frame(NULL)
             * flush 编码器——aenc_ctx 是所有输入共享的，提前 flush 会导致后续输入
             * 编码失败（拼接 -1 的根因）。编码器统一在全部输入结束后 flush。 */
            int bytes_ps = av_get_bytes_per_sample(aenc_ctx->sample_fmt);
            while (result >= 0 && av_audio_fifo_size(fifo) > 0) {
                int rem = av_audio_fifo_size(fifo);
                int got = rem < concat_frame_size ? rem : concat_frame_size;
                AVFrame *aof = av_frame_alloc();
                aof->format = aenc_ctx->sample_fmt;
                aof->sample_rate = aenc_ctx->sample_rate;
                av_channel_layout_copy(&aof->ch_layout, &aenc_ctx->ch_layout);
                aof->nb_samples = concat_frame_size;
                av_frame_get_buffer(aof, 0);
                av_audio_fifo_read(fifo, (void **)aof->data, got);
                /* 补零：兼容 packed 格式（同 do_audio_convert 修复） */
                if (av_sample_fmt_is_planar(aenc_ctx->sample_fmt)) {
                    for (int ch = 0; ch < aenc_ctx->ch_layout.nb_channels; ch++)
                        memset(aof->data[ch] + got * bytes_ps, 0, (concat_frame_size - got) * bytes_ps);
                } else {
                    memset(aof->data[0] + got * bytes_ps * aenc_ctx->ch_layout.nb_channels, 0,
                           (concat_frame_size - got) * bytes_ps * aenc_ctx->ch_layout.nb_channels);
                }
                aof->pts = concat_apts;
                concat_apts += concat_frame_size;
                ret = avcodec_send_frame(aenc_ctx, aof);
                if (ret == AVERROR(EAGAIN)) {
                    while (avcodec_receive_packet(aenc_ctx, pkt) >= 0) {
                        pkt->stream_index = aout->index;
                        av_interleaved_write_frame(out_fmt, pkt);
                        av_packet_unref(pkt);
                    }
                    ret = avcodec_send_frame(aenc_ctx, aof);
                }
                if (ret >= 0) {
                    while (avcodec_receive_packet(aenc_ctx, pkt) >= 0) {
                        pkt->stream_index = aout->index;
                        av_interleaved_write_frame(out_fmt, pkt);
                        av_packet_unref(pkt);
                    }
                } else { LOGE("concat in[%d] flush aenc send_frame %d (0x%x)", i, ret, (unsigned)(-ret)); result = ret; }
                av_frame_free(&aof);
            }
        }
        /* 时长推进 */
        if (in_dur_us > 0) {
            video_offset_us += in_dur_us;
        }
        if (vdec) avcodec_free_context(&vdec);
        if (adec) avcodec_free_context(&adec);
        av_frame_free(&lb_src);
        close_input_avio(&in_fmt);
        if (result < 0) break;
    }
    /* flush 输出编码器 */
    if (result >= 0) {
        if (any_video) {
            avcodec_send_frame(venc_ctx, NULL);
            while (avcodec_receive_packet(venc_ctx, pkt) >= 0) {
                pkt->stream_index = vout->index;
                av_interleaved_write_frame(out_fmt, pkt);
                av_packet_unref(pkt);
            }
        }
        if (aout) {
            avcodec_send_frame(aenc_ctx, NULL);
            while (avcodec_receive_packet(aenc_ctx, pkt) >= 0) {
                pkt->stream_index = aout->index;
                av_interleaved_write_frame(out_fmt, pkt);
                av_packet_unref(pkt);
            }
        }
    }
    av_write_trailer(out_fmt);

    av_packet_free(&pkt);
    av_frame_free(&frame);
    av_frame_free(&voframe);
    av_frame_free(&rot_src);
fail:
    if (result < 0 || (ret < 0 && ret != AVERROR(EAGAIN) && ret != AVERROR(EOF)))
        LOGE("%s failed: ret=%d (%s) result=%d", __func__, ret, av_err2str(ret), result);
    else
        LOGI("%s done: result=%d", __func__, result);
    close_output_avio(&out_fmt);
    if (fifo) av_audio_fifo_free(fifo);
    if (venc_ctx) avcodec_free_context(&venc_ctx);
    if (aenc_ctx) avcodec_free_context(&aenc_ctx);
    if (sws) sws_freeContext(sws);
    if (swr) swr_free(&swr);
    task_reset(handle);
    return result < 0 ? result : (ret < 0 && ret != AVERROR(EAGAIN) && ret != AVERROR(EOF) ? ret : 0);
}

/* =========================================================
 *  SNAPSHOT (frame extraction → JPEG)
 * ========================================================= */
static int do_snapshot(const char *input, const char *output, int64_t time_us, int handle) {
    AVFormatContext *in_fmt = NULL;
    AVCodecContext *dec_ctx = NULL, *enc_ctx = NULL;
    AVPacket *pkt = NULL;
    AVFrame *frame = NULL, *out_frame = NULL, *last = NULL, *rot_src = NULL;
    struct SwsContext *sws = NULL;
    AVIOContext *out_io = NULL;
    int ret = 0, result = 0, vidx = -1;
    const AVCodec *encoder = NULL;

    if ((ret = open_input_avio(&in_fmt, input)) < 0) return ret;
    avformat_find_stream_info(in_fmt, NULL);
    vidx = find_stream(in_fmt, AVMEDIA_TYPE_VIDEO);
    if (vidx < 0) { ret = -1; goto fail; }

    AVCodecParameters *vin_par = in_fmt->streams[vidx]->codecpar;
    const AVCodec *decoder = avcodec_find_decoder(vin_par->codec_id);
    dec_ctx = avcodec_alloc_context3(decoder);
    avcodec_parameters_to_context(dec_ctx, vin_par);
    if ((ret = avcodec_open2(dec_ctx, decoder, NULL)) < 0) goto fail;

    /* 修复截图方向：读取旋转角，90°/270° 时交换输出宽高并物理旋转像素
     * （此前 snapshot 无 rotation 处理，竖屏视频截图输出横图） */
    double rot = 0.0;
    uint8_t *disp = av_stream_get_side_data(in_fmt->streams[vidx], AV_PKT_DATA_DISPLAYMATRIX, NULL);
    if (disp) rot = av_display_rotation_get((const int32_t *)disp);
    else {
        AVDictionaryEntry *de = av_dict_get(in_fmt->streams[vidx]->metadata, "rotate", NULL, 0);
        if (de) rot = atoi(de->value);
    }
    int rotation = ((int)rot) % 360;
    if (rotation < 0) rotation += 360;
    int phys_rotate = (rotation == 90 || rotation == 270);
    int rot_w = dec_ctx->width, rot_h = dec_ctx->height;   /* sws 缩放目标（旋转前方向） */
    int out_w = dec_ctx->width, out_h = dec_ctx->height;
    if (phys_rotate) { int t = out_w; out_w = out_h; out_h = t; }
    LOGI("snapshot: rotation=%d out=%dx%d%s", rotation, out_w, out_h, phys_rotate ? " (phys rotate)" : "");

    encoder = avcodec_find_encoder_by_name("mjpeg");
    if (!encoder) { ret = AVERROR_ENCODER_NOT_FOUND; goto fail; }
    enc_ctx = avcodec_alloc_context3(encoder);
    enc_ctx->width = out_w;
    enc_ctx->height = out_h;
    enc_ctx->pix_fmt = encoder->pix_fmts[0];
    enc_ctx->time_base = (AVRational){1, 25};
    if ((ret = avcodec_open2(enc_ctx, encoder, NULL)) < 0) goto fail;

    sws = sws_getContext(dec_ctx->width, dec_ctx->height, dec_ctx->pix_fmt,
                         rot_w, rot_h, enc_ctx->pix_fmt,
                         SWS_BILINEAR, NULL, NULL, NULL);
    if (!sws) { ret = -1; goto fail; }

    /* seek 到目标时间 */
    if (time_us > 0) av_seek_frame(in_fmt, -1, time_us, AVSEEK_FLAG_BACKWARD);

    pkt = av_packet_alloc();
    frame = av_frame_alloc();
    out_frame = av_frame_alloc();
    out_frame->format = enc_ctx->pix_fmt;
    out_frame->width = enc_ctx->width;
    out_frame->height = enc_ctx->height;
    av_frame_get_buffer(out_frame, 0);
    last = av_frame_alloc();   /* 保存最后一帧，时间超出时长时兜底 */
    if (phys_rotate) {
        rot_src = av_frame_alloc();
        rot_src->format = enc_ctx->pix_fmt;
        rot_src->width = rot_w;
        rot_src->height = rot_h;
        av_frame_get_buffer(rot_src, 0);
    }

    AVRational tb = in_fmt->streams[vidx]->time_base;
    int got = 0;
    while (av_read_frame(in_fmt, pkt) >= 0 && !got) {
        if ((ret = task_check(handle)) < 0) { result = ret; break; }
        if (pkt->stream_index != vidx) { av_packet_unref(pkt); continue; }
        ret = avcodec_send_packet(dec_ctx, pkt);
        while (ret >= 0) {
            ret = avcodec_receive_frame(dec_ctx, frame);
            if (ret == AVERROR(EAGAIN) || ret == AVERROR_EOF) break;
            if (ret < 0) { result = ret; break; }
            int64_t pts_us = av_rescale_q(frame->pts, tb, AV_TIME_BASE_Q);
            av_frame_unref(last);
            av_frame_ref(last, frame);   /* 记录当前帧（引用计数） */
            if (pts_us >= time_us) {
                if (phys_rotate) {
                    sws_scale(sws, (const uint8_t *const *)frame->data, frame->linesize,
                              0, dec_ctx->height, rot_src->data, rot_src->linesize);
                    /* mjpeg pix_fmt 通常 YUVJ420P/YUV420P：U/V 平面尺寸减半 */
                    int cw = (rotation == 90);
                    rotate_plane(out_frame->data[0], rot_src->data[0], rot_w, rot_h,
                                 out_frame->linesize[0], rot_src->linesize[0], cw);
                    int sub_w = rot_w / 2, sub_h = rot_h / 2;
                    rotate_plane(out_frame->data[1], rot_src->data[1], sub_w, sub_h,
                                 out_frame->linesize[1], rot_src->linesize[1], cw);
                    rotate_plane(out_frame->data[2], rot_src->data[2], sub_w, sub_h,
                                 out_frame->linesize[2], rot_src->linesize[2], cw);
                } else {
                    sws_scale(sws, (const uint8_t *const *)frame->data, frame->linesize,
                              0, dec_ctx->height, out_frame->data, out_frame->linesize);
                }
                out_frame->pts = 0;
                ret = avcodec_send_frame(enc_ctx, out_frame);
                if (ret < 0) { result = ret; break; }
                if ((ret = avcodec_receive_packet(enc_ctx, pkt)) >= 0) {
                    out_io = open_output_avio_pb(output);
                    if (!out_io) { ret = AVERROR(ENOENT); goto fail; }
                    avio_write(out_io, pkt->data, pkt->size);
                    close_output_avio_pb(&out_io);
                    got = 1;
                }
                break;
            }
        }
        av_packet_unref(pkt);
    }
    if (!got && result >= 0 && last->data[0]) {
        /* 时间超出视频时长：用最后一帧 */
        if (phys_rotate) {
            sws_scale(sws, (const uint8_t *const *)last->data, last->linesize,
                      0, dec_ctx->height, rot_src->data, rot_src->linesize);
            int cw = (rotation == 90);
            rotate_plane(out_frame->data[0], rot_src->data[0], rot_w, rot_h,
                         out_frame->linesize[0], rot_src->linesize[0], cw);
            int sub_w = rot_w / 2, sub_h = rot_h / 2;
            rotate_plane(out_frame->data[1], rot_src->data[1], sub_w, sub_h,
                         out_frame->linesize[1], rot_src->linesize[1], cw);
            rotate_plane(out_frame->data[2], rot_src->data[2], sub_w, sub_h,
                         out_frame->linesize[2], rot_src->linesize[2], cw);
        } else {
            sws_scale(sws, (const uint8_t *const *)last->data, last->linesize,
                      0, dec_ctx->height, out_frame->data, out_frame->linesize);
        }
        out_frame->pts = 0;
        avcodec_send_frame(enc_ctx, out_frame);
        if (avcodec_receive_packet(enc_ctx, pkt) >= 0) {
            out_io = open_output_avio_pb(output);
                    if (!out_io) { ret = AVERROR(ENOENT); goto fail; }
            avio_write(out_io, pkt->data, pkt->size);
            close_output_avio_pb(&out_io);
            got = 1;
        }
    }
    if (!got && result >= 0) result = AVERROR(EAGAIN);   /* 无任何帧（视频为空）→ 失败 */

fail:
    if (result < 0 || (ret < 0 && ret != AVERROR(EAGAIN) && ret != AVERROR(EOF)))
        LOGE("%s failed: ret=%d (%s) result=%d", __func__, ret, av_err2str(ret), result);
    else
        LOGI("%s done: result=%d", __func__, result);
    if (out_io) close_output_avio_pb(&out_io);
    close_input_avio(&in_fmt);
    if (dec_ctx) avcodec_free_context(&dec_ctx);
    if (enc_ctx) avcodec_free_context(&enc_ctx);
    if (sws) sws_freeContext(sws);
    if (pkt) av_packet_free(&pkt);
    if (frame) av_frame_free(&frame);
    if (out_frame) av_frame_free(&out_frame);
    if (last) av_frame_free(&last);
    if (rot_src) av_frame_free(&rot_src);
    task_reset(handle);
    return result < 0 ? result : (ret < 0 && ret != AVERROR(EAGAIN) && ret != AVERROR(EOF) ? ret : 0);
}

/* ===== JNI ENTRY POINTS ===== */

JNIEXPORT jint JNICALL
Java_com_mymedia_app_utils_FFmpegBridge_nativeConvertAudio(
    JNIEnv *env, jobject thiz, jstring j_input, jstring j_output, jstring j_codec, jstring j_kv, jint handle) {
    const char *input = (*env)->GetStringUTFChars(env, j_input, NULL);
    const char *output = (*env)->GetStringUTFChars(env, j_output, NULL);
    const char *codec = (*env)->GetStringUTFChars(env, j_codec, NULL);
    const char *kv = j_kv ? (*env)->GetStringUTFChars(env, j_kv, NULL) : NULL;
    Opts o;
    opts_parse(&o, kv);
    LOGI("convertAudio in=%s out=%s codec=%s kv=%s handle=%d", input, output, codec, kv ? kv : "", handle);
    int ret = do_audio_convert(input, output, codec, &o, handle);
    (*env)->ReleaseStringUTFChars(env, j_input, input);
    (*env)->ReleaseStringUTFChars(env, j_output, output);
    (*env)->ReleaseStringUTFChars(env, j_codec, codec);
    if (j_kv) (*env)->ReleaseStringUTFChars(env, j_kv, kv);
    return ret;
}

JNIEXPORT jint JNICALL
Java_com_mymedia_app_utils_FFmpegBridge_nativeConvertVideo(
    JNIEnv *env, jobject thiz, jstring j_input, jstring j_output, jstring j_codec, jstring j_kv, jint handle) {
    const char *input = (*env)->GetStringUTFChars(env, j_input, NULL);
    const char *output = (*env)->GetStringUTFChars(env, j_output, NULL);
    const char *codec = (*env)->GetStringUTFChars(env, j_codec, NULL);
    const char *kv = j_kv ? (*env)->GetStringUTFChars(env, j_kv, NULL) : NULL;
    Opts o;
    opts_parse(&o, kv);
    LOGI("convertVideo in=%s out=%s codec=%s kv=%s handle=%d", input, output, codec, kv ? kv : "", handle);
    int ret;
    if (o.gif) ret = do_gif_convert(input, output, &o, handle);
    else ret = do_video_convert(input, output, codec, &o, handle);
    (*env)->ReleaseStringUTFChars(env, j_input, input);
    (*env)->ReleaseStringUTFChars(env, j_output, output);
    (*env)->ReleaseStringUTFChars(env, j_codec, codec);
    if (j_kv) (*env)->ReleaseStringUTFChars(env, j_kv, kv);
    return ret;
}

JNIEXPORT jint JNICALL
Java_com_mymedia_app_utils_FFmpegBridge_nativeConcat(
    JNIEnv *env, jobject thiz, jobjectArray j_inputs, jstring j_output, jint handle) {
    int n = (*env)->GetArrayLength(env, j_inputs);
    const char **inputs = calloc(n > 0 ? n : 1, sizeof(char *));
    jstring *jss = calloc(n > 0 ? n : 1, sizeof(jstring));
    for (int i = 0; i < n; i++) {
        jss[i] = (jstring)(*env)->GetObjectArrayElement(env, j_inputs, i);
        inputs[i] = (*env)->GetStringUTFChars(env, jss[i], NULL);
    }
    const char *output = (*env)->GetStringUTFChars(env, j_output, NULL);
    LOGI("concat n=%d out=%s handle=%d", n, output, handle);
    int ret = do_concat(inputs, n, output, handle);
    for (int i = 0; i < n; i++) (*env)->ReleaseStringUTFChars(env, jss[i], inputs[i]);
    (*env)->ReleaseStringUTFChars(env, j_output, output);
    free(inputs);
    free(jss);
    return ret;
}

JNIEXPORT jint JNICALL
Java_com_mymedia_app_utils_FFmpegBridge_nativeSnapshot(
    JNIEnv *env, jobject thiz, jstring j_input, jstring j_output, jlong time_us, jint handle) {
    const char *input = (*env)->GetStringUTFChars(env, j_input, NULL);
    const char *output = (*env)->GetStringUTFChars(env, j_output, NULL);
    LOGI("snapshot in=%s out=%s time_us=%lld handle=%d", input, output, (long long)time_us, handle);
    int ret = do_snapshot(input, output, (int64_t)time_us, handle);
    (*env)->ReleaseStringUTFChars(env, j_input, input);
    (*env)->ReleaseStringUTFChars(env, j_output, output);
    return ret;
}

JNIEXPORT void JNICALL
Java_com_mymedia_app_utils_FFmpegBridge_nativeTaskControl(JNIEnv *env, jobject thiz, jint handle, jint cancel, jint pause) {
    if (handle >= 0 && handle < MAX_TASKS) {
        if (cancel) g_cancel[handle] = 1;
        if (pause) g_pause[handle] = 1;
        if (!cancel && !pause) { g_cancel[handle] = 0; g_pause[handle] = 0; }
    }
}

JNIEXPORT jboolean JNICALL
Java_com_mymedia_app_utils_FFmpegBridge_nativeIsAvailable(JNIEnv *env, jobject thiz) {
#ifdef HAVE_AVFILTER
    LOGI("nativeIsAvailable: FFmpeg %s avformat=%d avcodec=%d avutil=%d atempo=ENABLED(avfilter)",
         av_version_info(), avformat_version(), avcodec_version(), avutil_version());
#else
    LOGI("nativeIsAvailable: FFmpeg %s avformat=%d avcodec=%d avutil=%d atempo=DISABLED(无avfilter，倍速可能变调——需重编FFmpeg)",
         av_version_info(), avformat_version(), avcodec_version(), avutil_version());
#endif
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_mymedia_app_utils_FFmpegBridge_nativeLog(JNIEnv *env, jobject thiz, jstring j_msg) {
    if (j_msg) {
        const char *m = (*env)->GetStringUTFChars(env, j_msg, NULL);
        if (m) {
            log_write(ANDROID_LOG_INFO, "%s", m);
            (*env)->ReleaseStringUTFChars(env, j_msg, m);
        }
    }
}

JNIEXPORT void JNICALL
Java_com_mymedia_app_utils_FFmpegBridge_nativeSetLogFile(JNIEnv *env, jobject thiz, jstring j_path) {
    if (j_path) {
        const char *p = (*env)->GetStringUTFChars(env, j_path, NULL);
        if (p) {
            snprintf(g_log_path, sizeof(g_log_path), "%s", p);
            LOGI("日志文件已设置: %s", g_log_path);
            (*env)->ReleaseStringUTFChars(env, j_path, p);
        }
    } else {
        g_log_path[0] = 0;
    }
}
