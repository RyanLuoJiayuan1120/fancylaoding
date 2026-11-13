# Fancy Loading

Fabric 1.21.11 客户端加载界面合集，包含两个可独立安装的 mod：

| 模块 | 作用 | 主要产物 |
|---|---|---|
| [`videoloading`](videoloading/) | **游戏加载**：Mixin 引导阶段建早期窗口，A 段播帧序列 + 进度条；B 段静态帧 + 日志 + 进度条 | `videoloading-0.1.0.jar`（约 44 KB，资源外置） |
| [`worldloading`](worldloading/) | **世界加载**：接管 `LevelLoadingScreen`，全宽条 + 帧序列视频背景 + 行楷百分比 | `worldloading-0.1.0.jar`（约 19 MB，视频帧内置） |

> 「Game Loading Screen」和「World Loading Screen」是两件事：
> 前者是 Mojang logo / 资源重载阶段，后者是创建世界、生成出生点的阶段。

## 特性

- **A 段早期窗口**：Mixin 引导阶段就显示视频帧、进度条和最新游戏日志。
- **单窗口交接**：同一个 GLFW 句柄 / GL 上下文交给 Minecraft，不闪第二个窗口。
- **B 段不卡死**：保留原版 `LoadingOverlay` 的 `onFinish`，只在 `TAIL` 覆盖绘制。
- **B 段静态帧**：不再渲染视频，用 `background.png` 作为背景，避免资源重载期做视频纹理。
- **日志行**：Log4j Appender 捕获原样日志；A/B 各显示最新一行；B 段空档显示
  `Loading resource packs...`。
- **统一进度条**：以 A 段为基准，三条都是全宽、`0.84h`、6 物理像素高、
  深色底 + 白色填充、文字左对齐 `3%`。
- **LXGW WenKai Lite**：v2 百分比和 v1 日志行共用同一外部 TTF；字体不进 jar。
- **worldloading 内置视频帧**：75 张 480×270 PNG，15 fps，COVER 缩放。

## 环境

- Minecraft **1.21.11**
- Fabric Loader **0.18.4+**
- Java **21+**
- 客户端 mod，不依赖 Fabric API

## 构建

在仓库根目录：

```bash
# 游戏加载（A/B 段）
./gradlew -p videoloading build

# 世界加载
./gradlew -p worldloading build
```

产物：

```text
videoloading/build/libs/videoloading-0.1.0.jar
worldloading/build/libs/worldloading-0.1.0.jar
```

当前开发环境用 Loom `1.19-SNAPSHOT` + Mojang mappings，输出 Java 21 字节码。
离线构建需要预先缓存 Loom / Gradle 依赖；CI 或新机器直接联网构建即可。

## 安装

把需要的 jar 放进 `mods/`。两个 mod 可以同时安装：一个管游戏加载，一个管世界加载。

### 外部资源

字体（两个 mod 共用，可选但推荐）：

```text
config/worldloading/fonts/LXGWWenKaiLite-Regular.ttf
```

下载：[lxgw/LxgwWenKai-Lite](https://github.com/lxgw/LxgwWenKai-Lite)
（OFL 许可，仓库内附 `OFL.txt` 时应一并保留）。

`videoloading` 还需要：

```text
config/videoloading.properties
config/videoloading/frames/frame_000.png ...   # 帧序列，按文件名排序
config/videoloading/background.png             # B 段静态帧，从视频任意截一帧
```

`worldloading` 需要的视频帧已打进 jar，只需要字体。

配置示例见 [`config.example/`](config.example/)；完整说明见各模块 README：

- [`videoloading/README.md`](videoloading/README.md)
- [`worldloading/README.md`](worldloading/README.md)

## 目录结构

```text
fancylaoding/
├── videoloading/          # 早期窗口 + A/B 游戏加载
│   ├── src/main/java/...
│   └── src/main/resources/
├── worldloading/          # LevelLoadingScreen 世界加载条
│   ├── src/main/java/...
│   └── src/main/resources/assets/worldloading/textures/loading/
├── config.example/
├── gradle/wrapper/
├── gradlew
└── README.md
```

## 实现备注

- A 段 GL 用 **framebuffer 物理像素**；B 段 / v2 用 **GUI 缩放坐标**。
  因此 B/v2 的 `barHeight`、字号会除以 `guiScale`，视觉尺寸与 A 一致。
- 早期代码（`early/` 包）不能 import Minecraft 类，否则会在 Mixin 引导期
  触发 `MixinTargetAlreadyLoadedException`。
- 当前 MixinExtras 0.5.0 + ASM 9.9 环境下不要用 `@Redirect`；
  窗口接管用 `@WrapOperation`，屏幕接管用 `@Inject`。
- 字体是外部文件，不打包；缺少字体时只画条，不阻断启动。

## 已知限制

- `MixinProbe` 在该 Fabric Loader 上可能计数为 0，A 段进度会走时间保底。
- B 段日志行使用 `DynamicTexture`；若个别环境看不到该行，A 段日志和进度条仍正常。
- 视频帧/字体体积较大，仓库不提交构建产物和字体文件；按上面的资源路径自行放置。

## License

MIT，见 [`LICENSE`](LICENSE)。
