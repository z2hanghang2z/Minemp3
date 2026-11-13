# 市场调查：MC 音乐播放器模组的技术栈

> 调查时间：2026-11-12
> 方法：直接读 GitHub 源码树、`build.gradle` 依赖、`THIRD-PARTY-NOTICES`、LICENSE，不看宣传文案
> 结论一句话：**市面上有四条技术路线，只有一条能真正做到"空间音频"，而 26.3 上这条路还是空的。**

---

## 一、总览

| 模组 | Star | 语言 | 授权 | 支持版本 | 音频路线 |
|---|---|---|---|---|---|
| **Dango Music** (Sorihiko) | 624 | Kotlin | **GPL-3.0** | 1.20.1 ~ **26.3**（fabric/neoforge） | Java Sound 直出 |
| **Net Music** (TartaricAcid) | — | Java | **BSD-3-Clause** | 1.12.2 ~ 1.21.1 | 原版 `AudioStream` + `SoundInstance` |
| **CloudMusic-Mod** (FengLiuFeseliud) | 73 | Java | **MIT** | 1.20.x ~ 1.21.x | Mixin 劫持 `SoundSystem` |
| **Music-Player** (MC-U-Team) | 64 | Java | — | 1.12.2 ~ 1.21.1 | 原版播放器 + 运行时下依赖 |
| **Music HUD** (Ephern) | 18 | Java | **LGPL-3.0** | 1.21.6 ~ 1.21.8 | **自管 OpenAL source** |
| audio-player (henkelmax) | 105 | Java | — | 多版本 | 本地文件 → 唱片（非在线） |

> Net Music 在 Modrinth 约 **300 万**下载，是国内事实标准。它的 GitHub star 少是因为主要走 MCBBS/CurseForge 分发。

---

## 二、四条技术路线（带源码证据）

### 路线 A：实现原版 `AudioStream` + 自定义 `SoundInstance`（Net Music）

**这是最聪明的一条路。** 关键代码：

```java
public class NetMusicAudioStream implements net.minecraft.client.sounds.AudioStream {
    // 从网络 URL 拉流 → Java Sound 转 PCM → 喂给原版
}

public class NetMusicSound extends AbstractTickableSoundInstance {
    // 原版 SoundInstance 子类，负责 3D 定位
}
```

**为什么聪明**：MC 的 `SoundEngine` 里，`AudioStream` 是个**公开接口**，专门给"流式音频"用的（原版 ogg 流音乐也走它）。你只要实现它，就能让原版帮你做：

- ✅ 3D 定位（`AL_POSITION`）
- ✅ 距离衰减（attenuation）
- ✅ 音量分类（音乐/唱片机分类，受游戏音量滑块控制）
- ✅ 暂停、切场景、退出游戏的自动回收
- ✅ 和其他模组音效和平共处

**代价**：拿不到低通滤波，做不了"隔墙变闷"。空间感只有距离衰减，没有遮挡。

### 路线 B：自管 OpenAL source（Music HUD）

```java
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.EXTFloat32;
import org.lwjgl.openal.EXTMCFormats;
import org.lwjgl.openal.SOFTDirectChannelsRemix;

public final class OpenAlSource implements AutoCloseable { ... }
```

配套类：`PcmSink` / `PcmChunk` / `StreamAudioPlayer` / `PlaybackLedger`（采样账本，用来精确推算播放位置）。

**亮点**：`EXTMCFormats` + `SOFTDirectChannelsRemix` + `EXTFloat32` —— 这是在做**多声道 / 环绕声输出**，不是普通立体声。是目前唯一真正在做"空间音频"的实现。

**代价**：要自己管 OpenAL 上下文线程、设备热插拔、音量分类、资源释放。工程量最大。

### 路线 C：Mixin 劫持 `SoundSystem`（CloudMusic-Mod）

```java
@Inject(method = "play(Lnet/minecraft/client/sound/SoundInstance;)V", at = @At("HEAD"), cancellable = true)
@Inject(method = "tick()V", at = @At("HEAD"), cancellable = true)
```

拦在原版播放入口，自己接管。好处是能复用原版的 source 池，坏处是**跟任何也改 `SoundSystem` 的模组都会打架**（音效物理、声音重载类模组重灾区）。

### 路线 D：Java Sound 直出（Dango Music）

```kotlin
class JavaSoundOutput(private val line: SourceDataLine) : AudioOutput
```

直接用 `javax.sound.sampled.SourceDataLine` 输出到系统默认音频设备，**完全不经过 OpenAL**。

**这是最省事的一条路**，但代价很直接：

- ❌ **没有任何空间化**——声音是纯 2D 的，跟 MC 世界坐标无关
- ❌ 不受游戏音量滑块控制
- ❌ 和 MC 音频设备切换、独占模式容易冲突

也就是说：Dango 的"3D 歌词"是**视觉上的 3D**，声音并不 3D。它 624 star 靠的是 UI 和歌词效果，不是音频技术。

---

## 三、解码方案对比

| 项目 | 解码库 | 支持格式 | 打包方式 |
|---|---|---|---|
| Net Music | `mp3spi 1.9.5.4` + `jlayer 1.0.1.4` + `tritonus-share` + `jflac` + `javasound-aac` | mp3 / flac / aac | jar-in-jar + **包名 relocate** |
| Dango Music | `javazoom:jlayer:1.0.1` + `org.jcraft:jorbis:0.0.17` | mp3 / ogg | 直接 include |
| Music HUD | `com.github.umjammer:jlayer:1.0.3` + `org.jflac:jflac-codec:1.5.2` + `jaudiotagger:3.0.1` | mp3 / flac | jar-in-jar + `META-INF/licenses/` |
| Music-Player | 运行时 `DependencyManager` + 独立 `ClassLoader` 动态下载 | 可扩展 | **运行时下载** |

**两个值得抄的工程细节**：

1. **Net Music 做了包名 relocate**（`javazoom.jl` → `com.github.tartaricacid.netmusic.soundlibs.javazoom.jl`）。这是防冲突的正确姿势——不然两个都打包 jlayer 的模组同时装就会炸。
2. **Music HUD 把 LGPL 许可证文本打进 jar 的 `META-INF/licenses/`**，并在 `THIRD-PARTY-NOTICES.txt` 里写明源码地址。这是合规的教科书做法。

---

## 四、其他共性选择

| 需求 | 大家都在用什么 |
|---|---|
| 二维码登录 | `com.google.zxing:core:3.3.3`（Dango 用） |
| 现代 UI | **ModernUI**（`dev.icyllis:modernui-core`，Music HUD 用）—— 声明式 UI，比自绘省一半工 |
| 多加载器 | `common` + `fabric` + `neoforge` + `legacy` 多模块，或 Architectury |
| 多版本 | Dango 用 `versions/26.3-fabric` 这种目录切版本，一套 core 代码喂多个 MC 版本 |
| 联机同步 | Dango 用 **Firebase** 做"一起听歌"房间 |
| 歌词多源 | Dango 同时接 **网易云 / QQ音乐 / 酷狗**，任一失败自动换源 |

---

## 五、市场空位在哪

把上面的表按"你要的功能"重新排一遍：

| 能力 | Dango | Net Music | CloudMusic | Music HUD |
|---|---|---|---|---|
| 26.3 支持 | ✅ | ❌ 停在 1.21.1 | ❌ | ❌ 停在 1.21.8 |
| 手持物品形态 | ❌ 是 GUI | ❌ 是方块 | ❌ 是 GUI | ❌ 是 HUD |
| **真空间音频** | ❌ 纯 2D | ⚠️ 只有距离衰减 | ❌ | ✅ 多声道 |
| 搜索点歌 | ✅ | ✅ | ✅ | ✅ |
| 逐字歌词 | ✅ | ⚠️ 逐行 | ⚠️ | ✅ |

**空位很明确**：`26.3` + `手持物品` + `真空间音效`，三个条件同时满足的一个都没有。

Dango 已经占了"26.3 + 好看 + 3D 歌词"，你正面刚 UI 没有胜算。但它**声音不空间化**——这就是你的切入点。一个"拿在手上、放下就变成世界里的音源、走远了变闷"的播放器，市面上还没有。

---

## 六、对我们计划的修正（重要）

我上一版 `PLAN.md` 里写过一句**错的**：

> ❌ "MC 的 `SoundEngine` 只吃预加载的 ogg，没有往正在播的流里塞 PCM 的接口。所以必须自己开 OpenAL context。"

**这句话是错的。** `net.minecraft.client.sounds.AudioStream` 就是那个接口，Net Music 用它稳定跑了三年、300 万下载。

### 修正后的音频层策略

```
第一档（推荐起步）：路线 A
  实现 AudioStream + AbstractTickableSoundInstance
  → 立刻拿到 3D 定位 + 距离衰减 + 音量分类 + 生命周期管理
  → 零 Mixin，零 context 管理，风险最低

第二档（空间感增强）：在 A 的基础上加料
  → Mixin SoundEngine 给 source 挂 AL_DIRECT_FILTER 低通
  → 射线遮挡检测 → 动态调低通截止频率
  → 这就是"隔墙变闷"，市面没人做

第三档（可选，做到极致）：路线 B
  → 自管 OpenAL source + EXTFloat32 多声道
  → 只在"必须环绕声"时才上，工程量翻倍
```

**P0 的验证项也跟着改**：原来 P0.2 是"独立 OpenAL context 播正弦波"，现在改成"实现 AudioStream 播网络流 + 验证距离衰减"，风险低得多，也快得多。

---

## 七、授权风险提示（会影响你能抄多少代码）

| 项目 | 协议 | 能不能直接抄代码 |
|---|---|---|
| **Net Music** | BSD-3-Clause | ✅ 可以，保留版权声明即可。**最值得参考的对象** |
| **CloudMusic-Mod** | MIT | ✅ 可以，最宽松 |
| **Music HUD** | LGPL-3.0 | ⚠️ 可以链接，改了它的代码要开源对应部分 |
| **Dango Music** | **GPL-3.0** | ❌ 抄了你的模组必须也 GPL 开源 |

**结论**：技术方案参考 Net Music（BSD-3）和 CloudMusic-Mod（MIT），这两个可以放心读、放心借鉴。Dango 只能看思路，不能抄代码——除非你打算整个项目 GPL 开源。

---

## 八、给我们的三条具体建议

1. **音频层从路线 A 起步**，别一上来就自管 OpenAL。省下的两周可以拿去做 UI 和歌词。
2. **解码抄 Net Music 的组合**（mp3spi + jflac），并且**一定要做包名 relocate**，否则和装了 Net Music 的整合包会冲突。
3. **差异化押在"空间"上**：手持时跟随、放下变世界音源、距离衰减 + 遮挡低通。这是 Dango 的软肋，也是唯一能做出"只有 MC 能做、普通播放器做不到"的体验点。
