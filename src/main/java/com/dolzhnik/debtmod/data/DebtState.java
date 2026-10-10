package com.dolzhnik.debtmod.data;

import it.unimi.dsi.fastutil.longs.Long2DoubleMap;
import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.PersistentState;
import net.minecraft.world.PersistentStateManager;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Всё состояние мода, сохраняется вместе с миром. */
public class DebtState extends PersistentState {
    public final Map<UUID, PlayerDebt> players = new HashMap<>();
    /** Долг по чанкам: где копали больше всего, оттуда мир забирает охотнее. */
    public final Long2DoubleOpenHashMap chunkDebt = new Long2DoubleOpenHashMap();
    /** Очередь «чёрных стволов»: позиция срубленного дерева и игровое время появления. */
    public final LongArrayList trunkPos = new LongArrayList();
    public final LongArrayList trunkDue = new LongArrayList();

    public PlayerDebt get(UUID id) {
        return players.computeIfAbsent(id, k -> new PlayerDebt());
    }

    @Override
    public NbtCompound writeNbt(NbtCompound nbt) {
        NbtList list = new NbtList();
        for (Map.Entry<UUID, PlayerDebt> e : players.entrySet()) {
            NbtCompound c = e.getValue().toNbt();
            c.putUuid("id", e.getKey());
            list.add(c);
        }
        nbt.put("players", list);

        NbtList ch = new NbtList();
        for (Long2DoubleMap.Entry e : chunkDebt.long2DoubleEntrySet()) {
            NbtCompound c = new NbtCompound();
            c.putLong("k", e.getLongKey());
            c.putDouble("v", e.getDoubleValue());
            ch.add(c);
        }
        nbt.put("chunks", ch);
        nbt.putLongArray("trunkPos", trunkPos.toLongArray());
        nbt.putLongArray("trunkDue", trunkDue.toLongArray());
        return nbt;
    }

    public static DebtState fromNbt(NbtCompound nbt) {
        DebtState s = new DebtState();
        NbtList list = nbt.getList("players", NbtElement.COMPOUND_TYPE);
        for (int i = 0; i < list.size(); i++) {
            NbtCompound c = list.getCompound(i);
            s.players.put(c.getUuid("id"), PlayerDebt.fromNbt(c));
        }
        NbtList ch = nbt.getList("chunks", NbtElement.COMPOUND_TYPE);
        for (int i = 0; i < ch.size(); i++) {
            NbtCompound c = ch.getCompound(i);
            s.chunkDebt.put(c.getLong("k"), c.getDouble("v"));
        }
        for (long l : nbt.getLongArray("trunkPos")) s.trunkPos.add(l);
        for (long l : nbt.getLongArray("trunkDue")) s.trunkDue.add(l);
        return s;
    }

    public static DebtState get(MinecraftServer server) {
        PersistentStateManager m = server.getOverworld().getPersistentStateManager();
        return m.getOrCreate(DebtState::fromNbt, DebtState::new, "dolzhnik_debt");
    }
}
