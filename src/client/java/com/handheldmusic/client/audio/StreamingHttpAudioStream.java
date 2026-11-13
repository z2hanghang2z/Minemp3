package com.handheldmusic.client.audio;

import com.handheldmusic.HandheldMusic;
import net.minecraft.client.sounds.AudioStream;

import javax.sound.sampled.AudioFormat;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.ByteBuffer;

/**
 * 边下边播的音频流。
 *
 * 结构：下载线程 → 环形缓冲 → InputStream → mp3spi 解码 → 原版 Channel
 *
 * 为什么要环形缓冲：原版 Channel 在 sound engine 线程上调 read()，
 * 直接在里面做网络 IO 会把整个游戏的音频卡住。这里让下载线程提前灌数据，
 * read() 只在缓冲空时才短暂等待（上限 5 秒，超时抛 IOException 由原版兜底）。
 */
public class StreamingHttpAudioStream implements AudioStream {
	/** 1MB ≈ 128kbps 下约 64 秒，够扛住网络抖动。 */
	private static final int RING_SIZE = 1 << 20;
	/** 单次等待上限，超时说明网络真断了。 */
	private static final long READ_TIMEOUT_MS = 5000L;

	private final RingBuffer ring = new RingBuffer(RING_SIZE);
	private final Mp3Stream delegate;

	public StreamingHttpAudioStream(String url) throws Exception {
		Thread downloader = new Thread(() -> download(url), "handheldmusic-download");
		downloader.setDaemon(true);
		downloader.start();

		// 这里会短暂阻塞，等 mp3 头被解析出来。调用方必须保证不在渲染线程上构造。
		this.delegate = new Mp3Stream(new RingInputStream(ring));
	}

	@Override
	public AudioFormat getFormat() {
		return delegate.getFormat();
	}

	@Override
	public ByteBuffer read(int size) throws IOException {
		return delegate.read(size);
	}

	/** 已解码的 PCM 字节数，用于进度推算。 */
	public long bytesRead() {
		return delegate.bytesRead();
	}

	/** 已播放时长（毫秒）：PCM 字节数 ÷ 每秒字节数。 */
	public long playedMs() {
		AudioFormat f = delegate.getFormat();
		long bytesPerSecond = (long) (f.getSampleRate() * f.getFrameSize());
		return bytesPerSecond > 0 ? delegate.bytesRead() * 1000L / bytesPerSecond : 0L;
	}

	// 采样级淡入淡出，直接透传给解码层
	public void beginFadeIn(long ms) {
		delegate.beginFadeIn(ms);
	}

	public void beginFadeOut(long ms) {
		delegate.beginFadeOut(ms);
	}

	public boolean fadeDone() {
		return delegate.fadeDone();
	}

	public int lastAbsSample() {
		return delegate.lastAbsSample();
	}

	@Override
	public void close() throws IOException {
		ring.abort();
		delegate.close();
	}

	// ---------------- 下载 ----------------

	private void download(String url) {
		long offset = 0L;
		for (int attempt = 0; attempt < 3; attempt++) {
			HttpURLConnection c = null;
			try {
				c = (HttpURLConnection) URI.create(url).toURL().openConnection();
				c.setConnectTimeout(15000);
				c.setReadTimeout(20000);
				c.setRequestProperty("User-Agent",
						"Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
								+ "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36");
				if (offset > 0) {
					c.setRequestProperty("Range", "bytes=" + offset + "-");
				}
				int code = c.getResponseCode();
				if (code != 200 && code != 206) {
					throw new IOException("HTTP " + code);
				}
				try (InputStream is = c.getInputStream()) {
					byte[] tmp = new byte[16384];
					int n;
					while ((n = is.read(tmp)) > 0) {
						ring.write(tmp, 0, n);
						offset += n;
					}
				}
				ring.finish();
				HandheldMusic.LOGGER.info("[HandheldMusic] 下载完成，共 {} KB", offset / 1024);
				return;
			} catch (Exception e) {
				if (ring.isAborted()) {
					return;
				}
				HandheldMusic.LOGGER.warn("[HandheldMusic] 下载中断（第 {} 次，已收 {} KB）：{}",
						attempt + 1, offset / 1024, e.toString());
				try {
					Thread.sleep(500L * (attempt + 1));
				} catch (InterruptedException ie) {
					Thread.currentThread().interrupt();
					return;
				}
			} finally {
				if (c != null) {
					c.disconnect();
				}
			}
		}
		HandheldMusic.LOGGER.error("[HandheldMusic] 下载彻底失败，按已收到的 {} KB 收尾", offset / 1024);
		ring.finish();
	}

	// ---------------- 环形缓冲 ----------------

	static final class RingBuffer {
		private final byte[] buf;
		private int readPos;
		private int writePos;
		private int count;
		private boolean finished;
		private boolean aborted;

		RingBuffer(int size) {
			this.buf = new byte[size];
		}

		synchronized void write(byte[] src, int off, int len) throws IOException {
			while (len > 0) {
				while (count == buf.length && !aborted) {
					try {
						wait();
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
						throw new IOException("下载线程被中断");
					}
				}
				if (aborted) {
					throw new IOException("流已关闭");
				}
				int space = buf.length - count;
				int n = Math.min(Math.min(space, len), buf.length - writePos);
				System.arraycopy(src, off, buf, writePos, n);
				writePos = (writePos + n) % buf.length;
				count += n;
				off += n;
				len -= n;
				notifyAll();
			}
		}

		/** @return 读到的字节数，-1 表示流结束 */
		synchronized int read(byte[] dst, int off, int len, long timeoutMs) throws IOException {
			long deadline = System.currentTimeMillis() + timeoutMs;
			while (count == 0) {
				if (aborted) {
					throw new IOException("流已关闭");
				}
				if (finished) {
					return -1;
				}
				long remain = deadline - System.currentTimeMillis();
				if (remain <= 0) {
					throw new IOException("网络读取超时");
				}
				try {
					wait(remain);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					throw new IOException("读取被中断");
				}
			}
			int n = Math.min(Math.min(count, len), buf.length - readPos);
			System.arraycopy(buf, readPos, dst, off, n);
			readPos = (readPos + n) % buf.length;
			count -= n;
			notifyAll();
			return n;
		}

		synchronized void finish() {
			finished = true;
			notifyAll();
		}

		synchronized void abort() {
			aborted = true;
			notifyAll();
		}

		synchronized boolean isAborted() {
			return aborted;
		}
	}

	static final class RingInputStream extends InputStream {
		private final RingBuffer ring;

		RingInputStream(RingBuffer ring) {
			this.ring = ring;
		}

		@Override
		public int read() throws IOException {
			byte[] one = new byte[1];
			int n = read(one, 0, 1);
			return n < 0 ? -1 : (one[0] & 0xFF);
		}

		@Override
		public int read(byte[] b, int off, int len) throws IOException {
			if (len == 0) {
				return 0;
			}
			return ring.read(b, off, len, READ_TIMEOUT_MS);
		}

		@Override
		public void close() {
			ring.abort();
		}
	}
}
