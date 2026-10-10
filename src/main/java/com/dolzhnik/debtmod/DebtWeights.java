package com.dolzhnik.debtmod;

import net.minecraft.block.BlockState;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/** Сколько «весит» каждое действие. Руда тяжелее земли, редкий моб тяжелее обычного. */
public final class DebtWeights {
    private DebtWeights() {}

    public static double forBlock(BlockState s, World w, BlockPos pos) {
        if (s.isAir()) return 0;
        float h = s.getHardness(w, pos);
        if (h < 0) return 0;
        String path = Registries.BLOCK.getId(s.getBlock()).getPath();
        if (path.contains("ancient_debris")) return 40;
        if (path.equals("spawner")) return 60;
        if (path.contains("diamond_ore") || path.contains("emerald_ore")) return 25;
        if (path.contains("gold_ore")) return 12;
        if (path.contains("iron_ore")) return 8;
        if (path.contains("lapis_ore") || path.contains("redstone_ore")) return 6;
        if (path.contains("coal_ore") || path.contains("copper_ore")) return 4;
        if (path.contains("quartz_ore")) return 3;
        if (s.isIn(BlockTags.LOGS)) return 6;
        if (s.isIn(BlockTags.LEAVES)) return 0.3;
        if (h == 0) return 0.2;
        return 0.5 + h;
    }

    public static double forEntity(LivingEntity e) {
        String id = Registries.ENTITY_TYPE.getId(e.getType()).getPath();
        return switch (id) {
            case "ender_dragon" -> 500;
            case "wither" -> 400;
            case "warden" -> 300;
            case "elder_guardian" -> 80;
            case "villager", "wandering_trader" -> 40;
            case "iron_golem" -> 30;
            case "evoker", "ravager" -> 20;
            case "enderman" -> 15;
            case "wither_skeleton" -> 12;
            default -> e.getMaxHealth() * (e instanceof HostileEntity ? 0.5 : 1.2);
        };
    }
}
