package com.imbet.weather.vortex;

import com.imbet.Config;
import com.imbet.weather.TornadoWindField;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Random;

public abstract class AbstractVortexInstance {
    protected static final int MAX_SUB_VORTICES = 6;
    protected static final int MAX_SATELLITES = 4;
    private static final int IMPACT_INTERVAL_TICKS = 5;
    private static final int ENTITY_INTERVAL_TICKS = 4;

    protected final long id;
    protected final long parentStormId;
    protected final long spawnTick;
    protected final Random random;
    protected VortexMode mode;
    protected VortexMode nonFireMode;
    protected VortexImpactProfile impactProfile;
    protected double x;
    protected double y;
    protected double z;
    protected double previousX;
    protected double previousY;
    protected double previousZ;
    protected double motionX;
    protected double motionY;
    protected double motionZ;
    protected float damagePathWidth;
    protected float targetDamagePathWidth;
    protected float visibleFunnelWidth;
    protected float targetVisibleFunnelWidth;
    protected float coreRadius;
    protected float radiusOfMaxWind;
    protected float maxWindMph;
    protected float targetMaxWindMph;
    protected float visualSpinSpeed = 0.055F;
    protected float targetVisualSpinSpeed = 0.055F;
    protected boolean antiCyclonic;
    protected boolean touchedDown = true;
    protected boolean dead;
    protected long expireTick;
    protected long lastSeenTick;
    protected long nextAnalysisTick;
    protected long lastImpactTick = Long.MIN_VALUE;
    protected long lastEntityImpactTick = Long.MIN_VALUE;
    protected long nativeDamageTick = Long.MIN_VALUE;
    protected int nativeDamageCount;
    protected int generation;
    protected boolean analysisPending;
    protected SurfaceAnalysis latestAnalysis;
    protected float ropeDecay;

    protected final boolean[] subActive = new boolean[MAX_SUB_VORTICES];
    protected final float[] subOrbitDegrees = new float[MAX_SUB_VORTICES];
    protected final float[] subOrbitSpeedDegrees = new float[MAX_SUB_VORTICES];
    protected final float[] subOrbitRadius = new float[MAX_SUB_VORTICES];
    protected final float[] subRadiusFraction = new float[MAX_SUB_VORTICES];
    protected final float[] subIntensityFraction = new float[MAX_SUB_VORTICES];
    protected final float[] subPulse = new float[MAX_SUB_VORTICES];
    protected final float[] subPulseRate = new float[MAX_SUB_VORTICES];

    protected final boolean[] satelliteActive = new boolean[MAX_SATELLITES];
    protected final long[] satelliteId = new long[MAX_SATELLITES];
    protected final double[] satelliteX = new double[MAX_SATELLITES];
    protected final double[] satelliteY = new double[MAX_SATELLITES];
    protected final double[] satelliteZ = new double[MAX_SATELLITES];
    protected final double[] satelliteMotionX = new double[MAX_SATELLITES];
    protected final double[] satelliteMotionY = new double[MAX_SATELLITES];
    protected final double[] satelliteMotionZ = new double[MAX_SATELLITES];
    protected final float[] satelliteOrbitDegrees = new float[MAX_SATELLITES];
    protected final float[] satelliteOrbitRadius = new float[MAX_SATELLITES];
    protected final float[] satelliteOrbitSpeedDegrees = new float[MAX_SATELLITES];
    protected final float[] satelliteDamagePathWidth = new float[MAX_SATELLITES];
    protected final float[] satelliteVisibleFunnelWidth = new float[MAX_SATELLITES];
    protected final float[] satelliteWindFraction = new float[MAX_SATELLITES];
    protected final long[] satelliteExpireTick = new long[MAX_SATELLITES];

    protected AbstractVortexInstance(long id, long parentStormId, VortexMode mode, Vec3 position, Vec3 motion, float damagePathWidth, float maxWindMph, long spawnTick, long expireTick) {
        this.id = id;
        this.parentStormId = parentStormId;
        this.mode = mode == null ? VortexMode.LAND_TORNADO : mode;
        this.nonFireMode = this.mode == VortexMode.FIRENADO ? VortexMode.LAND_TORNADO : this.mode;
        this.impactProfile = impactProfileFor(this.mode);
        this.spawnTick = spawnTick;
        this.expireTick = expireTick;
        this.lastSeenTick = spawnTick;
        this.random = new Random(id * 31L + 0x5DEECE66DL);
        Vec3 safePos = position == null ? Vec3.ZERO : position;
        Vec3 safeMotion = motion == null ? Vec3.ZERO : motion;
        this.x = safePos.x;
        this.y = safePos.y;
        this.z = safePos.z;
        this.previousX = x;
        this.previousY = y;
        this.previousZ = z;
        this.motionX = safeMotion.x;
        this.motionY = safeMotion.y;
        this.motionZ = safeMotion.z;
        this.damagePathWidth = Math.max(4.0F, damagePathWidth);
        this.targetDamagePathWidth = this.damagePathWidth;
        this.visibleFunnelWidth = Math.max(3.0F, this.damagePathWidth * 0.55F);
        this.targetVisibleFunnelWidth = this.visibleFunnelWidth;
        this.coreRadius = Math.max(2.0F, this.damagePathWidth * 0.16F);
        this.radiusOfMaxWind = Math.max(3.0F, this.damagePathWidth * 0.28F);
        this.maxWindMph = Math.max(0.0F, maxWindMph);
        this.targetMaxWindMph = this.maxWindMph;
    }

    public final void tick(ServerLevel level, long gameTime) {
        previousX = x;
        previousY = y;
        previousZ = z;
        tickSource(level, gameTime);
        if (expireTick > 0L && gameTime >= expireTick) {
            dead = true;
            return;
        }
        applyLatestAnalysis(gameTime);
        updateTargets(level, gameTime);
        smooth();
        tickChildVortices(gameTime);
        if (!analysisPending && gameTime >= nextAnalysisTick) {
            generation++;
            analysisPending = VortexEngine.requestAnalysis(level, this, generation);
            nextAnalysisTick = gameTime + Math.max(8, Config.ENVIRONMENT_SCAN_INTERVAL_TICKS.get());
        }
        if (gameTime - lastEntityImpactTick >= ENTITY_INTERVAL_TICKS) {
            lastEntityImpactTick = gameTime;
            applyEntityImpacts(level);
        }
        if (gameTime - lastImpactTick >= IMPACT_INTERVAL_TICKS) {
            lastImpactTick = gameTime;
            applyBlockImpacts(level);
        }
    }

    protected abstract void tickSource(ServerLevel level, long gameTime);

    protected abstract int intensityStage();

    void acceptAnalysis(SurfaceAnalysis analysis) {
        if (analysis.generation() != generation) {
            return;
        }
        latestAnalysis = analysis;
        analysisPending = false;
    }

    public final long id() {
        return id;
    }

    public final long parentStormId() {
        return parentStormId;
    }

    public final VortexMode mode() {
        return mode;
    }

    public final double x() {
        return x;
    }

    public final double y() {
        return y;
    }

    public final double z() {
        return z;
    }

    public final float damagePathWidth() {
        return damagePathWidth;
    }

    public final float visibleFunnelWidth() {
        return visibleFunnelWidth;
    }

    public final float coreRadius() {
        return coreRadius;
    }

    public final float radiusOfMaxWind() {
        return radiusOfMaxWind;
    }

    public final float maxWindMph() {
        return maxWindMph;
    }

    public final float visualSpinSpeed() {
        return visualSpinSpeed;
    }

    public final boolean antiCyclonic() {
        return antiCyclonic;
    }

    public final boolean dead() {
        return dead;
    }

    public final int childCount() {
        int count = 0;
        for (boolean active : subActive) {
            if (active) {
                count++;
            }
        }
        return count;
    }

    public final int satelliteCount() {
        int count = 0;
        for (boolean active : satelliteActive) {
            if (active) {
                count++;
            }
        }
        return count;
    }

    public final VortexSnapshot snapshot() {
        return new VortexSnapshot(
                id,
                parentStormId,
                mode,
                x,
                y,
                z,
                motionX,
                motionY,
                motionZ,
                Math.max(2.0F, damagePathWidth * 0.5F),
                damagePathWidth,
                visibleFunnelWidth,
                coreRadius,
                radiusOfMaxWind,
                maxWindMph,
                childCount(),
                antiCyclonic,
                touchedDown,
                spawnTick,
                expireTick
        );
    }

    public final net.minecraft.nbt.CompoundTag snapshotToNbt() {
        net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
        tag.putLong("id", id);
        tag.putLong("parentStormId", parentStormId);
        tag.putString("mode", mode.name());
        tag.putDouble("x", x);
        tag.putDouble("y", y);
        tag.putDouble("z", z);
        tag.putDouble("motionX", motionX);
        tag.putDouble("motionY", motionY);
        tag.putDouble("motionZ", motionZ);
        tag.putFloat("damagePathWidth", damagePathWidth);
        tag.putFloat("visibleFunnelWidth", visibleFunnelWidth);
        tag.putFloat("maxWindMph", maxWindMph);
        tag.putBoolean("antiCyclonic", antiCyclonic);
        tag.putLong("spawnTick", spawnTick);
        tag.putLong("expireTick", expireTick);
        return tag;
    }

    public final void addSatelliteSnapshots(java.util.List<VortexSnapshot> snapshots) {
        for (int i = 0; i < satelliteActive.length; i++) {
            if (!satelliteActive[i]) {
                continue;
            }
            float damageWidth = satelliteDamagePathWidth[i];
            snapshots.add(new VortexSnapshot(
                    satelliteId[i],
                    id,
                    VortexMode.SATELLITE_TORNADO,
                    satelliteX[i],
                    satelliteY[i],
                    satelliteZ[i],
                    satelliteMotionX[i],
                    satelliteMotionY[i],
                    satelliteMotionZ[i],
                    Math.max(2.0F, damageWidth * 0.5F),
                    damageWidth,
                    satelliteVisibleFunnelWidth[i],
                    Math.max(2.0F, damageWidth * 0.16F),
                    Math.max(3.0F, damageWidth * 0.28F),
                    maxWindMph * satelliteWindFraction[i],
                    0,
                    antiCyclonic,
                    true,
                    spawnTick,
                    satelliteExpireTick[i]
            ));
        }
    }

    public final boolean tryConsumeNativeBlockDamage(long gameTime) {
        if (nativeDamageTick != gameTime) {
            nativeDamageTick = gameTime;
            nativeDamageCount = 0;
        }
        int cap = mode == VortexMode.FIRENADO ? 0 : Config.DAMAGE_MAX_BLOCKS_PER_STORM_PER_TICK.get();
        if (nativeDamageCount >= cap) {
            return false;
        }
        nativeDamageCount++;
        return impactProfile == VortexImpactProfile.TORNADO_DAMAGE;
    }

    public final boolean canGrabBlock(ServerLevel level, BlockState state, BlockPos pos) {
        if (impactProfile != VortexImpactProfile.TORNADO_DAMAGE) {
            return false;
        }
        float hardness = state.getDestroySpeed(level, pos);
        if (hardness < 0.0F || state.is(Blocks.BEDROCK) || state.is(Blocks.END_PORTAL) || state.is(Blocks.END_PORTAL_FRAME) || state.is(Blocks.NETHER_PORTAL) || state.is(Blocks.COMMAND_BLOCK) || state.is(Blocks.CHAIN_COMMAND_BLOCK) || state.is(Blocks.REPEATING_COMMAND_BLOCK)) {
            return false;
        }
        if (Config.DAMAGE_PRESERVE_INVENTORY_BLOCK_ENTITIES.get() && level.getBlockEntity(pos) != null) {
            return false;
        }
        float stageAllowance = Math.max(0, intensityStage() - 4 + 1) * 1.25F;
        float maxHardness = Math.max(1.0F, stageAllowance * Config.DAMAGE_BLOCK_STRENGTH_MAX_MULTIPLIER.get().floatValue());
        return hardness <= maxHardness;
    }

    public final void sampleWind(double px, double py, double pz, MutableWindSample out) {
        sampleCore(id, x, z, px, py, pz, maxWindMph, Math.max(3.0D, radiusOfMaxWind), Math.max(6.0D, damagePathWidth * 0.5D), mode == VortexMode.DUST_DEVIL ? 0.10D : mode == VortexMode.WATERSPOUT ? 0.24D : 0.36D, out);
        double sign = antiCyclonic ? -1.0D : 1.0D;
        for (int i = 0; i < subActive.length; i++) {
            if (!subActive[i]) {
                continue;
            }
            double radians = Math.toRadians(subOrbitDegrees[i]);
            double childX = x + Math.cos(radians) * subOrbitRadius[i];
            double childZ = z + Math.sin(radians) * subOrbitRadius[i];
            double pulse = 0.82D + 0.18D * Math.sin(subPulse[i]);
            double childWind = maxWindMph * subIntensityFraction[i] * pulse;
            sampleCore(id, childX, childZ, px, py, pz, childWind, Math.max(1.5D, radiusOfMaxWind * subRadiusFraction[i] * 0.28D), Math.max(4.0D, damagePathWidth * subRadiusFraction[i]), 0.25D, out, sign);
        }
        for (int i = 0; i < satelliteActive.length; i++) {
            if (!satelliteActive[i]) {
                continue;
            }
            sampleCore(satelliteId[i], satelliteX[i], satelliteZ[i], px, py, pz, maxWindMph * satelliteWindFraction[i], Math.max(3.0D, satelliteDamagePathWidth[i] * 0.28D), Math.max(5.0D, satelliteDamagePathWidth[i] * 0.5D), 0.32D, out);
        }
    }

    protected void setAntiCyclonic(boolean antiCyclonic) {
        this.antiCyclonic = antiCyclonic;
    }

    protected void markSeen(long gameTime) {
        lastSeenTick = gameTime;
    }

    protected void transitionTo(VortexMode nextMode, long gameTime) {
        if (nextMode == null || nextMode == mode) {
            return;
        }
        if (mode != VortexMode.FIRENADO && nextMode != VortexMode.FIRENADO) {
            nonFireMode = nextMode;
        } else if (mode != VortexMode.FIRENADO && nextMode == VortexMode.FIRENADO) {
            nonFireMode = mode;
        }
        mode = nextMode;
        impactProfile = impactProfileFor(mode);
        if (mode == VortexMode.LAND_TORNADO && nonFireMode == VortexMode.WATERSPOUT) {
            ropeDecay = Math.max(ropeDecay, 1.0F);
            targetMaxWindMph = Math.min(targetMaxWindMph, 72.0F);
            targetDamagePathWidth = Math.min(targetDamagePathWidth, 26.0F);
            expireTick = expireTick <= 0L ? gameTime + 220L : Math.min(expireTick, gameTime + 220L);
        }
    }

    protected void applyLatestAnalysis(long gameTime) {
        if (latestAnalysis == null || latestAnalysis.medium() == SurfaceMedium.UNLOADED) {
            return;
        }
        y = latestAnalysis.surfaceY();
        if (latestAnalysis.thermalState() == ThermalState.OVERDRIVE && mode != VortexMode.DUST_DEVIL) {
            transitionTo(VortexMode.FIRENADO, gameTime);
        } else if (mode == VortexMode.FIRENADO && latestAnalysis.thermalScore() < 0.48F) {
            transitionTo(nonFireMode == VortexMode.FIRENADO ? VortexMode.LAND_TORNADO : nonFireMode, gameTime);
        }

        if (mode == VortexMode.WATERSPOUT && latestAnalysis.medium() != SurfaceMedium.WATER && latestAnalysis.medium() != SurfaceMedium.DEEP_WATER) {
            transitionTo(VortexMode.LAND_TORNADO, gameTime);
        } else if (mode == VortexMode.LAND_TORNADO && nonFireMode != VortexMode.WATERSPOUT && latestAnalysis.medium() == SurfaceMedium.DEEP_WATER && maxWindMph < 120.0F) {
            transitionTo(VortexMode.WATERSPOUT, gameTime);
        } else if (mode == VortexMode.DUST_DEVIL && (latestAnalysis.drySurfaceScore() < 0.28F || latestAnalysis.thermalState() == ThermalState.NORMAL && latestAnalysis.medium() != SurfaceMedium.ARID)) {
            expireTick = expireTick <= 0L ? gameTime + 80L : Math.min(expireTick, gameTime + 80L);
        }
    }

    protected void updateTargets(ServerLevel level, long gameTime) {
        float stage = Mth.clamp((intensityStage() - 4) / 5.0F, 0.0F, 1.0F);
        if (mode == VortexMode.DUST_DEVIL) {
            targetDamagePathWidth = Mth.clamp(targetDamagePathWidth, 4.0F, 13.0F);
            targetVisibleFunnelWidth = Math.max(3.0F, targetDamagePathWidth * 0.75F);
            targetMaxWindMph = Mth.clamp(targetMaxWindMph, 18.0F, 62.0F);
            return;
        }
        if (mode == VortexMode.WATERSPOUT) {
            targetDamagePathWidth = Mth.clamp(targetDamagePathWidth, 8.0F, 38.0F);
            targetVisibleFunnelWidth = Math.max(4.0F, targetDamagePathWidth * 0.48F);
            targetMaxWindMph = Mth.clamp(targetMaxWindMph, 38.0F, 105.0F);
            return;
        }
        if (mode == VortexMode.LANDSPOUT) {
            targetDamagePathWidth = Mth.clamp(targetDamagePathWidth, 7.0F, 34.0F);
            targetVisibleFunnelWidth = Math.max(4.0F, targetDamagePathWidth * 0.50F);
            targetMaxWindMph = Mth.clamp(targetMaxWindMph, 48.0F, 110.0F);
            return;
        }
        if (mode == VortexMode.FIRENADO) {
            targetMaxWindMph = Math.max(targetMaxWindMph, Math.min(260.0F, maxWindMph * 1.08F + 12.0F));
            targetVisibleFunnelWidth = Math.max(targetVisibleFunnelWidth, targetDamagePathWidth * 0.62F);
        } else {
            targetDamagePathWidth = Math.max(targetDamagePathWidth, Mth.lerp(stage, 18.0F, (float) Config.effectiveMaxDamageWidthBlocks()));
            targetVisibleFunnelWidth = Mth.clamp(targetDamagePathWidth * 0.55F, 3.0F, (float) Config.effectiveMaxVisibleFunnelWidthBlocks());
        }
        if (latestAnalysis != null) {
            targetDamagePathWidth *= Mth.clamp(1.0F - latestAnalysis.roughnessPenalty() * 0.18F, 0.76F, 1.0F);
        }
    }

    protected void smooth() {
        float smoothing = Config.TORNADO_WIDTH_SMOOTHING.get().floatValue();
        damagePathWidth += (targetDamagePathWidth - damagePathWidth) * smoothing;
        visibleFunnelWidth += (targetVisibleFunnelWidth - visibleFunnelWidth) * smoothing;
        maxWindMph += (targetMaxWindMph - maxWindMph) * 0.08F;
        if (ropeDecay > 0.0F) {
            ropeDecay *= 0.955F;
            damagePathWidth = Math.min(damagePathWidth, 26.0F + ropeDecay * 12.0F);
            visibleFunnelWidth = Math.min(visibleFunnelWidth, 10.0F + ropeDecay * 8.0F);
        }
        coreRadius = Math.max(2.0F, damagePathWidth * 0.16F);
        radiusOfMaxWind = Math.max(3.0F, damagePathWidth * 0.28F);
        updateVisualSpin();
    }

    protected void tickChildVortices(long gameTime) {
        double sign = antiCyclonic ? -1.0D : 1.0D;
        int desiredSubVortices = desiredSubVortices();
        for (int i = 0; i < subActive.length; i++) {
            if (i < desiredSubVortices) {
                if (!subActive[i]) {
                    subActive[i] = true;
                    subOrbitDegrees[i] = random.nextFloat() * 360.0F;
                    subOrbitSpeedDegrees[i] = 2.6F + random.nextFloat() * 5.2F;
                    subOrbitRadius[i] = Math.max(3.0F, damagePathWidth * (0.18F + random.nextFloat() * 0.24F));
                    subRadiusFraction[i] = 0.35F + random.nextFloat() * 0.40F;
                    subIntensityFraction[i] = 0.18F + random.nextFloat() * 0.26F;
                    subPulse[i] = random.nextFloat() * Mth.TWO_PI;
                    subPulseRate[i] = 0.025F + random.nextFloat() * 0.045F;
                }
                subOrbitDegrees[i] = wrapDegrees(subOrbitDegrees[i] + subOrbitSpeedDegrees[i] * (float) sign);
                subOrbitRadius[i] += (Math.max(3.0F, damagePathWidth * (0.20F + i * 0.035F)) - subOrbitRadius[i]) * 0.04F;
                subPulse[i] += subPulseRate[i];
            } else {
                subActive[i] = false;
            }
        }
        tickSatellites(gameTime, sign);
    }

    protected int desiredSubVortices() {
        if (!Config.MULTIVORTEX_ENABLED.get() || mode == VortexMode.WATERSPOUT || mode == VortexMode.DUST_DEVIL || mode == VortexMode.LANDSPOUT || intensityStage() < Config.MULTIVORTEX_MIN_STAGE.get()) {
            return 0;
        }
        int stageOver = Math.max(0, intensityStage() - Config.MULTIVORTEX_MIN_STAGE.get());
        return Mth.clamp(1 + stageOver + (maxWindMph >= 165.0F ? 1 : 0), 0, Config.MULTIVORTEX_MAX_COUNT.get());
    }

    protected void tickSatellites(long gameTime, double sign) {
        if (!Config.SATELLITE_TORNADOES_ENABLED.get() || mode == VortexMode.WATERSPOUT || mode == VortexMode.DUST_DEVIL || mode == VortexMode.LANDSPOUT || intensityStage() < Config.SATELLITE_TORNADOES_MIN_PARENT_STAGE.get()) {
            clearSatellites();
            return;
        }
        int maxActive = Math.min(MAX_SATELLITES, Config.SATELLITE_TORNADOES_MAX_ACTIVE.get());
        int active = satelliteCount();
        if (active < maxActive && random.nextFloat() < Config.SATELLITE_TORNADOES_SPAWN_CHANCE.get().floatValue() * 0.006F) {
            spawnSatellite(gameTime);
        }
        for (int i = 0; i < satelliteActive.length; i++) {
            if (!satelliteActive[i]) {
                continue;
            }
            if (gameTime >= satelliteExpireTick[i]) {
                satelliteActive[i] = false;
                continue;
            }
            double oldX = satelliteX[i];
            double oldY = satelliteY[i];
            double oldZ = satelliteZ[i];
            satelliteOrbitDegrees[i] = wrapDegrees(satelliteOrbitDegrees[i] + satelliteOrbitSpeedDegrees[i] * (float) sign);
            double radians = Math.toRadians(satelliteOrbitDegrees[i]);
            double targetX = x + Math.cos(radians) * satelliteOrbitRadius[i];
            double targetY = y;
            double targetZ = z + Math.sin(radians) * satelliteOrbitRadius[i];
            satelliteX[i] += (targetX - satelliteX[i]) * 0.18D + motionX * 0.45D;
            satelliteY[i] += (targetY - satelliteY[i]) * 0.18D;
            satelliteZ[i] += (targetZ - satelliteZ[i]) * 0.18D + motionZ * 0.45D;
            satelliteMotionX[i] = satelliteX[i] - oldX;
            satelliteMotionY[i] = satelliteY[i] - oldY;
            satelliteMotionZ[i] = satelliteZ[i] - oldZ;
        }
    }

    protected void applyEntityImpacts(ServerLevel level) {
        double range = Math.max(16.0D, damagePathWidth * 2.5D);
        AABB box = new AABB(x - range, y - 12.0D, z - range, x + range, y + 72.0D, z + range);
        MutableWindSample sample = new MutableWindSample();
        for (Entity entity : level.getEntities((Entity) null, box, Entity::isAlive)) {
            if (entity instanceof Player player && player.isCreative()) {
                continue;
            }
            sample.clear();
            sampleWind(entity.getX(), entity.getY(), entity.getZ(), sample);
            double threshold = mode == VortexMode.DUST_DEVIL ? 16.0D : 24.0D;
            if (sample.speedMph < threshold) {
                continue;
            }
            double scale = mode == VortexMode.DUST_DEVIL ? Config.WINDFIELD_ENTITY_PUSH_SCALE.get() * 0.42D : Config.WINDFIELD_ENTITY_PUSH_SCALE.get();
            entity.setDeltaMovement(entity.getDeltaMovement().add(sample.mphX * scale, sample.mphY * scale, sample.mphZ * scale));
            if (entity instanceof ItemEntity && mode == VortexMode.FIRENADO && level.random.nextInt(6) == 0) {
                entity.igniteForSeconds(4);
            }
            entity.hurtMarked = true;
        }
    }

    protected void applyBlockImpacts(ServerLevel level) {
        if (!Config.DAMAGE_ENABLED.get() && impactProfile != VortexImpactProfile.FIRENADO && impactProfile != VortexImpactProfile.LIGHT_DUST) {
            return;
        }
        int attempts = switch (impactProfile) {
            case FIRENADO -> 20;
            case TORNADO_DAMAGE -> Math.max(2, Config.CYCLOIDAL_SWATH_SAMPLES.get() / 2);
            case LIGHT_DUST -> 5;
            case WATER_SPRAY, NONE -> 0;
        };
        if (attempts <= 0) {
            return;
        }
        int maxChanges = switch (impactProfile) {
            case FIRENADO -> 10;
            case TORNADO_DAMAGE -> Math.max(1, Math.min(Config.DAMAGE_MAX_BLOCKS_PER_STORM_PER_TICK.get(), 14));
            case LIGHT_DUST -> 3;
            case WATER_SPRAY, NONE -> 0;
        };
        int changed = 0;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos above = new BlockPos.MutableBlockPos();
        for (int i = 0; i < attempts && changed < maxChanges; i++) {
            double angle = random.nextDouble() * Math.PI * 2.0D;
            double distance = Math.sqrt(random.nextDouble()) * Math.max(3.0D, damagePathWidth * 0.78D);
            int baseX = Mth.floor(x + Math.cos(angle) * distance);
            int baseZ = Mth.floor(z + Math.sin(angle) * distance);
            cursor.set(baseX, Mth.floor(y), baseZ);
            if (!level.isLoaded(cursor)) {
                continue;
            }
            BlockPos surface = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, cursor);
            cursor.set(surface.getX(), surface.getY() - 1, surface.getZ());
            above.set(surface.getX(), surface.getY(), surface.getZ());
            if (!level.isLoaded(cursor)) {
                continue;
            }
            BlockState state = level.getBlockState(cursor);
            if (impactProfile == VortexImpactProfile.FIRENADO) {
                if (applyFireImpact(level, cursor, above, state)) {
                    changed++;
                }
            } else if (impactProfile == VortexImpactProfile.TORNADO_DAMAGE) {
                if (applyScouring(level, cursor, state)) {
                    changed++;
                }
            } else if (impactProfile == VortexImpactProfile.LIGHT_DUST && applyLooseSurfaceStrip(level, cursor, state)) {
                changed++;
            }
        }
    }

    protected boolean applyFireImpact(ServerLevel level, BlockPos.MutableBlockPos pos, BlockPos.MutableBlockPos above, BlockState state) {
        if (state.is(BlockTags.LOGS)) {
            level.setBlock(pos, copyAxis(state, Blocks.COAL_BLOCK.defaultBlockState()), 3);
            return true;
        }
        if (state.is(BlockTags.LEAVES) || state.is(BlockTags.FLOWERS) || state.is(BlockTags.CROPS) || state.is(BlockTags.REPLACEABLE_BY_TREES)) {
            level.setBlock(pos, Blocks.FIRE.defaultBlockState(), 3);
            return true;
        }
        Block replacement = scorchReplacement(state);
        if (replacement != null && !state.is(replacement)) {
            level.setBlock(pos, replacement.defaultBlockState(), 3);
            return true;
        }
        if (level.isEmptyBlock(above) && Blocks.FIRE.defaultBlockState().canSurvive(level, above)) {
            level.setBlock(above, Blocks.FIRE.defaultBlockState(), 3);
            return true;
        }
        return false;
    }

    protected boolean applyScouring(ServerLevel level, BlockPos.MutableBlockPos pos, BlockState state) {
        if (mode == VortexMode.FIRENADO) {
            return false;
        }
        Block replacement = scouringReplacement(state);
        if (replacement != null && !state.is(replacement)) {
            level.setBlock(pos, replacement.defaultBlockState(), 3);
            return true;
        }
        if (state.is(BlockTags.CROPS) || state.is(BlockTags.FLOWERS) || state.is(BlockTags.REPLACEABLE_BY_TREES)) {
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
            return true;
        }
        return false;
    }

    protected boolean applyLooseSurfaceStrip(ServerLevel level, BlockPos.MutableBlockPos pos, BlockState state) {
        if (state.is(BlockTags.LEAVES) || state.is(BlockTags.CROPS) || state.is(BlockTags.FLOWERS) || state.is(BlockTags.REPLACEABLE_BY_TREES)) {
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
            return true;
        }
        return false;
    }

    private void updateVisualSpin() {
        float min = Math.min(Config.TORNADO_WIND_SPEED_SPIN_MIN.get().floatValue(), Config.TORNADO_WIND_SPEED_SPIN_MAX.get().floatValue());
        float max = Math.max(Config.TORNADO_WIND_SPEED_SPIN_MIN.get().floatValue(), Config.TORNADO_WIND_SPEED_SPIN_MAX.get().floatValue());
        float refMin = Math.min(Config.TORNADO_WIND_SPEED_SPIN_REFERENCE_MIN_MPH.get().floatValue(), Config.TORNADO_WIND_SPEED_SPIN_REFERENCE_MAX_MPH.get().floatValue());
        float refMax = Math.max(Config.TORNADO_WIND_SPEED_SPIN_REFERENCE_MIN_MPH.get().floatValue(), Config.TORNADO_WIND_SPEED_SPIN_REFERENCE_MAX_MPH.get().floatValue());
        float windNorm = refMax <= refMin ? 1.0F : Mth.clamp((maxWindMph - refMin) / (refMax - refMin), 0.0F, 1.0F);
        float windCurve = windNorm * windNorm * (3.0F - 2.0F * windNorm);
        targetVisualSpinSpeed = Mth.lerp(windCurve, min, max);
        visualSpinSpeed += (targetVisualSpinSpeed - visualSpinSpeed) * Config.TORNADO_WIND_SPEED_SPIN_SMOOTHING.get().floatValue();
    }

    private void spawnSatellite(long gameTime) {
        for (int i = 0; i < satelliteActive.length; i++) {
            if (satelliteActive[i]) {
                continue;
            }
            satelliteActive[i] = true;
            satelliteId[i] = (id * 31L) ^ (gameTime << 8) ^ i;
            satelliteOrbitDegrees[i] = random.nextFloat() * 360.0F;
            satelliteOrbitSpeedDegrees[i] = 1.6F + random.nextFloat() * 2.4F;
            satelliteOrbitRadius[i] = Math.max(18.0F, damagePathWidth * (0.75F + random.nextFloat() * 0.60F));
            satelliteDamagePathWidth[i] = Math.max(12.0F, damagePathWidth * (0.22F + random.nextFloat() * 0.22F));
            satelliteVisibleFunnelWidth[i] = Math.max(5.0F, satelliteDamagePathWidth[i] * 0.46F);
            satelliteWindFraction[i] = 0.32F + random.nextFloat() * (float) Math.max(0.05D, Config.SATELLITE_TORNADOES_MAX_WIND_FRACTION.get() - 0.32D);
            satelliteExpireTick[i] = gameTime + 360L + random.nextInt(840);
            double radians = Math.toRadians(satelliteOrbitDegrees[i]);
            satelliteX[i] = x + Math.cos(radians) * satelliteOrbitRadius[i];
            satelliteY[i] = y;
            satelliteZ[i] = z + Math.sin(radians) * satelliteOrbitRadius[i];
            return;
        }
    }

    private void clearSatellites() {
        for (int i = 0; i < satelliteActive.length; i++) {
            satelliteActive[i] = false;
        }
    }

    private static void sampleCore(long sourceId, double centerX, double centerZ, double px, double py, double pz, double maxWindMph, double radiusOfMaxWind, double baseRadius, double inflowFraction, MutableWindSample out) {
        sampleCore(sourceId, centerX, centerZ, px, py, pz, maxWindMph, radiusOfMaxWind, baseRadius, inflowFraction, out, 1.0D);
    }

    private static void sampleCore(long sourceId, double centerX, double centerZ, double px, double py, double pz, double maxWindMph, double radiusOfMaxWind, double baseRadius, double inflowFraction, MutableWindSample out, double rotationSign) {
        if (maxWindMph <= 0.0D) {
            return;
        }
        double dx = px - centerX;
        double dz = pz - centerZ;
        double radius = Math.max(0.4D, Math.sqrt(dx * dx + dz * dz));
        if (radius > Math.max(baseRadius * 4.2D, radiusOfMaxWind * 8.0D)) {
            return;
        }
        double radialX = dx / radius;
        double radialZ = dz / radius;
        double tangential = radius <= radiusOfMaxWind ? maxWindMph * (radius / radiusOfMaxWind) : maxWindMph * Math.pow(radiusOfMaxWind / radius, 0.78D);
        double falloff = Mth.clamp(1.0D - radius / Math.max(1.0D, baseRadius * 1.8D), 0.0D, 1.0D);
        double lift = maxWindMph * Config.WINDFIELD_VERTICAL_LIFT_FRACTION.get() * falloff;
        double tangentX = -radialZ * rotationSign;
        double tangentZ = radialX * rotationSign;
        double mphX = tangentX * tangential - radialX * tangential * inflowFraction;
        double mphY = lift;
        double mphZ = tangentZ * tangential - radialZ * tangential * inflowFraction;
        double speed = Math.sqrt(mphX * mphX + mphY * mphY + mphZ * mphZ);
        out.setIfStronger(sourceId, mphX, mphY, mphZ, speed, speed, radius, baseRadius, radiusOfMaxWind);
    }

    private static VortexImpactProfile impactProfileFor(VortexMode mode) {
        return switch (mode) {
            case FIRENADO -> VortexImpactProfile.FIRENADO;
            case DUST_DEVIL -> VortexImpactProfile.LIGHT_DUST;
            case WATERSPOUT -> VortexImpactProfile.WATER_SPRAY;
            case LANDSPOUT, LAND_TORNADO, SATELLITE_TORNADO -> VortexImpactProfile.TORNADO_DAMAGE;
        };
    }

    private static Block scouringReplacement(BlockState state) {
        if (state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.MYCELIUM) || state.is(Blocks.PODZOL) || state.is(Blocks.DIRT) || state.is(Blocks.COARSE_DIRT) || state.is(Blocks.ROOTED_DIRT)) {
            return Blocks.DIRT_PATH;
        }
        if (state.is(Blocks.FARMLAND)) {
            return Blocks.DIRT;
        }
        if (state.is(Blocks.SAND) || state.is(Blocks.RED_SAND)) {
            return Blocks.SANDSTONE;
        }
        if (state.is(Blocks.SNOW) || state.is(Blocks.SNOW_BLOCK)) {
            return Blocks.ICE;
        }
        return null;
    }

    private static Block scorchReplacement(BlockState state) {
        if (state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.MYCELIUM) || state.is(Blocks.PODZOL) || state.is(Blocks.DIRT) || state.is(Blocks.COARSE_DIRT) || state.is(Blocks.ROOTED_DIRT) || state.is(Blocks.FARMLAND)) {
            return Blocks.COARSE_DIRT;
        }
        if (state.is(Blocks.SAND) || state.is(Blocks.RED_SAND)) {
            return Blocks.GLASS;
        }
        if (state.is(Blocks.STONE) || state.is(Blocks.COBBLESTONE)) {
            return Blocks.BLACKSTONE;
        }
        return null;
    }

    private static BlockState copyAxis(BlockState original, BlockState replacement) {
        if (original.hasProperty(BlockStateProperties.AXIS) && replacement.hasProperty(BlockStateProperties.AXIS)) {
            return replacement.setValue(BlockStateProperties.AXIS, original.getValue(BlockStateProperties.AXIS));
        }
        return replacement;
    }

    private static float wrapDegrees(float value) {
        while (value >= 360.0F) {
            value -= 360.0F;
        }
        while (value < 0.0F) {
            value += 360.0F;
        }
        return value;
    }
}
