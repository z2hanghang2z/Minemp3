package com.handheldmusic.client.netease;

import com.handheldmusic.HandheldMusic;
import net.minecraft.client.Minecraft;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 把「关键词 → 歌曲 → 直链」跑在后台线程上，完成后回渲染线程交给播放层。
 *
 * 不落盘：直链直接喂给 {@code StreamingHttpAudioStream} 边下边播。
 * 直链有效期约 20 分钟（返回里的 expi 字段），所以不缓存 URL。
 */
public final class NeteasePlayer {
	/** 解析结果：歌曲元信息 + 直链信息，播放层据此算时长和进度。 */
	public record Resolved(NeteaseApi.Song song, NeteaseApi.Track track) {
		public String display() {
			return song.display() + (track.trial() ? "（试听）" : "");
		}

		public long durationMs() {
			return song.durationMs();
		}
	}

	public interface Callback {
		void onReady(Resolved resolved);

		void onError(String message);
	}

	private NeteasePlayer() {
	}

	public static void resolve(String keyword, Callback cb) {
		CompletableFuture.runAsync(() -> {
			try {
				List<NeteaseApi.Song> songs = NeteaseApi.search(keyword, 8);
				if (songs.isEmpty()) {
					fail(cb, "搜不到「" + keyword + "」");
					return;
				}

				// 热门歌经常是 VIP 无直链，逐首试到能拿到 url 为止。
				// 登录了就要高码率，没登录只有 standard 可用。
				String level = NeteaseSession.isLoggedIn() ? "exhigh" : "standard";
				NeteaseApi.Song song = null;
				NeteaseApi.Track track = null;
				for (NeteaseApi.Song candidate : songs) {
					NeteaseApi.Track t = NeteaseApi.track(candidate.id(), level);
					if (t != null) {
						song = candidate;
						track = t;
						break;
					}
					HandheldMusic.LOGGER.info("[HandheldMusic] 跳过无直链：{}（id={}）",
							candidate.display(), candidate.id());
				}
				if (song == null || track == null) {
					fail(cb, "前 " + songs.size() + " 首都拿不到直链（多为 VIP / 无版权）"
							+ (NeteaseSession.isLoggedIn() ? "" : "，试试登录后重试"));
					return;
				}

				HandheldMusic.LOGGER.info("[HandheldMusic] 命中：{}（id={}）{} {}kbps{}",
						song.display(), song.id(), track.type(), track.br() / 1000,
						track.trial() ? " [试听片段]" : "");

				Resolved resolved = new Resolved(song, track);
				Minecraft.getInstance().execute(() -> cb.onReady(resolved));
			} catch (Exception e) {
				HandheldMusic.LOGGER.error("[HandheldMusic] 取歌失败", e);
				fail(cb, "出错了：" + e.getClass().getSimpleName());
			}
		});
	}

	/** 已经有 Song 了（比如从搜索结果里点），跳过搜索直接取直链。 */
	public static void resolveSong(NeteaseApi.Song song, Callback cb) {
		CompletableFuture.runAsync(() -> {
			try {
				String level = NeteaseSession.isLoggedIn() ? "exhigh" : "standard";
				NeteaseApi.Track track = NeteaseApi.track(song.id(), level);
				if (track == null) {
					fail(cb, "「" + song.name() + "」拿不到直链（VIP / 无版权）");
					return;
				}
				HandheldMusic.LOGGER.info("[HandheldMusic] 选中：{}（id={}）{} {}kbps{}",
						song.display(), song.id(), track.type(), track.br() / 1000,
						track.trial() ? " [试听片段]" : "");
				Resolved resolved = new Resolved(song, track);
				Minecraft.getInstance().execute(() -> cb.onReady(resolved));
			} catch (Exception e) {
				HandheldMusic.LOGGER.error("[HandheldMusic] 取歌失败", e);
				fail(cb, "出错了：" + e.getClass().getSimpleName());
			}
		});
	}

	private static void fail(Callback cb, String message) {
		Minecraft.getInstance().execute(() -> cb.onError(message));
	}
}
