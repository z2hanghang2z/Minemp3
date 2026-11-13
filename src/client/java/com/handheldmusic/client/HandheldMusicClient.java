package com.handheldmusic.client;

import com.handheldmusic.ClientBridge;
import com.handheldmusic.HandheldMusic;
import com.handheldmusic.client.audio.PlaybackEngine;
import com.handheldmusic.client.gui.QrLoginScreen;
import com.handheldmusic.client.netease.NeteaseSession;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Screenshot;

import java.util.concurrent.CompletableFuture;

public class HandheldMusicClient implements ClientModInitializer {
	private int autotestTicks = -1;

	@Override
	public void onInitializeClient() {
		ClientBridge.useHandler = HandheldInteraction::onUse;
		HandheldCommands.register();

		// 登录态加载要走网络，别卡启动流程。
		CompletableFuture.runAsync(NeteaseSession::loadFromDisk)
				.thenRun(() -> HandheldMusic.LOGGER.info("[HandheldMusic] 登录状态：{}",
						NeteaseSession.statusLine()));

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			PlaybackEngine.tick();
			runAutotest(client);
		});
		HandheldMusic.LOGGER.info("[HandheldMusic] 客户端初始化完成");
	}

	/**
	 * 调试开关：
	 * -Dhandheldmusic.autotest=true  启动 6 秒后自动播一首
	 * -Dhandheldmusic.autotest=qr    自动打开扫码界面并截图
	 * -Dhandheldmusic.autotest=state 播一首 → 暂停 → 恢复 → 停止，全程打日志
	 */
	private void runAutotest(net.minecraft.client.Minecraft client) {
		String mode = System.getProperty("handheldmusic.autotest");
		if (mode == null || mode.isEmpty()) {
			return;
		}
		if (autotestTicks < 0) {
			autotestTicks = 0;
		}
		autotestTicks++;

		if (autotestTicks == 120 && "true".equals(mode)) {
			HandheldMusic.LOGGER.info("[HandheldMusic] 自动测试：触发播放");
			PlaybackEngine.autoPlay();
		}
		if (autotestTicks == 100 && "qr".equals(mode)) {
			HandheldMusic.LOGGER.info("[HandheldMusic] 自动测试：打开扫码界面");
			client.setScreenAndShow(new QrLoginScreen());
		}
		if (autotestTicks == 220 && "qr".equals(mode)) {
			HandheldMusic.LOGGER.info("[HandheldMusic] 自动测试：截图");
			Screenshot.grab(client, false);
		}

		if ("state".equals(mode)) {
			runStateMachineTest();
		}
		if ("switch".equals(mode)) {
			runSwitchTest();
		}
		if ("ui".equals(mode)) {
			runUiTest(client);
		}
		if ("search".equals(mode)) {
			runSearchTest(client);
		}
		if ("env".equals(mode)) {
			runEnvTest();
		}
		if ("click".equals(mode)) {
			runClickTest(client);
		}
		if ("daily".equals(mode)) {
			if (autotestTicks == 120) {
				HandheldMusic.LOGGER.info("[DailyTest] 打开每日推荐");
				client.setScreenAndShow(new com.handheldmusic.client.gui.RemoteListScreen(
						net.minecraft.network.chat.Component.literal("每日推荐"),
						com.handheldmusic.client.netease.NeteaseLists::daily));
			}
			if (autotestTicks == 220) {
				HandheldMusic.LOGGER.info("[DailyTest] 截图");
				Screenshot.grab(client, false);
			}
		}
		if ("playlist".equals(mode)) {
			if (autotestTicks == 120) {
				HandheldMusic.LOGGER.info("[PlaylistTest] 打开喜欢列表");
				client.setScreenAndShow(new com.handheldmusic.client.gui.RemoteListScreen(
						net.minecraft.network.chat.Component.literal("我喜欢的音乐"),
						com.handheldmusic.client.netease.NeteaseLists::liked));
			}
			if (autotestTicks == 220) {
				HandheldMusic.LOGGER.info("[PlaylistTest] 截图");
				Screenshot.grab(client, false);
			}
		}
		if ("recipe".equals(mode)) {
			runRecipeTest(client);
		}
		if ("queue".equals(mode)) {
			runQueueTest(client);
		}
		if ("press".equals(mode)) {
			runPressTest(client);
		}
		if ("idle".equals(mode)) {
			if (autotestTicks == 120) {
				client.setScreenAndShow(new com.handheldmusic.client.gui.PlayerScreen());
			}
			if (autotestTicks == 180) {
				HandheldMusic.LOGGER.info("[IdleTest] 截图");
				Screenshot.grab(client, false);
			}
		}
	}

	/**
	 * 配方验证：需要进世界才有数据包（主菜单不加载 recipe）。
	 * 配合 {@code --args="--quickPlaySingleplayer RecipeTest"} 使用。
	 *
	 * 客户端只同步精简的 RecipeAccess，拿不到完整配方表，
	 * 所以让服务端自己判断——/recipe give 成功会回一条聊天消息，失败会报「未知配方」。
	 */
	private void runRecipeTest(net.minecraft.client.Minecraft client) {
		switch (autotestTicks) {
			case 300 -> {
				if (client.getConnection() == null || client.player == null) {
					HandheldMusic.LOGGER.warn("[RecipeTest] 还没进世界，跳过（需要 --quickPlaySingleplayer）");
					return;
				}
				HandheldMusic.LOGGER.info("[RecipeTest] 请求解锁配方 handheldmusic:handheld_player");
				client.getConnection().sendCommand(
						"recipe give @s handheldmusic:handheld_player");
			}
			case 340 -> {
				HandheldMusic.LOGGER.info("[RecipeTest] 给一个随身听看物品本身");
				if (client.getConnection() != null) {
					client.getConnection().sendCommand("give @s handheldmusic:handheld_player");
				}
				matchCrafting(client);
			}
			case 400 -> Screenshot.grab(client, false);
			default -> {
			}
		}
	}

	/**
	 * 真拿 9 个物品摆一遍，让服务端配方表匹配一次。
	 * 客户端只有精简版 RecipeAccess，完整配方表在集成服务端，只能反射取。
	 */
	private void matchCrafting(net.minecraft.client.Minecraft client) {
		try {
			java.lang.reflect.Field f = net.minecraft.client.Minecraft.class
					.getDeclaredField("singleplayerServer");
			f.setAccessible(true);
			net.minecraft.server.MinecraftServer server =
					(net.minecraft.server.MinecraftServer) f.get(client);
			if (server == null || client.level == null) {
				HandheldMusic.LOGGER.warn("[RecipeTest] 没有集成服务端，跳过匹配");
				return;
			}

			net.minecraft.world.item.ItemStack iron =
					new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_INGOT);
			net.minecraft.world.item.ItemStack redstone =
					new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.REDSTONE);
			net.minecraft.world.item.ItemStack diamond =
					new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND);

			// 行优先：铁 红石 铁 / 铁 钻石 铁 / 铁 铁 铁
			var input = net.minecraft.world.item.crafting.CraftingInput.of(3, 3,
					java.util.List.of(iron, redstone, iron, iron, diamond, iron, iron, iron, iron));
			var match = server.getRecipeManager().getRecipeFor(
					net.minecraft.world.item.crafting.RecipeType.CRAFTING, input, client.level);
			HandheldMusic.LOGGER.info("[RecipeTest] 正例「铁红铁/铁钻铁/铁铁铁」→ {}",
					match.map(h -> h.id().toString()).orElse("无匹配"));

			// 反例：钻石换成铁，不该命中这个配方
			var wrong = net.minecraft.world.item.crafting.CraftingInput.of(3, 3,
					java.util.List.of(iron, redstone, iron, iron, iron, iron, iron, iron, iron));
			var match2 = server.getRecipeManager().getRecipeFor(
					net.minecraft.world.item.crafting.RecipeType.CRAFTING, wrong, client.level);
			HandheldMusic.LOGGER.info("[RecipeTest] 反例「铁红铁/铁铁铁/铁铁铁」→ {}",
					match2.map(h -> h.id().toString()).orElse("无匹配"));
		} catch (Exception e) {
			HandheldMusic.LOGGER.error("[RecipeTest] 匹配失败", e);
		}
	}

	/**
	 * 队列演练：搜索 5 首入队 → 手动下一首 → 模拟播完（应自动推进）→ 切模式 → 截图。
	 * 真等一首播完要 3 分钟，所以用 debugFinish() 走和 tick 完全相同的推进路径。
	 */
	private void runQueueTest(net.minecraft.client.Minecraft client) {
		switch (autotestTicks) {
			case 100 -> {
				HandheldMusic.LOGGER.info("[QueueTest] 搜索并建队列");
				java.util.concurrent.CompletableFuture.runAsync(() -> {
					try {
						java.util.List<com.handheldmusic.client.netease.NeteaseApi.Song> songs =
								com.handheldmusic.client.netease.NeteaseApi.search("海阔天空", 5);
						HandheldMusic.LOGGER.info("[QueueTest] 搜索到 {} 首", songs.size());
						queueTestSongs = songs;
						client.execute(() -> PlaybackEngine.playFromList(songs, 0, null));
					} catch (Exception e) {
						HandheldMusic.LOGGER.error("[QueueTest] 搜索失败", e);
					}
				});
			}
			case 180 -> HandheldMusic.LOGGER.info("[QueueTest] 起播后 → {}｜队列 {}",
					PlaybackEngine.statusLine(), PlaybackEngine.queuePosition());
			case 200 -> {
				HandheldMusic.LOGGER.info("[QueueTest] 手动下一首");
				PlaybackEngine.playNext(null);
			}
			case 280 -> HandheldMusic.LOGGER.info("[QueueTest] 下一首后 → {}｜队列 {}",
					PlaybackEngine.statusLine(), PlaybackEngine.queuePosition());
			case 300 -> {
				HandheldMusic.LOGGER.info("[QueueTest] 模拟播完（应自动推进到下一首）");
				PlaybackEngine.debugFinish();
			}
			case 380 -> HandheldMusic.LOGGER.info("[QueueTest] 自动推进后 → {}｜队列 {}",
					PlaybackEngine.statusLine(), PlaybackEngine.queuePosition());
			case 400 -> {
				HandheldMusic.LOGGER.info("[QueueTest] 切模式：{}", PlaybackEngine.cycleMode(null).label);
				HandheldMusic.LOGGER.info("[QueueTest] 切模式：{}", PlaybackEngine.cycleMode(null).label);
				HandheldMusic.LOGGER.info("[QueueTest] 切模式：{}", PlaybackEngine.cycleMode(null).label);
			}
			case 440 -> {
				HandheldMusic.LOGGER.info("[QueueTest] 打开界面截图");
				client.setScreenAndShow(new com.handheldmusic.client.gui.PlayerScreen());
			}
			case 500 -> Screenshot.grab(client, false);
			case 540 -> {
				// 循环回绕：跳到最后一首再模拟播完，列表循环应该回到 1/N
				if (queueTestSongs != null) {
					HandheldMusic.LOGGER.info("[QueueTest] 跳到最后一首（{}），验证循环回绕",
							queueTestSongs.size());
					PlaybackEngine.playFromList(queueTestSongs, queueTestSongs.size() - 1, null);
				}
			}
			case 620 -> {
				HandheldMusic.LOGGER.info("[QueueTest] 最后一首起播 → {}｜队列 {}",
						PlaybackEngine.statusLine(), PlaybackEngine.queuePosition());
				PlaybackEngine.debugFinish();
			}
			case 680 -> HandheldMusic.LOGGER.info("[QueueTest] 回绕后 → {}｜队列 {}（期望 1/{}）",
					PlaybackEngine.statusLine(), PlaybackEngine.queuePosition(),
					queueTestSongs == null ? 0 : queueTestSongs.size());
			case 720 -> {
				// 失败跳曲：队列里混一首必然拿不到直链的，播完自动推进到它时应跳过
				if (queueTestSongs != null && !queueTestSongs.isEmpty()) {
					java.util.List<com.handheldmusic.client.netease.NeteaseApi.Song> mixed =
							new java.util.ArrayList<>();
					mixed.add(queueTestSongs.get(0));
					mixed.add(new com.handheldmusic.client.netease.NeteaseApi.Song(
							1L, "不存在的曲目", "无", 0L, null));
					HandheldMusic.LOGGER.info("[QueueTest] 混合队列 [有效, 无效]，播完应跳过无效那首");
					PlaybackEngine.playFromList(mixed, 0, null);
				}
			}
			case 780 -> HandheldMusic.LOGGER.info("[QueueTest] 起播有效曲 → {}｜队列 {}",
					PlaybackEngine.statusLine(), PlaybackEngine.queuePosition());
			case 800 -> PlaybackEngine.debugFinish();
			case 880 -> HandheldMusic.LOGGER.info("[QueueTest] 跳过无效曲后 → {}｜队列 {}",
					PlaybackEngine.statusLine(), PlaybackEngine.queuePosition());
			default -> {
			}
		}
	}

	private java.util.List<com.handheldmusic.client.netease.NeteaseApi.Song> queueTestSongs;

	/** 界面截图：播一首后打开口袋机界面。 */
	private void runUiTest(net.minecraft.client.Minecraft client) {
		switch (autotestTicks) {
			case 120 -> PlaybackEngine.play("海阔天空", null);
			case 200 -> {
				HandheldMusic.LOGGER.info("[UiTest] 打开界面");
				client.setScreenAndShow(new com.handheldmusic.client.gui.PlayerScreen());
			}
			case 260 -> {
				HandheldMusic.LOGGER.info("[UiTest] 截图");
				Screenshot.grab(client, false);
			}
			default -> {
			}
		}
	}

	private com.handheldmusic.client.gui.PlayerScreen debugScreen;

	private com.handheldmusic.client.gui.RemoteListScreen clickScreen;

	/** 环境音效演练：播一首，然后依次强制室外 / 室内 / 水下，看 AL 有没有报错。 */
	private void runEnvTest() {
		switch (autotestTicks) {
			case 120 -> {
				HandheldMusic.LOGGER.info("[EnvTest] 播放并强制室外");
				com.handheldmusic.client.audio.EnvironmentAudio.force(
						com.handheldmusic.client.audio.EnvironmentAudio.Env.OUTDOOR);
				com.handheldmusic.client.audio.PlaybackEngine.autoPlay();
			}
			case 220 -> {
				HandheldMusic.LOGGER.info("[EnvTest] 切室内");
				com.handheldmusic.client.audio.EnvironmentAudio.force(
						com.handheldmusic.client.audio.EnvironmentAudio.Env.INDOOR);
			}
			case 320 -> {
				HandheldMusic.LOGGER.info("[EnvTest] 切水下");
				com.handheldmusic.client.audio.EnvironmentAudio.force(
						com.handheldmusic.client.audio.EnvironmentAudio.Env.UNDERWATER);
			}
			case 420 -> {
				HandheldMusic.LOGGER.info("[EnvTest] 恢复自动");
				com.handheldmusic.client.audio.EnvironmentAudio.clearForce();
			}
			case 480 -> HandheldMusic.LOGGER.info("[EnvTest] 状态 → {}",
					com.handheldmusic.client.audio.PlaybackEngine.statusLine());
			default -> {
			}
		}
	}

	/** 点击验证：打开每日推荐 → 等加载 → 模拟点击第一行 → 看有没有真的播。 */
	private void runClickTest(net.minecraft.client.Minecraft client) {
		switch (autotestTicks) {
			case 120 -> {
				clickScreen = new com.handheldmusic.client.gui.RemoteListScreen(
						net.minecraft.network.chat.Component.literal("每日推荐"),
						com.handheldmusic.client.netease.NeteaseLists::daily);
				client.setScreenAndShow(clickScreen);
			}
			case 200 -> {
				if (clickScreen == null) {
					return;
				}
				HandheldMusic.LOGGER.info("[ClickTest] 行控件数={}", clickScreen.debugRowWidgetCount());
				boolean handled = clickScreen.debugClickRow(0);
				HandheldMusic.LOGGER.info("[ClickTest] 点击第一行返回 {}｜队列 {}",
						handled, PlaybackEngine.queuePosition());
			}
			case 260 -> {
				HandheldMusic.LOGGER.info("[ClickTest] 状态 → {}｜队列 {}",
						PlaybackEngine.statusLine(), PlaybackEngine.queuePosition());
			}
			case 300 -> {
				HandheldMusic.LOGGER.info("[ClickTest] 点第 3 行，验证游标跳到 3/N");
				if (clickScreen != null) {
					clickScreen.debugClickRow(2);
				}
			}
			case 380 -> {
				HandheldMusic.LOGGER.info("[ClickTest] 点第 3 行后 → {}｜队列 {}",
						PlaybackEngine.statusLine(), PlaybackEngine.queuePosition());
			}
			default -> {
			}
		}
	}

	/** 手感验证：打开界面，模拟按下一个按钮，日志里看 y 是否 +1 再还原。 */
	private void runPressTest(net.minecraft.client.Minecraft client) {
		switch (autotestTicks) {
			case 120 -> {
				debugScreen = new com.handheldmusic.client.gui.PlayerScreen();
				client.setScreenAndShow(debugScreen);
			}
			case 140 -> {
				net.minecraft.client.gui.components.AbstractWidget w = firstButton();
				if (w != null) {
					int before = w.getY();
					com.handheldmusic.client.gui.UiFeedback.press(w);
					HandheldMusic.LOGGER.info("[PressTest] 按下：y {} -> {}", before, w.getY());
				}
			}
			case 144 -> {
				net.minecraft.client.gui.components.AbstractWidget w = firstButton();
				if (w != null) {
					HandheldMusic.LOGGER.info("[PressTest] 3 tick 后：y {}", w.getY());
				}
			}
			case 160 -> Screenshot.grab(client, false);
			default -> {
			}
		}
	}

	private net.minecraft.client.gui.components.AbstractWidget firstButton() {
		if (debugScreen == null) {
			return null;
		}
		for (Object o : debugScreen.children()) {
			if (o instanceof net.minecraft.client.gui.components.AbstractWidget w) {
				return w;
			}
		}
		return null;
	}

	/** 搜索页截图：打开界面 → 预填关键词搜索 → 截图。 */
	private void runSearchTest(net.minecraft.client.Minecraft client) {
		switch (autotestTicks) {
			case 120 -> {
				HandheldMusic.LOGGER.info("[SearchTest] 打开搜索页");
				com.handheldmusic.client.gui.SearchScreen screen =
						new com.handheldmusic.client.gui.SearchScreen();
				client.setScreenAndShow(screen);
				screen.prefill("海阔天空");
			}
			case 200 -> {
				HandheldMusic.LOGGER.info("[SearchTest] 截图");
				Screenshot.grab(client, false);
			}
			default -> {
			}
		}
	}

	/** 切歌演练：A 播到一半切 B，验证交叉淡化有没有爆音。 */
	private void runSwitchTest() {
		switch (autotestTicks) {
			case 120 -> {
				HandheldMusic.LOGGER.info("[SwitchTest] 播放 A");
				PlaybackEngine.play("海阔天空", null);
			}
			case 260 -> {
				HandheldMusic.LOGGER.info("[SwitchTest] A 播到 {}，切到 B",
						PlaybackEngine.statusLine());
				PlaybackEngine.play("起风了", null);
			}
			case 400 -> {
				HandheldMusic.LOGGER.info("[SwitchTest] B 状态 → {}", PlaybackEngine.statusLine());
				PlaybackEngine.stop();
			}
			case 480 -> HandheldMusic.LOGGER.info("[SwitchTest] 收尾 → {}", PlaybackEngine.statusLine());
			default -> {
			}
		}
	}

	/** 状态机演练：每 40 tick 推进一步，日志里能看出状态流转。 */
	private void runStateMachineTest() {
		switch (autotestTicks) {
			case 120 -> {
				HandheldMusic.LOGGER.info("[StateTest] 播放");
				PlaybackEngine.autoPlay();
			}
			case 200 -> {
				HandheldMusic.LOGGER.info("[StateTest] 暂停 → {}", PlaybackEngine.statusLine());
				PlaybackEngine.pause();
			}
			case 260 -> {
				HandheldMusic.LOGGER.info("[StateTest] 暂停 3 秒后 → {}", PlaybackEngine.statusLine());
				PlaybackEngine.resume();
			}
			case 320 -> {
				HandheldMusic.LOGGER.info("[StateTest] 恢复后 → {}", PlaybackEngine.statusLine());
			}
			case 380 -> {
				HandheldMusic.LOGGER.info("[StateTest] 停止 → {}", PlaybackEngine.statusLine());
				PlaybackEngine.stop();
			}
			case 440 -> {
				HandheldMusic.LOGGER.info("[StateTest] 停止后 → {}", PlaybackEngine.statusLine());
			}
			default -> {
				if (autotestTicks > 200 && autotestTicks < 380 && autotestTicks % 20 == 0) {
					HandheldMusic.LOGGER.info("[StateTest] tick {} → {}", autotestTicks,
							PlaybackEngine.statusLine());
				}
			}
		}
	}
}
