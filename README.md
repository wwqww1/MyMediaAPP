# 我的多媒体（MyMedia）

一个**纯本地运行**的 Android 音视频处理工具箱。分离、转换、裁剪、拼接、转 GIF、截图全部在手机上完成——不联网、不上传、不需要电脑。

> ## ⚠️ 关于这个项目
> 本项目使用 Kotlin + Jetpack Compose 开发，核心音视频处理基于 FFmpeg。
> 欢迎提 Issue 交流。

## 功能

**音视频工具**

| 工具 | 说明 |
| --- | --- |
| 分离音频 | 从视频里提取音轨 |
| 分离视频 | 从视频里提取纯画面（零重编码，不掉画质） |
| 音频转换 | `.m4a` / `.mp3` / `.wav` / `.flac` |
| 视频转换 | mp4（H.264 / H.265 硬编）、mp4（MPEG-4）、avi、gif |
| 时间裁剪 | 可视化时间轴截取片段 |
| 音视频拼接 | 多段合并；同格式输入直接流复制，无损 |
| 视频转 GIF | 画质 360p ~ 4K 可选、帧率 10 ~ 60fps 可选 |
| 视频截图 | 指定时刻抽帧，存 jpg / png |

**通用**

- 任务队列：并发数可调、可暂停 / 取消，未完成任务重启后可恢复
- 产出文件管理、历史记录
- 主题三选（跟随系统 / 浅色 / 深色）
- 宽屏 / 横屏自适应（自动切 NavigationRail）、异形屏适配

## 技术栈

| 层 | 用的什么 |
| --- | --- |
| UI | Kotlin + Jetpack Compose（Material 3） |
| 硬编解码 | Android MediaCodec（H.264 / H.265） |
| 软编软解 | FFmpeg 7.0（JNI + C，`app/src/main/cpp/ffmpeg_jni.c`） |
| 音频编码 | FFmpeg 原生 aac / wav / flac + libmp3lame 3.100（GPL） |
| 构建 | AGP 8.2.0 / Kotlin 1.9.20 / CMake 3.22.1 / Gradle 8.5 |

- `minSdk 21`（Android 5.0）/ `targetSdk 34`
- 只保留 `arm64-v8a` 单架构（放弃 32 位老设备，APK 体积减半）

## 目录结构

```
MyMediaApp/
├─ app/
│  ├─ build.gradle.kts
│  └─ src/main/
│     ├─ AndroidManifest.xml
│     ├─ java/com/mymedia/app/      Kotlin 源码
│     │  ├─ MainActivity.kt         入口 + 整体布局 / 导航
│     │  ├─ model/                  数据模型
│     │  ├─ ui/screens/             各页面（首页 / 任务 / 文件 / 设置 / 工具配置 / 引导页）
│     │  ├─ ui/theme/               配色与主题
│     │  └─ utils/                  FFmpegBridge / MediaConverter / TaskManager 等
│     ├─ cpp/                       ffmpeg_jni.c + CMakeLists.txt
│     └─ res/                       图标与资源
├─ FFmpeg编译/
│  └─ output/arm64/                 预编译的 FFmpeg 7.0 + libmp3lame 3.100（arm64 lib/*.so + include/）
│  └─ CMakeLists.txt                FFmpeg 交叉编译构建脚本（已在仓库）
│  └─ build.sh                      编译脚本（已在仓库）
│  └─ 源码需自行下载，见下方「FFmpeg 源码说明」
├─ gradle/wrapper/
├─ build.gradle.kts
├─ settings.gradle.kts
├─ gradlew
└─ APK/                             打包签名好的 APK 放这里（见 APK/README.md）
```

## 怎么构建

1. 用 Android Studio 打开**仓库根目录**（不是 `app/` 子目录）
2. 需要装好 **NDK** 和 **CMake 3.22.1**
3. `Build → Build APK(s)`，或 `Build → Generate Signed APK / Bundle`

FFmpeg 已经编译好放在 `FFmpeg编译/output/arm64/`，`CMakeLists.txt` 会自动探测，**不需要自己编译 FFmpeg**。

## 第三方开源组件声明

本项目使用了以下第三方开源组件，按 GPL-3.0 协议要求声明如下：

### FFmpeg

本应用使用了 FFmpeg 7.0（"Dijkstra"）及其部分库：

- 版本：7.0（发布代号 Dijkstra）
- 源码：https://ffmpeg.org/releases/ffmpeg-7.0.tar.xz
- 版权：Copyright (c) FFmpeg developers (https://ffmpeg.org/)
- 许可证：GNU General Public License v2 or later
  （本项目编译 FFmpeg 时启用了 --enable-gpl，故许可证为 GPL v2+；
  完整的 GPL 许可文本见本仓库 LICENSE 文件）
- 本项目使用的库版本：libavutil 59.8.100 / libavcodec 61.3.100 /
  libavformat 61.1.100 / libavfilter 10.1.100 /
  libswresample 5.1.100 / libswscale 8.1.100
- FFmpeg 7.0 官方源码完整副本见本项目 Releases 页面的附件。

### libmp3lame

- 版本：LAME 3.100
- 源码：https://lame.sourceforge.io/
- 许可证：GNU Lesser General Public License v2.1 or later

> **合规说明：** 本项目采用 GPL-3.0，GPL-3.0 与 FFmpeg 的 GPL v2+ 兼容，libmp3lame 的 LGPL v2.1 链接进 GPL 项目也符合许可要求。

### 关于签名

仓库里**不含任何签名密钥**。release 签名从根目录的 `local.properties` 读取（该文件不纳入版本控制）：

```properties
RELEASE_STORE_FILE=你的 keystore（相对仓库根目录的路径）
RELEASE_STORE_PASSWORD=密钥库口令
RELEASE_KEY_ALIAS=别名
RELEASE_KEY_PASSWORD=别名口令
```

不配也能编译，只是 release 包不带签名，需要自己在 Android Studio 里指定 keystore。

## 支持的编码器 / 输出格式

FFmpeg 7.0 + libmp3lame 3.100：

| 编码器 | 输出 |
| --- | --- |
| aac（原生） | `.m4a` |
| libmp3lame（LAME 3.100） | `.mp3` |
| wav（pcm_s16le 等） | `.wav` |
| flac（原生无损） | `.flac` |
| mpeg4（原生） | `.mp4` |
| mjpeg（原生） | `.jpg` 截图 |
| gif（原生，PAL8 + 抖动） | `.gif` |
| png（原生） | `.png` |

## 说明

- **全程离线**：没有声明任何网络权限，不联网、不上传任何文件
- 文件读写走系统文件选择器（SAF），不需要读媒体库权限；只有 Android 9 及以下写入公共目录才用 `WRITE_EXTERNAL_STORAGE`
- 首次启动会让选一个输出目录，之后所有产出都存那里

## 开源协议

本项目采用 **GNU General Public License v3 (GPL-3.0)** 开源。

- **版权人：** 谈水君
- **许可证：** [GPL-3.0](https://www.gnu.org/licenses/gpl-3.0.html)
- **协议全文：** 见 [LICENSE](LICENSE)

### 你可以
- 自由使用、修改、分发本项目源码
- 商业使用（需开源修改后的完整源码）

### 你必须
- 在分发时保留开源协议和版权声明
- 在修改后的文件中注明做了哪些改动
- 在基于本项目修改的成品中注明使用了本项目

### 你不能
- 将本项目闭源商业分发
- 在盈利产品中直接使用而不开源

> 💡 **GPL 的核心：** 如果你用 GPL 代码开发了自己的产品并发布，必须也用 GPL 开源你的产品源码。这是保护开源、维护社区的原则。
