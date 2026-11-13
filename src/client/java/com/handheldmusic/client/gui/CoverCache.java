package com.handheldmusic.client.gui;

import com.handheldmusic.HandheldMusic;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import com.handheldmusic.client.netease.NeteaseApi;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 专辑封面缓存。
 *
 * 只取 32×32 的小图（网易云 CDN 支持 {@code ?param=32y32} 直接返回缩略图），
 * 界面上再放大到 48×48 —— 反正要求是"能大概认出来"，没必要拉原图。
 *
 * 注册纹理必须在渲染线程，下载和解码放在后台；最多保留 {@value #MAX} 张，
 * 超了就把最早的释放掉，避免长时间切歌堆积显存。
 */
public final class CoverCache {
	private static final int MAX = 12;
	private static final int SIZE = 32;

	private static final Map<Long, Identifier> READY = new ConcurrentHashMap<>();
	private static final Set<Long> PENDING = ConcurrentHashMap.newKeySet();
	private static final Deque<Long> ORDER = new ArrayDeque<>();

	private CoverCache() {
	}

	public static Identifier get(long songId) {
		return READY.get(songId);
	}

	/** 请求一张封面；已有或正在下载则直接返回。 */
	public static void request(NeteaseApi.Song song) {
		long songId = song.id();
		if (READY.containsKey(songId) || !PENDING.add(songId)) {
			return;
		}
		CompletableFuture.runAsync(() -> {
			try {
				String url = song.coverUrl();
				if (url == null || url.isEmpty()) {
					// 搜索结果里的 album 没有 picUrl，单独查一次详情拿封面
					List<NeteaseApi.Song> detail = NeteaseApi.songDetails(List.of(songId));
					if (!detail.isEmpty()) {
						url = detail.get(0).coverUrl();
					}
				}
				if (url == null || url.isEmpty()) {
					PENDING.remove(songId);
					HandheldMusic.LOGGER.warn("[HandheldMusic] 这首歌没有封面：{}", songId);
					return;
				}
				String thumb = url + (url.contains("?") ? "&" : "?") + "param=" + SIZE + "y" + SIZE;
				byte[] bytes = download(thumb);
				NativeImage image = decode(bytes);
				Minecraft.getInstance().execute(() -> register(songId, image));
			} catch (Exception e) {
				PENDING.remove(songId);
				HandheldMusic.LOGGER.warn("[HandheldMusic] 封面下载失败 {}：{}", songId, e.toString());
			}
		});
	}

	private static void register(long songId, NativeImage image) {
		try {
			Identifier id = Identifier.fromNamespaceAndPath(HandheldMusic.MOD_ID, "cover/" + songId);
			Minecraft.getInstance().getTextureManager()
					.register(id, new DynamicTexture(() -> "handheldmusic cover " + songId, image));
			READY.put(songId, id);
			HandheldMusic.LOGGER.info("[HandheldMusic] 封面就绪：{}（{}×{}）",
					songId, image.getWidth(), image.getHeight());
			synchronized (ORDER) {
				ORDER.addLast(songId);
				while (ORDER.size() > MAX) {
					Long old = ORDER.pollFirst();
					if (old == null || old == songId) {
						continue;
					}
					Identifier oldId = READY.remove(old);
					if (oldId != null) {
						Minecraft.getInstance().getTextureManager().release(oldId);
					}
				}
			}
		} catch (Exception e) {
			image.close();
			HandheldMusic.LOGGER.warn("[HandheldMusic] 封面注册失败 {}", songId, e);
		} finally {
			PENDING.remove(songId);
		}
	}

	/**
	 * 解码图片。
	 *
	 * 不能用 NativeImage.read —— 它只认 PNG，而网易云封面是 JPEG（会抛 Bad PNG Signature）。
	 * 用 lwjgl-stb（MC 自带）解，JPEG / PNG 都支持，也不用引 AWT。
	 */
	private static NativeImage decode(byte[] bytes) throws Exception {
		java.nio.ByteBuffer source = org.lwjgl.BufferUtils.createByteBuffer(bytes.length);
		source.put(bytes).flip();

		try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
			java.nio.IntBuffer w = stack.mallocInt(1);
			java.nio.IntBuffer h = stack.mallocInt(1);
			java.nio.IntBuffer channels = stack.mallocInt(1);
			java.nio.ByteBuffer pixels =
					org.lwjgl.stb.STBImage.stbi_load_from_memory(source, w, h, channels, 4);
			if (pixels == null) {
				throw new java.io.IOException("图片解码失败："
						+ org.lwjgl.stb.STBImage.stbi_failure_reason());
			}
			try {
				int width = w.get(0);
				int height = h.get(0);
				NativeImage image = new NativeImage(NativeImage.Format.RGBA, width, height, false);
				for (int y = 0; y < height; y++) {
					for (int x = 0; x < width; x++) {
						int i = (y * width + x) * 4;
						int r = pixels.get(i) & 0xFF;
						int g = pixels.get(i + 1) & 0xFF;
						int b = pixels.get(i + 2) & 0xFF;
						int a = pixels.get(i + 3) & 0xFF;
						image.setPixelABGR(x, y, (a << 24) | (b << 16) | (g << 8) | r);
					}
				}
				return image;
			} finally {
				org.lwjgl.stb.STBImage.stbi_image_free(pixels);
			}
		}
	}

	private static byte[] download(String url) throws Exception {
		HttpURLConnection c = (HttpURLConnection) URI.create(url).toURL().openConnection();
		c.setConnectTimeout(8000);
		c.setReadTimeout(8000);
		c.setRequestProperty("User-Agent",
				"Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
						+ "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36");
		try (InputStream is = c.getInputStream()) {
			return is.readAllBytes();
		} finally {
			c.disconnect();
		}
	}
}
