package com.handheldmusic.client.netease;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.handheldmusic.HandheldMusic;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * 网易云 eapi 客户端（最小可用版）。
 *
 * 三个必须记住的点：
 * 1. 摘要里的 path 是 {@code /api/xxx}，实际请求必须打到 {@code /eapi/api/xxx}，
 *    少了 {@code /eapi} 一律回 400「参数错误」。
 * 2. 加密是 AES-128-ECB + 固定密钥，params 转大写十六进制。
 * 3. 匿名即可搜索、可取直链；登录只影响歌单/每日推荐/VIP 曲目。
 */
public final class NeteaseApi {
	private static final String EAPI_KEY = "e82ckenh8dichen8";
	private static final String HOST = "https://interface.music.163.com";
	private static final String WEB_HOST = "https://music.163.com";
	private static final String SEP = "-36cd479b6b5-";
	private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
			+ "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";

	/**
	 * 设备伪 cookie——扫码登录能不能过风控全靠它。
	 *
	 * 不带这个头时，网易看到的是一个「没有任何客户端标识的陌生请求」，
	 * 手机端确认后一律回 8821（安全环境风险）。带上 os=pc + appver 之后
	 * 服务端就当成正常 PC 客户端，803 直接过。
	 * 这是对照 Dango Music 的实现找出来的关键差异。
	 */
	private static final String DEVICE_COOKIE = "os=pc; appver=2.10.13";

	// weapi（网页版）加密常量
	private static final String WEAPI_PRESET_KEY = "0CoJUm6Qyw8W8jud";
	private static final String WEAPI_IV = "0102030405060708";
	private static final String WEAPI_PUBKEY =
			"00e0b509f6259df8642dbc35662901477df22677ec152b5ff68ace615bb7b725152b3ab17a876aea8a5aa76d2e417629ec4"
					+ "ee341f56135fccf695280104e0312ecbda92557c93870114af6c9d05c4f7f0c3685b7a46bee255932575cce10b424d81"
					+ "3cfe4875d3e82047b97ddef52741d546b8e289dc6935b3ece0462db0a22b8e7";
	private static final String KEY_CHARS =
			"abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

	private static final Map<String, String> COOKIES = new LinkedHashMap<>();
	private static final String DEVICE_ID = UUID.randomUUID().toString().replace("-", "");

	private NeteaseApi() {
	}

	// ---------------- 业务接口 ----------------

	public record Song(long id, String name, String artists, long durationMs, String coverUrl) {
		public String display() {
			return artists.isEmpty() ? name : name + " - " + artists;
		}
	}

	public record Track(String url, String type, long br, long size, boolean trial) {
	}

	public record Account(long userId, String nickname) {
	}

	/** 当前登录账号；未登录（或 cookie 失效）返回 null。 */
	public static Account account() throws Exception {
		JsonObject resp = eapi("/api/nuser/account/get", new JsonObject());
		JsonObject profile = asObject(resp, "profile");
		if (profile == null) {
			return null;
		}
		long uid = profile.has("userId") ? profile.get("userId").getAsLong() : 0L;
		String nick = profile.has("nickname") ? profile.get("nickname").getAsString() : "?";
		return new Account(uid, nick);
	}

	public static List<Song> search(String keyword, int limit) throws Exception {
		JsonObject body = new JsonObject();
		body.addProperty("s", keyword);
		body.addProperty("type", 1);
		body.addProperty("limit", limit);
		body.addProperty("offset", 0);
		JsonObject resp = eapi("/api/search/get", body);

		List<Song> out = new ArrayList<>();
		JsonObject result = asObject(resp, "result");
		if (result == null) {
			return out;
		}
		JsonArray songs = asArray(result, "songs");
		if (songs == null) {
			return out;
		}
		for (JsonElement el : songs) {
			if (el.isJsonObject()) {
				Song song = parseSong(el.getAsJsonObject());
				if (song != null) {
					out.add(song);
				}
			}
		}
		return out;
	}

	/** 「我喜欢的音乐」里所有歌曲的 id（需要登录）。 */
	public static List<Long> likedIds(long uid) throws Exception {
		JsonObject body = new JsonObject();
		body.addProperty("uid", String.valueOf(uid));
		JsonObject resp = eapi("/api/song/like/get", body);
		List<Long> out = new ArrayList<>();
		JsonArray ids = asArray(resp, "ids");
		if (ids == null) {
			return out;
		}
		for (JsonElement el : ids) {
			if (el.isJsonPrimitive()) {
				out.add(el.getAsLong());
			}
		}
		return out;
	}

	/**
	 * 批量取歌曲详情。
	 * 注意 v3/song/detail 的字段名和搜索结果不一样：
	 * 歌手是 {@code ar}（不是 artists），时长是 {@code dt}（不是 duration）。
	 */
	public static List<Song> songDetails(List<Long> ids) throws Exception {
		List<Song> out = new ArrayList<>();
		final int batch = 100;
		for (int start = 0; start < ids.size(); start += batch) {
			List<Long> chunk = ids.subList(start, Math.min(ids.size(), start + batch));
			JsonArray c = new JsonArray();
			for (Long id : chunk) {
				JsonObject item = new JsonObject();
				item.addProperty("id", id);
				c.add(item);
			}
			JsonObject body = new JsonObject();
			body.addProperty("c", c.toString());
			JsonObject resp = eapi("/api/v3/song/detail", body);
			JsonArray songs = asArray(resp, "songs");
			if (songs == null) {
				continue;
			}
			for (JsonElement el : songs) {
				if (el.isJsonObject()) {
					Song song = parseSong(el.getAsJsonObject());
					if (song != null) {
						out.add(song);
					}
				}
			}
		}
		return out;
	}


	/**
	 * 解析一个 song 对象。
	 * 网易云不同接口的字段名不一致，这里统一兼容：
	 * 歌手 {@code ar} / {@code artists}，时长 {@code dt} / {@code duration}。
	 */
	private static Song parseSong(JsonObject s) {
		long id = s.has("id") ? s.get("id").getAsLong() : 0L;
		String name = s.has("name") ? s.get("name").getAsString() : "?";
		long duration = s.has("dt") ? s.get("dt").getAsLong()
				: s.has("duration") ? s.get("duration").getAsLong() : 0L;

		JsonArray ar = asArray(s, "ar");
		if (ar == null) {
			ar = asArray(s, "artists");
		}
		StringBuilder artists = new StringBuilder();
		if (ar != null) {
			for (JsonElement a : ar) {
				if (a.isJsonObject() && a.getAsJsonObject().has("name")) {
					if (artists.length() > 0) {
						artists.append('/');
					}
					artists.append(a.getAsJsonObject().get("name").getAsString());
				}
			}
		}
		return id == 0 ? null : new Song(id, name, artists.toString(), duration, parseCover(s));
	}

	/** 专辑封面地址：v3 接口在 {@code al.picUrl}，搜索/每日推荐在 {@code album.picUrl}。 */
	private static String parseCover(JsonObject s) {
		JsonObject al = asObject(s, "al");
		if (al == null) {
			al = asObject(s, "album");
		}
		if (al == null) {
			return null;
		}
		// 搜索接口的 album 只有 picId，没有 picUrl；v3 详情有 picUrl；每日推荐常带 blurPicUrl
		for (String field : new String[]{"picUrl", "blurPicUrl"}) {
			if (al.has(field) && !al.get(field).isJsonNull()) {
				String url = al.get(field).getAsString();
				if (!url.isEmpty()) {
					return url;
				}
			}
		}
		return null;
	}

	/** 每日推荐（需要登录）。返回 data.dailySongs。 */
	public static List<Song> dailyRecommend() throws Exception {
		JsonObject resp = eapi("/api/v1/discovery/recommend/songs", new JsonObject());
		JsonArray songs = null;
		JsonObject data = asObject(resp, "data");
		if (data != null) {
			songs = asArray(data, "dailySongs");
		}
		if (songs == null) {
			songs = asArray(resp, "dailySongs");
		}
		List<Song> out = new ArrayList<>();
		if (songs == null) {
			return out;
		}
		for (JsonElement el : songs) {
			if (el.isJsonObject()) {
				Song song = parseSong(el.getAsJsonObject());
				if (song != null) {
					out.add(song);
				}
			}
		}
		return out;
	}

	/**
	 * 取播放直链。
	 *
	 * encodeType 传 mp3 就能直接拿 mp3，省掉 AAC 解码器；
	 * 不可用时服务端会自动降级成它认为可用的格式，返回里看 type 字段。
	 *
	 * @param level 音质档位：standard / higher / exhigh / lossless / hires。
	 *              未登录时高码率拿不到，会被降级；登录 + VIP 时 exhigh 给 320kbps mp3。
	 */
	public static Track track(long songId, String level) throws Exception {
		JsonObject body = new JsonObject();
		body.addProperty("ids", "[" + songId + "]");
		body.addProperty("level", level);
		body.addProperty("encodeType", "mp3");
		JsonObject resp = eapi("/api/song/enhance/player/url/v1", body);

		JsonArray data = asArray(resp, "data");
		if (data == null || data.isEmpty()) {
			return null;
		}
		JsonObject first = data.get(0).getAsJsonObject();
		String url = first.has("url") && !first.get("url").isJsonNull()
				? first.get("url").getAsString() : null;
		if (url == null || url.isEmpty()) {
			long code = first.has("code") ? first.get("code").getAsLong() : -1L;
			long fee = first.has("fee") ? first.get("fee").getAsLong() : -1L;
			HandheldMusic.LOGGER.info("[HandheldMusic] 无直链：id={} code={} fee={}", songId, code, fee);
			return null;
		}
		String type = first.has("type") ? first.get("type").getAsString() : "?";
		long br = first.has("br") ? first.get("br").getAsLong() : 0L;
		long size = first.has("size") ? first.get("size").getAsLong() : 0L;
		boolean trial = first.has("freeTrialInfo") && !first.get("freeTrialInfo").isJsonNull();
		return new Track(url, type, br, size, trial);
	}

	// ---------------- eapi 加密 ----------------

	public static JsonObject eapi(String path, JsonObject body) throws Exception {
		body.add("header", header());
		String json = body.toString();
		String digest = md5("nobody" + path + "use" + json + "md5forencrypt");
		String plain = path + SEP + json + SEP + digest;
		String params = hexUpper(aesEcb(plain.getBytes(StandardCharsets.UTF_8)));
		String resp = post(HOST + "/eapi" + path, "params=" + params);
		HandheldMusic.LOGGER.debug("[HandheldMusic] eapi {} -> {}", path, resp);
		return JsonParser.parseString(resp).getAsJsonObject();
	}

	private static JsonObject header() {
		JsonObject h = new JsonObject();
		h.addProperty("os", "pc");
		h.addProperty("appver", "8.9.70");
		h.addProperty("osver", "");
		h.addProperty("deviceId", DEVICE_ID);
		h.addProperty("requestId", String.valueOf(System.nanoTime()));
		return h;
	}

	// ---------------- weapi 加密（网页版，扫码登录必须走这套） ----------------

	/**
	 * 网页版接口。
	 *
	 * 路径规则和 eapi 不同：把 {@code /api/xxx} 里的 api 段**替换**成 weapi
	 * （{@code /api/login/qrcode/unikey} → {@code /weapi/login/qrcode/unikey}），
	 * 而 eapi 是加前缀 {@code /eapi/api/xxx}。
	 *
	 * 加密 = AES-128-CBC 两次（presetKey + 随机 16 位 key，IV 固定）+ RSA 无填充。
	 */
	public static JsonObject weapi(String path, JsonObject body) throws Exception {
		String json = body.toString();
		String secretKey = randomKey16();
		String stage1 = aesCbcBase64(json.getBytes(StandardCharsets.UTF_8), WEAPI_PRESET_KEY);
		String params = aesCbcBase64(stage1.getBytes(StandardCharsets.UTF_8), secretKey);
		String encSecKey = rsaHex(new StringBuilder(secretKey).reverse().toString());
		String form = "params=" + URLEncoder.encode(params, StandardCharsets.UTF_8)
				+ "&encSecKey=" + encSecKey;
		String url = WEB_HOST + path.replaceFirst("^/api/", "/weapi/");
		String resp = post(url, form);
		HandheldMusic.LOGGER.debug("[HandheldMusic] weapi {} -> {}", path, resp);
		return JsonParser.parseString(resp).getAsJsonObject();
	}

	private static String aesCbcBase64(byte[] data, String key) throws Exception {
		Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
		cipher.init(Cipher.ENCRYPT_MODE,
				new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "AES"),
				new IvParameterSpec(WEAPI_IV.getBytes(StandardCharsets.UTF_8)));
		return Base64.getEncoder().encodeToString(cipher.doFinal(data));
	}

	private static String randomKey16() {
		Random r = new Random();
		StringBuilder sb = new StringBuilder(16);
		for (int i = 0; i < 16; i++) {
			sb.append(KEY_CHARS.charAt(r.nextInt(KEY_CHARS.length())));
		}
		return sb.toString();
	}

	/** RSA 无填充：m^e mod n，结果补零到 256 位十六进制。 */
	private static String rsaHex(String text) {
		BigInteger m = new BigInteger(1, text.getBytes(StandardCharsets.UTF_8));
		BigInteger e = new BigInteger("010001", 16);
		BigInteger n = new BigInteger(WEAPI_PUBKEY, 16);
		String hex = m.modPow(e, n).toString(16);
		StringBuilder sb = new StringBuilder(hex);
		while (sb.length() < 256) {
			sb.insert(0, '0');
		}
		return sb.toString();
	}

	private static byte[] aesEcb(byte[] data) throws Exception {
		Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
		cipher.init(Cipher.ENCRYPT_MODE,
				new SecretKeySpec(EAPI_KEY.getBytes(StandardCharsets.UTF_8), "AES"));
		return cipher.doFinal(data);
	}

	private static String md5(String s) throws Exception {
		byte[] d = MessageDigest.getInstance("MD5").digest(s.getBytes(StandardCharsets.UTF_8));
		StringBuilder sb = new StringBuilder(d.length * 2);
		for (byte b : d) {
			sb.append(Character.forDigit((b >> 4) & 0xF, 16));
			sb.append(Character.forDigit(b & 0xF, 16));
		}
		return sb.toString();
	}

	// ---------------- HTTP ----------------

	private static String post(String url, String body) throws Exception {
		HttpURLConnection c = (HttpURLConnection) URI.create(url).toURL().openConnection();
		c.setRequestMethod("POST");
		c.setDoOutput(true);
		c.setConnectTimeout(15000);
		c.setReadTimeout(20000);
		c.setRequestProperty("User-Agent", UA);
		c.setRequestProperty("Referer", "https://music.163.com/");
		c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
		// 设备伪 cookie 必须常驻，否则扫码登录会被判 8821
		String cookie = cookieHeader();
		c.setRequestProperty("Cookie",
				cookie.isEmpty() ? DEVICE_COOKIE : DEVICE_COOKIE + "; " + cookie);
		try (OutputStream os = c.getOutputStream()) {
			os.write(body.getBytes(StandardCharsets.UTF_8));
		}
		int code = c.getResponseCode();
		InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
		String text = is == null ? "" : new String(is.readAllBytes(), StandardCharsets.UTF_8);
		collectCookies(c);
		return text;
	}

	private static void collectCookies(HttpURLConnection c) {
		Map<String, List<String>> fields = c.getHeaderFields();
		if (fields == null) {
			return;
		}
		for (Map.Entry<String, List<String>> e : fields.entrySet()) {
			if (e.getKey() == null || !e.getKey().equalsIgnoreCase("Set-Cookie")) {
				continue;
			}
			for (String raw : e.getValue()) {
				int semi = raw.indexOf(';');
				String pair = semi >= 0 ? raw.substring(0, semi) : raw;
				int eq = pair.indexOf('=');
				if (eq > 0) {
					String k = pair.substring(0, eq).trim();
					String v = pair.substring(eq + 1).trim();
					if (!k.isEmpty() && !v.isEmpty()) {
						COOKIES.put(k, v);
					}
				}
			}
		}
	}

	public static String cookieHeader() {
		StringBuilder sb = new StringBuilder();
		for (Map.Entry<String, String> e : COOKIES.entrySet()) {
			if (sb.length() > 0) {
				sb.append("; ");
			}
			sb.append(e.getKey()).append('=').append(e.getValue());
		}
		return sb.toString();
	}

	public static void setCookies(Map<String, String> cookies) {
		COOKIES.clear();
		COOKIES.putAll(cookies);
	}

	// ---------------- 小工具 ----------------

	private static JsonObject asObject(JsonObject o, String key) {
		return o.has(key) && o.get(key).isJsonObject() ? o.getAsJsonObject(key) : null;
	}

	private static JsonArray asArray(JsonObject o, String key) {
		return o.has(key) && o.get(key).isJsonArray() ? o.getAsJsonArray(key) : null;
	}

	private static String hexUpper(byte[] bytes) {
		StringBuilder sb = new StringBuilder(bytes.length * 2);
		for (byte b : bytes) {
			sb.append(Character.toUpperCase(Character.forDigit((b >> 4) & 0xF, 16)));
			sb.append(Character.toUpperCase(Character.forDigit(b & 0xF, 16)));
		}
		return sb.toString();
	}
}
