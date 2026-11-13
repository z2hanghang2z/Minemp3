package com.handheldmusic.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;

import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * 手感反馈（P2.5）：按钮下沉 1px + 咔哒音效。
 *
 * 为什么不自己画按钮：原版 Button 的贴图本身就是像素风，重画反而会跑偏。
 * 这里只做「按下瞬间把控件整体下移 1px，几 tick 后还原」——物理感来自位移，
 * 不需要自定义渲染，也不会和原版主题冲突。
 *
 * 音效用 UI_BUTTON_CLICK（Holder 类型，走 forUI 重载）。
 */
public final class UiFeedback {
	/** 下沉持续 tick 数，3 tick ≈ 150ms，够短促又不至于看不见。 */
	private static final int PRESS_TICKS = 3;

	private static final Map<AbstractWidget, Integer> PENDING = new IdentityHashMap<>();

	private UiFeedback() {
	}

	/** 带下沉反馈 + 咔哒音效的按钮工厂。 */
	public static Button button(Component label, Button.OnPress action, int x, int y, int w, int h) {
		return Button.builder(label, b -> {
			press(b);
			action.onPress(b);
		}).bounds(x, y, w, h).build();
	}

	public static void press(AbstractWidget widget) {
		if (widget == null || PENDING.containsKey(widget)) {
			return;
		}
		widget.setY(widget.getY() + 1);
		PENDING.put(widget, PRESS_TICKS);
		click();
	}

	public static void click() {
		Minecraft.getInstance().getSoundManager()
				.play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0f));
	}

	/** 每 tick 推进一次，到期的控件还原位置。 */
	public static void tick() {
		if (PENDING.isEmpty()) {
			return;
		}
		Iterator<Map.Entry<AbstractWidget, Integer>> it = PENDING.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<AbstractWidget, Integer> e = it.next();
			int left = e.getValue() - 1;
			if (left <= 0) {
				e.getKey().setY(e.getKey().getY() - 1);
				it.remove();
			} else {
				e.setValue(left);
			}
		}
	}

	/** 界面关闭时调用，把还压着的按钮还原，避免下次复用坐标错位。 */
	public static void reset() {
		for (AbstractWidget w : PENDING.keySet()) {
			w.setY(w.getY() - 1);
		}
		PENDING.clear();
	}
}
