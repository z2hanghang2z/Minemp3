package com.handheldmusic.client.netease;

import java.util.List;

/**
 * 列表数据源。需要登录的两个列表放这里，界面只负责显示。
 *
 * 只做「我喜欢的音乐」和「每日推荐」——其他歌单明确不做。
 */
public final class NeteaseLists {
	private NeteaseLists() {
	}

	/** 我喜欢的音乐（红心）。 */
	public static List<NeteaseApi.Song> liked() throws Exception {
		requireLogin();
		return NeteaseApi.songDetails(NeteaseApi.likedIds(NeteaseSession.userId()));
	}

	/** 每日推荐。 */
	public static List<NeteaseApi.Song> daily() throws Exception {
		requireLogin();
		return NeteaseApi.dailyRecommend();
	}

	private static void requireLogin() {
		if (!NeteaseSession.isLoggedIn()) {
			throw new IllegalStateException("需要登录：/hmqr 或 /hmpaste");
		}
	}
}
