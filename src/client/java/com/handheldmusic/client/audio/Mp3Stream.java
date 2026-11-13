package com.handheldmusic.client.audio;

import com.handheldmusic.HandheldMusic;
import net.minecraft.client.sounds.AudioStream;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * 把 mp3（后续还有 flac / m4a）解成 PCM，喂给原版 SoundEngine。
 *
 * 关键约束来自 26.3 的 com.mojang.blaze3d.audio.OpenAlUtil#audioFormatToOpenAl：
 * 只认 PCM_SIGNED / PCM_UNSIGNED + 8 或 16 bit + 1 或 2 声道，其它一律抛
 * IllegalArgumentException。所以这里统一转成 16bit 小端、保留原声道数与采样率。
 *
 * 采样率不强制 44100——OpenAL 会自己重采样，Channel 那边只按
 * "秒数 × 位深/8 × 声道 × 采样率" 算缓冲大小。
 */
public class Mp3Stream implements AudioStream {
	private static final int TARGET_BITS = 16;

	private final AudioInputStream source;
	private final AudioInputStream pcm;
	private final AudioFormat format;

	private byte[] scratch = new byte[0];
	private boolean closed;
	/** 已解码输出的 PCM 字节数，用来推算播放进度。 */
	private long bytesRead;
	/** 采样级增益斜坡 */
	private final Fade fade = new Fade();
	/** 最近一个缓冲块里的峰值幅度（0~32768） */
	private int lastAbsSample;

	public Mp3Stream(InputStream raw) throws IOException, UnsupportedAudioFileException {
		this.source = SpiAudio.open(new BufferedInputStream(raw, 64 * 1024));
		AudioFormat base = source.getFormat();

		int channels = base.getChannels() == 2 ? 2 : 1;
		this.format = new AudioFormat(
				AudioFormat.Encoding.PCM_SIGNED,
				base.getSampleRate(),
				TARGET_BITS,
				channels,
				channels * (TARGET_BITS / 8),
				base.getSampleRate(),
				false
		);

		// 格式已经一致时 AudioSystem 会直接返回原流，不额外套转换器。
		this.pcm = SpiAudio.convert(format, source);

		HandheldMusic.LOGGER.info("[HandheldMusic] mp3 解码器就绪：{}Hz {}ch {}bit（源：{}）",
				(int) format.getSampleRate(), format.getChannels(), format.getSampleSizeInBits(), base);
	}

	@Override
	public AudioFormat getFormat() {
		return format;
	}

	@Override
	public ByteBuffer read(int size) throws IOException {
		if (closed || size <= 0) {
			return ByteBuffer.allocateDirect(0);
		}
		if (scratch.length < size) {
			scratch = new byte[size];
		}
		int n = pcm.read(scratch, 0, size);
		if (n <= 0) {
			// 返回空 buffer 是原版约定的"流结束"，Channel 会据此收尾。
			return ByteBuffer.allocateDirect(0);
		}
		bytesRead += n;
		applyFade(n);

		ByteBuffer out = ByteBuffer.allocateDirect(n).order(ByteOrder.LITTLE_ENDIAN);
		out.put(scratch, 0, n);
		out.flip();
		return out;
	}

	// ---------------- 淡入淡出（采样级） ----------------

	/** 从静音线性升到满音量。 */
	public void beginFadeIn(long ms) {
		fade.jump(0.0f);
		fade.rampTo(1.0f, framesOf(ms));
	}

	/** 从当前音量线性降到静音。 */
	public void beginFadeOut(long ms) {
		fade.rampTo(0.0f, framesOf(ms));
	}

	public boolean fadeDone() {
		return fade.isSilent();
	}

	/** 最后一个输出帧的绝对幅度，用来客观判断「切的时候会不会爆音」。 */
	public int lastAbsSample() {
		return lastAbsSample;
	}

	private long framesOf(long ms) {
		return Math.max(1L, (long) (format.getSampleRate() * ms / 1000.0));
	}

	/**
	 * 对 16bit 小端 PCM 逐帧乘增益。
	 * 同一帧的多个声道用同一个增益值，否则立体声会跑偏。
	 */
	private void applyFade(int bytes) {
		int frameSize = format.getFrameSize();
		if (frameSize <= 0) {
			frameSize = format.getChannels() * 2;
		}
		int frames = bytes / frameSize;
		lastAbsSample = 0;
		for (int f = 0; f < frames; f++) {
			float gain = fade.next();
			int base = f * frameSize;
			for (int b = 0; b + 1 < frameSize; b += 2) {
				int i = base + b;
				short s = (short) ((scratch[i] & 0xFF) | (scratch[i + 1] << 8));
				int v = (int) (s * gain);
				if (v > 32767) {
					v = 32767;
				} else if (v < -32768) {
					v = -32768;
				}
				scratch[i] = (byte) (v & 0xFF);
				scratch[i + 1] = (byte) ((v >> 8) & 0xFF);
				int abs = Math.abs(v);
				if (abs > lastAbsSample) {
					lastAbsSample = abs;
				}
			}
		}
	}

	public long bytesRead() {
		return bytesRead;
	}

	@Override
	public void close() throws IOException {
		if (closed) {
			return;
		}
		closed = true;
		try {
			pcm.close();
		} finally {
			if (pcm != source) {
				source.close();
			}
		}
	}
}
