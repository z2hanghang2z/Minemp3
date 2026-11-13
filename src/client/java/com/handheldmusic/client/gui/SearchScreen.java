package com.handheldmusic.client.gui;

import com.handheldmusic.HandheldMusic;
import com.handheldmusic.client.netease.NeteaseApi;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/** 搜索页（P2.6）。列表部分全部继承自 {@link AbstractSongListScreen}。 */
public class SearchScreen extends AbstractSongListScreen {
	private EditBox input;
	private Button searchButton;

	public SearchScreen() {
		super(Component.literal("搜索"));
	}

	@Override
	protected Component titleText() {
		return Component.literal("搜索");
	}

	@Override
	protected void buildWidgets() {
		this.input = new EditBox(this.font, x0 + 8, y0 + 18, 116, 18, Component.literal("关键词"));
		this.input.setMaxLength(64);
		this.input.setHint(Component.literal("歌名 / 歌手"));
		addRenderableWidget(this.input);

		this.searchButton = UiFeedback.button(Component.literal("搜索"),
				b -> doSearch(), x0 + 128, y0 + 18, 40, 18);
		addRenderableWidget(this.searchButton);

		this.message = "输入歌名或歌手，点「搜索」";
	}

	@Override
	public void tick() {
		super.tick();
		if (this.searchButton != null) {
			this.searchButton.active = !loading;
		}
	}

	/** 自动测试用：填好关键词并立刻搜。 */
	public void prefill(String keyword) {
		if (this.input != null) {
			this.input.setValue(keyword);
		}
		doSearch();
	}

	private void doSearch() {
		String keyword = this.input == null ? "" : this.input.getValue().trim();
		if (keyword.isEmpty() || loading) {
			return;
		}
		loading = true;
		message = "搜索「" + keyword + "」…";
		CompletableFuture.runAsync(() -> {
			try {
				List<NeteaseApi.Song> found = NeteaseApi.search(keyword, 30);
				Minecraft.getInstance().execute(() -> {
					results = found;
					scroll = 0;
					loading = false;
					message = found.isEmpty() ? "没有结果" : "共 " + found.size() + " 首";
				});
			} catch (Exception e) {
				HandheldMusic.LOGGER.warn("[HandheldMusic] 搜索失败", e);
				Minecraft.getInstance().execute(() -> {
					loading = false;
					message = "搜索失败：" + e.getClass().getSimpleName();
				});
			}
		});
	}
}
