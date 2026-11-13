package com.handheldmusic.client.netease;

import com.google.gson.JsonObject;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.handheldmusic.HandheldMusic;

import java.util.concurrent.CompletableFuture;

/**
 * 二维码登录状态机。
 *
 * 流程：取 unikey → 生成二维码 → 每 2 秒轮询
 *   801 等待扫码 / 802 已扫码待确认 / 803 成功 / 800 过期
 *   8821 = 风控拦截（提示用户改用 cookie）
 */
public final class NeteaseQrLogin {
	public enum State {
		IDLE, LOADING, WAITING, SCANNED, SUCCESS, EXPIRED, ERROR
	}

	private static final String QR_CONTENT_PREFIX = "https://music.163.com/login?codekey=";
	private static final long POLL_INTERVAL_MS = 2000L;
	private static final long TIMEOUT_MS = 300_000L;

	private static volatile State state = State.IDLE;
	private static volatile String message = "";
	private static volatile BitMatrix matrix;
	private static volatile String unikey;
	private static volatile String qrUrl;
	private static volatile boolean running;
	/** 「刷新二维码」时置 true，让旧轮询线程自己退出。 */
	private static volatile boolean cancelled;

	private NeteaseQrLogin() {
	}

	/** 二维码里编码的链接，可以复制到浏览器打开。 */
	public static String qrUrl() {
		return qrUrl;
	}

	public static State state() {
		return state;
	}

	public static String message() {
		return message;
	}

	public static BitMatrix matrix() {
		return matrix;
	}

	public static boolean isRunning() {
		return running;
	}

	/**
	 * 开始一轮登录；已在跑就直接忽略。
	 *
	 * @param px 二维码边长（像素）。要和界面上的显示尺寸一致，
	 *           这样 1:1 绘制才不会把相邻模块糊在一起导致扫不出来。
	 */
	public static void start(int px) {
		if (running) {
			return;
		}
		// 已经有同尺寸的可用二维码就别重新申请，省一次请求
		if (matrix != null && matrix.getWidth() == px
				&& state != State.EXPIRED && state != State.ERROR) {
			return;
		}
		cancelled = false;
		running = true;
		state = State.LOADING;
		message = "正在获取二维码…";
		matrix = null;
		unikey = null;
		qrUrl = null;

		CompletableFuture.runAsync(() -> {
			try {
				String key = requestUnikey();
				if (key == null) {
					fail("拿不到二维码，请稍后重试");
					return;
				}
				unikey = key;
				qrUrl = QR_CONTENT_PREFIX + key;
				matrix = new QRCodeWriter().encode(qrUrl, BarcodeFormat.QR_CODE, px, px);
				state = State.WAITING;
				message = "请用手机网易云 App 扫码";
				poll(key);
			} catch (Exception e) {
				HandheldMusic.LOGGER.error("[HandheldMusic] 二维码登录失败", e);
				fail("登录出错：" + e.getClass().getSimpleName());
			} finally {
				running = false;
			}
		});
	}

	/** 「刷新二维码」用：中断当前轮询并清空状态。 */
	public static void reset() {
		cancelled = true;
		running = false;
		state = State.IDLE;
		message = "";
		matrix = null;
		unikey = null;
		qrUrl = null;
	}

	private static String requestUnikey() {
		for (int i = 0; i < 3; i++) {
			try {
				// 注意：type 必传，漏了服务端就回 400「参数错误」
				JsonObject body = new JsonObject();
				body.addProperty("type", 1);
				JsonObject resp = NeteaseApi.weapi("/api/login/qrcode/unikey", body);
				if (resp.has("unikey") && !resp.get("unikey").isJsonNull()) {
					return resp.get("unikey").getAsString();
				}
				HandheldMusic.LOGGER.warn("[HandheldMusic] 取 unikey 失败：{}", resp);
			} catch (Exception e) {
				HandheldMusic.LOGGER.warn("[HandheldMusic] 取 unikey 异常", e);
			}
			sleep(2000L * (i + 1));
		}
		return null;
	}

	private static void poll(String key) {
		long deadline = System.currentTimeMillis() + TIMEOUT_MS;
		while (!cancelled && System.currentTimeMillis() < deadline) {
			sleep(POLL_INTERVAL_MS);
			try {
				JsonObject body = new JsonObject();
				body.addProperty("key", key);
				body.addProperty("type", 1);
				JsonObject resp = NeteaseApi.weapi("/api/login/qrcode/client/login", body);
				int code = resp.has("code") ? resp.get("code").getAsInt() : -1;

				switch (code) {
					case 801 -> {
						state = State.WAITING;
						message = "请用手机网易云 App 扫码";
					}
					case 802 -> {
						state = State.SCANNED;
						message = "已扫码，请在手机上确认登录";
					}
					case 803 -> {
						// 登录成功后 cookie 已由 NeteaseApi 自动收进 COOKIES
						if (NeteaseSession.commitCurrent()) {
							state = State.SUCCESS;
							message = "登录成功：" + NeteaseSession.nickname();
							HandheldMusic.LOGGER.info("[HandheldMusic] 二维码登录成功：{}",
									NeteaseSession.nickname());
						} else {
							fail("登录返回成功，但 cookie 校验失败");
						}
						return;
					}
					case 800 -> {
						state = State.EXPIRED;
						message = "二维码已过期，请重新获取";
						return;
					}
					case 8821 -> {
						state = State.ERROR;
						message = "风控拦截（8821）：请在浏览器登录后用 /hmpaste 粘贴 MUSIC_U";
						HandheldMusic.LOGGER.warn("[HandheldMusic] 二维码登录被风控拦截：{}", resp);
						return;
					}
					default -> {
						state = State.ERROR;
						message = "登录失败（code " + code + "）";
						return;
					}
				}
			} catch (Exception e) {
				HandheldMusic.LOGGER.warn("[HandheldMusic] 轮询异常", e);
			}
		}
		state = State.EXPIRED;
		message = "等待超时，请重新获取二维码";
	}

	private static void fail(String text) {
		state = State.ERROR;
		message = text;
	}

	private static void sleep(long ms) {
		try {
			Thread.sleep(ms);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}
