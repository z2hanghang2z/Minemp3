package com.handheldmusic.client.audio;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;

/**
 * Java Sound SPI 的安全入口。
 *
 * {@code AudioSystem} 找解码器走的是**线程上下文类加载器（TCCL）**。
 * 打包后 mp3spi/jlayer 在 META-INF/jars 里、只对模组自己的 classloader 可见，
 * 而 ForkJoinPool 工作线程、sound engine 线程的 TCCL 往往是 system loader ——
 * 那样就会 UnsupportedAudioFileException。
 *
 * 实测：TCCL = 模组加载器 → 能解；TCCL = system loader → 解不了。
 * 所以这里在调用期间把 TCCL 临时换成模组的，结束后还原。
 *
 * 刻意不依赖任何 Minecraft 类，方便脱离游戏单独验证。
 */
public final class SpiAudio {
	private SpiAudio() {
	}

	public static AudioInputStream open(InputStream raw) throws UnsupportedAudioFileException, IOException {
		Thread thread = Thread.currentThread();
		ClassLoader previous = thread.getContextClassLoader();
		ClassLoader own = SpiAudio.class.getClassLoader();
		try {
			if (previous != own) {
				thread.setContextClassLoader(own);
			}
			return AudioSystem.getAudioInputStream(raw);
		} finally {
			if (previous != own) {
				thread.setContextClassLoader(previous);
			}
		}
	}

	public static AudioInputStream open(File file) throws UnsupportedAudioFileException, IOException {
		Thread thread = Thread.currentThread();
		ClassLoader previous = thread.getContextClassLoader();
		ClassLoader own = SpiAudio.class.getClassLoader();
		try {
			if (previous != own) {
				thread.setContextClassLoader(own);
			}
			return AudioSystem.getAudioInputStream(file);
		} finally {
			if (previous != own) {
				thread.setContextClassLoader(previous);
			}
		}
	}

	/** 转成目标格式；格式已一致时 AudioSystem 会直接返回原流。 */
	public static AudioInputStream convert(AudioFormat target, AudioInputStream source) throws UnsupportedAudioFileException, IOException {
		Thread thread = Thread.currentThread();
		ClassLoader previous = thread.getContextClassLoader();
		ClassLoader own = SpiAudio.class.getClassLoader();
		try {
			if (previous != own) {
				thread.setContextClassLoader(own);
			}
			return AudioSystem.getAudioInputStream(target, source);
		} finally {
			if (previous != own) {
				thread.setContextClassLoader(previous);
			}
		}
	}
}
