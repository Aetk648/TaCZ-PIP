# TaCZ PIP

把 [TaCZ（永恒枪械工坊：零）](https://modrinth.com/mod/timeless-and-classics-zero) 枪械瞄准镜的
"全屏幕放大" 改成 "只在镜片内放大"（Picture-In-Picture）的客户端模组。

- 游戏版本：Minecraft 1.21.1
- 加载器：NeoForge 21.1.x
- 作者：I50208
- 许可证：MIT（见 [LICENSE](LICENSE)）

## 功能

- 开镜后只有瞄准镜镜片内部按瞄具自身倍率放大，镜外保持正常视野；
- 倍率直接读取 TaCZ 瞄具数据（枪包 display 的 `zoom` 数组与 `zoom_number`），自动适配所有枪包；
- 高倍镜十字线、低倍红点/全息各自按描述倍率呈现，分划板位置与弹道方向对齐；
- 对 Iris 光影与 Sodium / Voxy 渲染器做了专门适配：镜内实体走独立实心补画通道，
  开光影也清晰不透明；后坐力帧画面不空、不出现透明圈；
- 纯客户端模组，服务端不需要安装。

## 依赖

| 依赖 | 作用 | 必须 |
| --- | --- | --- |
| Minecraft 1.21.1 + NeoForge 21.1.x | 运行环境 | 是 |
| TaCZ（tacz） | 本体，玩家自行安装 | 是（运行期） |
| Iris / Sodium / Voxy | 光影与渲染优化，自动检测适配 | 否 |

## 构建

需要 JDK 21 和 Gradle 8.12+。仓库自带 `libs/tacz-*-compile-only.jar`（从 TaCZ 发行 jar 中
只提取 class 文件的编译期子集，约 1.5MB），clone 下来不需要下载任何东西：

```bash
gradle build
```

（想以后用 `./gradlew` 的话，先执行一次 `gradle wrapper --gradle-version 8.12` 生成包装器。）

产物在 `build/libs/TaCZ PIP-<版本号>.jar`。

如需对其他 TaCZ 版本编译：下载对应发行 jar，解压后只保留 `*.class` 重新打包成
`libs/tacz-<版本>-compile-only.jar` 替换即可。

## 目录结构

```
src/main/java/com/i50208/taczpip/
├── ScopeLensMod.java          # 模组入口，注册配置与事件
├── client/                    # 镜内渲染主体
│   ├── LensZoomState.java     # 每帧状态机：开镜进度、小视野取景、补画名单
│   ├── LensZoomConfig.java    # 客户端配置项
│   ├── ShaderLensCompositor.java  # 镜片遮罩/放大/分划板的 GL 合成
│   ├── LensEntitySupplement.java  # 镜内实体独立补画通道
│   ├── IrisLensPipelineSwap.java  # Iris 光影下的管线摘换
│   ├── IrisCompat / VoxyLensCompat # 光影与渲染器兼容探测
│   ├── LensCaptureStrategy / LensCaptureProbe # 取景路线选择与自检
│   ├── ReticleRenderer / ScopeLensGuiOverlay  # 分划板与 HUD 时机
│   └── ScopedGlState.java     # GL 状态保存/恢复
└── mixin/                     # 注入点（accessor 与少量行为改写）

src/main/resources/
├── META-INF/neoforge.mods.toml    # 模组元数据
├── scope_lens_zoom.mixins.json    # mixin 配置
├── icon.png                       # 模组图标
└── assets/scope_lens_zoom/shaders/lens/  # 镜片合成用的 GLSL
```

## 致谢

- TaCZ 开发组（MUKSC / MCModderAnchor）提供枪械与瞄具框架。
