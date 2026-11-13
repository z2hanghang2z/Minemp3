package com.handheldmusic.client.gui;

import com.handheldmusic.HandheldMusic;
import com.handheldmusic.client.netease.NeteaseApi;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 通用远程列表页：进页面自动拉一次，可手动刷新，点行即播。
 *
 * 「我喜欢的音乐」和「每日推荐」都用它，区别只在传进来的 loader。
 */
public class RemoteListScreen extends AbstractSongListScreen {
	@FunctionalInterface
	public interface Loader {
		List<NeteaseApi.Song> load() throws Exception;
	}

	private final Component title;
	private final Loader loader;
	/** Screen#init 会被重复调用（窗口尺寸变化等），别每次都重拉接口。 */
	private boolean requested;

	public RemoteListScreen(Component title, Loader loader) {
		super(title);
		this.title = title;
		this.loader = loader;
		// 没有输入框，列表可以上移
		this.listTopOffset = 28;
	}

	@Override
	protected Component titleText() {
		return title;
	}

	@Override
	protected void buildWidgets() {
		addRenderableWidget(UiFeedback.button(Component.literal("刷新"), b -> {
			requested = false;
			load();
		}, x0 + 120, y0 + 4, 48, 16));
		if (!requested) {
			requested = true;
			load();
		}
	}

	private void load() {
		if (loading) {
			return;
		}
		loading = true;
		message = "读取中…";
		CompletableFuture.runAsync(() -> {
			try {
				List<NeteaseApi.Song> songs = loader.load();
				HandheldMusic.LOGGER.info("[HandheldMusic] 列表加载完成：{} 首", songs.size());
				Minecraft.getInstance().execute(() -> {
					results = songs;
					scroll = 0;
					loading = false;
					message = "共 " + songs.size() + " 首";
				});
			} catch (Exception e) {
				HandheldMusic.LOGGER.warn("[HandheldMusic] 列表加载失败", e);
				String hint = e instanceof IllegalStateException
						? e.getMessage()
						: "读取失败：" + e.getClass().getSimpleName();
				Minecraft.getInstance().execute(() -> {
					loading = false;
					message = hint;
				});
			}
		});
	}
}
