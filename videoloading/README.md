# Video Loading — Fabric 1.21.11 早期加载窗口

在 Mixin 引导阶段创建 GLFW 窗口，播放帧序列视频 + 进度条；随后把**同一个 GLFW
句柄 / GL 上下文**交接给 Minecraft。A 段（Mixin 引导）和 B 段（资源重载）都有
进度条、百分比和游戏加载日志行。

## 运行环境

- Minecraft **1.21.11**
- Fabric Loader **0.18.4+**
- Java **21+**
- 客户端 mod（`environment: client`）
- 不依赖 Fabric API

## 当前行为

| 阶段 | 背景 | 进度来源 | 文字 |
|---|---|---|---|
| A 段：Mixin 引导 → 创建游戏窗口 | `config/videoloading/frames/` 帧序列 | `MixinProbe` 计数；不可用时按时间保底 | 最新游戏日志 + `xx%` |
| B 段：资源加载（原版 Mojang logo 阶段） | `config/videoloading/background.png` 静态帧 | `ResourceReload#getProgress()` | 最新游戏日志（空档显示 `Loading resource packs...`）+ `xx%` |

- A 段视频按 `fps` 播放；`maxHoldMs` 到点强制交接，不会让长视频拖慢启动。
- B 段原版 `LoadingOverlay.render` 正常执行（保留 `onFinish`，不会卡在 100%），
  本 mod 在 `TAIL` 用 `GuiGraphics` 覆盖静态背景、进度条和文字。
- 日志行来自 Log4j Appender，原样显示最新一条 INFO/WARN/ERROR 日志。
- 进度条基准尺寸按 **物理像素** 定义：全宽、`0.84h`、6px 高、深色底 + 白色填充；
  B 段会把 `barHeight` 除以 GUI scale，保持一致。

## 安装

1. 把 `videoloading-0.1.0.jar` 放进 `mods/`。
2. 准备视频帧：PNG/JPG 按月文件名排序，放到
   `config/videoloading/frames/`（当前测试用 75 张 480x270、15fps）。
3. 准备 B 段静态背景：从视频任意截一帧，保存为
   `config/videoloading/background.png`。
4. 准备字体（可选，但当前文字依赖它）：
   下载 [LXGW WenKai Lite](https://github.com/lxgw/LxgwWenKai-Lite) 的
   `LXGWWenKaiLite-Regular.ttf`，放到
   `config/worldloading/fonts/LXGWWenKaiLite-Regular.ttf`。
   字体不进 jar，缺失时只画条/不画文字。
5. 首次启动会生成 `config/videoloading.properties`，按需修改。

## 配置

`config/videoloading.properties`：

```properties
enabled=true
adoptWindow=true
windowWidth=854
windowHeight=480

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

- `windowWidth/Height` 必须和游戏最终窗口尺寸一致，避免交接时 resize。
- `barHeight` 是物理像素；`barY` 是视口高度比例。
- `mixinShare=0.8` 表示 A 段占 0–80%，B 段占 80–100%。

## 构建

```bash
JAVA_HOME=/path/to/jdk-25 \
GRADLE_USER_HOME=/path/to/gradle-home \
./gradlew -p videoloading/videoloading build --offline
```

产物：

```text
videoloading/videoloading/build/libs/videoloading-0.1.0.jar
```

构建脚本使用 Loom 1.19-SNAPSHOT + Mojang mappings，输出 Java 21 字节码。

## 代码结构

```text
src/main/java/com/example/videoloading/
├── early/
│   ├── EarlyBootstrap.java     # Mixin 配置插件；最早建窗
│   ├── EarlyWindow.java        # 建窗、渲染线程、handoff
│   ├── EarlyRenderer.java      # GL 3.2：视频/静态背景/条/位图百分比/日志纹理
│   ├── FrameSequence.java      # 帧序列解码（按 fps 限速）
│   ├── LoadingLog.java         # Log4j Appender + 日志环形缓冲
│   ├── MixinProbe.java         # transform 计数（失败降级）
│   ├── ProgressBus.java        # 进度总线
│   ├── VLConfig.java           # 手写 Properties 配置
│   └── PreLaunchHook.java      # preLaunch 重试安装 probe
├── client/
│   ├── VideoLoadingClient.java # client 初始化 / Drippy 互斥
│   ├── LogTexture.java         # B 段日志行（AWT -> DynamicTexture）
│   └── StaticBackground.java   # B 段静态帧（延迟建 GPU 纹理）
└── mixin/
    ├── WindowMixin.java        # @WrapOperation glfwCreateWindow 接管句柄
    └── SplashOverlayMixin.java # B 段 TAIL 覆盖
```

## 已知限制

- `MixinProbe` 在当前 Fabric Loader 上计数为 0，A 段进度主要靠时间保底；
  日志会打印 `probe=0.00(installed=true,count=0)`，属预期降级。
- B 段日志行使用 `DynamicTexture + GuiGraphics.blit`；最后一次自动化截图里
  没看到该行（A 段正常），如果后续需要可单独排查纹理管线。
- 早期代码不能引用 Minecraft 类；不要在 `early/` 包中 import MC。
- 本 mod 不打包字体；LXGW 文楷 Lite 为 OFL 字体，仍按外部文件加载。
- `@Redirect` 在当前 MixinExtras 0.5.0 环境会崩，接管窗口用的是 `@WrapOperation`。

## License

MIT（见 `fabric.mod.json`）。
