package com.handheldmusic.client.netease;

import com.handheldmusic.HandheldMusic;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 登录态管理：走「粘贴 cookie」方案，绕开二维码登录的 8821 风控。
 *
 * 用法（任选其一）：
 * 1. 游戏内命令 {@code /hmcookie <值>}，值可以是 MUSIC_U 的裸值，也可以是整串 cookie
 * 2. 手动编辑 {@code run/handheldmusic/cookie.txt}，启动时自动加载
 *
 * 实际上只有 MUSIC_U 决定身份，其余 cookie 是补充。
 */
public final class NeteaseSession {
	private static final Path COOKIE_FILE =
			FabricLoader.getInstance().getGameDir().resolve("handheldmusic").resolve("cookie.txt");

	private static String nickname;
	private static long userId;

	private NeteaseSession() {
	}

	public static boolean isLoggedIn() {
		return nickname != null;
	}

	public static String nickname() {
		return nickname;
	}

	public static long userId() {
		return userId;
	}

	public static String statusLine() {
		return isLoggedIn() ? "已登录：" + nickname + "（uid " + userId + "）" : "未登录（匿名模式）";
	}

	/** 启动时从磁盘加载。 */
	public static void loadFromDisk() {
		try {
			if (!Files.isRegularFile(COOKIE_FILE)) {
				HandheldMusic.LOGGER.info("[HandheldMusic] 未找到 cookie.txt，走匿名模式");
				return;
			}
			String raw = Files.readString(COOKIE_FILE, StandardCharsets.UTF_8).trim();
			if (raw.isEmpty()) {
				return;
			}
			NeteaseApi.setCookies(parse(raw));
			refresh();
		} catch (Exception e) {
			HandheldMusic.LOGGER.warn("[HandheldMusic] 加载 cookie 失败", e);
		}
	}

	/** 粘贴 cookie 的结果。 */
	public enum ApplyResult {
		/** 成功 */
		OK,
		/** 缺 MUSIC_U——多半是用 document.cookie 复制的，那个读不到 HttpOnly */
		NO_MUSIC_U,
		/** 有 MUSIC_U 但服务端不认 */
		INVALID
	}

	/**
	 * 应用一串 cookie 并落盘、校验。
	 *
	 * @param raw 可以是 {@code MUSIC_U=xxx}、{@code k=v; k2=v2}，或直接是 MUSIC_U 的值
	 */
	public static ApplyResult apply(String raw) {
		try {
			Map<String, String> cookies = parse(raw);
			if (cookies.isEmpty()) {
				return ApplyResult.NO_MUSIC_U;
			}
			if (!cookies.containsKey("MUSIC_U")) {
				// MUSIC_U 是 HttpOnly，document.cookie 拿不到，
				// 用户多半复制到了一堆统计 cookie。
				return ApplyResult.NO_MUSIC_U;
			}
			NeteaseApi.setCookies(cookies);

			if (!refresh()) {
				NeteaseApi.setCookies(Map.of());
				return ApplyResult.INVALID;
			}

			Files.createDirectories(COOKIE_FILE.getParent());
			Files.writeString(COOKIE_FILE, raw.trim(), StandardCharsets.UTF_8);
			HandheldMusic.LOGGER.info("[HandheldMusic] 登录成功：{}（uid {}）", nickname, userId);
			return ApplyResult.OK;
		} catch (Exception e) {
			HandheldMusic.LOGGER.warn("[HandheldMusic] 应用 cookie 失败", e);
			return ApplyResult.INVALID;
		}
	}

	/**
	 * 二维码登录成功后调用：当前 COOKIES 里已经有 MUSIC_U 了，
	 * 校验一次再落盘。
	 */
	public static boolean commitCurrent() {
		try {
			if (!refresh()) {
				return false;
			}
			String raw = NeteaseApi.cookieHeader();
			if (raw.isEmpty()) {
				return false;
			}
			Files.createDirectories(COOKIE_FILE.getParent());
			Files.writeString(COOKIE_FILE, raw, StandardCharsets.UTF_8);
			HandheldMusic.LOGGER.info("[HandheldMusic] 登录 cookie 已保存：{}", COOKIE_FILE);
			return true;
		} catch (Exception e) {
			HandheldMusic.LOGGER.warn("[HandheldMusic] 保存登录 cookie 失败", e);
			return false;
		}
	}

	/** 用当前 cookie 请求一次账号信息，刷新登录态。 */
	public static boolean refresh() {
		try {
			NeteaseApi.Account account = NeteaseApi.account();
			if (account == null) {
				nickname = null;
				userId = 0L;
				return false;
			}
			nickname = account.nickname();
			userId = account.userId();
			return true;
		} catch (Exception e) {
			HandheldMusic.LOGGER.warn("[HandheldMusic] 校验 cookie 失败", e);
			return false;
		}
	}

	public static void logout() {
		nickname = null;
		userId = 0L;
		NeteaseApi.setCookies(Map.of());
		try {
			Files.deleteIfExists(COOKIE_FILE);
		} catch (Exception ignored) {
		}
	}

	/** 解析 cookie 串；没有 '=' 时按裸 MUSIC_U 处理。 */
	private static Map<String, String> parse(String raw) {
		Map<String, String> out = new LinkedHashMap<>();
		String s = raw.trim();
		if (!s.contains("=")) {
			out.put("MUSIC_U", s);
			return out;
		}
		for (String part : s.split(";")) {
			String p = part.trim();
			int eq = p.indexOf('=');
			if (eq > 0) {
				String k = p.substring(0, eq).trim();
				String v = p.substring(eq + 1).trim();
				if (!k.isEmpty() && !v.isEmpty()) {
					out.put(k, v);
				}
			}
		}
		return out;
	}
}
