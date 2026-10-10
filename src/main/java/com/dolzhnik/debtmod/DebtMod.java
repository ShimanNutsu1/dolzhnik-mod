package com.dolzhnik.debtmod;

import com.dolzhnik.debtmod.block.BlackTrunkBlock;
import com.dolzhnik.debtmod.data.DebtState;
import com.dolzhnik.debtmod.entity.CreditorEntity;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.item.v1.FabricItemSettings;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricEntityTypeBuilder;
import net.minecraft.block.Block;
import net.minecraft.block.SaplingBlock;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.entity.EntityDimensions;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Identifier;

public class DebtMod implements ModInitializer {
    public static final String MOD_ID = "dolzhnik";

    public static final Block BLACK_TRUNK = Registry.register(Registries.BLOCK, new Identifier(MOD_ID, "black_trunk"), new BlackTrunkBlock());
    public static final Item BLACK_TRUNK_ITEM = Registry.register(Registries.ITEM, new Identifier(MOD_ID, "black_trunk"),
            new BlockItem(BLACK_TRUNK, new FabricItemSettings()));

    public static final EntityType<CreditorEntity> CREDITOR = Registry.register(Registries.ENTITY_TYPE, new Identifier(MOD_ID, "creditor"),
            FabricEntityTypeBuilder.create(SpawnGroup.MONSTER, CreditorEntity::new)
                    .dimensions(EntityDimensions.fixed(0.6f, 2.1f)).build());

    @Override
    public void onInitialize() {
        FabricDefaultAttributeRegistry.register(CREDITOR, CreditorEntity.createCreditorAttributes());
        ItemGroupEvents.modifyEntriesEvent(ItemGroups.NATURAL).register(e -> e.add(BLACK_TRUNK_ITEM));

        PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, be) -> {
            if (world.isClient || !(player instanceof ServerPlayerEntity sp)) return;
            DebtManager.onBlockBroken(sp, (ServerWorld) world, pos, state, be);
        });

        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (source.getAttacker() instanceof ServerPlayerEntity sp) DebtManager.onKill(sp, entity);
        });

        // Огонь добавляет долг; посадка саженцев немного его гасит.
        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (world.isClient || !(player instanceof ServerPlayerEntity sp)) return ActionResult.PASS;
            ItemStack st = player.getStackInHand(hand);
            if (st.getItem() instanceof BlockItem bi && bi.getBlock() instanceof SaplingBlock) {
                DebtManager.addDebt(sp, -2.5);
            } else if (st.isOf(Items.FLINT_AND_STEEL) || st.isOf(Items.FIRE_CHARGE) || st.isOf(Items.LAVA_BUCKET)) {
                DebtManager.addDebt(sp, 5);
            }
            return ActionResult.PASS;
        });

        ServerTickEvents.END_SERVER_TICK.register(DebtManager::tick);

        // Команды для операторов: /debt get|set|add <игрок> [значение]. Нужны для тестов.
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(CommandManager.literal("debt")
                        .requires(src -> src.hasPermissionLevel(2))
                        .then(CommandManager.literal("get")
                                .then(CommandManager.argument("player", EntityArgumentType.player()).executes(ctx -> {
                                    ServerPlayerEntity t = EntityArgumentType.getPlayer(ctx, "player");
                                    double v = DebtState.get(ctx.getSource().getServer()).get(t.getUuid()).debt;
                                    ctx.getSource().sendFeedback(() -> Text.literal(t.getName().getString()
                                            + ": долг " + (int) v + ", стадия " + DebtManager.stage(v)), false);
                                    return (int) v;
                                })))
                        .then(CommandManager.literal("set")
                                .then(CommandManager.argument("player", EntityArgumentType.player())
                                        .then(CommandManager.argument("value", DoubleArgumentType.doubleArg(0))
                                                .executes(ctx -> modify(ctx, false)))))
                        .then(CommandManager.literal("add")
                                .then(CommandManager.argument("player", EntityArgumentType.player())
                                        .then(CommandManager.argument("value", DoubleArgumentType.doubleArg())
                                                .executes(ctx -> modify(ctx, true)))))));
    }

    private static int modify(CommandContext<ServerCommandSource> ctx, boolean add) throws CommandSyntaxException {
        ServerPlayerEntity t = EntityArgumentType.getPlayer(ctx, "player");
        double val = DoubleArgumentType.getDouble(ctx, "value");
        DebtState st = DebtState.get(ctx.getSource().getServer());
        var d = st.get(t.getUuid());
        d.debt = Math.max(0, add ? d.debt + val : val);
        st.markDirty();
        double now = d.debt;
        ctx.getSource().sendFeedback(() -> Text.literal(t.getName().getString()
                + ": долг " + (int) now + ", стадия " + DebtManager.stage(now)), true);
        return (int) now;
    }
}
