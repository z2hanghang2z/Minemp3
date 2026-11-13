package com.handheldmusic.client.gui;

import com.handheldmusic.client.audio.EnvironmentAudio;
import com.handheldmusic.client.netease.NeteaseApi;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import com.handheldmusic.client.audio.PlaybackEngine;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 口袋机主界面（UI 方案 A）。
 *
 * 176×166 标准容器，纵向布局：
 *   封面 48×48 + 歌名 + 歌手 / 进度条 + 双时间 / 控制键 / 音量条
 *
 * 视觉全部用 1px 明暗边做立体感，不用渐变、圆角、阴影——和原版像素风一致。
 * 注意 26.3 的渲染顺序是 extractBackground → extractRenderState(控件)，
 * 面板必须画在 extractBackground，否则会把按钮盖住。
 */
public class PlayerScreen extends Screen {
	private static final int W = 176;
	private static final int H = 166;

	private static final int COLOR_PANEL = 0xFF2B2B2B;
	private static final int COLOR_BORDER_DARK = 0xFF000000;
	private static final int COLOR_BORDER_LIGHT = 0xFF5A5A5A;
	private static final int COLOR_SLOT = 0xFF161616;
	private static final int COLOR_TEXT = 0xFFE6E6E6;
	private static final int COLOR_DIM = 0xFF8A8A8A;
	private static final int COLOR_ACCENT = 0xFF7EC850;
	private static final int COLOR_ACCENT_DIM = 0xFF3E6B28;

	private int x0;
	private int y0;
	private Button playButton;
	private Button modeButton;
	/** 2 帧动画计时 */
	private int animTick;

	public PlayerScreen() {
		super(Component.literal("手持播放器"));
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	protected void init() {
		this.x0 = (this.width - W) / 2;
		this.y0 = (this.height - H) / 2;

		// 三行 × 四列，每列 38 宽、间距 2，右边留 10——比之前的混排宽度更整齐
		int c1 = x0 + 8;
		int c2 = x0 + 48;
		int c3 = x0 + 88;
		int c4 = x0 + 128;
		int bw = 38;
		int bh = 18;
		int r1 = y0 + 82;
		int r2 = y0 + 104;
		int r3 = y0 + 126;

		this.playButton = UiFeedback.button(label(), b -> onPlayPressed(), c1, r1, bw, bh);
		addRenderableWidget(this.playButton);
		addRenderableWidget(UiFeedback.button(Component.literal("停止"),
				b -> PlaybackEngine.stop(), c2, r1, bw, bh));
		addRenderableWidget(UiFeedback.button(Component.literal("搜索"),
				b -> net.minecraft.client.Minecraft.getInstance()
						.setScreenAndShow(new SearchScreen()), c3, r1, bw, bh));
		addRenderableWidget(UiFeedback.button(Component.literal("歌单"),
				b -> net.minecraft.client.Minecraft.getInstance()
						.setScreenAndShow(new RemoteListScreen(
								Component.literal("我喜欢的音乐"),
								com.handheldmusic.client.netease.NeteaseLists::liked)),
				c4, r1, bw, bh));

		addRenderableWidget(UiFeedback.button(Component.literal("上一首"),
				b -> PlaybackEngine.playPrev(this.minecraft == null ? null : this.minecraft.player),
				c1, r2, bw, bh));
		addRenderableWidget(UiFeedback.button(Component.literal("下一首"),
				b -> PlaybackEngine.playNext(this.minecraft == null ? null : this.minecraft.player),
				c2, r2, bw, bh));
		addRenderableWidget(UiFeedback.button(Component.literal("每日"),
				b -> net.minecraft.client.Minecraft.getInstance()
						.setScreenAndShow(new RemoteListScreen(
								Component.literal("每日推荐"),
								com.handheldmusic.client.netease.NeteaseLists::daily)),
				c3, r2, bw, bh));
		addRenderableWidget(UiFeedback.button(Component.literal("低通"),
				b -> PlaybackEngine.toggleLowpass(), c4, r2, bw, bh));

		addRenderableWidget(UiFeedback.button(Component.literal("音量-"),
				b -> PlaybackEngine.addVolume(-0.1f), c1, r3, bw, bh));
		addRenderableWidget(UiFeedback.button(Component.literal("音量+"),
				b -> PlaybackEngine.addVolume(0.1f), c2, r3, bw, bh));
		this.modeButton = UiFeedback.button(modeLabel(),
				b -> PlaybackEngine.cycleMode(this.minecraft == null ? null : this.minecraft.player),
				c3, r3, bw, bh);
		addRenderableWidget(this.modeButton);
		addRenderableWidget(UiFeedback.button(Component.literal("关闭"),
				b -> this.onClose(), c4, r3, bw, bh));
	}

	private Component modeLabel() {
		return Component.literal(PlaybackEngine.queue().mode().label);
	}

	@Override
	public void removed() {
		super.removed();
		UiFeedback.reset();
	}

	/**
	 * 空闲时「播放」要能用：队列里还有歌就从当前位置继续，没有就跳去搜索页。
	 * （早期版本这里只调 togglePause，空闲状态下按钮是死的。）
	 */
	private void onPlayPressed() {
		switch (PlaybackEngine.state()) {
			case PLAYING, PAUSED -> PlaybackEngine.togglePause();
			case RESOLVING -> {
			}
			default -> {
				if (PlaybackEngine.hasQueue()) {
					PlaybackEngine.replayCurrent(this.minecraft == null ? null : this.minecraft.player);
				} else {
					net.minecraft.client.Minecraft.getInstance()
							.setScreenAndShow(new SearchScreen());
				}
			}
		}
	}

	private long requestedCoverFor = Long.MIN_VALUE;

	/** 换歌时拉一次封面；同一首不重复请求。 */
	private void requestCoverIfNeeded() {
		NeteaseApi.Song song = PlaybackEngine.currentSong();
		if (song == null || song.id() == requestedCoverFor) {
			return;
		}
		requestedCoverFor = song.id();
		CoverCache.request(song);
	}

	private Component label() {
		return Component.literal(switch (PlaybackEngine.state()) {
			case PLAYING -> "暂停";
			case PAUSED -> "继续";
			case RESOLVING -> "…";
			default -> "播放";
		});
	}

	@Override
	public void tick() {
		super.tick();
		UiFeedback.tick();
		this.animTick++;
		requestCoverIfNeeded();
		if (this.playButton != null) {
			this.playButton.setMessage(label());
		}
		if (this.modeButton != null) {
			this.modeButton.setMessage(modeLabel());
		}
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (scrollY != 0.0) {
			PlaybackEngine.addVolume((float) (scrollY > 0 ? 0.05 : -0.05));
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		super.extractBackground(g, mouseX, mouseY, partialTick);

		// 外壳：外深内亮两道 1px 边
		g.fill(x0 - 2, y0 - 2, x0 + W + 2, y0 + H + 2, COLOR_BORDER_DARK);
		g.fill(x0, y0, x0 + W, y0 + H, COLOR_PANEL);
		// outline 的后两个参数是「宽、高」，不是右下角坐标
		g.outline(x0, y0, W, H, COLOR_BORDER_LIGHT);

		drawCover(g);
		drawTrackInfo(g);
		drawProgress(g);
		drawVolume(g);
	}

	/** 封面位：有纹理就贴，没有就画占位纹路。 */
	private void drawCover(GuiGraphicsExtractor g) {
		int cx = x0 + 8;
		int cy = y0 + 8;
		g.fill(cx - 1, cy - 1, cx + 49, cy + 49, COLOR_BORDER_DARK);

		NeteaseApi.Song song = PlaybackEngine.currentSong();
		Identifier texture = song == null ? null : CoverCache.get(song.id());
		if (texture != null) {
			// 12 参版本：目标 48×48，源区域取整张 32×32，纹理尺寸 32×32。
			// 用 10 参版本会把 uWidth 默认成目标宽高（48），导致 UV 越界平铺成 2×2。
			g.blit(RenderPipelines.GUI_TEXTURED, texture, cx, cy, 0f, 0f,
					48, 48, 32, 32, 32, 32);
			return;
		}

		g.fill(cx, cy, cx + 48, cy + 48, 0xFF3A3A3A);
		for (int i = 0; i < 3; i++) {
			int ly = cy + 14 + i * 8;
			g.fill(cx + 8, ly, cx + 40, ly + 1, 0xFF4A4A4A);
		}
		g.fill(cx + 20, cy + 20, cx + 28, cy + 28, 0xFF2E2E2E);
	}

	private void drawTrackInfo(GuiGraphicsExtractor g) {
		int tx = x0 + 64;
		int maxW = W - 64 - 10;

		String title = PlaybackEngine.title();
		String artists = PlaybackEngine.artists();

		g.text(this.font, Component.literal(trim(title, maxW)), tx, y0 + 10, COLOR_TEXT);
		g.text(this.font, Component.literal(trim(artists, maxW)), tx, y0 + 24, COLOR_DIM);

		String status = switch (PlaybackEngine.state()) {
			case PLAYING -> "播放中";
			case PAUSED -> "已暂停";
			case RESOLVING -> "缓冲中";
			case ERROR -> "出错";
			default -> "空闲";
		};
		g.text(this.font, Component.literal(status), tx, y0 + 38,
				PlaybackEngine.state() == PlaybackEngine.State.PLAYING ? COLOR_ACCENT : COLOR_DIM);

		drawBars(g, tx + this.font.width(status) + 6, y0 + 47);

		// 右上角显示当前环境；手动低通开着时补一个 +LP
		String env = PlaybackEngine.environment().label
				+ (PlaybackEngine.isLowpassOn() ? "+LP" : "");
		g.text(this.font, Component.literal(env),
				tx + maxW - this.font.width(env), y0 + 38,
				PlaybackEngine.environment() == EnvironmentAudio.Env.OUTDOOR ? COLOR_DIM : COLOR_ACCENT);
	}

	/**
	 * 2 帧律动条：播放时在两帧之间切换（每 8 tick ≈ 0.4 秒），
	 * 暂停时压成一条静止短线。这是整个界面唯一的动画，刻意做得很轻。
	 */
	private void drawBars(GuiGraphicsExtractor g, int bx, int baselineY) {
		boolean playing = PlaybackEngine.state() == PlaybackEngine.State.PLAYING;
		int frame = playing ? (this.animTick / 8) % 2 : -1;

		int[][] frames = {{3, 6, 4}, {5, 3, 6}};
		int[] heights = frame >= 0 ? frames[frame] : new int[]{2, 2, 2};
		int color = playing ? COLOR_ACCENT : COLOR_DIM;

		for (int i = 0; i < heights.length; i++) {
			int bx0 = bx + i * 4;
			int h = heights[i];
			g.fill(bx0, baselineY - h, bx0 + 2, baselineY, color);
		}
	}

	private void drawProgress(GuiGraphicsExtractor g) {
		int px = x0 + 8;
		int py = y0 + 60;
		int pw = W - 16;

		long dur = PlaybackEngine.durationMs();
		long pos = PlaybackEngine.progressMs();
		double ratio = dur > 0 ? Math.min(1.0, (double) pos / dur) : 0.0;

		g.fill(px, py, px + pw, py + 5, COLOR_BORDER_DARK);
		g.fill(px + 1, py + 1, px + pw - 1, py + 4, COLOR_SLOT);
		int filled = (int) Math.round((pw - 2) * ratio);
		if (filled > 0) {
			g.fill(px + 1, py + 1, px + 1 + filled, py + 4, COLOR_ACCENT_DIM);
			g.fill(px + 1, py + 1, px + 1 + filled, py + 2, COLOR_ACCENT);
		}

		g.text(this.font, Component.literal(fmt(pos)), px, py + 9, COLOR_DIM);
		String total = fmt(dur);
		g.text(this.font, Component.literal(total), px + pw - this.font.width(total), py + 9, COLOR_DIM);

		// 队列位置放时间行中间，左右两个时间各占一边不会撞
		String qp = PlaybackEngine.queuePosition();
		if (!qp.isEmpty()) {
			int qw = this.font.width(qp);
			g.text(this.font, Component.literal(qp), px + pw / 2 - qw / 2, py + 9, COLOR_DIM);
		}
	}

	private void drawVolume(GuiGraphicsExtractor g) {
		int labelX = x0 + 8;
		int vy = y0 + 149;
		int vx = x0 + 40;
		int vw = W - 48;

		g.text(this.font, Component.literal("音量"), labelX, y0 + 147, COLOR_DIM);
		g.fill(vx, vy, vx + vw, vy + 5, COLOR_BORDER_DARK);
		g.fill(vx + 1, vy + 1, vx + vw - 1, vy + 4, COLOR_SLOT);
		int filled = (int) Math.round((vw - 2) * PlaybackEngine.volume());
		if (filled > 0) {
			g.fill(vx + 1, vy + 1, vx + 1 + filled, vy + 4, 0xFFB0B0B0);
		}
	}

	private String trim(String s, int maxWidth) {
		if (s == null) {
			return "";
		}
		if (this.font.width(s) <= maxWidth) {
			return s;
		}
		String ellipsis = "…";
		int limit = maxWidth - this.font.width(ellipsis);
		StringBuilder sb = new StringBuilder();
		for (char c : s.toCharArray()) {
			if (this.font.width(sb.toString() + c) > limit) {
				break;
			}
			sb.append(c);
		}
		return sb + ellipsis;
	}

	private static String fmt(long ms) {
		long sec = Math.max(0, ms) / 1000;
		return String.format("%d:%02d", sec / 60, sec % 60);
	}
}
