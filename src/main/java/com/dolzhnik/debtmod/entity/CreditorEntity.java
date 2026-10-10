package com.dolzhnik.debtmod.entity;

import com.dolzhnik.debtmod.DebtManager;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.damage.DamageTypes;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.PathAwareEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

/**
 * Кредитор. Тихая чёрная фигура.
 * Пока на него смотришь, он стоит неподвижно. Стоит отвернуться, он идёт к тебе.
 * Убить его нельзя.
 */
public class CreditorEntity extends PathAwareEntity {
    private int stare;
    private int lifetime;

    public CreditorEntity(EntityType<? extends CreditorEntity> type, World world) {
        super(type, world);
        this.setSilent(true);
        this.setPersistent();
        this.experiencePoints = 0;
    }

    public static DefaultAttributeContainer.Builder createCreditorAttributes() {
        return PathAwareEntity.createMobAttributes()
                .add(EntityAttributes.GENERIC_MAX_HEALTH, 20.0)
                .add(EntityAttributes.GENERIC_MOVEMENT_SPEED, 0.3)
                .add(EntityAttributes.GENERIC_FOLLOW_RANGE, 64.0);
    }

    @Override
    public void tick() {
        super.tick();
        if (getWorld().isClient) return;
        ServerWorld w = (ServerWorld) getWorld();

        ServerPlayerEntity target = nearestVictim(w);
        if (target == null || ++lifetime > 6000) { discard(); return; }

        int stage = DebtManager.stageOf(target);
        if (stage < 3) { discard(); return; } // долг погашен, уходит

        double dist = Math.sqrt(squaredDistanceTo(target));
        boolean watched = isWatchedBy(target);
        getLookControl().lookAt(target, 30f, 30f);

        if (watched) {
            getNavigation().stop();
            setVelocity(0, getVelocity().y, 0);
            if (stage == 3) {
                if (dist < 6) { vanishWithScare(target); return; }
                if (++stare > 100) { discard(); return; }
            }
            return;
        }

        if (age % 4 == 0) getNavigation().startMovingTo(target, stage >= 4 ? 1.7 : 1.1);

        if (stage == 3 && dist < 4) {
            getNavigation().stop(); // просто стоит за спиной и ждёт, когда обернёшься
        }
        if (stage >= 4 && dist < 1.8) {
            DebtManager.collect(target);
            discard();
        }
    }

    private void vanishWithScare(ServerPlayerEntity p) {
        DebtManager.playTo(p, SoundEvents.ENTITY_ENDERMAN_STARE, p.getX(), p.getY(), p.getZ(), 1.0f, 0.5f);
        p.addStatusEffect(new StatusEffectInstance(StatusEffects.DARKNESS, 100, 0, false, false));
        discard();
    }

    private boolean isWatchedBy(ServerPlayerEntity p) {
        Vec3d look = p.getRotationVec(1.0f);
        Vec3d to = getEyePos().subtract(p.getEyePos()).normalize();
        return look.dotProduct(to) > 0.55 && p.canSee(this);
    }

    private ServerPlayerEntity nearestVictim(ServerWorld w) {
        ServerPlayerEntity best = null;
        double bd = 100 * 100;
        for (ServerPlayerEntity p : w.getPlayers()) {
            if (p.isCreative() || p.isSpectator()) continue;
            double d = squaredDistanceTo(p);
            if (d < bd) { bd = d; best = p; }
        }
        return best;
    }

    @Override
    public boolean damage(DamageSource source, float amount) {
        return source.isOf(DamageTypes.GENERIC_KILL) && super.damage(source, amount);
    }

    @Override public boolean isFireImmune() { return true; }
    @Override public boolean canHaveStatusEffect(StatusEffectInstance effect) { return false; }
}
