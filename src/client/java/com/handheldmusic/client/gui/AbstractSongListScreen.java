package com.handheldmusic.client.gui;

import com.handheldmusic.HandheldMusic;
import com.handheldmusic.client.audio.PlaybackEngine;
import com.handheldmusic.client.netease.NeteaseApi;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 歌曲列表页的公共骨架：面板、列表、滚动、点选播放、返回。
 * 搜索页和「我喜欢的音乐」都建在这上面，避免列表逻辑写两遍。
 *
 * 子类只需决定：标题、列表起始纵向偏移、额外的控件（输入框 / 刷新按钮）、数据怎么来。
 */
public abstract class AbstractSongListScreen extends Screen {
	protected static final int W = 176;
	protected static final int H = 166;
	protected static final int ROW_H = 16;
	protected static final int VISIBLE_ROWS = 5;

	protected static final int COLOR_PANEL = 0xFF2B2B2B;
	protected static final int COLOR_BORDER_DARK = 0xFF000000;
	protected static final int COLOR_BORDER_LIGHT = 0xFF5A5A5A;
	protected static final int COLOR_TEXT = 0xFFE6E6E6;
	protected static final int COLOR_DIM = 0xFF8A8A8A;
	protected static final int COLOR_ACCENT = 0xFF7EC850;
	protected static final int COLOR_ROW_HOVER = 0xFF3E6B28;
	protected static final int COLOR_SLOT = 0xFF161616;

	protected int x0;
	protected int y0;

	protected volatile List<NeteaseApi.Song> results = List.of();
	protected volatile String message = "";
	protected volatile boolean loading;
	protected int scroll;

	/** 列表相对 y0 的起始偏移，子类按自己的头部高度覆盖。 */
	protected int listTopOffset = 40;

	/**
	 * 每行一个隐形控件。
	 *
	 * 为什么不靠 Screen#mouseClicked：实测按钮能收到点击、屏幕级的 mouseClicked 却收不到，
	 * 走控件分发这条路径是确定可用的（按钮就是这么工作的）。
	 */
	private final List<RowWidget> rowWidgets = new ArrayList<>();
	private int builtScroll = -1;
	private int builtSize = -1;

	protected AbstractSongListScreen(Component title) {
		super(title);
	}

	/** 面板标题。 */
	protected abstract Component titleText();

	/** 子类的额外控件。 */
	protected void buildWidgets() {
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	protected void init() {
		this.x0 = (this.width - W) / 2;
		this.y0 = (this.height - H) / 2;
		buildWidgets();
		addRenderableWidget(UiFeedback.button(Component.literal("返回"),
				b -> this.onClose(), x0 + 8, y0 + 134, 44, 18));
	}

	@Override
	public void tick() {
		super.tick();
		UiFeedback.tick();
		ensureRowWidgets();
	}

	/** 结果或滚动位置变化时重建行控件。 */
	private void ensureRowWidgets() {
		if (builtScroll == scroll && builtSize == results.size()) {
			return;
		}
		for (RowWidget w : rowWidgets) {
			removeWidget(w);
		}
		rowWidgets.clear();
		builtScroll = scroll;
		builtSize = results.size();

		for (int i = 0; i < VISIBLE_ROWS; i++) {
			int index = scroll + i;
			if (index >= results.size()) {
				break;
			}
			RowWidget widget = new RowWidget(x0 + 9, listTop() + i * ROW_H + 1,
					W - 18, ROW_H - 2, i);
			rowWidgets.add(widget);
			addRenderableWidget(widget);
		}
	}

	/** 隐形行控件：不画东西（视觉由本类统一绘制），只负责接点击。 */
	private final class RowWidget extends AbstractWidget {
		private final int slot;

		private RowWidget(int x, int y, int w, int h, int slot) {
			super(x, y, w, h, Component.empty());
			this.slot = slot;
		}

		@Override
		protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mx, int my, float pt) {
			// 视觉全部由 AbstractSongListScreen 绘制
		}

		@Override
		protected void updateWidgetNarration(NarrationElementOutput out) {
		}

		@Override
		public void onClick(MouseButtonEvent event, boolean doubleClick) {
			int index = scroll + slot;
			if (index < results.size()) {
				onRowClicked(index, results.get(index));
			}
		}
	}

	@Override
	public void removed() {
		super.removed();
		UiFeedback.reset();
	}

	// ---------------- 列表交互 ----------------

	protected int listTop() {
		return y0 + listTopOffset;
	}

	protected int listBottom() {
		return listTop() + VISIBLE_ROWS * ROW_H;
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (scrollY != 0.0 && !results.isEmpty()) {
			int max = Math.max(0, results.size() - VISIBLE_ROWS);
			scroll = Math.max(0, Math.min(max, scroll + (scrollY > 0 ? -1 : 1)));
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		// 左键在 26.3 是 button == 1（1 基），不是 0
		if (event.button() == 1) {
			int row = rowAt(event.x(), event.y());
			if (row >= 0 && row < results.size()) {
				onRowClicked(row, results.get(row));
				return true;
			}
		}
		return super.mouseClicked(event, doubleClick);
	}

	/**
	 * 点行 = 整个列表入队 + 从这一首开始播。
	 *
	 * 隐式队列：用户不需要「加入队列」这个动作，点哪首就从哪首开始，
	 * 后面自动接着播。传 index 而不是只传 song，是因为队列要记住位置。
	 */
	protected void onRowClicked(int index, NeteaseApi.Song song) {
		HandheldMusic.LOGGER.info("[HandheldMusic] 列表点选：{}（{}/{}）",
				song.display(), index + 1, results.size());
		this.message = "正在获取「" + song.name() + "」…";
		PlaybackEngine.playFromList(results, index,
				this.minecraft == null ? null : this.minecraft.player);
	}

	// 调试用
	public int debugRowWidgetCount() {
		return rowWidgets.size();
	}

	/** 走控件自己的 mouseClicked（含命中测试），等价于真实点击路径。 */
	public boolean debugClickRow(int slot) {
		if (slot < 0 || slot >= rowWidgets.size()) {
			return false;
		}
		RowWidget w = rowWidgets.get(slot);
		// 注意：26.3 的 MouseButtonInfo.button() 是 1 基（1 = 左键），
		// AbstractWidget.isValidClickButton 判的是 button == 1，传 0 会被直接拒掉。
		net.minecraft.client.input.MouseButtonEvent ev =
				new net.minecraft.client.input.MouseButtonEvent(w.getX() + 5, w.getY() + 5,
						new net.minecraft.client.input.MouseButtonInfo(1, 0));
		return w.mouseClicked(ev, false);
	}

	protected int rowAt(double mx, double my) {
		int listX = x0 + 8;
		int listY = listTop();
		int listW = W - 16;
		int listH = VISIBLE_ROWS * ROW_H;
		if (mx < listX || mx > listX + listW || my < listY || my > listY + listH) {
			return -1;
		}
		int index = scroll + (int) ((my - listY) / ROW_H);
		return index < results.size() ? index : -1;
	}

	// ---------------- 绘制 ----------------

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		super.extractBackground(g, mouseX, mouseY, partialTick);

		g.fill(x0 - 2, y0 - 2, x0 + W + 2, y0 + H + 2, COLOR_BORDER_DARK);
		g.fill(x0, y0, x0 + W, y0 + H, COLOR_PANEL);
		// outline 的后两个参数是「宽、高」，不是右下角坐标
		g.outline(x0, y0, W, H, COLOR_BORDER_LIGHT);

		g.text(this.font, titleText(), x0 + 8, y0 + 6, COLOR_TEXT);
		drawResults(g, mouseX, mouseY);
		// 界面开着的时候 HUD 是看不见的，播放状态/错误必须显示在界面里
		String line = message;
		if (PlaybackEngine.state() == PlaybackEngine.State.ERROR) {
			line = PlaybackEngine.status();
		} else if (PlaybackEngine.state() == PlaybackEngine.State.RESOLVING) {
			line = "正在获取…";
		}
		g.text(this.font, Component.literal(line), x0 + 8, y0 + 122, COLOR_DIM);
	}

	private void drawResults(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		int listX = x0 + 8;
		int listY = listTop();
		int listW = W - 16;
		int listH = VISIBLE_ROWS * ROW_H;

		g.fill(listX, listY, listX + listW, listY + listH, COLOR_SLOT);

		if (results.isEmpty()) {
			String hint = loading ? "加载中…" : "—";
			g.text(this.font, Component.literal(hint), listX + 6, listY + listH / 2 - 4, COLOR_DIM);
			return;
		}

		int hoverRow = rowAt(mouseX, mouseY);
		for (int i = 0; i < VISIBLE_ROWS; i++) {
			int index = scroll + i;
			if (index >= results.size()) {
				break;
			}
			NeteaseApi.Song song = results.get(index);
			int ry = listY + i * ROW_H;
			if (index == hoverRow) {
				g.fill(listX + 1, ry + 1, listX + listW - 1, ry + ROW_H - 1, COLOR_ROW_HOVER);
			}
			// 歌手先定宽，剩下的才给歌名——固定两段宽度会在长歌名 + 长歌手时撞在一起
			String artist = trim(song.artists(), 58);
			int artistW = this.font.width(artist);
			int titleMax = listW - 10 - artistW - 6;
			g.text(this.font, Component.literal(trim(song.name(), Math.max(20, titleMax))),
					listX + 5, ry + 4, COLOR_TEXT);
			g.text(this.font, Component.literal(artist),
					listX + listW - 5 - artistW, ry + 4, COLOR_DIM);
		}

		if (results.size() > VISIBLE_ROWS) {
			int barH = Math.max(6, listH * VISIBLE_ROWS / results.size());
			int max = results.size() - VISIBLE_ROWS;
			int barY = listY + (listH - barH) * scroll / Math.max(1, max);
			g.fill(listX + listW - 2, barY, listX + listW, barY + barH, COLOR_ACCENT);
		}
	}

	protected String trim(String s, int maxWidth) {
		if (s == null) {
			return "";
		}
		if (this.font.width(s) <= maxWidth) {
			return s;
		}
		StringBuilder sb = new StringBuilder();
		for (char c : s.toCharArray()) {
			if (this.font.width(sb.toString() + c) > maxWidth - 6) {
				break;
			}
			sb.append(c);
		}
		return sb + "…";
	}
}
