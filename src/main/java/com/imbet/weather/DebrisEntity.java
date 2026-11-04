package com.imbet.weather;

import com.imbet.Config;
import com.imbet.registry.ModContent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import weather2.weathersystem.storm.StormObject;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A single piece of the tornado's airborne debris: a real, server-authoritative entity rather than a
 * Weather2 block particle.
 *
 * <p>It exists because the debris physics this mod wanted - the material decides how the air holds it,
 * and it is carried at a fraction of the wind rather than at the wind - needs the block it is made of
 * to be known for its whole life. Weather2's grabbed-block particle uses the state once to pick a
 * sprite and then throws it away, so the material can only be recovered by patching it back; an entity
 * can simply own it.</p>
 *
 * <p>Being an entity also makes the debris a fact the server agrees on rather than a client drawing: it
 * can be tracked, saved, capped, and it can do the one thing a particle cannot - land, shatter, and
 * hurt what it hits.</p>
 *
 * <p>The motion is the same model the particle hook used, so the look carries over: quadratic drag by
 * material ({@link DebrisPhysics}), a wind clamp of {@code [min, max]} of the sampled local wind, and
 * gravity. What is new here is that the physics runs on the server, once, instead of on every client.</p>
 */
public class DebrisEntity extends Entity {
    private static final EntityDataAccessor<Integer> DATA_STATE =
            SynchedEntityData.defineId(DebrisEntity.class, EntityDataSerializers.INT);

    /**
     * How many debris entities are alive per level. Kept here rather than asked of the world, because a
     * full entity scan per spawn is exactly the cost this cap exists to avoid. Weak keys so a level that
     * is unloaded does not leak.
     */
    private static final Map<Level, AtomicInteger> ACTIVE = Collections.synchronizedMap(new WeakHashMap<>());

    private int ageTicks;
    private boolean counted;
    private boolean impacted;

    public DebrisEntity(EntityType<? extends DebrisEntity> type, Level level) {
        super(type, level);
    }

    /**
     * Spawns one piece of debris, or returns null when it would exceed the active cap.
     *
     * @param storm the storm that ripped it, used to shape its launch, or null to sample the world
     */
    public static DebrisEntity spawn(ServerLevel level, StormObject storm, BlockPos pos, BlockState state) {
        if (level == null || pos == null || state == null || state.isAir()) {
            return null;
        }
        AtomicInteger active = ACTIVE.computeIfAbsent(level, ignored -> new AtomicInteger());
        int cap = Config.DEBRIS_ENTITY_MAX_ACTIVE.get();
        if (cap > 0 && active.get() >= cap) {
            // Over budget: the block still breaks, but as dust rather than a tracked entity.
            level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, state),
                    pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D, 6, 0.3D, 0.3D, 0.3D, 0.05D);
            return null;
        }

        Vec3 center = Vec3.atCenterOf(pos);
        DebrisEntity debris = new DebrisEntity(ModContent.DEBRIS_ENTITY.get(), level);
        debris.setBlockState(state);
        debris.moveTo(center.x, center.y, center.z,
                level.random.nextFloat() * 360.0F, level.random.nextFloat() * 360.0F);
        debris.setDeltaMovement(launchVelocity(storm, level, center));
        debris.counted = true;
        active.incrementAndGet();
        level.addFreshEntity(debris);
        return debris;
    }

    /** How a ripped block leaves the funnel: a fraction of the local wind, thrown outward and up. */
    private static Vec3 launchVelocity(StormObject storm, ServerLevel level, Vec3 pos) {
        TornadoWindField.WindSample wind = storm != null
                ? TornadoWindField.sample(storm, level, pos)
                : TornadoWindField.sampleStrongest(level, pos);
        Vec3 flat = new Vec3(wind.mphVector().x, 0.0D, wind.mphVector().z);
        if (flat.lengthSqr() > 1.0E-6D) {
            flat = flat.normalize();
        }
        double speed = wind.speedMph() * DebrisPhysics.BLOCKS_PER_TICK_PER_MPH
                * Config.DEBRIS_ENTITY_LAUNCH_FRACTION.get();
        return flat.scale(speed).add(0.0D, Config.DEBRIS_ENTITY_LAUNCH_LIFT.get(), 0.0D);
    }

    public BlockState blockState() {
        return Block.stateById(entityData.get(DATA_STATE));
    }

    private void setBlockState(BlockState state) {
        entityData.set(DATA_STATE, Block.getId(state));
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(DATA_STATE, 0);
    }

    @Override
    public void tick() {
        super.tick();
        if (!(level() instanceof ServerLevel level)) {
            return;
        }
        BlockState state = blockState();
        ageTicks++;
        int lifetimeTicks = Math.max(20, (int) Math.round(Config.DEBRIS_ENTITY_LIFETIME_SECONDS.get() * 20.0D));
        if (ageTicks > lifetimeTicks) {
            shatter(level, state);
            return;
        }

        Vec3 velocity = getDeltaMovement();

        // Quadratic drag first, on the speed it already has, then gravity - so a light block is slowed
        // before it falls and a heavy one drops nearly ballistically.
        double drag = DebrisPhysics.dragCoefficient(state) * Config.DEBRIS_ENTITY_DRAG_STRENGTH.get();
        double speed = velocity.length();
        if (drag > 0.0D && speed > 1.0E-6D) {
            velocity = velocity.scale(Math.max(0.0D, 1.0D - drag * speed));
        }
        velocity = velocity.add(0.0D, -Config.DEBRIS_ENTITY_GRAVITY.get(), 0.0D);

        // Couple toward a fraction of the local wind, so debris inside the circulation is carried and
        // everything outside it falls freely. The clamp is the same band the particle path used.
        TornadoWindField.WindSample wind = TornadoWindField.sampleStrongest(level, position());
        double windTicks = wind.speedMph() * DebrisPhysics.BLOCKS_PER_TICK_PER_MPH;
        if (windTicks > DebrisPhysics.CALM_WIND_BLOCKS_PER_TICK) {
            Vec3 flat = new Vec3(wind.mphVector().x, 0.0D, wind.mphVector().z);
            if (flat.lengthSqr() > 1.0E-6D) {
                flat = flat.normalize();
                double min = windTicks * Config.DEBRIS_WIND_MIN_FRACTION.get();
                double max = Mth.clamp(windTicks * Config.DEBRIS_WIND_MAX_FRACTION.get(), min, windTicks);
                double horizontal = Math.sqrt(velocity.x * velocity.x + velocity.z * velocity.z);
                double target = Mth.clamp(horizontal, min, max);
                double coupling = Mth.clamp(Config.DEBRIS_ENTITY_WIND_COUPLING.get(), 0.0D, 1.0D);
                velocity = new Vec3(
                        Mth.lerp(coupling, velocity.x, flat.x * target),
                        velocity.y,
                        Mth.lerp(coupling, velocity.z, flat.z * target));
                velocity = velocity.add(0.0D,
                        Config.DEBRIS_ENTITY_LIFT.get() * Mth.clamp(wind.speedMph() / 150.0D, 0.0D, 1.0D), 0.0D);
            }
        }

        setDeltaMovement(velocity);
        move(MoverType.SELF, velocity);
        if (velocity.horizontalDistanceSqr() > 0.01D || Math.abs(velocity.y) > 0.02D) {
            setYRot(getYRot() + (float) (Config.DEBRIS_ENTITY_SPIN_DEGREES_PER_TICK.get()
                    * (level.random.nextBoolean() ? 1.0F : -1.0F)));
            setXRot(getXRot() + (float) (Config.DEBRIS_ENTITY_SPIN_DEGREES_PER_TICK.get() * 0.6F));
        }

        if (Config.DEBRIS_ENTITY_IMPACT_DAMAGE.get()) {
            impactEntities(level);
        }

        if (onGround()
                && velocity.horizontalDistanceSqr() < 0.02D
                && Math.abs(velocity.y) < 0.08D) {
            settle(level, state);
        }
    }

    /** Once it has come to rest, either put the block down or leave a puff of dust. */
    private void settle(ServerLevel level, BlockState state) {
        if (Config.DEBRIS_ENTITY_SETTLE_INTO_BLOCKS.get()) {
            BlockPos target = blockPosition();
            if (level.getBlockState(target).canBeReplaced()) {
                level.setBlock(target, state, 3);
                discard();
                return;
            }
        }
        shatter(level, state);
    }

    private void shatter(ServerLevel level, BlockState state) {
        if (state != null && !state.isAir()) {
            level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, state),
                    getX(), getY(), getZ(), 8, 0.2D, 0.2D, 0.2D, 0.08D);
        }
        discard();
    }

    /** A fast piece hurts whatever it passes through, once, so it is a hit rather than a grinding tick. */
    private void impactEntities(ServerLevel level) {
        if (impacted) {
            return;
        }
        double speed = getDeltaMovement().length();
        double minSpeed = Config.DEBRIS_ENTITY_DAMAGE_MIN_SPEED_BLOCKS_PER_TICK.get();
        if (speed < minSpeed) {
            return;
        }
        float damage = (float) (Config.DEBRIS_ENTITY_DAMAGE_BASE.get() * (speed - minSpeed));
        if (damage <= 0.0F) {
            return;
        }
        AABB box = getBoundingBox().inflate(0.2D);
        for (LivingEntity living : level.getEntitiesOfClass(LivingEntity.class, box)) {
            if (living.hurt(level.damageSources().fallingBlock(this), damage)) {
                impacted = true;
                return;
            }
        }
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        ageTicks = tag.getInt("Age");
        if (tag.contains("State")) {
            setBlockState(Block.stateById(tag.getInt("State")));
        }
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        tag.putInt("Age", ageTicks);
        tag.putInt("State", Block.getId(blockState()));
    }

    @Override
    public void remove(RemovalReason reason) {
        super.remove(reason);
        if (counted && level() instanceof Level level) {
            AtomicInteger active = ACTIVE.get(level);
            if (active != null) {
                active.decrementAndGet();
            }
            counted = false;
        }
    }
}
