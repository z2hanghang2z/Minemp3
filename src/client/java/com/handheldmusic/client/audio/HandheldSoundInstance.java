package com.handheldmusic.client.audio;

import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.valueproviders.ConstantFloat;

/**
 * 交给原版 SoundEngine 播放的声音实例。
 * 关键点：getSound() 返回一个 stream=true 的 Sound，指向我们注册的假 Identifier，
 * 原版据此走 getStream 分支，再由 Mixin 换成真正的音频流。
 */
public class HandheldSoundInstance extends AbstractTickableSoundInstance {
	private final Identifier streamId;
	private final Sound streamSound;

	public HandheldSoundInstance(Identifier streamId) {
		super(SoundEvent.createVariableRangeEvent(streamId), SoundSource.MUSIC,
				SoundInstance.createUnseededRandom());
		this.streamId = streamId;
		this.streamSound = new Sound(
				streamId,
				ConstantFloat.of(1.0f),
				ConstantFloat.of(1.0f),
				1,
				Sound.Type.FILE,
				true,
				false,
				0
		);
		// 关键：父类 getVolume()/getPitch() 读的是 protected 字段 sound，而不是 getSound()。
		// 不填这里，SoundEngine.play -> getVolume() 会直接 NPE。
		this.sound = this.streamSound;
		this.volume = 1.0f;
		this.pitch = 1.0f;
	}

	/**
	 * 默认实现会去 sounds.json 查这个 Identifier，查不到返回 null，
	 * SoundEngine.play 直接判 NOT_STARTED（日志里的 "Unable to play unknown soundEvent"）。
	 * 这里自己造一个，把我们的 Sound 塞进去。
	 * 注意要同时写回父类的 soundEvent / sound 两个字段，否则后面照样 NPE。
	 */
	@Override
	public WeighedSoundEvents getOrResolve(SoundManager soundManager) {
		if (this.soundEvent == null) {
			WeighedSoundEvents weighed = new WeighedSoundEvents(streamId, null);
			weighed.addSound(this.streamSound);
			this.soundEvent = weighed;
		}
		this.sound = this.streamSound;
		return this.soundEvent;
	}

	@Override
	public Sound getSound() {
		return this.streamSound;
	}

	@Override
	public boolean isRelative() {
		return true;
	}

	/**
	 * 用户音量。原版 tick 里会对 TickableSoundInstance 重新算音量并写回 channel，
	 * 所以改这个字段下一 tick 就生效，不用直接碰 OpenAL。
	 */
	public void setBaseVolume(float v) {
		this.volume = Math.max(0.0f, Math.min(1.0f, v));
	}

	public float baseVolume() {
		return this.volume;
	}

	@Override
	public boolean isLooping() {
		return false;
	}

	@Override
	public void tick() {
	}

	public void finish() {
		StreamRegistry.unregister(streamId);
		stop();
	}
}
