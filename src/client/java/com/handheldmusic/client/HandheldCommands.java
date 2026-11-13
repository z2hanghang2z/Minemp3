package com.handheldmusic.client;

import com.handheldmusic.client.audio.EnvironmentAudio;
import com.handheldmusic.client.audio.PlaybackEngine;
import com.handheldmusic.client.gui.QrLoginScreen;
import com.handheldmusic.client.netease.NeteaseSession;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.concurrent.CompletableFuture;

/**
 * 客户端命令：
 *
 * /hmpaste          从系统剪贴板读取 cookie 并登录（推荐，无长度限制）
 * /hmcookie &lt;值&gt;  手动粘贴 cookie（受聊天框 256 字符限制，MUSIC_U 有 800+ 字符，基本用不了）
 * /hmreload         重新读取 run/handheldmusic/cookie.txt
 * /hmqr             打开扫码登录界面
 * /hmlogin          查看登录状态 / 重新校验
 * /hmlogout         清除登录态
 */
public final class HandheldCommands {
	/** ChatScreen 里 EditBox.setMaxLength(256)，MUSIC_U 有 800+ 字符，必被截断。 */
	private static final int CHAT_LIMIT = 256;

	private HandheldCommands() {
	}

	public static void register() {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
			dispatcher.register(ClientCommands.literal("hmpaste").executes(ctx -> {
				FabricClientCommandSource src = ctx.getSource();
				String clip = Minecraft.getInstance().keyboardHandler.getClipboard();
				if (clip == null || clip.isBlank()) {
					src.sendFeedback(Component.literal("剪贴板是空的，先在浏览器里复制 MUSIC_U"));
					return 0;
				}
				applyAsync(src, clip.trim());
				return 1;
			}));

			dispatcher.register(ClientCommands.literal("hmcookie")
					.then(ClientCommands.argument("value", StringArgumentType.greedyString())
							.executes(ctx -> {
								applyAsync(ctx.getSource(),
										StringArgumentType.getString(ctx, "value"));
								return 1;
							})));

			dispatcher.register(ClientCommands.literal("hmreload").executes(ctx -> {
				FabricClientCommandSource src = ctx.getSource();
				CompletableFuture.runAsync(() -> {
					NeteaseSession.loadFromDisk();
					Minecraft.getInstance().execute(() ->
							src.sendFeedback(Component.literal(NeteaseSession.statusLine())));
				});
				return 1;
			}));

			dispatcher.register(ClientCommands.literal("hmqr").executes(ctx -> {
				// 26.3 里 Minecraft#setScreen 改名成了 setScreenAndShow
				Minecraft.getInstance().execute(() ->
						Minecraft.getInstance().setScreenAndShow(new QrLoginScreen()));
				return 1;
			}));

			dispatcher.register(ClientCommands.literal("hmlogin").executes(ctx -> {
				FabricClientCommandSource src = ctx.getSource();
				CompletableFuture.runAsync(() -> {
					NeteaseSession.refresh();
					Minecraft.getInstance().execute(() ->
							src.sendFeedback(Component.literal(NeteaseSession.statusLine())));
				});
				return 1;
			}));

			dispatcher.register(ClientCommands.literal("hmstop").executes(ctx -> {
				PlaybackEngine.stop();
				ctx.getSource().sendFeedback(Component.literal("已停止"));
				return 1;
			}));

			dispatcher.register(ClientCommands.literal("hmpause").executes(ctx -> {
				PlaybackEngine.togglePause();
				ctx.getSource().sendFeedback(Component.literal(PlaybackEngine.statusLine()));
				return 1;
			}));

			dispatcher.register(ClientCommands.literal("hmnext").executes(ctx -> {
				PlaybackEngine.playNext(ctx.getSource().getPlayer());
				ctx.getSource().sendFeedback(Component.literal(PlaybackEngine.statusLine()));
				return 1;
			}));

			dispatcher.register(ClientCommands.literal("hmprev").executes(ctx -> {
				PlaybackEngine.playPrev(ctx.getSource().getPlayer());
				ctx.getSource().sendFeedback(Component.literal(PlaybackEngine.statusLine()));
				return 1;
			}));

			dispatcher.register(ClientCommands.literal("hmmode").executes(ctx -> {
				PlaybackEngine.cycleMode(ctx.getSource().getPlayer());
				ctx.getSource().sendFeedback(Component.literal(
						"播放模式：" + PlaybackEngine.queue().mode().label
								+ "　队列 " + PlaybackEngine.queuePosition()));
				return 1;
			}));

			dispatcher.register(ClientCommands.literal("hmstatus").executes(ctx -> {
				ctx.getSource().sendFeedback(Component.literal(
						PlaybackEngine.statusLine() + "　|　" + NeteaseSession.statusLine()));
				return 1;
			}));

			dispatcher.register(ClientCommands.literal("hmplay")
					.then(ClientCommands.argument("keyword", StringArgumentType.greedyString())
							.executes(ctx -> {
								String keyword = StringArgumentType.getString(ctx, "keyword");
								PlaybackEngine.play(keyword, ctx.getSource().getPlayer());
								return 1;
							})));

			dispatcher.register(ClientCommands.literal("hmenv")
					.then(ClientCommands.argument("mode", StringArgumentType.word())
							.suggests((c, b) -> {
								b.suggest("outdoor");
								b.suggest("indoor");
								b.suggest("underwater");
								b.suggest("auto");
								return b.buildFuture();
							})
							.executes(ctx -> {
								String mode = StringArgumentType.getString(ctx, "mode");
								FabricClientCommandSource src = ctx.getSource();
								switch (mode) {
									case "outdoor" -> EnvironmentAudio.force(EnvironmentAudio.Env.OUTDOOR);
									case "indoor" -> EnvironmentAudio.force(EnvironmentAudio.Env.INDOOR);
									case "underwater" -> EnvironmentAudio.force(EnvironmentAudio.Env.UNDERWATER);
									default -> EnvironmentAudio.clearForce();
								}
								src.sendFeedback(Component.literal("环境：" + (EnvironmentAudio.isForced()
										? "强制 " + EnvironmentAudio.current().label
										: "跟随场景（" + EnvironmentAudio.current().label + "）")));
								return 1;
							})));

			dispatcher.register(ClientCommands.literal("hmlogout").executes(ctx -> {
				NeteaseSession.logout();
				ctx.getSource().sendFeedback(Component.literal("已退出登录，回到匿名模式"));
				return 1;
			}));
		});
	}

	private static void applyAsync(FabricClientCommandSource src, String value) {
		src.sendFeedback(Component.literal("正在校验 cookie…（长度 " + value.length() + "）"));
		CompletableFuture.runAsync(() -> {
			NeteaseSession.ApplyResult result = NeteaseSession.apply(value);
			Component msg = switch (result) {
				case OK -> Component.literal("登录成功：" + NeteaseSession.nickname());
				case NO_MUSIC_U -> Component.literal(
						"这串 cookie 里没有 MUSIC_U。MUSIC_U 是 HttpOnly，document.cookie 读不到——"
								+ "请用 F12 → Application → Cookies → music.163.com 里找 MUSIC_U 那一行");
				case INVALID -> Component.literal(truncationHint(value));
			};
			Minecraft.getInstance().execute(() -> src.sendFeedback(msg));
		});
	}

	private static String truncationHint(String value) {
		if (value.length() >= CHAT_LIMIT - 10 && value.length() <= CHAT_LIMIT + 10) {
			return "登录失败：MUSIC_U 无效。注意聊天框最多 " + CHAT_LIMIT
					+ " 字符，你的值有 " + value.length()
					+ " 字符，粘贴时被截断了——改用 /hmpaste（读剪贴板，无长度限制）";
		}
		return "登录失败：MUSIC_U 无效或已过期，重新登录网页版再复制一次";
	}
}
