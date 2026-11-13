package com.handheldmusic.client.audio;

import com.handheldmusic.HandheldMusic;
import com.handheldmusic.client.netease.NeteaseApi;
import com.handheldmusic.client.netease.NeteasePlayer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.sounds.ChannelAccess;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Player;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 播放状态机（P1.2 / P1.4）+ 播放队列。
 *
 * 状态流转：
 *   IDLE ──play──▶ RESOLVING ──解析完成──▶ PLAYING ⇄ PAUSED
 *                    │                      │
 *                    └──失败──▶ ERROR        └──播完 / stop──▶ IDLE
 *
 * 几个关键决定：
 * 1. **暂停用 channel.pause()**。原版 ChannelHandle.isStopped() 只认 AL_STOPPED(4116)，
 *    暂停态是 4115，所以 tick 不会把暂停中的音源回收掉。
 * 2. **切歌用交叉淡化**：旧实例转入 outgoing 淡出，新实例从 0 淡入，两者短暂共存，
 *    避免硬切造成爆音。淡入淡出靠改 AbstractSoundInstance.volume——
 *    SoundEngine.tick 每 tick 会重算 TickableSoundInstance 的音量并写回 channel。
 * 3. **进度靠 PCM 字节数推算**：playedMs = 已解码字节 ÷ (采样率 × 帧大小)。
 *    比按时间估算准，暂停时也自动停住。
 * 4. **队列是隐式的**：列表点歌 = 整个列表入队。播完自动 advance，
 *    失败自动跳下一首（有连续失败上限），切歌复用交叉淡化所以天然无爆音。
 * 5. **请求序号**：点得快时只认最后一次请求，旧回调直接丢弃——
 *    早期版本遇到 RESOLVING 就静默 return，连点第二首会被吞掉。
 */
public final class PlaybackEngine {
	public enum State {
		IDLE, RESOLVING, PLAYING, PAUSED, ERROR
	}

	/** 淡入淡出时长（毫秒）。采样级斜坡，不是逐 tick 步进。 */
	private static final long FADE_IN_MS = 400L;
	private static final long FADE_OUT_MS = 300L;

	/** 解析超时阈值：超过这么久还卡在 RESOLVING，就当失败放行，避免后续点击全被吞掉。 */
	private static final long RESOLVE_TIMEOUT_MS = 25_000L;

	/** 自动切歌时连续失败多少首就停下报错，防止整个队列都拿不到直链时无限请求。 */
	private static final int MAX_CONSECUTIVE_FAILURES = 5;

	/**
	 * 起播宽限 tick 数。
	 *
	 * SoundEngine 收到 play() 之后要到下一 tick 才真正建 channel，这之前
	 * isActive() 是 false。队列模式下 state 一直是 PLAYING，不做宽限就会
	 * 把「刚起播」误判成「已播完」，于是一首接一首无限往后跳。
	 */
	private static final int STARTUP_GRACE_TICKS = 4;

	private static volatile State state = State.IDLE;
	private static volatile String status = "未播放";

	private static final PlaybackQueue queue = new PlaybackQueue();

	private static NeteasePlayer.Resolved resolved;
	/** 进入 RESOLVING 的时刻，用于超时兜底。 */
	private static long resolvingSince;
	private static HandheldSoundInstance current;
	private static Identifier currentId;
	private static AtomicReference<StreamingHttpAudioStream> streamRef;

	private static HandheldSoundInstance outgoing;
	private static Identifier outgoingId;
	private static AtomicReference<StreamingHttpAudioStream> outgoingStreamRef;
	private static int outgoingStopTicks;
	/** 切点前最后一个缓冲块的峰值幅度，越小说明切得越干净。 */
	private static int outgoingPeak;

	private static int tickCounter;
	/** 当前曲目起播后过了多少 tick，用于跳过开头的 channel 建立期。 */
	private static int ticksSinceStart;
	/** 用户音量 0~1，作用在 SoundInstance.volume 上（引擎每 tick 会写回 channel）。 */
	private static float volume = 0.8f;

	/** 请求序号：回调里对不上就说明用户已经点了别的歌，直接丢弃。 */
	private static volatile int requestSeq;
	/** 本次解析是不是队列自动推进触发的（决定失败时跳不跳下一首）。 */
	private static boolean autoAdvancing;
	/** 关键词模式：解析出哪首就把队列设成那一首。 */
	private static boolean keywordMode;
	private static int consecutiveFailures;

	private PlaybackEngine() {
	}

	// ---------------- 对外接口 ----------------

	public static State state() {
		return state;
	}

	public static String status() {
		return status;
	}

	public static PlaybackQueue queue() {
		return queue;
	}

	/** 队列位置，形如 "3/30"；队列空时返回空串。 */
	public static String queuePosition() {
		return queue.isEmpty() ? "" : queue.position() + "/" + queue.size();
	}

	public static boolean isLowpassOn() {
		return EnvironmentAudio.isManualLowpass();
	}

	/** 当前环境（室外 / 室内 / 水下），界面用。 */
	public static EnvironmentAudio.Env environment() {
		return EnvironmentAudio.current();
	}

	/** 只有歌名，界面标题用；歌手单独走 artists()。 */
	public static String title() {
		if (resolved == null) {
			return "未播放";
		}
		return resolved.song().name() + (resolved.track().trial() ? "（试听）" : "");
	}

	/** 当前播放的歌曲（没有就返回 null），封面等 UI 用。 */
	public static NeteaseApi.Song currentSong() {
		return resolved == null ? null : resolved.song();
	}

	public static String artists() {
		return resolved == null ? "" : resolved.song().artists();
	}

	public static long durationMs() {
		return resolved == null ? 0L : Math.max(0L, resolved.durationMs());
	}

	public static long progressMs() {
		AtomicReference<StreamingHttpAudioStream> ref = streamRef;
		StreamingHttpAudioStream s = ref == null ? null : ref.get();
		return s == null ? 0L : Math.min(s.playedMs(), durationMs());
	}

	/** 带百分比的状态行，给 UI 和调试用。 */
	public static String statusLine() {
		StringBuilder sb = new StringBuilder();
		sb.append(switch (state) {
			case IDLE -> "空闲";
			case RESOLVING -> "解析中";
			case PLAYING -> "播放中";
			case PAUSED -> "已暂停";
			case ERROR -> "出错";
		});
		if (state == State.PLAYING || state == State.PAUSED) {
			sb.append("：").append(title());
			String qp = queuePosition();
			if (!qp.isEmpty()) {
				sb.append(" [").append(qp).append(']');
			}
			long d = durationMs();
			if (d > 0) {
				sb.append("  ").append(fmt(progressMs())).append(" / ").append(fmt(d));
			}
		} else if (!status.isEmpty()) {
			sb.append("：").append(status);
		}
		return sb.toString();
	}

	private static String fmt(long ms) {
		long sec = ms / 1000;
		return String.format("%d:%02d", sec / 60, sec % 60);
	}

	/** 右键：在播/暂停就停，空闲就继续播队列当前。 */
	public static void togglePlayback(Player player) {
		switch (state) {
			case PLAYING, PAUSED, RESOLVING -> {
				stop();
				if (player != null) {
					player.sendOverlayMessage(Component.literal("已停止"));
				}
			}
			default -> replayCurrent(player);
		}
	}

	/**
	 * 从列表里点某一首：整个列表变成队列，游标定位到点击位置。
	 * 这是「隐式队列」的入口，搜索 / 红心 / 每日推荐都走它。
	 */
	public static void playFromList(List<NeteaseApi.Song> songs, int index, Player player) {
		queue.set(songs, index);
		consecutiveFailures = 0;
		playCurrent(player, false);
	}

	/** 单曲播放：队列退化成只有这一首（关键词搜索、重播当前等）。 */
	public static void playSong(NeteaseApi.Song song, Player player) {
		playFromList(List.of(song), 0, player);
	}

	/** 关键词播放：先搜再播，队列在解析成功后设成命中的那一首。 */
	public static void play(String keyword, Player player) {
		keywordMode = true;
		consecutiveFailures = 0;
		int seq = ++requestSeq;
		autoAdvancing = false;
		state = State.RESOLVING;
		resolvingSince = System.currentTimeMillis();
		status = "搜索「" + keyword + "」…";
		if (player != null) {
			player.sendOverlayMessage(Component.literal(status));
		}
		NeteasePlayer.resolve(keyword, callback(player, seq));
	}

	/** 手动下一首（列表循环下会绕回第一首）。 */
	public static void playNext(Player player) {
		if (queue.isEmpty()) {
			return;
		}
		consecutiveFailures = 0;
		queue.next();
		playCurrent(player, false);
	}

	/** 手动上一首。按用户要求：直接跳上一曲，不回本曲开头。 */
	public static void playPrev(Player player) {
		if (queue.isEmpty()) {
			return;
		}
		consecutiveFailures = 0;
		queue.prev();
		playCurrent(player, false);
	}

	/** 切换播放模式：循环 → 单曲 → 随机。 */
	public static PlaybackQueue.Mode cycleMode(Player player) {
		PlaybackQueue.Mode m = queue.cycleMode();
		if (player != null) {
			player.sendOverlayMessage(Component.literal("播放模式：" + m.label));
		}
		return m;
	}

	/** 空闲状态下从队列当前位置继续（停止后按「播放」走这里）。 */
	public static void replayCurrent(Player player) {
		if (queue.isEmpty()) {
			return;
		}
		consecutiveFailures = 0;
		playCurrent(player, false);
	}

	public static boolean hasQueue() {
		return !queue.isEmpty();
	}

	public static void pause() {
		if (state != State.PLAYING || current == null) {
			return;
		}
		ChannelAccess.ChannelHandle handle = ChannelUtil.handleOf(current);
		if (handle != null) {
			handle.execute(channel -> channel.pause());
		}
		state = State.PAUSED;
		status = "已暂停";
	}

	public static void resume() {
		if (state != State.PAUSED || current == null) {
			return;
		}
		ChannelAccess.ChannelHandle handle = ChannelUtil.handleOf(current);
		if (handle != null) {
			handle.execute(channel -> channel.unpause());
		}
		state = State.PLAYING;
		status = "播放中";
	}

	public static void togglePause() {
		if (state == State.PLAYING) {
			pause();
		} else if (state == State.PAUSED) {
			resume();
		}
	}

	/** 停止：走淡出，不硬切。队列保留，按「播放」从当前位置继续。 */
	public static void stop() {
		moveCurrentToOutgoing();
		state = State.IDLE;
		status = "已停止";
		resolved = null;
		streamRef = null;
		// 让飞行中的回调失效，否则停止后几秒又自己响起来
		requestSeq++;
	}

	public static float volume() {
		return volume;
	}

	public static void setVolume(float v) {
		volume = Math.max(0.0f, Math.min(1.0f, v));
		applyVolume();
	}

	public static void addVolume(float delta) {
		setVolume(volume + delta);
	}

	private static void applyVolume() {
		if (current != null) {
			current.setBaseVolume(volume);
		}
		if (outgoing != null) {
			outgoing.setBaseVolume(volume);
		}
	}

	public static void toggleLowpass() {
		EnvironmentAudio.toggleManualLowpass();
		if (current != null) {
			EnvironmentAudio.apply(current);
		}
	}

	// ---------------- 内部实现 ----------------

	/** 解析并播放队列当前曲目。auto = 队列自动推进（失败时继续跳）。 */
	private static void playCurrent(Player player, boolean auto) {
		NeteaseApi.Song song = queue.current();
		if (song == null) {
			state = State.IDLE;
			status = "队列为空";
			return;
		}
		keywordMode = false;
		autoAdvancing = auto;
		int seq = ++requestSeq;
		state = State.RESOLVING;
		resolvingSince = System.currentTimeMillis();
		status = "获取「" + song.name() + "」…";
		if (player != null && !auto) {
			player.sendOverlayMessage(Component.literal(status));
		}
		HandheldMusic.LOGGER.info("[HandheldMusic] 请求播放：{}（id={}）{}",
				song.display(), song.id(), auto ? " [队列自动]" : "");
		NeteasePlayer.resolveSong(song, callback(player, seq));
	}

	/** 播完一首后推进队列。 */
	private static void advanceAfterFinish() {
		if (queue.isEmpty()) {
			state = State.IDLE;
			status = "播放结束";
			return;
		}
		queue.next();
		playCurrent(Minecraft.getInstance().player, true);
	}

	private static NeteasePlayer.Callback callback(Player player, int seq) {
		return new NeteasePlayer.Callback() {
			@Override
			public void onReady(NeteasePlayer.Resolved r) {
				if (seq != requestSeq) {
					HandheldMusic.LOGGER.info("[HandheldMusic] 丢弃过期回调：{}", r.display());
					return;
				}
				consecutiveFailures = 0;
				if (keywordMode) {
					// 关键词模式：命中的这一首就是整个队列
					queue.set(List.of(r.song()), 0);
					keywordMode = false;
				}
				startStream(r);
				if (player != null) {
					player.sendOverlayMessage(Component.literal("播放：" + r.display()));
				}
			}

			@Override
			public void onError(String message) {
				if (seq != requestSeq) {
					return;
				}
				consecutiveFailures++;
				// 队列自动推进时跳过这首，继续下一首；连续失败太多就停下
				if (autoAdvancing && consecutiveFailures < MAX_CONSECUTIVE_FAILURES) {
					HandheldMusic.LOGGER.warn("[HandheldMusic] 跳过：{}（连续第 {} 次）",
							message, consecutiveFailures);
					queue.next();
					playCurrent(player, true);
					return;
				}
				state = State.ERROR;
				status = message;
				if (player != null) {
					player.sendOverlayMessage(Component.literal(message));
				}
			}
		};
	}

	private static void startStream(NeteasePlayer.Resolved r) {
		moveCurrentToOutgoing();

		AtomicReference<StreamingHttpAudioStream> ref = new AtomicReference<>();
		String url = r.track().url();
		Identifier id = StreamRegistry.register(() -> {
			try {
				StreamingHttpAudioStream s = new StreamingHttpAudioStream(url);
				// 淡入在采样级完成，channel 音量保持满值
				s.beginFadeIn(FADE_IN_MS);
				ref.set(s);
				return s;
			} catch (Exception e) {
				HandheldMusic.LOGGER.error("[HandheldMusic] 创建音频流失败", e);
				return null;
			}
		});

		HandheldSoundInstance instance = new HandheldSoundInstance(id);

		instance.setBaseVolume(volume);

		current = instance;
		currentId = id;
		streamRef = ref;
		resolved = r;
		state = State.PLAYING;
		status = "播放中";
		ticksSinceStart = 0;

		Minecraft.getInstance().getSoundManager().play(instance);
		EnvironmentAudio.tick(Minecraft.getInstance().player);
		EnvironmentAudio.apply(instance);
		HandheldMusic.LOGGER.info("[HandheldMusic] 开始播放：{}，流 id = {}（队列 {}）",
				r.display(), id, queuePosition());
	}

	/** 把当前实例转成「淡出中的旧实例」。 */
	private static void moveCurrentToOutgoing() {
		if (current == null) {
			return;
		}
		stopOutgoingNow();
		outgoing = current;
		outgoingId = currentId;

		// 让旧流自己把 PCM 拉到静音，再等淡出时长过去才真正停
		AtomicReference<StreamingHttpAudioStream> ref = streamRef;
		outgoingStreamRef = ref;
		StreamingHttpAudioStream s = ref == null ? null : ref.get();
		if (s != null) {
			s.beginFadeOut(FADE_OUT_MS);
			outgoingStopTicks = (int) (FADE_OUT_MS / 50L) + 3;
		} else {
			// 流还没建好，没在出声，直接停不会有爆音
			outgoingStopTicks = 1;
		}
		outgoingPeak = -1;

		current = null;
		currentId = null;
		streamRef = null;
	}

	private static int samplePeakOfOutgoing() {
		AtomicReference<StreamingHttpAudioStream> r = outgoingStreamRef;
		StreamingHttpAudioStream s = r == null ? null : r.get();
		return s == null ? -1 : s.lastAbsSample();
	}

	private static void stopOutgoingNow() {
		if (outgoing == null) {
			return;
		}
		Minecraft.getInstance().getSoundManager().stop(outgoing);
		outgoing.finish();
		if (outgoingId != null) {
			StreamRegistry.unregister(outgoingId);
		}
		outgoing = null;
		outgoingId = null;
		outgoingStreamRef = null;
		outgoingStopTicks = 0;
	}

	/** 每 tick 调一次。 */
	public static void tick() {
		// 解析卡死兜底：网络请求最长 15+20 秒，超过阈值还没回调就强制放行
		if (state == State.RESOLVING
				&& System.currentTimeMillis() - resolvingSince > RESOLVE_TIMEOUT_MS) {
			HandheldMusic.LOGGER.warn("[HandheldMusic] 解析超时，强制退出 RESOLVING");
			state = State.ERROR;
			status = "获取超时，请重试";
		}

		// 旧实例：等它的采样级淡出走完再停
		if (outgoing != null) {
			outgoingPeak = samplePeakOfOutgoing();
			if (--outgoingStopTicks <= 0) {
				HandheldMusic.LOGGER.info("[HandheldMusic] 淡出完成，切点峰值 {} / 32768", outgoingPeak);
				stopOutgoingNow();
			}
		}

		if (current == null) {
			return;
		}
		ticksSinceStart++;

		// 刚起播这几 tick 里 channel 还没建好，isActive 一定是 false，不能当播完处理
		if (ticksSinceStart > STARTUP_GRACE_TICKS
				&& !ChannelUtil.engine().isActive(current)) {
			// 播完了（流 EOF 或 channel 被回收）
			handleFinished();
			return;
		}

		// 环境判定每 tick 推进平滑，参数下发每 10 tick（channel 可能重建）
		EnvironmentAudio.tick(Minecraft.getInstance().player);
		if (++tickCounter % 10 == 0) {
			EnvironmentAudio.apply(current);
		}
	}

	/**
	 * 一首播完：清掉当前实例再推进队列。
	 *
	 * 顺序很关键——必须先把 current 置空再 advance。
	 * advance 会走 startStream → moveCurrentToOutgoing，那边看到 current == null 就
	 * 直接返回，不会把刚建好的新实例误当成旧实例淡出掉。
	 */
	private static void handleFinished() {
		if (current != null) {
			// 正常播完时 channel 已经结束，stop 是 no-op；
			// 但误判/主动结束时这行能防止留下没人管的幽灵音源。
			Minecraft.getInstance().getSoundManager().stop(current);
			current.finish();
		}
		StreamRegistry.unregister(currentId);
		current = null;
		currentId = null;
		streamRef = null;
		if (state == State.PLAYING) {
			advanceAfterFinish();
		}
	}

	/** 仅供自动测试：模拟「当前曲目自然播完」，走和 tick 完全相同的推进路径。 */
	public static void debugFinish() {
		if (current == null) {
			return;
		}
		handleFinished();
	}

	/** 供自动测试调用，不带 UI。 */
	public static void autoPlay() {
		play("海阔天空", null);
	}
}
