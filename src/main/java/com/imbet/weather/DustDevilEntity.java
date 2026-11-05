package com.imbet.weather;

import com.imbet.registry.ModContent;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public class DustDevilEntity extends Entity {
    private static final EntityDataAccessor<Float> DATA_RADIUS = SynchedEntityData.defineId(DustDevilEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> DATA_MAX_WIND_MPH = SynchedEntityData.defineId(DustDevilEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> DATA_MOTION_X = SynchedEntityData.defineId(DustDevilEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> DATA_MOTION_Z = SynchedEntityData.defineId(DustDevilEntity.class, EntityDataSerializers.FLOAT);
    /**
     * How long it has left, and had to begin with.
     *
     * <p>The client draws from these: a vortex forms and decays over its life, and a client that first sees one in
     * the middle of that has to know how far through it is. The remaining count used to live only on the server, so
     * on the client {@link #lifeTicksRemaining} answered the field's default of 240 for ever - which a waterspout read
     * as "in its last twelve seconds" and hovered itself clear of the water for its entire life.
     */
    private static final EntityDataAccessor<Integer> DATA_LIFE_TICKS = SynchedEntityData.defineId(DustDevilEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> DATA_LIFE_TOTAL = SynchedEntityData.defineId(DustDevilEntity.class, EntityDataSerializers.INT);
    /** How often the remaining count is sent; the client runs its own count down in between. */
    private static final int LIFE_SYNC_TICKS = 20;

    private int lifeTicks = 240;
    private int lifeTotal = 240;
    private float radius = 3.0F;
    private float maxWindMph = 38.0F;
    private int clientLifeTicks = -1;
    private int lastSyncedLife = -1;

    public DustDevilEntity(EntityType<? extends DustDevilEntity> type, Level level) {
        super(type, level);
        noPhysics = true;
    }

    protected DustDevilEntity(EntityType<? extends DustDevilEntity> type, Level level, Vec3 position, int lifeTicks, float radius, float maxWindMph) {
        this(type, level);
        setPos(position);
        this.lifeTicks = Math.max(20, lifeTicks);
        this.lifeTotal = this.lifeTicks;
        this.radius = Math.max(1.0F, radius);
        this.maxWindMph = Math.max(0.0F, maxWindMph);
        entityData.set(DATA_RADIUS, this.radius);
        entityData.set(DATA_MAX_WIND_MPH, this.maxWindMph);
        entityData.set(DATA_LIFE_TICKS, this.lifeTicks);
        entityData.set(DATA_LIFE_TOTAL, this.lifeTotal);
    }

    public DustDevilEntity(Level level, Vec3 position, int lifeTicks, float radius, float maxWindMph) {
        this(ModContent.DUST_DEVIL_ENTITY.get(), level, position, lifeTicks, radius, maxWindMph);
    }

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide()) {
            Vec3 syncedMotion = syncedHorizontalMotion();
            if (syncedMotion.lengthSqr() > 1.0E-8D) {
                super.setDeltaMovement(syncedMotion);
            }
            moveWithStormMotion();
            int synced = entityData.get(DATA_LIFE_TICKS);
            if (synced != lastSyncedLife) {
                lastSyncedLife = synced;
                clientLifeTicks = synced;
            } else if (clientLifeTicks > 0) {
                clientLifeTicks--;
            }
            return;
        }
        if (--lifeTicks <= 0 || !(level() instanceof ServerLevel level)) {
            discard();
            return;
        }
        if (tickCount % LIFE_SYNC_TICKS == 0) {
            entityData.set(DATA_LIFE_TICKS, lifeTicks);
        }
        moveWithStormMotion();
        clampToBoundary(level);
        applyWeakSuction(level);
    }

    protected void moveWithStormMotion() {
        Vec3 motion = getDeltaMovement();
        if (!Double.isFinite(motion.x) || !Double.isFinite(motion.y) || !Double.isFinite(motion.z)) {
            return;
        }
        double horizontal = Math.sqrt(motion.x * motion.x + motion.z * motion.z);
        double minSpeed = minimumHorizontalMotion();
        if (horizontal < 1.0E-5D && minSpeed > 0.0D) {
            motion = fallbackHorizontalMotion(minSpeed);
            horizontal = minSpeed;
        } else if (horizontal < minSpeed && minSpeed > 0.0D) {
            double scale = minSpeed / horizontal;
            motion = new Vec3(motion.x * scale, 0.0D, motion.z * scale);
            horizontal = minSpeed;
        }
        if (horizontal < 1.0E-5D) {
            return;
        }
        move(MoverType.SELF, new Vec3(motion.x, 0.0D, motion.z));
        double drag = motionDrag();
        setDeltaMovement(new Vec3(motion.x * drag, 0.0D, motion.z * drag));
    }

    @Override
    public void setDeltaMovement(Vec3 deltaMovement) {
        super.setDeltaMovement(deltaMovement);
        if (!level().isClientSide() && deltaMovement != null) {
            entityData.set(DATA_MOTION_X, finite(deltaMovement.x));
            entityData.set(DATA_MOTION_Z, finite(deltaMovement.z));
        }
    }

    protected void clampToBoundary(ServerLevel level) {
    }

    protected void clampYAboveTerrain(ServerLevel level, double minAboveTerrain, double maxAboveTerrain) {
        BlockPos surface = BlockGrabSafety.safeSurface(level, BlockPos.containing(position()), Heightmap.Types.MOTION_BLOCKING);
        if (surface == null) {
            return;
        }
        double minY = surface.getY() + Math.max(0.0D, minAboveTerrain);
        double maxY = surface.getY() + Math.max(minAboveTerrain, maxAboveTerrain);
        double y = Math.max(minY, Math.min(maxY, getY()));
        if (Math.abs(y - getY()) > 0.001D) {
            setPos(getX(), y, getZ());
        }
    }

    private void applyWeakSuction(ServerLevel level) {
        double range = Math.max(2.0D, radius * suctionRangeMultiplier());
        AABB box = getBoundingBox().inflate(range, verticalSuctionRange(), range);
        for (Entity entity : level.getEntities(this, box, Entity::isAlive)) {
            if (entity instanceof Player player && player.isCreative()) {
                continue;
            }
            Vec3 fromCenter = entity.position().subtract(position());
            double distance = Math.max(0.35D, Math.sqrt(fromCenter.x * fromCenter.x + fromCenter.z * fromCenter.z));
            if (distance > range) {
                continue;
            }
            Vec3 radial = new Vec3(fromCenter.x / distance, 0.0D, fromCenter.z / distance);
            Vec3 tangent = new Vec3(-radial.z, 0.0D, radial.x);
            double falloff = Math.max(0.0D, 1.0D - distance / range);
            double scale = maxWindMph * falloff * horizontalForceScale() * entityForceMultiplier();
            entity.setDeltaMovement(entity.getDeltaMovement().add(tangent.scale(scale).add(radial.scale(-scale * inflowScale())).add(0.0D, scale * liftScale(), 0.0D)));
            entity.hurtMarked = true;
        }
    }

    protected double suctionRangeMultiplier() {
        return 2.7D;
    }

    protected double verticalSuctionRange() {
        return 2.5D;
    }

    protected double entityForceMultiplier() {
        return 1.0D;
    }

    protected double horizontalForceScale() {
        return 0.00045D;
    }

    protected double inflowScale() {
        return 0.35D;
    }

    protected double liftScale() {
        return 0.90D;
    }

    protected double motionDrag() {
        return 0.990D;
    }

    protected double minimumHorizontalMotion() {
        return 0.0D;
    }

    protected Vec3 fallbackHorizontalMotion(double speed) {
        double angle = getId() * 0.61803398875D + tickCount * 0.015D;
        return new Vec3(Math.cos(angle) * speed, 0.0D, Math.sin(angle) * speed);
    }

    public float radius() {
        return entityData.get(DATA_RADIUS);
    }

    public float maxWindMph() {
        return entityData.get(DATA_MAX_WIND_MPH);
    }

    public int lifeTicksRemaining() {
        if (level().isClientSide()) {
            return clientLifeTicks >= 0 ? clientLifeTicks : entityData.get(DATA_LIFE_TICKS);
        }
        return lifeTicks;
    }

    /** How long it had to live when it formed. */
    public int lifeTicksTotal() {
        return level().isClientSide() ? entityData.get(DATA_LIFE_TOTAL) : lifeTotal;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(DATA_RADIUS, 3.0F);
        builder.define(DATA_MAX_WIND_MPH, 38.0F);
        builder.define(DATA_MOTION_X, 0.0F);
        builder.define(DATA_MOTION_Z, 0.0F);
        builder.define(DATA_LIFE_TICKS, 240);
        builder.define(DATA_LIFE_TOTAL, 240);
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag compound) {
        lifeTicks = compound.getInt("LifeTicks");
        // Worlds saved before the total was kept: treat what is left as the whole of it, which at worst replays the
        // forming seconds of a vortex that is already standing.
        lifeTotal = compound.contains("LifeTotal") ? compound.getInt("LifeTotal") : lifeTicks;
        radius = compound.getFloat("Radius");
        maxWindMph = compound.getFloat("MaxWindMph");
        entityData.set(DATA_RADIUS, Math.max(1.0F, radius));
        entityData.set(DATA_MAX_WIND_MPH, Math.max(0.0F, maxWindMph));
        entityData.set(DATA_LIFE_TICKS, lifeTicks);
        entityData.set(DATA_LIFE_TOTAL, lifeTotal);
        setDeltaMovement(new Vec3(compound.getFloat("MotionX"), 0.0D, compound.getFloat("MotionZ")));
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag compound) {
        compound.putInt("LifeTicks", lifeTicks);
        compound.putInt("LifeTotal", lifeTotal);
        compound.putFloat("Radius", radius);
        compound.putFloat("MaxWindMph", maxWindMph);
        Vec3 motion = getDeltaMovement();
        compound.putFloat("MotionX", finite(motion.x));
        compound.putFloat("MotionZ", finite(motion.z));
    }

    private Vec3 syncedHorizontalMotion() {
        return new Vec3(entityData.get(DATA_MOTION_X), 0.0D, entityData.get(DATA_MOTION_Z));
    }

    private static float finite(double value) {
        return Double.isFinite(value) ? (float) Math.max(-4.0D, Math.min(4.0D, value)) : 0.0F;
    }
}
