package com.dolzhnik.debtmod;

import com.dolzhnik.debtmod.data.DebtState;
import com.dolzhnik.debtmod.data.PlayerDebt;
import com.dolzhnik.debtmod.entity.CreditorEntity;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.PillarBlock;
import net.minecraft.block.entity.BarrelBlockEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.s2c.play.PlaySoundS2CPacket;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;
import net.minecraft.world.World;
import net.minecraft.world.chunk.WorldChunk;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Сердце мода: учёт долга и все проявления по стадиям. */
public final class DebtManager {
    private DebtManager() {}

    /** Пороги стадий: первые знаки / изъятие / взыскание / расчёт. */
    public static final double[] STAGES = {250, 900, 2500, 5000};
    private static final double DECAY_PER_SECOND = 0.01;

    private static final Map<UUID, Vec3d> LAST_POS = new HashMap<>();
    private static final List<Task> TASKS = new ArrayList<>();
    private record Task(int due, Runnable action) {}

    public static int stage(double debt) {
        int s = 0;
        for (double t : STAGES) if (debt >= t) s++;
        return s;
    }

    public static int stageOf(ServerPlayerEntity p) {
        return stage(DebtState.get(p.getServer()).get(p.getUuid()).debt);
    }

    // ------------------------------------------------------------------ учёт

    public static void onBlockBroken(ServerPlayerEntity p, ServerWorld w, BlockPos pos, BlockState s, BlockEntity be) {
        if (p.isCreative() || w.getRegistryKey() != World.OVERWORLD) return;
        double weight = DebtWeights.forBlock(s, w, pos);
        if (weight <= 0) return;

        DebtState st = DebtState.get(w.getServer());
        PlayerDebt d = st.get(p.getUuid());
        d.debt += weight;

        boolean isLog = s.isIn(BlockTags.LOGS);
        if (weight >= 1) {
            st.chunkDebt.addTo(new ChunkPos(pos).toLong(), weight);
            if (!isLog) d.addMined(pos.asLong(), w.random);
            for (ItemStack stack : Block.getDroppedStacks(s, w, pos, be, p, p.getMainHandStack())) {
                if (!stack.isEmpty()) d.minedItems.add(Registries.ITEM.getId(stack.getItem()).toString());
            }
        }
        // Срубил дерево, и позже на его месте вырастет чёрный ствол.
        if (isLog && stage(d.debt) >= 2 && w.random.nextFloat() < 0.55f) {
            st.trunkPos.add(pos.asLong());
            st.trunkDue.add(w.getTime() + 400 + w.random.nextInt(1600));
        }
        st.markDirty();
    }

    public static void onKill(ServerPlayerEntity p, LivingEntity victim) {
        if (p.isCreative() || victim instanceof PlayerEntity) return;
        DebtState st = DebtState.get(p.getServer());
        st.get(p.getUuid()).debt += DebtWeights.forEntity(victim);
        st.markDirty();
    }

    public static void addDebt(ServerPlayerEntity p, double amount) {
        if (p.isCreative()) return;
        DebtState st = DebtState.get(p.getServer());
        PlayerDebt d = st.get(p.getUuid());
        d.debt = Math.max(0, d.debt + amount);
        st.markDirty();
    }

    // ------------------------------------------------------------------ тик

    public static void tick(MinecraftServer server) {
        int t = server.getTicks();
        runTasks(t);
        DebtState st = DebtState.get(server);
        if (t % 20 == 0) processTrunks(server, st);

        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            if (p.isCreative() || p.isSpectator()) continue;
            PlayerDebt d = st.players.get(p.getUuid());
            if (d == null || d.debt <= 0) continue;

            if (t % 20 == 0) { d.debt = Math.max(0, d.debt - DECAY_PER_SECOND); st.markDirty(); }
            int stage = stage(d.debt);
            if (stage < 1) continue;
            if (p.getServerWorld().getRegistryKey() != World.OVERWORLD) continue;

            footsteps(p, stage, t);
            if (t % 40 == 7) regrow(p, d, stage);
            if (stage >= 2) {
                if (t % 60 == 21) snuffTorches(p, d);
                if (t % 200 == 33) stealFromChests(p, d, st, stage);
                if (t % 300 == 47 && p.getRandom().nextFloat() < 0.4f) echo(p, d);
            }
            if (stage >= 3 && t % 100 == 61) ravageHome(p, stage, d.debt);
            if (stage >= 3 && t % 200 == 99) {
                if (p.getRandom().nextFloat() < (stage >= 4 ? 0.6f : 0.35f)) trySpawnCreditor(p);
            }
        }
    }

    private static void runTasks(int now) {
        for (int i = TASKS.size() - 1; i >= 0; i--) {
            Task t = TASKS.get(i);
            if (t.due() <= now) { TASKS.remove(i); t.action().run(); }
        }
    }

    // ------------------------------------------------------------------ стадия 1

    /** Шаги, которые раздаются чуть позже твоих собственных. */
    private static void footsteps(ServerPlayerEntity p, int stage, int t) {
        if (t % 10 != 0) return;
        Vec3d now = p.getPos();
        Vec3d prev = LAST_POS.put(p.getUuid(), now);
        if (prev == null || !p.isOnGround()) return;

        boolean moving = now.squaredDistanceTo(prev) > 0.8 * 0.8 && !p.isSneaking();
        float chance = moving ? 0.04f + 0.03f * stage : (stage >= 3 ? 0.015f : 0f);
        if (p.getRandom().nextFloat() > chance) return;

        int delay = moving ? 4 + p.getRandom().nextInt(3) : 0;
        TASKS.add(new Task(t + delay, () -> {
            if (p.isRemoved()) return;
            ServerWorld w = p.getServerWorld();
            Vec3d look = p.getRotationVec(1f).multiply(1, 0, 1);
            if (look.lengthSquared() < 1e-4) return;
            Vec3d pos = p.getPos().subtract(look.normalize().multiply(1.6));
            BlockState below = w.getBlockState(BlockPos.ofFloored(p.getPos()).down());
            playTo(p, below.getSoundGroup().getStepSound(), pos.x, pos.y, pos.z, 0.8f, 0.9f + p.getRandom().nextFloat() * 0.2f);
        }));
    }

    /** Ночью шахты, которые ты вырыл, зарастают. Появляются блоки, которых ты не ставил. */
    private static void regrow(ServerPlayerEntity p, PlayerDebt d, int stage) {
        ServerWorld w = p.getServerWorld();
        if (!w.isNight() || d.mined.isEmpty()) return;
        float chance = 0.25f + 0.15f * stage;
        for (int k = 0; k < 6; k++) {
            if (w.random.nextFloat() > chance) continue;
            int i = w.random.nextInt(d.mined.size());
            BlockPos pos = BlockPos.fromLong(d.mined.getLong(i));
            double dist2 = Vec3d.ofCenter(pos).squaredDistanceTo(p.getPos());
            if (dist2 < 25 || dist2 > 40 * 40) continue;
            if (!w.isChunkLoaded(pos) || !w.getBlockState(pos).isAir()) continue;
            if (looking(p, Vec3d.ofCenter(pos), 0.35)) continue; // не на глазах
            if (!w.getOtherEntities(null, new Box(pos)).isEmpty()) continue;

            BlockState place = pos.getY() < 0 ? Blocks.DEEPSLATE.getDefaultState() : Blocks.STONE.getDefaultState();
            for (Direction dir : Direction.values()) {
                BlockPos np = pos.offset(dir);
                BlockState n = w.getBlockState(np);
                String path = Registries.BLOCK.getId(n.getBlock()).getPath();
                if (n.isSolidBlock(w, np) && !n.hasBlockEntity() && !n.isIn(BlockTags.LEAVES)
                        && !path.endsWith("_ore") && !path.contains("debris") && !path.contains("sand") && !path.contains("gravel")) {
                    place = n;
                    break;
                }
            }
            w.setBlockState(pos, place);
            d.mined.removeLong(i);
            return;
        }
    }

    // ------------------------------------------------------------------ стадия 2

    /** Из сундуков пропадает то, что ты добыл. Особенно там, где копал больше всего. */
    private static void stealFromChests(ServerPlayerEntity p, PlayerDebt d, DebtState st, int stage) {
        if (d.minedItems.isEmpty() && stage < 3) return;
        ServerWorld w = p.getServerWorld();
        ChunkPos cp = p.getChunkPos();
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                WorldChunk ch = w.getChunkManager().getWorldChunk(cp.x + dx, cp.z + dz);
                if (ch == null) continue;
                double cd = st.chunkDebt.get(ChunkPos.toLong(cp.x + dx, cp.z + dz));
                if (cd < 80) continue;
                float chance = (float) Math.min(0.3, cd / 5000.0);
                if (stage >= 3) chance = Math.max(chance, stage >= 4 ? 0.6f : 0.35f);
                for (BlockEntity be : new ArrayList<>(ch.getBlockEntities().values())) {
                    if (!(be instanceof ChestBlockEntity) && !(be instanceof BarrelBlockEntity)) continue;
                    if (w.random.nextFloat() > chance) continue;
                    Vec3d c = Vec3d.ofCenter(be.getPos());
                    if (c.squaredDistanceTo(p.getPos()) < 64 && looking(p, c, 0.4)) continue; // не на глазах

                    Inventory inv = (Inventory) be;
                    if (stage >= 3) {
                        // Сундук ломается. На 4 стадии содержимое исчезает вместе с ним.
                        if (stage >= 4) inv.clear();
                        w.breakBlock(be.getPos(), false);
                        continue;
                    }
                    List<Integer> slots = new ArrayList<>();
                    for (int i = 0; i < inv.size(); i++) {
                        ItemStack s = inv.getStack(i);
                        if (!s.isEmpty() && d.minedItems.contains(Registries.ITEM.getId(s.getItem()).toString())) slots.add(i);
                    }
                    if (slots.isEmpty()) continue;
                    int slot = slots.get(w.random.nextInt(slots.size()));
                    ItemStack s = inv.getStack(slot);
                    if (s.getCount() > 1 && w.random.nextBoolean()) s.decrement(s.getCount() / 2);
                    else inv.setStack(slot, ItemStack.EMPTY);
                    inv.markDirty();
                }
            }
        }
    }

    /** Факелы в вырытых тобой тоннелях гаснут сами. */
    private static void snuffTorches(ServerPlayerEntity p, PlayerDebt d) {
        ServerWorld w = p.getServerWorld();
        Vec3d pp = p.getPos();
        for (int i = 0; i < d.mined.size(); i++) {
            BlockPos pos = BlockPos.fromLong(d.mined.getLong(i));
            if (Vec3d.ofCenter(pos).squaredDistanceTo(pp) > 32 * 32) continue;
            if (!w.isChunkLoaded(pos)) continue;
            BlockState s = w.getBlockState(pos);
            if (!(s.isOf(Blocks.TORCH) || s.isOf(Blocks.WALL_TORCH) || s.isOf(Blocks.SOUL_TORCH) || s.isOf(Blocks.SOUL_WALL_TORCH))) continue;
            if (w.random.nextFloat() > 0.12f) continue;
            w.setBlockState(pos, Blocks.AIR.getDefaultState());
            w.playSound(null, pos, SoundEvents.BLOCK_FIRE_EXTINGUISH, SoundCategory.BLOCKS, 0.4f, 2.0f);
            w.spawnParticles(ParticleTypes.SMOKE, pos.getX() + 0.5, pos.getY() + 0.6, pos.getZ() + 0.5, 4, 0.1, 0.1, 0.1, 0.01);
        }
    }

    /** Где-то вдали кто-то «копает» твои старые тоннели. */
    private static void echo(ServerPlayerEntity p, PlayerDebt d) {
        if (d.mined.isEmpty()) return;
        ServerWorld w = p.getServerWorld();
        for (int k = 0; k < 10; k++) {
            BlockPos pos = BlockPos.fromLong(d.mined.getLong(w.random.nextInt(d.mined.size())));
            double dist2 = Vec3d.ofCenter(pos).squaredDistanceTo(p.getPos());
            if (dist2 < 64 || dist2 > 40 * 40) continue;
            playTo(p, SoundEvents.BLOCK_STONE_BREAK, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 1.0f, 0.6f + w.random.nextFloat() * 0.2f);
            return;
        }
    }

    /** Чёрные стволы вырастают на месте срубленных деревьев, пока никто не смотрит. */
    private static void processTrunks(MinecraftServer server, DebtState st) {
        if (st.trunkPos.isEmpty()) return;
        ServerWorld w = server.getOverworld();
        long now = w.getTime();
        for (int i = st.trunkPos.size() - 1; i >= 0; i--) {
            if (st.trunkDue.getLong(i) > now) continue;
            BlockPos pos = BlockPos.fromLong(st.trunkPos.getLong(i));
            if (!w.isChunkLoaded(pos)) continue; // дождётся загрузки чанка

            Vec3d c = Vec3d.ofCenter(pos);
            boolean watched = false;
            for (ServerPlayerEntity p : w.getPlayers()) {
                if (c.squaredDistanceTo(p.getPos()) < 40 * 40 && looking(p, c, 0.5)) { watched = true; break; }
            }
            if (watched) { st.trunkDue.set(i, now + 60); continue; }

            growTrunk(w, pos);
            st.trunkPos.removeLong(i);
            st.trunkDue.removeLong(i);
            st.markDirty();
        }
    }

    private static void growTrunk(ServerWorld w, BlockPos pos) {
        BlockPos p = pos;
        if (!w.getBlockState(p).isAir()) p = p.up();
        if (!w.getBlockState(p).isAir()) return;
        for (int i = 0; i < 10 && w.getBlockState(p.down()).isAir(); i++) p = p.down();
        BlockPos ground = p.down();
        if (!w.getBlockState(ground).isSolidBlock(w, ground)) return;
        int h = 3 + w.random.nextInt(4);
        BlockState trunk = DebtMod.BLACK_TRUNK.getDefaultState().with(PillarBlock.AXIS, Direction.Axis.Y);
        for (int i = 0; i < h; i++) {
            if (!w.getBlockState(p.up(i)).isAir()) break;
            w.setBlockState(p.up(i), trunk);
        }
    }

    // ------------------------------------------------------------------ стадия 3 и 4

    /**
     * Дом = окрестности точки возрождения (кровати). Мир ломает рукотворные блоки.
     * Чем больше долг, тем больше блоков за раз и тем шире радиус.
     * На 3 стадии он ломает, пока ты не смотришь. На 4 уже не прячется.
     */
    private static void ravageHome(ServerPlayerEntity p, int stage, double debt) {
        BlockPos home = p.getSpawnPointPosition();
        if (home == null) return;
        ServerWorld w = p.getServerWorld();
        if (p.getSpawnPointDimension() != w.getRegistryKey()) return;
        if (!w.isChunkLoaded(home)) return;

        int count = stage >= 4
                ? 4 + (int) ((debt - STAGES[3]) / 500.0)
                : 1 + (int) ((debt - STAGES[2]) / 1000.0);
        count = Math.min(count, 40);
        int radius = stage >= 4 ? 18 : 10;

        for (int k = 0; k < count; k++) {
            for (int tries = 0; tries < 14; tries++) {
                BlockPos pos = home.add(w.random.nextBetween(-radius, radius),
                        w.random.nextBetween(-4, 8), w.random.nextBetween(-radius, radius));
                if (!w.isChunkLoaded(pos)) continue;
                BlockState s = w.getBlockState(pos);
                if (!isHouseBlock(s)) continue;
                if (stage < 4 && looking(p, Vec3d.ofCenter(pos), 0.3)
                        && Vec3d.ofCenter(pos).squaredDistanceTo(p.getPos()) < 48 * 48) continue;
                w.breakBlock(pos, false);
                break;
            }
        }
    }

    /** Рукотворные блоки: то, из чего строят дома. */
    private static boolean isHouseBlock(BlockState s) {
        if (s.isAir()) return false;
        String n = Registries.BLOCK.getId(s.getBlock()).getPath();
        return n.contains("planks") || n.contains("door") || n.contains("glass") || n.contains("wool")
                || n.contains("brick") || n.contains("slab") || n.contains("stairs") || n.contains("fence")
                || n.endsWith("_bed") || n.contains("torch") || n.contains("lantern") || n.contains("table")
                || n.contains("furnace") || n.contains("smoker") || n.equals("chest") || n.equals("trapped_chest")
                || n.equals("barrel") || n.contains("ladder") || n.contains("carpet") || n.contains("sign")
                || n.contains("cobblestone") || n.contains("polished") || n.contains("terracotta")
                || n.contains("concrete") || n.contains("bookshelf") || n.contains("campfire")
                || n.contains("button") || n.contains("pressure_plate") || n.contains("shulker")
                || n.contains("frame") || n.equals("crafting_table") || n.equals("anvil");
    }

    private static void trySpawnCreditor(ServerPlayerEntity p) {
        ServerWorld w = p.getServerWorld();
        Box box = p.getBoundingBox().expand(96);
        if (!w.getEntitiesByType(DebtMod.CREDITOR, box, e -> true).isEmpty()) return;

        boolean surface = w.isSkyVisible(p.getBlockPos());
        for (int i = 0; i < 24; i++) {
            double ang = w.random.nextDouble() * Math.PI * 2;
            double r = 16 + w.random.nextDouble() * 16;
            int x = (int) Math.floor(p.getX() + Math.cos(ang) * r);
            int z = (int) Math.floor(p.getZ() + Math.sin(ang) * r);
            if (!w.isChunkLoaded(new BlockPos(x, p.getBlockY(), z))) continue;
            int startY = surface ? w.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z) : p.getBlockY();

            BlockPos stand = null;
            for (int yy = startY + 3; yy >= startY - 6; yy--) {
                BlockPos b = new BlockPos(x, yy, z);
                if (w.getBlockState(b).isAir() && w.getBlockState(b.up()).isAir()
                        && w.getBlockState(b.down()).isSolidBlock(w, b.down())) { stand = b; break; }
            }
            if (stand == null) continue;
            // Появляется там, где его видно: на краю поля зрения, вдалеке.
            if (!looking(p, Vec3d.ofBottomCenter(stand), 0.55)) continue;

            CreditorEntity e = DebtMod.CREDITOR.create(w);
            if (e == null) return;
            e.refreshPositionAndAngles(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5, w.random.nextFloat() * 360f, 0f);
            w.spawnEntity(e);
            return;
        }
    }

    /** Расчёт: Кредитор забирает вещи и бьёт. Долг при этом НЕ списывается. */
    public static void collect(ServerPlayerEntity p) {
        DebtState st = DebtState.get(p.getServer());
        PlayerDebt d = st.get(p.getUuid());

        List<Integer> all = new ArrayList<>(), preferred = new ArrayList<>();
        for (int i = 0; i < 36; i++) {
            ItemStack s = p.getInventory().getStack(i);
            if (s.isEmpty()) continue;
            all.add(i);
            if (d.minedItems.contains(Registries.ITEM.getId(s.getItem()).toString())) preferred.add(i);
        }
        List<Integer> pool = preferred.size() >= 3 ? preferred : all;
        Collections.shuffle(pool);
        for (int k = 0; k < Math.min(3, pool.size()); k++) p.getInventory().setStack(pool.get(k), ItemStack.EMPTY);

        if (p.getHealth() > 2f) p.damage(p.getServerWorld().getDamageSources().magic(), Math.min(6f, p.getHealth() - 1f));
        p.addStatusEffect(new StatusEffectInstance(StatusEffects.DARKNESS, 160, 0, false, false));
        playTo(p, SoundEvents.ENTITY_WARDEN_NEARBY_CLOSE, p.getX(), p.getY(), p.getZ(), 1.0f, 0.6f);
        p.sendMessage(Text.literal("Этого мало.").formatted(Formatting.DARK_RED), true);
    }

    // ------------------------------------------------------------------ утилиты

    public static boolean looking(Entity viewer, Vec3d target, double cosThreshold) {
        Vec3d look = viewer.getRotationVec(1f).normalize();
        Vec3d to = target.subtract(viewer.getEyePos()).normalize();
        return look.dotProduct(to) > cosThreshold;
    }

    /** Звук только для одного игрока, из заданной точки. */
    public static void playTo(ServerPlayerEntity p, SoundEvent se, double x, double y, double z, float vol, float pitch) {
        p.networkHandler.sendPacket(new PlaySoundS2CPacket(Registries.SOUND_EVENT.getEntry(se),
                SoundCategory.PLAYERS, x, y, z, vol, pitch, p.getServerWorld().getRandom().nextLong()));
    }
}
