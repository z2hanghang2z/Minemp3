package com.handheldmusic.client.audio;

import com.handheldmusic.mixin.client.SoundEngineAccessor;
import com.handheldmusic.mixin.client.SoundManagerAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.ChannelAccess;
import net.minecraft.client.sounds.SoundEngine;

/** 从 SoundEngine 的 instanceToChannel 里取 channel 句柄。 */
public final class ChannelUtil {
	private ChannelUtil() {
	}

	public static SoundEngine engine() {
		return ((SoundManagerAccessor) Minecraft.getInstance().getSoundManager()).getSoundEngine();
	}

	public static ChannelAccess.ChannelHandle handleOf(SoundInstance instance) {
		if (instance == null) {
			return null;
		}
		return ((SoundEngineAccessor) engine()).getInstanceToChannel().get(instance);
	}
}
