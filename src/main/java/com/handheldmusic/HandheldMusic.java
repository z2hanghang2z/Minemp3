package com.handheldmusic;

import com.handheldmusic.item.ModItems;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HandheldMusic implements ModInitializer {
	public static final String MOD_ID = "handheldmusic";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		ModItems.register();
		LOGGER.info("[HandheldMusic] 初始化完成");
	}
}
