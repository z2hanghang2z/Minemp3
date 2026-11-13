package com.handheldmusic;

import net.minecraft.world.entity.player.Player;

import java.util.function.Consumer;

/**
 * 主源码集与客户端源码集之间的桥。
 * 主源码集看不到客户端类（splitEnvironmentSourceSets），所以用回调注入。
 */
public final class ClientBridge {
	public static Consumer<Player> useHandler = player -> {
	};

	private ClientBridge() {
	}

	public static void onUse(Player player) {
		useHandler.accept(player);
	}
}
