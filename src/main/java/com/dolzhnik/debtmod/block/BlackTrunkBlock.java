package com.dolzhnik.debtmod.block;

import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Material;
import net.minecraft.block.PillarBlock;
import net.minecraft.block.MapColor;
import net.minecraft.sound.BlockSoundGroup;

/** Чёрный ствол без листвы. Нельзя сломать (кроме креатива), взрывы его не берут. */
public class BlackTrunkBlock extends PillarBlock {
    public BlackTrunkBlock() {
        super(AbstractBlock.Settings.of(Material.WOOD, MapColor.BLACK)
                .strength(-1.0f, 3600000.0f)
                .dropsNothing()
                .sounds(BlockSoundGroup.WOOD));
    }
}
