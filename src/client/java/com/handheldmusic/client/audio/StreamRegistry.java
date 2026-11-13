package com.handheldmusic.client.audio;

import com.handheldmusic.HandheldMusic;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.resources.Identifier;

import javax.sound.sampled.AudioFormat;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * 虚拟音频流注册表。
 * 原版 SoundBufferLibrary.getStream(Identifier) 只认资源包路径，
 * 这里给每条流分配一个假 Identifier，由 Mixin 在 getStream 里拦截。
 *
 * 注意：SoundEngine 传给 getStream 的不是我们给的裸 Identifier，而是经过
 * Sound.getPath() 转换后的形态（FileToIdConverter("sounds", ".ogg")）：
 *   handheldmusic:stream/3  ->  handheldmusic:sounds/stream/3.ogg
 * 所以查表前必须先归一化，否则永远 miss。
 */
public final class StreamRegistry {
	private static final String PATH_PREFIX = "sounds/";
	private static final String PATH_SUFFIX = ".ogg";

	private static final Map<Identifier, Supplier<AudioStream>> STREAMS = new ConcurrentHashMap<>();
	private static final AtomicInteger COUNTER = new AtomicInteger();

	private StreamRegistry() {
	}

	public static Identifier register(Supplier<AudioStream> supplier) {
		Identifier id = Identifier.fromNamespaceAndPath(
				HandheldMusic.MOD_ID, "stream/" + COUNTER.incrementAndGet());
		STREAMS.put(id, supplier);
		return id;
	}

	/**
	 * 每次调用都产出一条新流（原版可能多次请求同一条）。
	 * 失败时返回一条「立即结束」的空流，而不是 null——原版拿到 null 会 NPE。
	 */
	public static AudioStream create(Identifier id) {
		Supplier<AudioStream> supplier = STREAMS.get(normalize(id));
		if (supplier == null) {
			return empty();
		}
		try {
			AudioStream stream = supplier.get();
			return stream != null ? stream : empty();
		} catch (Exception e) {
			HandheldMusic.LOGGER.error("[HandheldMusic] 创建音频流失败：{}", id, e);
			return empty();
		}
	}

	private static AudioStream empty() {
		return new AudioStream() {
			private final AudioFormat format = new AudioFormat(44100.0f, 16, 2, true, false);

			@Override
			public AudioFormat getFormat() {
				return format;
			}

			@Override
			public ByteBuffer read(int size) {
				return ByteBuffer.allocateDirect(0);
			}

			@Override
			public void close() {
			}
		};
	}

	public static boolean contains(Identifier id) {
		return STREAMS.containsKey(normalize(id));
	}

	public static void unregister(Identifier id) {
		STREAMS.remove(normalize(id));
	}

	/** 把 sounds/stream/3.ogg 还原成 stream/3。 */
	private static Identifier normalize(Identifier id) {
		if (id == null) {
			return null;
		}
		String path = id.getPath();
		boolean changed = false;
		if (path.startsWith(PATH_PREFIX)) {
			path = path.substring(PATH_PREFIX.length());
			changed = true;
		}
		if (path.endsWith(PATH_SUFFIX)) {
			path = path.substring(0, path.length() - PATH_SUFFIX.length());
			changed = true;
		}
		return changed ? Identifier.fromNamespaceAndPath(id.getNamespace(), path) : id;
	}
}
