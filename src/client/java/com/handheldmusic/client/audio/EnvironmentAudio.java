package com.handheldmusic.client.audio;

import com.handheldmusic.HandheldMusic;
import com.handheldmusic.mixin.client.ChannelAccessor;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.ChannelAccess;
import net.minecraft.world.entity.player.Player;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.AL11;
import org.lwjgl.openal.EXTEfx;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 环境音效（P3）。
 *
 * 根据玩家所处环境给正在播放的音乐叠两层处理：
 *   - 低通（AL_DIRECT_FILTER + AL_LOWPASS_GAINHF）：水下把高频闷掉
 *   - 混响（EFX 辅助效果槽 + AL_EFFECT_REVERB）：矿洞/室内加空间感
 *
 * 环境判定只看水和天空可见性，**不看头盔**（按需求，戴头盔不影响听感）。
 *
 * 所有 AL 调用必须跑在 sound engine 线程上，统一塞进 ChannelHandle.execute()。
 */
public final class EnvironmentAudio {
	public enum Env {
		OUTDOOR("室外"), INDOOR("室内"), UNDERWATER("水下");

		public final String label;

		Env(String label) {
			this.label = label;
		}
	}

	// ---- 目标参数 ----
	private static final float CUT_OPEN = 20000.0f;
	private static final float CUT_WATER = 600.0f;

	/** 每 tick 向目标靠拢的比例，避免环境切换时参数突变。 */
	private static final float SMOOTH = 0.18f;

	private static volatile Env current = Env.OUTDOOR;
	private static volatile float cutoff = CUT_OPEN;
	private static volatile float targetCutoff = CUT_OPEN;
	private static volatile float wet = 0.0f;
	private static volatile float targetWet = 0.0f;
	private static volatile boolean fxDirty = true;

	/** 手动低通（界面按钮）叠加在环境之上。 */
	private static volatile boolean manualLowpass;

	/** 调试用：强制指定环境，null 表示跟随实际场景。 */
	private static volatile Env forced;

	// ---- AL 资源（只在 sound engine 线程上访问）----
	private static final Map<Integer, Integer> FILTER_BY_SOURCE = new ConcurrentHashMap<>();
	private static int auxSlot;
	private static int effect;

	private EnvironmentAudio() {
	}

	public static Env current() {
		return current;
	}

	public static boolean isManualLowpass() {
		return manualLowpass;
	}

	public static void toggleManualLowpass() {
		manualLowpass = !manualLowpass;
		fxDirty = true;
	}

	public static void force(Env env) {
		forced = env;
	}

	public static void clearForce() {
		forced = null;
	}

	public static boolean isForced() {
		return forced != null;
	}

	/** 每 tick 调一次（渲染线程）：判定环境并推进平滑。 */
	public static void tick(Player player) {
		Env detected = forced != null ? forced : detect(player);
		if (detected != current) {
			HandheldMusic.LOGGER.info("[HandheldMusic] 环境切换：{} → {}",
					current.label, detected.label);
			current = detected;
			switch (detected) {
				case OUTDOOR -> {
					targetCutoff = CUT_OPEN;
					targetWet = 0.0f;
				}
				case INDOOR -> {
					targetCutoff = CUT_OPEN;
					targetWet = 0.60f;
				}
				case UNDERWATER -> {
					targetCutoff = CUT_WATER;
					targetWet = 0.30f;
				}
			}
			fxDirty = true;
		}

		float targetCut = manualLowpass ? Math.min(targetCutoff, CUT_WATER) : targetCutoff;
		cutoff += (targetCut - cutoff) * SMOOTH;
		wet += (targetWet - wet) * SMOOTH;
	}

	private static Env detect(Player player) {
		if (player == null) {
			return Env.OUTDOOR;
		}
		if (player.isUnderWater()) {
			return Env.UNDERWATER;
		}
		// 头顶看不到天空 = 洞穴 / 室内（下界天然满足，也合理：下界本来就该有混响）
		boolean openSky = player.level().canSeeSky(player.blockPosition());
		return openSky ? Env.OUTDOOR : Env.INDOOR;
	}

	/** 把当前参数写到正在播放的 source 上。 */
	public static void apply(SoundInstance instance) {
		ChannelAccess.ChannelHandle handle = ChannelUtil.handleOf(instance);
		if (handle == null) {
			return;
		}
		final float cut = cutoff;
		final float wetAmt = wet;
		final boolean rewriteFx = fxDirty;
		final Env env = current;
		fxDirty = false;

		handle.execute(channel -> {
			int source = ((ChannelAccessor) (Object) channel).getSource();
			ensureResources();

			// 1) 低通
			int filter = FILTER_BY_SOURCE.computeIfAbsent(source, s -> {
				int f = EXTEfx.alGenFilters();
				EXTEfx.alFilteri(f, EXTEfx.AL_FILTER_TYPE, EXTEfx.AL_FILTER_LOWPASS);
				EXTEfx.alFilterf(f, EXTEfx.AL_LOWPASS_GAIN, 1.0f);
				return f;
			});
			EXTEfx.alFilterf(filter, EXTEfx.AL_LOWPASS_GAINHF, gainHf(cut));
			AL10.alSourcei(source, EXTEfx.AL_DIRECT_FILTER, filter);

			// 2) 混响参数（只在环境变化时重写）
			if (rewriteFx) {
				writeReverb(env);
			}
			EXTEfx.alEffectf(effect, EXTEfx.AL_REVERB_GAIN, wetAmt);

			// 3) 把 source 送到辅助槽（第 0 条发送，不带额外滤波）
			AL11.alSource3i(source, EXTEfx.AL_AUXILIARY_SEND_FILTER, auxSlot, 0,
					EXTEfx.AL_FILTER_NULL);
		});
	}

	private static void ensureResources() {
		if (auxSlot == 0) {
			auxSlot = EXTEfx.alGenAuxiliaryEffectSlots();
			effect = EXTEfx.alGenEffects();
			EXTEfx.alEffecti(effect, EXTEfx.AL_EFFECT_TYPE, EXTEfx.AL_EFFECT_REVERB);
			int err = AL10.alGetError();
			HandheldMusic.LOGGER.info("[HandheldMusic] 创建混响辅助槽 slot={} effect={} alError={}",
					auxSlot, effect, err);
		}
	}

	private static void writeReverb(Env env) {
		switch (env) {
			case INDOOR -> {
				EXTEfx.alEffectf(effect, EXTEfx.AL_REVERB_DENSITY, 1.0f);
				EXTEfx.alEffectf(effect, EXTEfx.AL_REVERB_DIFFUSION, 0.9f);
				EXTEfx.alEffectf(effect, EXTEfx.AL_REVERB_DECAY_TIME, 1.8f);
				EXTEfx.alEffectf(effect, EXTEfx.AL_REVERB_DECAY_HFRATIO, 0.7f);
				EXTEfx.alEffectf(effect, EXTEfx.AL_REVERB_GAINHF, 0.6f);
				EXTEfx.alEffectf(effect, EXTEfx.AL_REVERB_REFLECTIONS_GAIN, 0.4f);
				EXTEfx.alEffectf(effect, EXTEfx.AL_REVERB_LATE_REVERB_GAIN, 0.5f);
			}
			case UNDERWATER -> {
				EXTEfx.alEffectf(effect, EXTEfx.AL_REVERB_DENSITY, 1.0f);
				EXTEfx.alEffectf(effect, EXTEfx.AL_REVERB_DIFFUSION, 1.0f);
				EXTEfx.alEffectf(effect, EXTEfx.AL_REVERB_DECAY_TIME, 0.9f);
				EXTEfx.alEffectf(effect, EXTEfx.AL_REVERB_DECAY_HFRATIO, 0.3f);
				EXTEfx.alEffectf(effect, EXTEfx.AL_REVERB_GAINHF, 0.15f);
				EXTEfx.alEffectf(effect, EXTEfx.AL_REVERB_REFLECTIONS_GAIN, 0.2f);
				EXTEfx.alEffectf(effect, EXTEfx.AL_REVERB_LATE_REVERB_GAIN, 0.3f);
			}
			default -> {
				EXTEfx.alEffectf(effect, EXTEfx.AL_REVERB_DENSITY, 1.0f);
				EXTEfx.alEffectf(effect, EXTEfx.AL_REVERB_DIFFUSION, 1.0f);
				EXTEfx.alEffectf(effect, EXTEfx.AL_REVERB_DECAY_TIME, 0.3f);
				EXTEfx.alEffectf(effect, EXTEfx.AL_REVERB_DECAY_HFRATIO, 0.6f);
				EXTEfx.alEffectf(effect, EXTEfx.AL_REVERB_GAINHF, 0.9f);
				EXTEfx.alEffectf(effect, EXTEfx.AL_REVERB_REFLECTIONS_GAIN, 0.0f);
				EXTEfx.alEffectf(effect, EXTEfx.AL_REVERB_LATE_REVERB_GAIN, 0.0f);
			}
		}
		EXTEfx.alAuxiliaryEffectSloti(auxSlot, EXTEfx.AL_EFFECTSLOT_EFFECT, effect);
	}

	/** 截止频率映射到 AL_LOWPASS_GAINHF（0~1），对数刻度听感更自然。 */
	private static float gainHf(float cutoffHz) {
		double c = Math.max(200.0, Math.min(20000.0, cutoffHz));
		double g = Math.log(c / 200.0) / Math.log(20000.0 / 200.0);
		return (float) Math.max(0.0, Math.min(1.0, g));
	}
}
