package com.dolzhnik.debtmod.block;

import net.fabricmc.fabric.api.object.builder.v1.block.FabricBlockSettings;
import net.minecraft.block.MapColor;
import net.minecraft.block.PillarBlock;
import net.minecraft.sound.BlockSoundGroup;

/** Чёрный ствол без листвы. Нельзя сломать (кроме креатива), взрывы его не берут. */
public class BlackTrunkBlock extends PillarBlock {
    public BlackTrunkBlock() {
        super(FabricBlockSettings.create()
                .mapColor(MapColor.BLACK)
                .strength(-1.0f, 3600000.0f)
                .dropsNothing()
                .sounds(BlockSoundGroup.WOOD));
    }
}
