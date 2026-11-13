package com.handheldmusic.client.audio;

import com.handheldmusic.client.netease.NeteaseApi;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 播放队列。
 *
 * 设计成「隐式队列」：在任意列表里点某一首，整个列表就变成队列、游标定位到点击位置。
 * 用户不需要「加入队列」这个动作，点歌即入队——这样「下一首」永远有东西可播。
 *
 * 三种模式：
 *   LIST_LOOP   列表循环（默认），最后一首播完回第一首
 *   REPEAT_ONE  单曲循环
 *   SHUFFLE     随机，走洗牌索引，一轮内不重复
 *
 * 随机模式用 {@code order} 存「播放顺序 → songs 下标」的映射，游标指向 order 的位置，
 * 这样上一首/下一首在随机模式下也是可逆的（真随机选曲做不到这点）。
 */
public final class PlaybackQueue {
	public enum Mode {
		LIST_LOOP("循环"),
		REPEAT_ONE("单曲"),
		SHUFFLE("随机");

		public final String label;

		Mode(String label) {
			this.label = label;
		}

		/** 按 循环 → 单曲 → 随机 → 循环 轮转。 */
		public Mode cycle() {
			Mode[] all = values();
			return all[(ordinal() + 1) % all.length];
		}
	}

	private static final Random RANDOM = new Random();

	private final List<NeteaseApi.Song> songs = new ArrayList<>();
	/** 随机模式的播放顺序，元素是 songs 的下标；非随机模式为空数组。 */
	private int[] order = new int[0];
	/** 随机模式：order 里的位置；其他模式：songs 里的下标。 */
	private int cursor;
	private Mode mode = Mode.LIST_LOOP;

	/** 整体替换队列，并把游标放到 index。 */
	public void set(List<NeteaseApi.Song> list, int index) {
		songs.clear();
		if (list != null) {
			songs.addAll(list);
		}
		if (songs.isEmpty()) {
			order = new int[0];
			cursor = 0;
			return;
		}
		int safe = Math.max(0, Math.min(index, songs.size() - 1));
		rebuildOrder(safe);
	}

	public void clear() {
		songs.clear();
		order = new int[0];
		cursor = 0;
	}

	public boolean isEmpty() {
		return songs.isEmpty();
	}

	public int size() {
		return songs.size();
	}

	/** 1 基位置，给界面显示 3/30 用。空队列返回 0。 */
	public int position() {
		return songs.isEmpty() ? 0 : cursor + 1;
	}

	public Mode mode() {
		return mode;
	}

	/** 切换模式，保持当前曲不变（重建随机顺序时把当前曲放到第一位）。 */
	public void setMode(Mode m) {
		if (m == mode) {
			return;
		}
		int keep = currentIndex();
		mode = m;
		rebuildOrder(Math.max(0, keep));
	}

	public Mode cycleMode() {
		setMode(mode.cycle());
		return mode;
	}

	/** 当前曲目在 songs 里的下标；空队列返回 -1。 */
	public int currentIndex() {
		if (songs.isEmpty()) {
			return -1;
		}
		return mode == Mode.SHUFFLE ? order[cursor] : cursor;
	}

	public NeteaseApi.Song current() {
		int i = currentIndex();
		return i < 0 ? null : songs.get(i);
	}

	/**
	 * 推进游标并返回新曲。
	 * 单曲循环不推进游标，直接返回同一首（调用方会重新解析直链再播）。
	 */
	public NeteaseApi.Song next() {
		if (songs.isEmpty()) {
			return null;
		}
		if (mode == Mode.REPEAT_ONE) {
			return current();
		}
		cursor = (cursor + 1) % songs.size();
		if (mode == Mode.SHUFFLE && cursor == 0) {
			// 一轮播完，重新洗牌，避免每轮顺序都一样
			reshuffle();
		}
		return current();
	}

	public NeteaseApi.Song prev() {
		if (songs.isEmpty()) {
			return null;
		}
		if (mode == Mode.REPEAT_ONE) {
			return current();
		}
		cursor = (cursor - 1 + songs.size()) % songs.size();
		return current();
	}

	// ---------------- 内部 ----------------

	private void rebuildOrder(int currentIndex) {
		int n = songs.size();
		if (n == 0) {
			order = new int[0];
			cursor = 0;
			return;
		}
		if (mode != Mode.SHUFFLE) {
			order = new int[0];
			cursor = currentIndex;
			return;
		}
		// 当前曲放第一位，其余打乱
		order = new int[n];
		order[0] = currentIndex;
		int w = 1;
		for (int i = 0; i < n; i++) {
			if (i != currentIndex) {
				order[w++] = i;
			}
		}
		for (int i = 1; i < n; i++) {
			int j = 1 + RANDOM.nextInt(n - 1);
			int t = order[i];
			order[i] = order[j];
			order[j] = t;
		}
		cursor = 0;
	}

	private void reshuffle() {
		int n = songs.size();
		order = new int[n];
		for (int i = 0; i < n; i++) {
			order[i] = i;
		}
		for (int i = n - 1; i > 0; i--) {
			int j = RANDOM.nextInt(i + 1);
			int t = order[i];
			order[i] = order[j];
			order[j] = t;
		}
		cursor = 0;
	}
}
