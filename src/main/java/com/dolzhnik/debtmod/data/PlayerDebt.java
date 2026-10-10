package com.dolzhnik.debtmod.data;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;
import net.minecraft.util.math.random.Random;

import java.util.HashSet;
import java.util.Set;

/** Скрытый долговой счёт одного игрока. */
public class PlayerDebt {
    public static final int MAX_MINED = 8000;

    public double debt;
    /** Позиции блоков, которые игрок вырыл (для «зарастания» шахт и гаснущих факелов). */
    public final LongArrayList mined = new LongArrayList();
    /** Предметы, которые игрок добыл. Именно их мир заберёт из сундуков. */
    public final Set<String> minedItems = new HashSet<>();

    public void addMined(long pos, Random rnd) {
        if (mined.size() < MAX_MINED) mined.add(pos);
        else mined.set(rnd.nextInt(mined.size()), pos);
    }

    public NbtCompound toNbt() {
        NbtCompound n = new NbtCompound();
        n.putDouble("debt", debt);
        n.putLongArray("mined", mined.toLongArray());
        NbtList items = new NbtList();
        for (String s : minedItems) items.add(NbtString.of(s));
        n.put("items", items);
        return n;
    }

    public static PlayerDebt fromNbt(NbtCompound n) {
        PlayerDebt d = new PlayerDebt();
        d.debt = n.getDouble("debt");
        for (long l : n.getLongArray("mined")) d.mined.add(l);
        NbtList items = n.getList("items", NbtElement.STRING_TYPE);
        for (int i = 0; i < items.size(); i++) d.minedItems.add(items.getString(i));
        return d;
    }
}
