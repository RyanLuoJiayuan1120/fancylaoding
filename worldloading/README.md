# World Loading Bar — Fabric 1.21.11 世界加载界面

接管 `LevelLoadingScreen`（单人世界创建 / 生成出生点 / 进世界）的渲染：

- 去掉原版彩色 chunk 网格和原版进度条
- 全宽进度条（`0.84h`，6 物理像素高）
- MP4 视频背景（自动安装 FFmpeg）；没有 MP4 时回退 75 张 480x270 PNG（随 jar 分发）
- 左下角行楷百分比文字（LXGW WenKai Lite，外部字体）
- 进度单调递增，不因重试/回退跳变

> 这是 **World Loading Screen**，不是 Mojang logo 的 **Game Loading Screen**。
> 游戏加载（A/B 段、早期窗口、静态帧、日志行）在另一个 mod
> `videoloading` 中。

## 运行环境

- Minecraft **1.21.11**
- Fabric Loader **0.18.4+**
- Java **21+**
- 客户端 mod（`environment: client`）
- 不依赖 Fabric API

## 安装

1. 把 `worldloading-0.1.0.jar` 放进 `mods/`。
2. 字体可选但推荐：下载 [LXGW WenKai Lite](https://github.com/lxgw/LxgwWenKai-Lite)
   的 `LXGWWenKaiLite-Regular.ttf`，放到：
   `config/worldloading/fonts/LXGWWenKaiLite-Regular.ttf`
   字体不进 jar；缺失时只画条。
3. 首次启动生成 `config/worldloading.properties`。

## 配置

```properties
enabled=true
barWidth=1.0
barHeight=6
barY=0.84
textY=0.78
textPadding=0.03
showText=true
fontSize=14
fontPath=config/worldloading/fonts/LXGWWenKaiLite-Regular.ttf
estimateK=7.0
estimateCap=0.95
barColor=FFFFFFFF
barBgColor=AA000000

videoEnabled=true
videoFps=15
videoFrames=75
videoWidth=480
videoHeight=270
videoDim=0.18
videoPath=config/worldloading/video.mp4
```

- `barHeight` 是**物理像素**；`BarRenderer` 会除以 GUI scale，和 A 段基准一致。
- `textY` 是文字中心的高度比例；文字左对齐 `3%`。
- `estimateK` / `estimateCap`：没有真实进度时的估算曲线，封顶 0.95，永不到 1.0。

## 视频背景

优先使用 `videoPath` 的 MP4，由共享的 FFmpeg 自举器按平台下载/校验/解压：

```text
config/worldloading/video.mp4
config/fancyloading/tools/ffmpeg-b6.1.1-<platform>/ffmpeg
```

- 下载逻辑与 `videoloading` 相同：GitHub release + 加速站 + SHA-256 + 自检
- 下载失败/没有 MP4 时，回退 jar 内的 `frame_000.png ... frame_074.png`
- COVER 缩放每帧重算，不缓存 drawable 尺寸
- `videoDim` 是叠加的黑色透明度（0–0.9）

换视频：改 `videoPath`，或重新导出 75 张同名帧替换资源。

## 构建

```bash
JAVA_HOME=/path/to/jdk-25 \
GRADLE_USER_HOME=/path/to/gradle-home \
./gradlew -p worldloading build --offline
```

产物：

```text
worldloading/build/libs/worldloading-0.1.0.jar
```

Loom 1.19-SNAPSHOT + Mojang mappings；输出 Java 21。

## 代码结构

```text
src/main/java/com/example/worldloading/
├── WLConfig.java            # config/worldloading.properties
├── WorldProgress.java       # 单调进度 + 估算
├── BarRenderer.java         # 全宽条 + 文字入口
├── XingkaiFont.java         # AWT -> DynamicTexture；LXGW 字体
├── VideoBackground.java     # MP4（FFmpeg）或 75 帧 PNG 背景
├── WorldLoadingClient.java  # client entrypoint
└── mixin/LevelLoadingScreenMixin.java
```

Mixin 关键：

- `renderChunks(..., ChunkLoadStatusView)`：HEAD cancel，去掉 chunk 网格
- `drawProgressBar(...)`：HEAD cancel，去掉原版条
- `render(...)`：TAIL 画视频 + 条 + 文字
- `onClose(...)`：结束视频/进度状态

## 已知限制

- 当前 Fabric / MixinExtras 0.5.0 环境下不要用 `@Redirect`（会抛
  `ArrayList cannot be cast to AnnotationNode`）；这里用 `@Inject`。
- 1.21.11 没有 `ReceivingLevelScreen` / `GenericDirtMessageScreen` /
  `StoringChunkProgressListener`；只针对 `LevelLoadingScreen`。
- 首次使用 MP4 需要联网下载 FFmpeg；离线环境请预置 `config/fancyloading/tools/`。
- 字体为外部文件；不进 jar。
- 与 `videoloading` 同时安装时，共享 video 类会触发 Fabric duplicate class 警告；
  两份字节码一致，当前不影响功能。

## License

MIT（见 `fabric.mod.json`）。
