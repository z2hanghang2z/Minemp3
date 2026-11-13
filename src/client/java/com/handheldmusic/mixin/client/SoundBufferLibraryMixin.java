package com.handheldmusic.mixin.client;

import com.handheldmusic.HandheldMusic;
import com.handheldmusic.client.audio.StreamRegistry;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.CompletableFuture;

/**
 * 拦截原版的取流入口。
 * 26.3 的 SoundEngine.play 走的是 getStream(sound.getPath(), loop)，
 * 只要我们的 Identifier 命中注册表，就把自建 AudioStream 塞进去。
 *
 * ⚠️ 必须用 supplyAsync：流式音频在构造时要等 mp3 头解析出来（会阻塞），
 * 同步构造会卡在渲染线程上。原版自己的 getStream 也是异步的。
 */
@Mixin(SoundBufferLibrary.class)
public class SoundBufferLibraryMixin {
	@Inject(method = "getStream", at = @At("HEAD"), cancellable = true)
	private void handheldmusic$getStream(Identifier id, boolean loop,
										 CallbackInfoReturnable<CompletableFuture<AudioStream>> cir) {
		if (!StreamRegistry.contains(id)) {
			return;
		}
		cir.setReturnValue(CompletableFuture.supplyAsync(() -> {
			AudioStream stream = StreamRegistry.create(id);
			HandheldMusic.LOGGER.info("[HandheldMusic] 拦截 getStream：{}（{}）",
					id, stream.getClass().getSimpleName());
			return stream;
		}));
	}
}
