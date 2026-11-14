# Fancy Loading

Fabric 1.21.11 客户端加载界面 mod，把**游戏加载**和**世界加载**合在一个 jar 里：

- **A 段（Mixin 引导）**：早期 GLFW 窗口，直接播放 MP4（自动安装 FFmpeg）或帧序列，显示进度条和最新游戏日志。
- **B 段（资源重载 / Mojang logo）**：静态帧背景 + 进度条 + 日志，保留原版 `onFinish`，不会卡 100%。
- **世界加载（`LevelLoadingScreen`）**：全宽进度条 + 视频背景 + 左下角行楷百分比。

> 「Game Loading Screen」和「World Loading Screen」是两件事：
> 前者是 Mojang logo / 资源重载阶段，后者是创建世界、生成出生点的阶段。

## 特性

- 单窗口 GLFW 句柄交接，不闪第二个窗口
- A 段 MP4 直读；B 段用 A 段第一帧自动生成 `background.png`
- 世界加载支持 MP4，回退 jar 内置 75 张 PNG
- 三条进度条以 A 段为基准：全宽、`0.84h`、6 物理像素高、深色底 + 白色填充
- 文字左对齐 `3%`，14 物理像素高，4 方向深色描边
- 日志行：Log4j Appender 原样捕获；B 段空档显示 `Loading resource packs...`
- 字体：LXGW WenKai Lite，外部文件，不进 jar
- FFmpeg：首次使用自动从 GitHub 下载、校验 SHA-256、解压，用户无需安装

## 环境

- Minecraft **1.21.11**
- Fabric Loader **0.18.4+**
- Java **21+**
- 客户端 mod，不依赖 Fabric API

## 构建

```bash
JAVA_HOME=/path/to/jdk-25 \
GRADLE_USER_HOME=/path/to/gradle-home \
./gradlew build --offline
```

产物：

```text
build/libs/fancyloading-0.1.0.jar
```

Loom `1.19-SNAPSHOT` + Mojang mappings，输出 Java 21 字节码。

## 安装

1. 把 `fancyloading-0.1.0.jar` 放进 `mods/`。
2. 字体（推荐）：

```text
config/worldloading/fonts/LXGWWenKaiLite-Regular.ttf
```

下载：[lxgw/LxgwWenKai-Lite](https://github.com/lxgw/LxgwWenKai-Lite)
（OFL；下载目录保留其 `OFL.txt`）。

3. 可选 MP4：

```text
config/videoloading/video.mp4     # A 段
config/worldloading/video.mp4     # 世界加载
```

4. 没有 MP4 时的 A 段回退帧序列：

```text
config/videoloading/frames/frame_000.png ...
```

5. B 段背景无需手放：A 段第一帧会自动写成
   `config/videoloading/background.png`。

配置示例在 `config.example/`。

## 配置

`config/videoloading.properties` 关键项：

```properties
enabled=true
adoptWindow=true
windowWidth=854
windowHeight=480

videoPath=config/videoloading/video.mp4
videoWidth=960
videoHeight=540
videoFps=15

framesDir=config/videoloading/frames
background=config/videoloading/background.png
fontPath=config/worldloading/fonts/LXGWWenKaiLite-Regular.ttf
fps=15
maxHoldMs=3000
mixinShare=0.8

barColor=FFFFFFFF
barBgColor=AA000000
barWidth=1.0
barHeight=6
barY=0.84
```

`config/worldloading.properties` 关键项：

```properties
enabled=true
barWidth=1.0
barHeight=6
barY=0.84
textY=0.78
textPadding=0.03
fontSize=14
fontPath=config/worldloading/fonts/LXGWWenKaiLite-Regular.ttf

videoPath=config/worldloading/video.mp4
videoWidth=960
videoHeight=540
videoFps=15
videoDim=0.18
```

## FFmpeg 自举

首次需要 MP4 时：

- 按 `os.name` / `os.arch` 选平台：Windows x64、Linux x64/arm64、macOS x64/arm64
- 加速站顺序：`gh-proxy.com` → `ghfast.top` → `ghproxy.net` → GitHub 直连
- 下载固定版本 `eugeneware/ffmpeg-static` `b6.1.1`
- 校验固定 SHA-256，解压到：

```text
config/fancyloading/tools/ffmpeg-b6.1.1-<platform>/ffmpeg
```

- `ffmpeg -version` 自检通过才启用；失败/离线回退 PNG 帧序列
- 用户不需要自己安装 FFmpeg
- FFmpeg 构建为 GPLv3，下载目录内保留其 `LICENSE`

## Android / FCL

早期 GLFW 窗口在 FCL 上已验证可用。FFmpeg 的问题不是平台识别，而是 **sdcard 通常 noexec**：
即使 `chmod +x`，从 `/sdcard/...` 也执行不了。

现在工具目录改为：

1. `-Dfancyloading.ffmpegDir=...` 强制指定
2. Android: `Context.getFilesDir()/fancyloading/tools`（应用私有目录）
3. 桌面: `<gameDir>/config/fancyloading/tools`

Android 路径通过反射 `android.app.ActivityThread.currentApplication().getFilesDir()` 获取，
不写 sdcard。若 FCL 运行时拿不到 `Context`，可在 JVM 参数里显式指定：

```text
-Dfancyloading.ffmpegDir=/data/user/0/<FCL包名>/files/fancyloading/tools
```

仍然失败时，说明该 Android 环境不允许执行 app 私有目录（SELinux/`noexec`）或
Linux 静态二进制与 Bionic 不兼容；此时会自动回退 PNG 帧序列。要彻底解决，
需要改用 Android 原生 `.so` / MediaCodec 解码器。

## 目录结构

```text
fancylaoding/
├── src/main/java/com/example/
│   ├── videoloading/       # A/B 游戏加载
│   ├── worldloading/       # LevelLoadingScreen 世界加载
│   └── fancyloading/video/ # FFmpeg 下载/解压 + 原始帧解码
├── src/main/resources/
│   ├── fabric.mod.json
│   ├── videoloading.mixins.json
│   ├── worldloading.mixins.json
│   ├── videoloading.properties.example
│   └── assets/worldloading/textures/loading/
├── config.example/
├── gradle/wrapper/
├── gradlew
└── README.md
```

## 实现备注

- A 段 GL 用 **framebuffer 物理像素**；B 段 / 世界加载用 **GUI 缩放坐标**。
  因此后两者的 `barHeight`、字号会除以 `guiScale`，视觉尺寸与 A 一致。
- `early/` 包不能 import Minecraft 类，否则会在 Mixin 引导期
  触发 `MixinTargetAlreadyLoadedException`。
- 当前 MixinExtras 0.5.0 + ASM 9.9 环境下不要用 `@Redirect`；
  窗口接管用 `@WrapOperation`，屏幕接管用 `@Inject`。
- 字体是外部文件；缺少字体时只画条，不阻断启动。

## 已知限制

- 首次播放 MP4 需要联网下载 FFmpeg；离线环境可预置 `config/fancyloading/tools/`。
- FFmpeg 下载走 GitHub/加速站；网络受限时回退 PNG 序列。
- 世界加载的 MP4 通过 `DynamicTexture` 每帧更新，低端 GPU 上比 PNG 序列更吃带宽。
- 与 Fox 的 `fox-party-hud` 同时安装时，对方的 `WorldLoadingScreenMixin` 会取消
  `LevelLoadingScreen.render`，本 mod 的世界加载画面会被它覆盖；两者不要同时用。

## License

MIT，见 [`LICENSE`](LICENSE)。
