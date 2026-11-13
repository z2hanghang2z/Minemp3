# 手持音乐播放器模组 — 开发计划与技术栈

> 目标版本：Minecraft **26.3** / Fabric / Java **25**
> 文档状态：v1.1 — 方案二 + 纯手持 + 接受 2D 音效 · 最后更新 2026-11-12
> 配套文档：`docs/技术栈方案.md`、`docs/市场调查-MC音乐播放器技术栈.md`、`docs/UI方案.md`

---

## 一、已定决策

| 项 | 决定 |
|---|---|
| 播放方式 | 模组内自己解码播放，不遥控本机网易云客户端 |
| 技术栈 | **方案二**：原版 `AudioStream` 管线 + Mixin 取 source 挂低通滤镜 |
| 形态 | **纯手持物品**，不可落地方块 |
| 交互 | **手持状态下即可操作**，不强制打开界面 |
| 音效定位 | **接受 2D**：不做距离衰减与遮挡，只保留环境低通 |
| 歌词 | 不做 |
| UI | 原版像素风，只留核心歌曲信息 |
| 依赖 | 纯模组本体，不装任何外部组件 |

**不在范围内**：BetterNCM / InfLink-rs / SMTC / bridge 进程 / 歌词 / 方块形态。

---

## 二、三个硬骨头

### 1. 登录与会员

网易云没有公开的第三方播放授权。唯一路径：**游戏内渲染二维码 → 手机网易云 App 扫码 → 拿 cookie 存本地**。

- 无 cookie：只能 128k mp3，大量歌曲"无版权"
- 普通账号：大部分可用，VIP 歌单/高音质受限
- 会员账号：可 `exhigh` / `lossless`（lossless 是 flac，解码要多一套）

MVP 只做 mp3（`standard` / `higher` / `exhigh`）。

### 2. 版权与分发

模组不含任何音频，运行时从 CDN 拉取。三条底线：

1. 不打包音频文件进 jar
2. 不内置账号凭据
3. 不对外分发带个人 cookie 的构建

### 3. 音频引擎（方案二）

```
网络 mp3 流
   ↓  mp3spi + jlayer（Java Sound SPI）
16bit PCM
   ↓  实现原版 AudioStream
原版 SoundEngine（自动管 source、生命周期、音量分类）
   ↓  Accessor Mixin 取 ChannelHandle.source
AL_DIRECT_FILTER 低通  ←  射线遮挡检测（每 5 tick）
```

**为什么这么选**：`AudioStream` 让你白嫖 3D 定位、衰减、音量联动、暂停回收；Mixin 只补一个"原版不给你"的能力——挂滤镜。比全自管 OpenAL 少一半风险，比纯原版多一个卖点。

**关键约束**：AL 调用必须在 sound engine 线程执行。做法是把命令塞进 `SoundEngine` 的 executor 队列，禁止在渲染线程直接调 `AL10`。

---

## 三、技术栈

### 3.1 游戏侧

| 项 | 选型 | 说明 |
|---|---|---|
| 游戏版本 | Minecraft 26.3 | 2026-09-15 稳定版 |
| 加载器 | Fabric Loader 0.20.1 | 26.3 官方支持 |
| API | Fabric API 0.166.2+26.3 | 已确认可用 |
| Java | **25** | `java-runtime-epsilon` |
| 映射 | **mojmap** | 26.x 起 Yarn 无映射 |
| 构建 | Gradle + Fabric Loom | 以官方 `fabric-example-mod` 26.x 分支为基线 |
| GUI | 原版 `Screen` + 自绘 Widget | 不引第三方 UI 库 |
| HUD | Fabric API `HudRenderCallback` | 1.21.6+ 渲染 API 已大改 |

### 3.2 网络层

| 项 | 选型 |
|---|---|
| HTTP | JDK `HttpClient` |
| JSON | Gson（MC 内置） |
| 加密 | 自实现 eapi / weapi，集中在 `Crypto` 一个类 |
| 登录 | 二维码 unikey + 轮询 |
| 拉流 | CDN 直链 + `Range` 分段，边下边播 |
| 并发 | 单下载线程 + 单解码线程 + 有界队列 |

### 3.3 音频层（方案二）

| 项 | 选型 | 说明 |
|---|---|---|
| 解码 | mp3spi 1.9.5.4 + jlayer 1.0.1.4 + tritonus-share + jflac | Net Music 同款，约 1.2MB |
| 播放 | 原版 `AudioStream` + `AbstractTickableSoundInstance` | 零成本拿到 3D 管线 |
| 滤镜 | Accessor Mixin → `ChannelHandle.source` → `AL_DIRECT_FILTER` | **项目最大技术风险点** |
| 低通用途 | 环境低通：水下 / 潜行 / 戴头盔 / 开界面时压低截止频率 | 不做遮挡射线——声源在玩家身上，射线起点终点重合 |
| 进度 | 已解码采样数 ÷ 采样率 | 不受网络抖动影响 |
| 抽象 | `AudioSink` 接口隔离实现 | 见下 |
| 打包 | jar-in-jar + **包名 relocate** | 否则和 Net Music 冲突 |

### 3.4 抽象层（防止未来重写）

```java
public interface AudioSink {
    void play(TrackSource src, SourcePos pos);
    void pause();
    void seek(long ms);
    void setLowpass(float cutoffHz);
    void setVolume(float v);
    void dispose();
}
```

- `VanillaAudioSink`：纯原版管线，低通是空实现 → 万一 Mixin 方案翻车，退回这个还能用
- `FilteredAudioSink`：方案二完整版

---

## 四、架构

```
┌──────────────────── Minecraft 客户端进程 ────────────────────┐
│                                                              │
│  UI / 交互层                                                  │
│  ├─ HandheldPlayerItem   物品 + 右键 / 潜行右键 / 滚轮         │
│  ├─ PlayerScreen         主界面（176×166 口袋机布局）          │
│  ├─ NowPlayingHud        右下角信息条（手持时常显）            │
│  └─ LoginScreen          二维码登录                            │
│                          │                                    │
│  业务层                   ▼                                    │
│  ├─ PlaybackController   状态机 IDLE→BUFFERING→PLAYING→PAUSED │
│  └─ PlaylistService      歌单 / 每日推荐 / 搜索                │
│                          │                                    │
│  网络层                   ▼                                    │
│  ├─ NeteaseClient        eapi + HttpClient + cookie           │
│  └─ StreamFetcher        Range 分段拉流 → 有界队列             │
│                          │                                    │
│  音频层                   ▼                                    │
│  ├─ Mp3Decoder           Java Sound SPI                       │
│  ├─ AudioStreamImpl      实现原版 AudioStream                  │
│  ├─ MusicSoundInstance   extends AbstractTickableSoundInstance│
│  ├─ AudioSink            接口（Vanilla / Filtered）            │
│  └─ EnvironmentFilter    水下 / 潜行 / 界面 → 低通参数         │
│                                                              │
└──────────────────────────────────────────────────────────────┘
```

**数据流**：选歌 → 取直链 → 拉流 → 解码 → `AudioStream` → 原版出声 → 采样数回写 → UI 更新

---

## 五、手持交互设计（核心体验）

「手持在手上时可以进行交互」——具体落到这些操作：

| 操作 | 行为 |
|---|---|
| **右键** | 播放 / 暂停（最快路径，不打断游戏） |
| **潜行 + 右键** | 打开 / 关闭播放器界面 |
| **滚轮**（手持时） | 音量 ±5% |
| **`[` / `]`** | 上一首 / 下一首 |
| **`P`** | 播放 / 暂停（与右键等效，可改键） |
| **界面内方向键** | ↑↓ 切歌，←→ 快退快进，Enter 播放暂停 |
| **界面内拖动进度条** | seek |

**反馈闭环**（"手持感"靠这些立住）：

1. 按下右键时物品有 **1px 下沉**动画
2. 同时播放一声 **"咔哒"** 音效（自定义 ogg，音量跟随主音量）
3. 物品屏幕上的进度条 **2 帧循环爬动**（播放中），暂停时定格
4. 切歌时屏幕闪一下白色 1 帧，模拟"换碟"

---

## 六、模块与包结构

```
com.yourname.handheldmusic
├─ HandheldMusicClient
├─ item/
│   ├─ HandheldPlayerItem      右键 / 潜行右键 / 滚轮
│   └─ ModItems
├─ client/
│   ├─ screen/                 PlayerScreen / SearchScreen / PlaylistScreen / LoginScreen
│   ├─ hud/                    NowPlayingHud
│   ├─ widget/                 进度条 / 封面卡 / 列表项 / 机械按键
│   └─ keybind/                快捷键
├─ playback/
│   ├─ PlaybackController
│   ├─ PlaybackState
│   └─ AudioSettings
├─ audio/
│   ├─ Mp3Decoder
│   ├─ AudioStreamImpl
│   ├─ MusicSoundInstance
│   ├─ sink/                   AudioSink / VanillaAudioSink / FilteredAudioSink
│   └─ EnvironmentFilter       环境低通映射
├─ mixin/
│   └─ SoundEngineAccessor     取 ChannelHandle.source
├─ netease/
│   ├─ NeteaseClient
│   ├─ Crypto
│   ├─ SessionStore
│   └─ model/
└─ util/
```

---

## 七、分阶段计划表

> **P0 不通过就不进 P1**——这是硬门槛。

### P0 · 技术验证（4–6 人日）⚑

| 编号 | 任务 | 验收标准 |
|---|---|---|
| P0.1 | 26.3 + Java 25 + Loom + mojmap 空工程跑通 | ✅ 构建通过（`build/libs/handheld-music-player-0.1.0.jar`），待进游戏实测 |
| P0.2 | `AudioStream` + `SoundInstance` 播本地 PCM | ✅ **链路已验证**：合成音进原版管线，`getStream` 拦截成功、channel 建立、无异常 |
| P0.3 | Java Sound 解码本地 mp3 → PCM | ✅ **已打通**：mp3spi 1.9.5.4 + jlayer，`run/handheldmusic/test.mp3` 完整播完，无读流错误 |
| P0.4 | **Mixin 取 `ChannelHandle.source` 并挂低通** | ✅ **已验证**：`instanceToChannel` 取到 source，EFX 低通滤镜挂上成功 |
| P0.5 | eapi 加密 + 二维码登录（独立 Java 程序先验） | ✅ **已完成**：二维码登录实测成功（weapi + 设备伪 cookie 破解 8821），cookie 方案也保留 |
| P0.6 | `/song/url/v1` 直链 + Range 拉流 | ✅ **已完成**：模组内搜索→取直链→下载→播放全通，0 读流错误 |

**P0.4 若失败**：退回 `VanillaAudioSink`，项目照常推进，只是失去"隔墙变闷"。

### P1 · 播放引擎（5–7 人日）

| 编号 | 任务 | 验收标准 |
|---|---|---|
| P1.1 | 下载 / 解码 / 播放三线程 + 有界队列 | ✅ **边下边播已实现**（下载线程 + 1MB 环形缓冲），待补：切歌不泄漏、连播压测 |
| P1.2 | 状态机（缓冲 / 暂停 / 恢复 / 切歌） | ✅ **已完成**：`PlaybackEngine` 五态机，暂停/恢复/切歌实测通过 |
| P1.3 | 进度推算 + seek | ⚠️ 进度推算 ✅（PCM 字节数），seek 未做（流式 mp3 需 Range 定位，见下） |
| P1.4 | 淡入淡出 / 交叉淡化 | ✅ **已完成**：采样级增益斜坡（非逐 tick），切点峰值实测 0/32768 |
| P1.5 | 播放队列（隐式队列 / 循环模式 / 失败跳曲） | ✅ **已完成**：`PlaybackQueue`，回绕与跳过实测通过 |
| P1.5 | 异常兜底（无版权 / VIP / 断网 / 401） | 全部有 UI 提示，不崩 |

### P2 · 物品与交互（6–8 人日）

| 编号 | 任务 | 验收标准 |
|---|---|---|
| P2.1 | 物品注册 + 16×16 贴图 + 手持模型（12×12×3） | ✅ 已完成 |
| P2.1b | 工作台合成配方（3×3，钻石居中 / 上方红石 / 其余铁） | ✅ **已完成**：正例命中、反例不匹配实测通过 |
| P2.2 | 主界面（口袋机布局）：封面 / 歌名 / 歌手 / 进度 / 控制 | ✅ **已完成**：176×166 口袋机，`PlayerScreen` |
| P2.3 | 封面动态贴图（异步 + 32×32 降采样 + 缓存） | ✅ **已完成**：`CoverCache`，JPEG 走 lwjgl-stb 解码 |
| P2.4 | **手持交互**：右键打开播放器 / 界面内控制 / 滚轮音量 | ✅ **已完成**：右键（含潜行）统一打开播放器，无常驻 HUD |
| P2.5 | 反馈闭环：下沉动画 + 咔哒音效 + 屏幕 2 帧动画 | ✅ **已完成**：`UiFeedback`，位移实测 123→124→123 |
| P2.6 | 搜索页 + 红心歌单 + 每日推荐 | ✅ **全部完成**（其他歌单明确不做） |
| P2.7 | 二维码登录界面 | 扫码 3 秒内完成 |
| P2.8 | ~~HUD 信息条~~ + 快捷键 + 设置页 | **HUD 条明确不做**（用户要求：不用播放器时屏幕上不出现任何东西） |

### P3 · 音效体感（1–2 人日）

| 编号 | 任务 | 验收标准 |
|---|---|---|
| P3.1 | 环境音效：水下低通 + 矿洞/室内混响 | ✅ **已完成**：`EnvironmentAudio`，AL 零错误 |
| P3.2 | 音量包络（打开界面时压到 60%） | 未做（环境参数已做平滑过渡） |

### P4 · 稳定与发布（3–4 人日）

| 编号 | 任务 | 验收标准 |
|---|---|---|
| P4.1 | 配置持久化 + 会话恢复 | 重启保持登录与设置 |
| P4.2 | 磁盘缓存（封面 / 歌单 JSON） | 二次打开秒开 |
| P4.3 | 内存与句柄泄漏排查 | 连跑 2 小时无增长 |
| P4.4 | 多语言（简中 / 繁中 / en_us） | 无硬编码文本 |
| P4.5 | 打包与文档 | ✅ **已完成**：4 个解码库内嵌 + TCCL 修复，922K 单 jar |

**合计：约 19–27 人日**（比含方块的版本少约 3 人日）

---

## 八、风险与对策

| # | 风险 | 影响 | 对策 |
|---|---|---|---|
| R1 | **Mixin 拿不到 `ChannelHandle.source`** | 高 | P0.4 最先验证；失败退回 VanillaAudioSink，功能照用 |
| R2 | 网易云接口加密/风控变动 | 致命 | 加密集中在 `Crypto`；预留降级通道 |
| R3 | 26.3 用 mojmap，教程全是 Yarn | 高 | 只信官方 example-mod + 反编译对照 |
| R4 | 渲染管线大改 | 高 | P0 阶段打通 HUD，别留到最后 |
| R5 | 纯手持 → 距离衰减失去载体 | 已接受 | 决定走 A：不做衰减与遮挡，只做环境低通，见第九节 |
| R6 | VIP / 无版权歌曲 | 中 | UI 灰显 + 明确提示 |
| R7 | 解码 CPU 占用 | 中 | 单线程 + 复用 buffer |
| R8 | 版权与分发 | 中 | 不打包音频/凭据，公开前只发源码 |
| R9 | cookie 明文存储 | 中 | 提示风险 |
| R10 | 高清封面贴进像素界面割裂 | 低 | 降采样 32×32 + 最近邻放大 |

---

## 九、决策记录：为什么接受 2D

**技术事实**：空间感来自「声源位置」与「听者位置」的差异。纯手持时两者重合，距离恒为 0，衰减、方位、遮挡射线全部失效。

**结论**：单人游戏里的纯手持播放器，本质就是 2D 背景音乐。这和原版唱片机不同——唱片机是方块，走开就会变小。

**已选 A**：不做衰减与遮挡，把"空间感"换成"体感"——

| 保留 | 具体做法 |
|---|---|
| 环境低通 | 水下 600Hz / 潜行 2kHz / 戴头盔 4kHz / 打开界面 3kHz |
| 音量包络 | 开界面压到 60%，切场景 200ms 淡入淡出 |
| 手感反馈 | 右键下沉 1px + 咔哒音效 + 屏幕 2 帧动画 |

这套组合听起来不"空间"，但实际体验会比假的空间化更舒服——玩家能感知"我在水里，音乐变闷了"，这比"声音从右前方 1.5 格传来"自然得多。

**B（服务端广播）留作 v2**：架构已留口子。`PlaybackController` 的状态是纯数据，加一层 `SessionSync` 就能把"谁在放什么"广播出去。届时要再评估 +8~12 人日的投入。

---

## 十、工程现状与下一步

### 已就绪（P0.1）

| 项 | 实际值 |
|---|---|
| 便携 JDK | `.tools/jdk-25.0.5+8`（Temurin 25.0.5，已入 .gitignore） |
| 便携 Gradle | `.tools/gradle-9.8.2`，wrapper 指向腾讯镜像 |
| Loom | **1.19.4**（`1.19-SNAPSHOT` 实际解析到的最新版） |
| Fabric API | 0.165.2+26.3 |
| 模组 id / 包 | `handheldmusic` / `com.handheldmusic` |
| 产物 | `build/libs/handheld-music-player-0.1.0.jar`（6.2 KB） |

**26.3 实测踩到的坑**（写下来免得再踩）：

- `net.minecraft.resources.ResourceLocation` **已改名 `Identifier`**，方法 `fromNamespaceAndPath` 不变
- 物品需要**两份文件**：`assets/<ns>/items/<id>.json`（模型定义）+ `assets/<ns>/models/item/<id>.json`（模型本体），缺前者显示紫黑格
- `build.gradle` 里**不需要写 mappings 行**，Loom 1.19 默认 mojmap
- 依赖用 `implementation`，不是老的 `modImplementation`
- 官方模板分支名就叫 `26.3`
- **LWJGL 3.4 把 `EFX10` 改名 `EXTEfx`**，滤镜常量与方法都在里面；`alSourcei` 仍在 `AL10`
- `Player.displayClientMessage` → **`Player.sendOverlayMessage(Component)`**
- `SoundBufferLibrary.getStream(Identifier, boolean)` 只按资源包路径取流 → **必须 Mixin 才能注入自建音频流**
- `SoundEngine.instanceToChannel` 与 `Channel.source` 都是私有字段，用 `@Accessor` 取
- AL 调用必须走 `ChannelHandle.execute(Consumer<Channel>)`，它在 sound engine 线程上执行

### P0.2 / P0.4 当前实现

```
右键物品     → 播放 6 秒测试音（440 + 2500 + 6000Hz 叠加）
潜行 + 右键   → 切换 600Hz 低通
```

链路：`HandheldSoundInstance`（stream=true 的 Sound）→ `SoundBufferLibraryMixin` 拦截 `getStream` → 塞入 `PcmToneStream` → 原版建 channel → `LowpassController` 每 10 tick 给 source 挂 `AL_DIRECT_FILTER`

### 26.3 音频管线三个坑（已踩平，务必记住）

1. **`getOrResolve()` 必须自己造 `WeighedSoundEvents`**
   默认实现去 `sounds.json` 查 Identifier，查不到返回 `null` → `SoundEngine.play` 直接判 `NOT_STARTED`（日志 `Unable to play unknown soundEvent`）。
   自建：`new WeighedSoundEvents(streamId, null)` + `addSound(sound)`。

2. **`getOrResolve()` 必须同时写回两个 protected 字段**
   反汇编可见它是这样写的：
   ```java
   this.soundEvent = sm.getSoundEvent(this.identifier);
   this.sound = (soundEvent == null) ? SoundManager.EMPTY_SOUND : soundEvent.getSound(this.random);
   return this.soundEvent;
   ```
   而 `getVolume()` / `getPitch()` 读的是**字段 `sound`**，不是 `getSound()`。只覆写返回值不改字段 → `SoundEngine.play` 里 `getVolume()` NPE 崩溃。

3. **`getStream()` 收到的 Identifier 不是裸 ID**
   `SoundEngine` 传的是 `sound.getPath()`，而 `Sound.SOUND_LISTER = new FileToIdConverter("sounds", ".ogg")`：
   `handheldmusic:stream/1` → **`handheldmusic:sounds/stream/1.ogg`**。
   `StreamRegistry` 里必须归一化（剥 `sounds/` 前缀、`.ogg` 后缀），否则永远 miss、静默无声。

### 26.3 音频格式硬约束（P0.3 实测）

`com.mojang.blaze3d.audio.OpenAlUtil#audioFormatToOpenAl` 只接受四种组合，其它一律抛
`IllegalArgumentException`：

| 编码 | 声道 | 位深 | AL 格式 |
|---|---|---|---|
| PCM_SIGNED / PCM_UNSIGNED | 1 | 8 | MONO8 |
| PCM_SIGNED / PCM_UNSIGNED | 1 | 16 | MONO16 |
| PCM_SIGNED / PCM_UNSIGNED | 2 | 8 | STEREO8 |
| PCM_SIGNED / PCM_UNSIGNED | 2 | 16 | STEREO16 |

采样率不限（OpenAL 自行重采样）。`Channel.attachBufferStream` 按
`秒数 × 位深/8 × 声道 × 采样率` 算缓冲，默认 1 秒一块、预灌 4 块。
所以解码器只需保证输出 **16bit 小端、1 或 2 声道**。

### 解码库选型（P0.3 落地）

- `com.googlecode.soundlibs:mp3spi:1.9.5.4`（传递带 `jlayer` + `tritonus-share`）
- 走标准 Java Sound SPI：`AudioSystem.getAudioInputStream()` 直接认 mp3，无需手写 JLayer 循环
- ⚠️ `jflac-codec` 在阿里云镜像 404，FLAC 支持等找到可用源再加（P1 再说）
- 打包时要 `include`（jar-in-jar）；Fabric 每个 mod 独立 classloader，理论上不必 relocate，但发布前要实测与 Net Music 共存

### 网易云 eapi 实测结论（P0.5 / P0.6）

**加密（已验证，一次通过）**
```
key    = "e82ckenh8dichen8"（AES-128-ECB，PKCS5/7 填充）
digest = md5("nobody" + path + "use" + json + "md5forencrypt")
plain  = path + "-36cd479b6b5-" + json + "-36cd479b6b5-" + digest
params = hex(密文).toUpperCase()
body   = params=<params>
```

**⚠️ 最容易踩的坑：路径前缀**
摘要里用 `/api/xxx`，但**实际请求必须打到 `/eapi/api/xxx`**。少了 `/eapi` 服务端一律返回
`{"code":400,"msg":"参数错误"}`——加密完全正确也会报"参数错误"，极易误判成限流或加密写错。

**匿名可用性（重要）**
不登录也能：
- `/api/search/get` 搜索 ✅
- `/api/song/enhance/player/url/v1` 取直链 ✅
- CDN Range 分段拉流（HTTP 206）✅

**`encodeType` 传 `mp3` 就能直接拿 mp3**，不用引入 AAC 解码器；传 `flac` 不可用时会自动降级成 mp3，看返回的 `type` 字段。

**VIP 曲目会返回 `code:-110, fee:1` 且 url 为 null**，所以要按搜索结果逐个回退试。

**二维码登录 8821 已解决（关键：设备伪 cookie）**

症状：扫码能识别账号（802 + nickname），紧接着回：
```
{"code":8821,"message":"请切换其他登录方式或升级新版本再试",
 "redirectUrl":"https://qa-yyy.igame.anquanhuanjingfengxian"}
```

排除矩阵（每次都拿到同一个 `captchaId` = `73a18dc827b24b18ad0783701a75277d`）：
eapi / weapi、代理 / 直连、随机 deviceId / 固定、5 个不同 unikey —— 全都一样。

**根因：请求里缺一个「设备伪 cookie」。**
Dango Music（Kotlin，GPL-3，同样支持 26.3）的实现里，每个 weapi 请求都带：

```
Cookie: os=pc; appver=2.10.13
```

不带这个头时，网易看到的是一个「没有任何客户端标识的陌生请求」，手机端确认后一律判安全环境风险。
带上之后服务端当成正常 PC 客户端，`803` 直接过。

**修复 = weapi + 设备伪 cookie，两个一起才行**（只换 weapi 仍然 8821）。

实测结果：
```
轮询#12 code=802 {"nickname":"z2hanghang","message":"授权中"}
轮询#13 code=803 {"code":803,"message":"授权登陆成功"}
登录 cookie 已保存：run\handheldmusic\cookie.txt
二维码登录成功：z2hanghang
```
模组内端到端验证通过（从匿名状态 → 扫码 → 803 → cookie 落盘）。

**weapi 实现要点**：路径规则是把 `/api/xxx` 里的 `api` 段**替换**成 `weapi`（`/weapi/xxx`），
和 eapi 的「加前缀 `/eapi/api/xxx`」不同。加密 = AES-128-CBC 两次
（presetKey `0CoJUm6Qyw8W8jud` + 随机 16 位 key，IV `0102030405060708`）+ RSA 无填充。

### 登录方案：粘贴 cookie（已实测通过）

网易云身份实际上只看 **`MUSIC_U`** 这一个 cookie，其余（`__csrf` 等）只影响写操作。

**⚠️ 取 cookie 必须用 DevTools → Application，不能用 `document.cookie`**
`MUSIC_U` 是 **HttpOnly**，JS 读不到。用 `document.cookie` 复制出来的全是
`_ga` / `NMTID` / `WM_NI` / `gdxidpyhxdE` 这类统计与设备指纹 cookie，一个身份凭证都没有
（实测这串打 `/api/nuser/account/get` 返回 `account:null`）。

正确路径：F12 → **Application** → Storage → Cookies → `https://music.163.com` → 找 `MUSIC_U` 行 → 复制 Value。
备用路径：Network → 任一请求 → Request Headers → `Cookie:` 里截出 `MUSIC_U=...`。

模组侧入口：
- 游戏内 `/hmcookie <值>`：裸 `MUSIC_U` 值或整串 `k=v; k2=v2` 都认，自动落盘 + 校验
- 手写 `run/handheldmusic/cookie.txt`，启动时自动加载

`apply()` 返回三态 `OK / NO_MUSIC_U / INVALID`，缺 `MUSIC_U` 时直接提示用 Application 面板，
不再笼统报「cookie 无效」。

**码率策略**：登录后请求 `level=exhigh`（320kbps mp3），未登录用 `standard`。
实测同一首 VIP 曲目（id 1357375695）匿名返回 `code:-110 fee:1` 无直链，
登录后直接给 320kbps mp3。

配套命令：`/hmcookie` 设 cookie、`/hmqr` 打开扫码界面、`/hmlogin` 查状态、`/hmlogout` 清除。
校验接口 `/api/nuser/account/get`，匿名返回 `{"code":200,"account":null,"profile":null}`，
登录后返回 `profile.nickname` / `profile.userId`。

### 游戏内扫码界面（已实现）

`/hmqr` 打开 `QrLoginScreen`，直接在 GUI 里画二维码，不依赖贴图。

26.3 渲染接口的两个变化：
- `Screen#render(GuiGraphics,...)` → **`extractRenderState(GuiGraphicsExtractor, int, int, float)`**
- `Minecraft#setScreen` → **`setScreenAndShow`**

绘制要点：
- `GuiGraphicsExtractor` 有 `fill / outline / centeredText`
- **26.3 渲染顺序：`extractBackground` → `nextStratum` → `extractRenderState`（控件）**。
  面板/二维码必须画在 `extractBackground` 里；画在 `extractRenderState` 的 `super` 之后会把按钮整个盖住
- 二维码按行把连续黑块合并成一个 `fill`，避免逐像素调用（1369 次 → 约 200 次）
- **布局必须自适应**：GUI 缩放为 2 时逻辑高度只有 240，固定 272 高的面板会被裁掉。
  二维码边长按 `min(width-80, height-40-110)` 算，再让 `NeteaseQrLogin.start(px)` 按同一尺寸编码，
  保证 1:1 绘制（缩放会让相邻模块糊在一起，扫码必失败）
- 面板高度 = 二维码 + 110（标题 8 + 间距 20 + 状态 16 + 提示 16 + 按钮间距 30 + 按钮 20 + 底部 8）
- 底部两个按钮：「刷新二维码」（中断旧轮询重新申请）、「复制登录链接」（写剪贴板，可到浏览器打开）
- 验证手段：`-Dhandheldmusic.autotest=qr` 自动开界面并 `Screenshot.grab`，再用 zxing 解码截图确认可扫

### 边下边播（已实现，P1.1 提前做掉）

```
HTTP 下载线程 ──write──▶ 1MB 环形缓冲 ──read──▶ InputStream
                                                  │
                                          mp3spi 解码（AudioSystem）
                                                  │
                                          Mp3Stream.read(size)
                                                  │
                                    原版 Channel.pumpBuffers（sound engine 线程）
```

关键设计：
- **1MB 环形缓冲**（128kbps 下约 64 秒），下载线程灌满后阻塞等待，天然限速不占内存
- `read()` 最多等 5 秒，超时抛 `IOException`，由原版 `pumpBuffers` 兜住（只打日志不崩）
- **Mixin 里必须用 `CompletableFuture.supplyAsync`**：`StreamingHttpAudioStream` 构造时要等 mp3 头解析，
  同步构造会卡渲染线程。原版自己的 `getStream` 也是异步的
- `StreamRegistry.create()` 失败时返回「立即结束的空流」而不是 null——原版拿到 null 会在
  `attachBufferStream` 里 NPE
- 不落盘：直链有效期约 20 分钟（`expi` 字段），不做 URL 缓存

### 播放状态机（P1.2 / P1.3 / P1.4）

`client/audio/PlaybackEngine.java`，五态：

```
IDLE ──play──▶ RESOLVING ──解析完成──▶ PLAYING ⇄ PAUSED
                 │                      │
                 └──失败──▶ ERROR        └──播完 / stop──▶ IDLE
```

**三个关键实现决定**

1. **暂停用 `channel.pause()`**
   原版 `ChannelHandle.isStopped()` 只认 `AL_STOPPED`(4116)，暂停态是 4115，
   所以 `SoundEngine.tick` 不会把暂停中的音源回收掉。`Channel.pause()/unpause()` 可用。
   （`Channel.playing()` 判断 4114，`Channel.stopped()` 判断 4116，都在 `com.mojang.blaze3d.audio.Channel`。）

2. **切歌走交叉淡化，且淡入淡出是采样级的**
   最初版本靠「每 tick 改 `AbstractSoundInstance.volume`」：原版 tick 是 20Hz，
   步长 0.22 → 每 50ms 一次 22% 幅度跳变，在持续音上就是可闻的拉链噪声。**已废弃**。
   现在的做法：`Fade` 类做**按帧**推进的线性斜坡，`Mp3Stream.read()` 里逐帧乘增益
   （同帧各声道用同一增益，否则立体声跑偏）。44100Hz 粒度，斜坡本身平滑无阶跃。
   - 新曲：`beginFadeIn(400ms)`，channel 音量保持满值
   - 旧曲：`beginFadeOut(300ms)`，等倒计时走完才真正 stop
   注意 `SoundEngine.play` 里「音量为 0 就不播」的分支对 `SoundSource.MUSIC` 有豁免，
   所以淡入从 0 开始也能正常启动。

   **客观验证指标**：`Mp3Stream` 每块统计输出峰值 `lastAbsSample()`，
   停止前打日志。切点峰值 = 0 意味着 PCM 已完全拉到静音才切，**不存在幅度阶跃**。

3. **进度靠 PCM 字节数推算**
   `playedMs = 已解码字节数 ÷ (采样率 × 帧大小)`，比按时间估算准，且暂停时自动停住。
   总时长直接用搜索接口返回的 `duration` 字段（实测 239560ms ↔ 界面显示 3:59，一致）。

**seek 为什么没做**：流式播放是顺序读 mp3 字节流，跳转需要按字节偏移重新发 Range 请求，
再让解码器重新对齐帧边界。mp3 可以按 CBR 估算偏移（不精确），flac 需解析 seektable。
留到 P1.3 单独处理，优先级低于 UI。

**实测（`-Dhandheldmusic.autotest=state` 自动演练）**
```
播放
暂停 → 播放中  0:03 / 3:59
tick 220 → 已暂停  0:03 / 3:59
tick 240 → 已暂停  0:03 / 3:59   ← 3 秒过去进度纹丝不动
暂停 3 秒后 → 已暂停  0:03 / 3:59
tick 280 → 播放中  0:04 / 3:59   ← 恢复后继续
恢复后 → 播放中  0:06 / 3:59
停止后 → 空闲：已停止
```
0 条音频异常。

**切歌实测（`-Dhandheldmusic.autotest=switch`）**
```
播放 A
命中：海阔天空 320kbps，开始播放
A 播到 0:06，切到 B
命中：起风了 320kbps，开始播放
淡出完成，切点峰值 0 / 32768      ← A→B 交叉淡化切点
B 状态 → 播放中  0:06 / 5:25
淡出完成，切点峰值 0 / 32768      ← 最终停止切点
收尾 → 空闲：已停止
```
切点峰值为 0 说明两次切换都没有幅度阶跃，**数学上不可能产生爆音**。

### 下一步

1. **听感验证**：`./gradlew runClient` → `/give @s handheldmusic:handheld_player` → 右键在线听歌
2. 新命令：`/hmplay <关键词>`、`/hmpause`、`/hmstop`、`/hmstatus`
3. 进 **P2**：手持物品 + 口袋机界面（进度条已经有数据源了）
4. seek 单独排期（流式 mp3 需要 Range 定位）
