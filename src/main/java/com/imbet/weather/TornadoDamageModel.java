package com.imbet.weather;

import com.imbet.Config;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import weather2.weathersystem.storm.StormObject;

public final class TornadoDamageModel {
    private static final double WEAK_WIND_DRAG_MPH = 50.0D;
    private static final double STRUCTURAL_LIFT_MPH = 95.0D;
    private static final double VIOLENT_PROJECTILE_MPH = 166.0D;

    private TornadoDamageModel() {
    }

    public static PickupDecision evaluatePickup(StormObject storm, Level level, BlockGrabSafety.BlockSample sample, TornadoWindField.WindSample wind) {
        if (storm == null || level == null || sample == null || wind == null || !sample.valid() || !sample.loaded()) {
            return PickupDecision.none();
        }
        if (!BlockGrabSafety.canEdit(sample)) {
            return PickupDecision.none();
        }
        BlockState state = sample.state();
        double localWindMph = Math.max(wind.speedMph(), wind.strongestLocalMph());
        double resistanceMph = BlockGrabSafety.resistanceMph(sample);
        if (Config.DAMAGE_NEVER_PICKUP_SOIL.get() && BlockGrabSafety.isNeverPickupTerrain(sample)) {
            return new PickupDecision(PickupAction.SCUFF, localWindMph, resistanceMph, localWindMph - resistanceMph, 0.0D, 0.0D, 0.0D, 0.0D);
        }
        if (Config.DAMAGE_OPEN_DOORS.get()
                && state.hasProperty(BlockStateProperties.OPEN)
                && localWindMph >= Math.max(WEAK_WIND_DRAG_MPH, resistanceMph - 25.0D)) {
            return new PickupDecision(PickupAction.OPEN, localWindMph, resistanceMph, localWindMph - resistanceMph, 0.0D, 0.0D, 0.0D, 0.0D);
        }
        if (!Config.DAMAGE_WIND_RESISTANCE.get()) {
            return new PickupDecision(PickupAction.LOFT, localWindMph, 0.0D, localWindMph, 1.0D, 1.0D, Math.max(1.5D, sample.destroySpeed() + 0.1D), impactEnergy(localWindMph, sample));
        }

        double excessMph = localWindMph - resistanceMph;
        double liftGrip = Mth.clamp((wind.mphVector().y - 12.0D) / 90.0D, 0.0D, 1.4D);
        double coreGrip = wind.baseRadius() <= 0.0D ? 0.0D : Mth.clamp(1.0D - wind.distanceFromCore() / (wind.baseRadius() * 1.75D), 0.0D, 1.0D);
        double stageGrip = Mth.clamp((storm.levelCurIntensityStage - StormObject.STATE_FORMING) / 5.0D, 0.0D, 1.0D);
        double materialGrip = Mth.clamp((excessMph + 30.0D) / 82.0D, 0.0D, 1.9D);
        double violentCoreBoost = wind.strongestLocalMph() > TornadoWindField.maxWindMph(storm) * 0.82D
                ? Config.MULTIVORTEX_DAMAGE_MULTIPLIER.get()
                : 1.0D;
        double hardnessLimit = hardnessLimit(sample, localWindMph, materialGrip, liftGrip, coreGrip, stageGrip, violentCoreBoost);
        double energy = impactEnergy(localWindMph, sample);

        if (isFragileOrLoose(state, sample.destroySpeed()) && localWindMph >= Math.max(45.0D, resistanceMph - 18.0D)) {
            return new PickupDecision(PickupAction.PEEL, localWindMph, resistanceMph, excessMph, materialGrip, liftGrip, hardnessLimit, energy);
        }
        if (excessMph < -12.0D) {
            return new PickupDecision(localWindMph >= WEAK_WIND_DRAG_MPH ? PickupAction.SCUFF : PickupAction.NONE,
                    localWindMph, resistanceMph, excessMph, materialGrip, liftGrip, hardnessLimit, energy);
        }
        if (localWindMph >= Math.max(VIOLENT_PROJECTILE_MPH, resistanceMph + 62.0D)) {
            return new PickupDecision(PickupAction.PROJECTILE, localWindMph, resistanceMph, excessMph, materialGrip, liftGrip, hardnessLimit, energy);
        }
        if (localWindMph >= STRUCTURAL_LIFT_MPH && (excessMph >= 18.0D || liftGrip >= 0.35D)) {
            return new PickupDecision(PickupAction.LOFT, localWindMph, resistanceMph, excessMph, materialGrip, liftGrip, hardnessLimit, energy);
        }
        if (excessMph >= 0.0D) {
            return new PickupDecision(PickupAction.DRAG, localWindMph, resistanceMph, excessMph, materialGrip, liftGrip, hardnessLimit, energy);
        }
        return new PickupDecision(PickupAction.SCUFF, localWindMph, resistanceMph, excessMph, materialGrip, liftGrip, hardnessLimit, energy);
    }

    public static Vec3 adjustEntityMotion(StormObject storm, TornadoWindField.WindSample sample, Vec3 originalMotion, boolean forPlayer, boolean forCube, float grabAdj) {
        Vec3 base = originalMotion == null ? Vec3.ZERO : originalMotion;
        if (storm == null || sample == null || sample.speedMph() <= 1.0D) {
            return base;
        }
        double localWindMph = Math.max(sample.speedMph(), sample.strongestLocalMph());
        double scale = Config.WINDFIELD_ENTITY_PUSH_SCALE.get();
        double stageScale = Mth.clamp(TornadoWindField.maxWindMph(storm) / 135.0D, 0.70D, 2.45D);
        double actorScale = forCube ? 1.75D : forPlayer ? 0.68D : 1.08D;
        double grabScale = Mth.clamp(0.80D + grabAdj * 0.35D, 0.65D, 1.45D);

        Vec3 mphVector = sample.mphVector();
        Vec3 horizontal = new Vec3(mphVector.x, 0.0D, mphVector.z)
                .scale(scale * actorScale * grabScale * Mth.clamp(0.78D + stageScale * 0.25D, 0.8D, 1.45D));
        double liftGate = Mth.clamp((localWindMph - (forCube ? 72.0D : 92.0D)) / 105.0D, 0.0D, 1.0D);
        double vertical = mphVector.y * scale * actorScale * liftGate;
        if (forCube && localWindMph < Config.DAMAGE_WOOD_PICKUP_MPH.get()) {
            vertical = Math.min(vertical, 0.018D);
        }
        Vec3 delta = horizontal.add(0.0D, vertical, 0.0D);
        double cap = forCube ? 0.34D : forPlayer ? 0.16D : 0.24D;
        if (localWindMph >= 136.0D) {
            cap += forCube ? 0.16D : 0.08D;
        }
        if (localWindMph >= 200.0D) {
            cap += forCube ? 0.20D : 0.10D;
        }
        if (delta.lengthSqr() > cap * cap) {
            delta = delta.normalize().scale(cap);
        }
        Vec3 adjusted = base.add(delta);
        if (forCube && localWindMph >= Config.DAMAGE_WOOD_PICKUP_MPH.get() && adjusted.y < 0.02D) {
            adjusted = new Vec3(adjusted.x, Math.min(0.22D, adjusted.y + 0.035D + liftGate * 0.06D), adjusted.z);
        }
        return adjusted;
    }

    public static boolean applyImpact(ServerLevel level, StormObject storm, BlockPos pos, BlockGrabSafety.BlockSample sample, TornadoWindField.WindSample wind) {
        if (level == null || storm == null || pos == null || sample == null || wind == null) {
            return false;
        }
        double localWindMph = Math.max(wind.speedMph(), wind.strongestLocalMph());
        if (!BlockGrabSafety.canImpactDamageByWind(sample, localWindMph)) {
            return false;
        }
        double energy = impactEnergy(localWindMph, sample);
        boolean violent = localWindMph >= 166.0D || storm.levelCurIntensityStage >= StormObject.STATE_STAGE4;
        Vec3 velocity = wind.mphVector().scale(0.012D);
        double mass = massFactor(sample);
        StructureCrusher.CrushResult crush = StructureCrusher.crush(level, pos, velocity, mass, violent);
        if (crush.destroyedBlocks() > 0) {
            return true;
        }
        double threshold = isFragileOrLoose(sample.state(), sample.destroySpeed()) ? 0.85D : 1.85D;
        if (energy < threshold) {
            return false;
        }
        PickupDecision decision = evaluatePickup(storm, level, sample, wind);
        if (!decision.allowsWeather2Pickup() && decision.action() != PickupAction.SCUFF) {
            return false;
        }
        if (sample.destroySpeed() > Math.max(0.55D, decision.hardnessLimit() * (violent ? 1.15D : 0.85D))) {
            return false;
        }
        return BlockGrabSafety.safeDestroyBlock(level, pos, false);
    }

    private static double hardnessLimit(BlockGrabSafety.BlockSample sample, double localWindMph, double materialGrip, double liftGrip, double coreGrip, double stageGrip, double violentCoreBoost) {
        double stageMultiplier = 0.78D + stageGrip * 0.72D;
        double grip = Math.max(materialGrip, liftGrip * 0.72D + coreGrip * 0.42D);
        double limit = 0.42D + Math.pow(grip, 1.12D) * 4.7D * stageMultiplier * violentCoreBoost;
        if (localWindMph >= 166.0D) {
            limit = Math.max(limit, 3.2D + (localWindMph - 166.0D) / 34.0D);
        }
        if (isFragileOrLoose(sample.state(), sample.destroySpeed())) {
            limit = Math.max(limit, 0.8D);
        }
        return Mth.clamp(limit, 0.0D, 18.0D);
    }

    private static double impactEnergy(double localWindMph, BlockGrabSafety.BlockSample sample) {
        double normalizedSpeed = Math.max(0.0D, localWindMph) / 65.0D;
        return 0.5D * massFactor(sample) * normalizedSpeed * normalizedSpeed;
    }

    private static double massFactor(BlockGrabSafety.BlockSample sample) {
        if (sample == null || sample.state() == null) {
            return 0.4D;
        }
        float hardness = Math.max(0.05F, sample.destroySpeed());
        BlockState state = sample.state();
        double mass = 0.32D + Math.sqrt(hardness) * 0.82D;
        if (state.is(BlockTags.LOGS) || state.is(BlockTags.BASE_STONE_OVERWORLD) || state.is(BlockTags.STONE_BRICKS)) {
            mass += 0.8D;
        }
        if (state.is(Blocks.IRON_BLOCK) || state.is(Blocks.COPPER_BLOCK) || state.is(Blocks.GOLD_BLOCK) || state.is(Blocks.DIAMOND_BLOCK)) {
            mass += 1.4D;
        }
        return Mth.clamp(mass, 0.25D, 6.0D);
    }

    private static boolean isFragileOrLoose(BlockState state, float hardness) {
        String path = blockPath(state);
        return hardness <= 0.65F
                || state.is(BlockTags.LEAVES)
                || state.is(BlockTags.FLOWERS)
                || state.is(BlockTags.CROPS)
                || state.is(BlockTags.REPLACEABLE_BY_TREES)
                || path.contains("glass")
                || path.contains("pane")
                || path.contains("carpet")
                || path.contains("wool")
                || path.contains("torch")
                || path.contains("lantern");
    }

    private static String blockPath(BlockState state) {
        if (state == null) {
            return "";
        }
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        return id == null ? "" : id.getPath();
    }

    public enum PickupAction {
        NONE,
        OPEN,
        SCUFF,
        PEEL,
        DRAG,
        LOFT,
        PROJECTILE
    }

    public record PickupDecision(
            PickupAction action,
            double localWindMph,
            double resistanceMph,
            double excessMph,
            double materialGrip,
            double liftGrip,
            double hardnessLimit,
            double impactEnergy
    ) {
        private static PickupDecision none() {
            return new PickupDecision(PickupAction.NONE, 0.0D, Double.MAX_VALUE, 0.0D, 0.0D, 0.0D, 0.0D, 0.0D);
        }

        public boolean allowsWeather2Pickup() {
            return action == PickupAction.PEEL
                    || action == PickupAction.DRAG
                    || action == PickupAction.LOFT
                    || action == PickupAction.PROJECTILE;
        }
    }
}
