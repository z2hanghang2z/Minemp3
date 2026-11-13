package com.handheldmusic.client.gui;

import com.google.zxing.common.BitMatrix;
import com.handheldmusic.client.netease.NeteaseQrLogin;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 游戏内的扫码登录界面。
 *
 * 26.3 的渲染接口换成了 extractRenderState + GuiGraphicsExtractor，
 * 这里直接把二维码的 BitMatrix 按行合并成色块画出来，不用贴图。
 *
 * 布局自适应：GUI 缩放为 2 时逻辑高度只有 240，固定高度的面板会被裁掉，
 * 所以二维码尺寸按可用空间算，并让 NeteaseQrLogin 按这个尺寸编码（1:1 绘制不糊）。
 */
public class QrLoginScreen extends Screen {
	private static final int MAX_QR = 200;
	private static final int MIN_QR = 96;
	/**
	 * 二维码之外占的高度：
	 * 标题 8 + 间距 20 + 状态 16 + 提示 16 + 按钮间距 30 + 按钮 20 + 底部留白 8 ≈ 110。
	 */
	private static final int VERTICAL_CHROME = 110;

	private static final int COLOR_PANEL = 0xFF1E1E1E;
	private static final int COLOR_BORDER = 0xFF4A4A4A;
	private static final int COLOR_TITLE = 0xFFFFFFFF;
	private static final int COLOR_TEXT = 0xFFFFD060;
	private static final int COLOR_HINT = 0xFF909090;

	private int qrSize = MAX_QR;
	private Button refreshButton;
	private Button copyButton;

	public QrLoginScreen() {
		super(Component.literal("网易云登录"));
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	protected void init() {
		int avail = Math.min(this.width - 80, this.height - 40 - VERTICAL_CHROME);
		this.qrSize = Math.max(MIN_QR, Math.min(MAX_QR, avail));
		NeteaseQrLogin.start(this.qrSize);

		int panelW = this.qrSize + 60;
		int cx = this.width / 2;
		int y0 = this.height / 2 - (this.qrSize + VERTICAL_CHROME) / 2;
		int btnY = y0 + 28 + this.qrSize + 8 + 46;
		int btnW = (panelW - 30) / 2;

		this.refreshButton = Button.builder(Component.literal("刷新二维码"), b -> {
			NeteaseQrLogin.reset();
			NeteaseQrLogin.start(this.qrSize);
		}).bounds(cx - btnW - 4, btnY, btnW, 20).build();

		this.copyButton = Button.builder(Component.literal("复制登录链接"), b -> {
			String url = NeteaseQrLogin.qrUrl();
			if (url != null && this.minecraft != null) {
				this.minecraft.keyboardHandler.setClipboard(url);
			}
		}).bounds(cx + 4, btnY, btnW, 20).build();

		addRenderableWidget(this.refreshButton);
		addRenderableWidget(this.copyButton);
	}

	/**
	 * 面板、二维码、文字都画在「背景阶段」。
	 *
	 * 26.3 的渲染顺序是 extractBackground → nextStratum → extractRenderState（控件），
	 * 如果把面板画在 extractRenderState 里且排在 super 之后，会把按钮整个盖住。
	 */
	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		super.extractBackground(g, mouseX, mouseY, partialTick);

		int panelW = this.qrSize + 60;
		int panelH = this.qrSize + VERTICAL_CHROME;
		int cx = this.width / 2;
		int x0 = cx - panelW / 2;
		int y0 = this.height / 2 - panelH / 2;

		g.fill(x0, y0, x0 + panelW, y0 + panelH, COLOR_PANEL);
		// outline 的后两个参数是「宽、高」，不是右下角坐标
		g.outline(x0, y0, panelW, panelH, COLOR_BORDER);
		g.centeredText(this.font, Component.literal("网易云登录"), cx, y0 + 8, COLOR_TITLE);

		int qy = y0 + 28;
		int qx = cx - this.qrSize / 2;

		BitMatrix matrix = NeteaseQrLogin.matrix();
		if (matrix != null) {
			g.fill(qx - 5, qy - 5, qx + this.qrSize + 5, qy + this.qrSize + 5, 0xFFFFFFFF);
			drawMatrix(g, matrix, qx, qy);
		} else {
			g.fill(qx, qy, qx + this.qrSize, qy + this.qrSize, 0xFF2A2A2A);
			g.centeredText(this.font, Component.literal("生成中…"),
					cx, qy + this.qrSize / 2 - 4, COLOR_HINT);
		}

		int ty = qy + this.qrSize + 8;
		g.centeredText(this.font, Component.literal(NeteaseQrLogin.message()), cx, ty, COLOR_TEXT);
		g.centeredText(this.font, Component.literal("扫码失败时可按「复制登录链接」到浏览器打开"),
				cx, ty + 16, COLOR_HINT);
	}

	/** 按行把连续黑块合并成一个 fill，避免逐像素调用。 */
	private void drawMatrix(GuiGraphicsExtractor g, BitMatrix m, int qx, int qy) {
		int w = m.getWidth();
		int h = m.getHeight();
		double scale = (double) this.qrSize / w;
		for (int row = 0; row < h; row++) {
			int py0 = qy + (int) Math.floor(row * scale);
			int py1 = qy + (int) Math.floor((row + 1) * scale);
			int col = 0;
			while (col < w) {
				if (!m.get(col, row)) {
					col++;
					continue;
				}
				int start = col;
				while (col < w && m.get(col, row)) {
					col++;
				}
				int px0 = qx + (int) Math.floor(start * scale);
				int px1 = qx + (int) Math.floor(col * scale);
				g.fill(px0, py0, px1, py1, 0xFF000000);
			}
		}
	}
}
