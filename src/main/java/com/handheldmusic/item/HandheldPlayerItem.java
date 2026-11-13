package com.handheldmusic.item;

import com.handheldmusic.ClientBridge;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;

public class HandheldPlayerItem extends Item {
	public HandheldPlayerItem(Properties properties) {
		super(properties);
	}

	@Override
	public InteractionResult use(Level level, Player player, InteractionHand hand) {
		if (level.isClientSide()) {
			ClientBridge.onUse(player);
		}
		return InteractionResult.SUCCESS;
	}
}
