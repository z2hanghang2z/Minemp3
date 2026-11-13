package com.handheldmusic.item;

import com.handheldmusic.HandheldMusic;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;

public final class ModItems {
	public static final Item HANDHELD_PLAYER = register("handheld_player");

	private ModItems() {
	}

	private static Item register(String name) {
		ResourceKey<Item> key = ResourceKey.create(
				Registries.ITEM,
				Identifier.fromNamespaceAndPath(HandheldMusic.MOD_ID, name)
		);
		Item item = new HandheldPlayerItem(new Item.Properties().stacksTo(1).setId(key));
		return Registry.register(BuiltInRegistries.ITEM, key, item);
	}

	public static void register() {
		HandheldMusic.LOGGER.info("[HandheldMusic] 物品注册完成");
	}
}
