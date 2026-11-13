package com.handheldmusic.client.audio;

/**
 * 采样级线性增益斜坡。
 *
 * 为什么不靠「每 tick 改音量」：原版 tick 是 20Hz，淡入淡出如果按 tick 步进，
 * 每 50ms 就会有一次 20% 量级的幅度跳变——在持续音上表现为可闻的拉链噪声。
 * 这里按「帧」推进，等于 44100Hz 粒度，斜坡本身是平滑的，不会有阶跃。
 */
public final class Fade {
	private float value = 1.0f;
	private float target = 1.0f;
	private float step;
	private boolean active;

	/** 立刻跳到某个增益（不产生斜坡）。 */
	public void jump(float v) {
		value = v;
		target = v;
		step = 0f;
		active = false;
	}

	/** 在 frames 帧内线性逼近目标增益。 */
	public void rampTo(float t, long frames) {
		target = t;
		if (frames <= 0) {
			value = t;
			step = 0f;
			active = false;
			return;
		}
		step = (t - value) / frames;
		active = true;
	}

	/** 取下一帧的增益，并推进斜坡。 */
	public float next() {
		if (active) {
			value += step;
			if ((step > 0 && value >= target) || (step < 0 && value <= target)) {
				value = target;
				active = false;
			}
		}
		return value;
	}

	/** 已经淡到静音且斜坡结束。 */
	public boolean isSilent() {
		return !active && value <= 0.0001f;
	}

	public float value() {
		return value;
	}
}
