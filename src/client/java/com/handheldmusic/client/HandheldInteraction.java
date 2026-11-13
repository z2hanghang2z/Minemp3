package com.handheldmusic.client;

import com.handheldmusic.client.gui.PlayerScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;

/**
 * 手持物品的交互入口。
 *
 * 右键（含潜行）统一是「打开播放器」——不做常驻 HUD，
 * 不在用播放器的时候屏幕上不该出现任何东西。
 *
 * 放在 client 包而不是 audio 包，避免 audio → gui 的包依赖。
 */
public final class HandheldInteraction {
	private HandheldInteraction() {
	}

	public static void onUse(Player player) {
		Minecraft.getInstance().execute(() ->
				Minecraft.getInstance().setScreenAndShow(new PlayerScreen()));
	}
}
