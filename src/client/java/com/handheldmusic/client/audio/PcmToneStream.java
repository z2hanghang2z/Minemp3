package com.handheldmusic.client.audio;

import net.minecraft.client.sounds.AudioStream;

import javax.sound.sampled.AudioFormat;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.function.Supplier;

/**
 * 测试音源：6 秒，440Hz + 2500Hz + 6000Hz 三个正弦叠加。
 * 故意加高频成分，这样挂低通时听感差异会非常明显。
 */
public class PcmToneStream implements AudioStream {
	private static final float SAMPLE_RATE = 44100f;
	private static final int DURATION_SECONDS = 6;

	private final AudioFormat format = new AudioFormat(SAMPLE_RATE, 16, 1, true, false);
	private final ByteBuffer source;
	private boolean closed;

	public PcmToneStream() {
		int total = (int) (SAMPLE_RATE * DURATION_SECONDS);
		ByteBuffer buf = ByteBuffer.allocate(total * 2).order(ByteOrder.LITTLE_ENDIAN);
		for (int i = 0; i < total; i++) {
			double t = i / SAMPLE_RATE;
			double v = 0.30 * Math.sin(2 * Math.PI * 440 * t)
					+ 0.25 * Math.sin(2 * Math.PI * 2500 * t)
					+ 0.20 * Math.sin(2 * Math.PI * 6000 * t);
			v = Math.max(-1.0, Math.min(1.0, v));
			buf.putShort((short) (v * Short.MAX_VALUE));
		}
		buf.flip();
		this.source = buf;
	}

	public static Supplier<AudioStream> supplier() {
		return PcmToneStream::new;
	}

	@Override
	public AudioFormat getFormat() {
		return format;
	}

	@Override
	public ByteBuffer read(int size) throws IOException {
		if (closed || !source.hasRemaining()) {
			return ByteBuffer.allocateDirect(0);
		}
		int n = Math.min(size, source.remaining());
		ByteBuffer out = ByteBuffer.allocateDirect(n).order(ByteOrder.LITTLE_ENDIAN);
		ByteBuffer slice = source.duplicate();
		slice.position(source.position());
		slice.limit(source.position() + n);
		out.put(slice);
		source.position(source.position() + n);
		out.flip();
		return out;
	}

	@Override
	public void close() {
		closed = true;
	}
}
