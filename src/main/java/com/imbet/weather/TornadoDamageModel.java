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
import weather2.weathersystem.tornado.simple.TornadoFunnelSimple;

import java.util.HashMap;
import java.util.Map;

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
        double localWindMph = damageWindMph(storm, wind);
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
        return adjustEntityMotion(storm, sample, originalMotion, forPlayer, forCube, grabAdj, true);
    }

    /**
     * @param tornadoCapture whether the violent-core capture (the orbit-and-lift that a tornado's core
     *                       does to whatever it catches) may apply. A hurricane pushes, drags and knocks
     *                       things over with the same wind, but it has no core to be caught in: its
     *                       "core" is a calm eye, so capture is exactly the wrong behaviour there.
     */
    public static Vec3 adjustEntityMotion(StormObject storm, TornadoWindField.WindSample sample, Vec3 originalMotion, boolean forPlayer, boolean forCube, float grabAdj, boolean tornadoCapture) {
        Vec3 base = originalMotion == null ? Vec3.ZERO : originalMotion;
        if (storm == null || sample == null || sample.speedMph() <= 1.0D) {
            return base;
        }
        double localWindMph = Math.max(sample.speedMph(), sample.strongestLocalMph());
        if (forPlayer && tornadoCapture && playerCaptureRamp(sample, localWindMph) > 0.0D) {
            return capturePlayerMotion(sample, base, localWindMph);
        }
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

    /**
     * Capture ramp for the violent core: 0 outside the core or below the wind gate, ramping to 1
     * deep inside a violent tornado. Shared by the player motion, the mob capture, and the fall-safe
     * so they all agree on who is captured.
     */
    public static double captureRamp(TornadoWindField.WindSample sample, double localWindMph, double gateMph) {
        if (sample == null || !Config.WINDFIELD_PLAYER_LAUNCH_ENABLED.get()) {
            return 0.0D;
        }
        if (localWindMph < gateMph) {
            return 0.0D;
        }
        double coreDistance = Math.max(sample.radiusMaxWind() * 1.8D, 8.0D);
        double distanceFactor = Mth.clamp(1.0D - sample.distanceFromCore() / coreDistance, 0.0D, 1.0D);
        if (distanceFactor <= 0.0D) {
            return 0.0D;
        }
        double windFactor = Mth.clamp((localWindMph - gateMph) / 130.0D, 0.0D, 1.0D);
        return Math.min(1.0D, distanceFactor * (0.35D + windFactor * 0.65D));
    }

    public static double playerCaptureRamp(TornadoWindField.WindSample sample, double localWindMph) {
        return captureRamp(sample, localWindMph, Config.WINDFIELD_PLAYER_LAUNCH_MPH.get());
    }

    /**
     * Mob capture opens slightly earlier than the player's so mobs get picked up and
     * fly in with the block debris instead of standing in the wind until it is violent.
     */
    public static double mobCaptureGateMph() {
        return Math.max(75.0D, Config.WINDFIELD_PLAYER_LAUNCH_MPH.get() - 35.0D);
    }

    public static double mobCaptureRamp(TornadoWindField.WindSample sample, double localWindMph) {
        return captureRamp(sample, localWindMph, mobCaptureGateMph());
    }

    /**
     * Target velocity of the capture: a real orbit around the funnel (tangent direction of the local
     * wind) with strong upward lift. Returns null when the sample has no usable horizontal wind.
     */
    public static Vec3 captureTargetVelocity(TornadoWindField.WindSample sample, double ramp, double strength) {
        if (sample == null || strength <= 0.0D) {
            return null;
        }
        Vec3 windHorizontal = new Vec3(sample.mphVector().x, 0.0D, sample.mphVector().z);
        double windSpeed = windHorizontal.length();
        if (windSpeed <= 0.5D) {
            return null;
        }
        double orbitSpeed = windSpeed * 0.0021D * (0.75D + ramp * 0.65D) * strength;
        double liftTarget = (0.032D + ramp * 0.078D) * strength;
        return windHorizontal.normalize().scale(orbitSpeed).add(0.0D, liftTarget, 0.0D);
    }

    /**
     * Captured motion: instead of accumulating gentle pushes, converge the entity's velocity onto the
     * capture orbit so they get launched up and rolled around the tornado like debris.
     */
    private static Vec3 captureConvergeMotion(Vec3 target, Vec3 base, double ramp) {
        double k = 0.10D + ramp * 0.12D;
        Vec3 delta = target.subtract(base).scale(k);
        double cap = 0.38D + ramp * 0.26D;
        if (delta.lengthSqr() > cap * cap) {
            delta = delta.normalize().scale(cap);
        }
        return base.add(delta);
    }

    private static Vec3 capturePlayerMotion(TornadoWindField.WindSample sample, Vec3 base, double localWindMph) {
        double ramp = playerCaptureRamp(sample, localWindMph);
        Vec3 target = captureTargetVelocity(sample, ramp, Config.WINDFIELD_PLAYER_LAUNCH_STRENGTH.get());
        if (target == null) {
            return base;
        }
        return captureConvergeMotion(target, base, ramp);
    }

    /**
     * Server-side mob capture, invoked from the spinEntityv2 mixin. Replaces Weather2's own toss for
     * captured living entities so the mob orbits and rises with the funnel instead of being flung.
     * Returns true when the capture handled the entity (caller should cancel Weather2's handling).
     */
    public static boolean applyMobCapture(StormObject storm, net.minecraft.world.entity.Entity entity) {
        if (storm == null || entity == null || !(entity instanceof net.minecraft.world.entity.LivingEntity living)
                || entity instanceof net.minecraft.world.entity.player.Player
                || entity.isInWater()
                || entity.getPersistentData().getBoolean("tornado_shoot")
                || !Config.WINDFIELD_PLAYER_LAUNCH_ENABLED.get()) {
            return false;
        }
        Level level = entity.level();
        if (level == null || level.isClientSide() || !(level instanceof ServerLevel)) {
            return false;
        }
        TornadoWindField.WindSample sample = TornadoWindField.sample(storm, level, entity.position());
        double localWindMph = Math.max(sample.speedMph(), sample.strongestLocalMph());
        double ramp = mobCaptureRamp(sample, localWindMph);
        if (ramp <= 0.0D) {
            return false;
        }
        double strength = Config.WINDFIELD_PLAYER_LAUNCH_STRENGTH.get() * Config.WINDFIELD_MOB_LAUNCH_SCALE.get();
        Vec3 target = captureTargetVelocity(sample, ramp, strength);
        if (target == null) {
            return false;
        }
        long tick = level.getGameTime();
        MobTumble previous = MOB_TUMBLE.getOrDefault(entity.getUUID(),
                new MobTumble(living.getXRot(), tick - 2L));
        boolean freshlyCaptured = tick - previous.lastTick() > 1L;
        // Mobs have gravity and their own AI fighting the wind - lift them hard enough
        // to clearly beat gravity so they fly up with the block debris instead of
        // hovering or tumbling in place on the ground.
        target = target.add(0.0D, (0.22D + ramp * 0.20D) * strength, 0.0D);
        Vec3 newMotion = captureConvergeMotion(target, living.getDeltaMovement(), ramp);
        if (freshlyCaptured) {
            // hard pick-up kick so the mob leaves the ground instantly, like a block
            Vec3 windHorizontal = new Vec3(sample.mphVector().x, 0.0D, sample.mphVector().z);
            double windSpeed = windHorizontal.length();
            newMotion = newMotion.add(0.0D, 0.42D * strength, 0.0D);
            if (windSpeed > 0.5D) {
                newMotion = newMotion.add(windHorizontal.normalize().scale(0.28D * strength));
            }
        }
        living.setDeltaMovement(newMotion);
        living.hurtMarked = true;
        // The flip, plus the roll the renderer adds client-side: an entity thrown through a funnel turns
        // end over end, which is what the grabbed block debris does.
        //
        // The yaw is deliberately NOT touched, and this is the one thing here that is easy to get wrong.
        // Yaw turns a model about its own vertical axis - head, body and legs together - so accumulating it
        // does not read as tumbling at all: it reads as the mob being spun by the ankle on the spot, which is
        // the one motion nothing in a tornado actually does. Pitch and roll alone give the tumble, and they
        // leave the model's own facing where the entity put it.
        int hash = entity.getUUID().hashCode();
        double speedJitter = 0.70D + 0.60D * ((hash >> 4) & 0xF) / 15.0D;
        // NOTE: deliberately NOT scaled by launch strength or fully by the capture ramp - those
        // are motion knobs, and scaling the flip by them made the tumble crawl in all but the
        // deepest core (ramp is only ~0.1-0.4 for a typical grab). Speed lives in its own knob so
        // the whole capture tumble can be turned up without touching how hard the mob is thrown.
        double tumbleSpeed = (5.5D + ramp * 7.0D) * speedJitter * Config.tumbleSpeedMultiplier();
        double pitchDirection = ((hash >> 1) & 1) == 0 ? 1.0D : -1.0D;
        float pitch = (float) ((previous.pitch() + tumbleSpeed * 0.78D * pitchDirection) % 360.0D);
        if (pitch < 0.0F) {
            pitch += 360.0F;
        }
        MOB_TUMBLE.put(entity.getUUID(), new MobTumble(pitch, tick));
        if (MOB_TUMBLE.size() > 2048) {
            MOB_TUMBLE.clear();
        }
        living.xRotO = previous.pitch();
        living.setXRot(pitch);
        if (level instanceof ServerLevel serverLevel) {
            TornadoPlayerCapture.markCaptured(living.getUUID(), serverLevel.getGameTime());
        }
        return true;
    }

    /**
     * Weather2 runs its OWN entity pick-up in parallel with ours: {@code canGrabEntity} +
     * {@code spinEntityv2} toss entities at thresholds of their own (from STATE_FORMING up, with
     * its own pull radius and baby/pet/playerControlled modifiers), and its "tornado_shoot" branch
     * flings entities at players outright. Entities carried by that system never enter
     * {@link #applyMobCapture} - which only takes over the violent core above our wind gate - so
     * they used to fly through the funnel with no tumble at all. This runs after Weather2 has moved
     * them and adds the same end-over-end flip, leaving Weather2's motion - and its own facing - untouched.
     */
    public static void applyTossTumble(StormObject storm, net.minecraft.world.entity.Entity entity) {
        if (storm == null || entity == null
                || !(entity instanceof net.minecraft.world.entity.LivingEntity living)
                || entity instanceof net.minecraft.world.entity.player.Player
                || entity.isInWater()
                || !Config.WINDFIELD_PLAYER_LAUNCH_ENABLED.get()) {
            return;
        }
        Level level = entity.level();
        if (!(level instanceof ServerLevel)) {
            return;
        }
        if (!isCarriedByStorm(storm, entity)) {
            return;
        }
        long tick = level.getGameTime();
        MobTumble previous = MOB_TUMBLE.get(entity.getUUID());
        if (previous != null && tick - previous.lastTick() <= 1L) {
            return;
        }
        MobTumble state = previous == null
                ? new MobTumble(living.getXRot(), tick - 2L)
                : previous;
        double strength = Config.WINDFIELD_PLAYER_LAUNCH_STRENGTH.get() * Config.WINDFIELD_MOB_LAUNCH_SCALE.get();
        int hash = entity.getUUID().hashCode();
        double speedJitter = 0.70D + 0.60D * ((hash >> 4) & 0xF) / 15.0D;
        double tumbleSpeed = (5.0D + 2.6D * strength) * speedJitter * Config.tumbleSpeedMultiplier();
        double pitchDirection = ((hash >> 1) & 1) == 0 ? 1.0D : -1.0D;
        // Only the flip, for the reason applyMobCapture gives: a spinning yaw turns the whole model about its
        // vertical axis - head and body together - and reads as pirouetting rather than being thrown. Leaving
        // the yaw alone also means Weather2's own tornado_shoot aim, which writes it every tick, is never
        // fought over, so those entities keep pointing at whoever they were flung at.
        float pitch = (float) ((state.pitch() + tumbleSpeed * 0.78D * pitchDirection) % 360.0D);
        if (pitch < 0.0F) {
            pitch += 360.0F;
        }
        MOB_TUMBLE.put(entity.getUUID(), new MobTumble(pitch, tick));
        if (MOB_TUMBLE.size() > 2048) {
            MOB_TUMBLE.clear();
        }
        living.xRotO = state.pitch();
        living.setXRot(pitch);
    }

    /**
     * True when an entity is being carried by the storm's own pick-up: airborne (or sliding fast)
     * inside Weather2's entity pull radius with meaningful motion, i.e. not simply standing or
     * falling nearby.
     */
    private static boolean isCarriedByStorm(StormObject storm, net.minecraft.world.entity.Entity entity) {
        Vec3 motion = entity.getDeltaMovement();
        double horizontal = Math.sqrt(motion.x * motion.x + motion.z * motion.z);
        if (horizontal < 0.14D && Math.abs(motion.y) < 0.24D) {
            return false;
        }
        if (entity.onGround() && horizontal < 0.30D) {
            return false;
        }
        Vec3 position = entity.position();
        double dx = position.x - storm.pos.x;
        double dz = position.z - storm.pos.z;
        return dx * dx + dz * dz <= entityPullDistance(storm) * entityPullDistance(storm);
    }

    /** Weather2's own entity pull radius for this storm, falling back to the funnel width. */
    private static double entityPullDistance(StormObject storm) {
        if (storm == null) {
            return 0.0D;
        }
        TornadoFunnelSimple funnel = storm.getTornadoFunnelSimple();
        if (funnel != null && funnel.getConfig() != null && funnel.getConfig().getEntityPullDistXZ() > 1.0F) {
            return funnel.getConfig().getEntityPullDistXZ();
        }
        return Math.max(10.0D, DynamicStormController.baseRadiusForWind(storm) * 1.4D);
    }

    private static final Map<java.util.UUID, MobTumble> MOB_TUMBLE = new HashMap<>();

    /**
     * Per-mob tumble state. It carries the FLIP only: there is no yaw field by design, because the yaw is
     * never touched - see {@link #applyMobCapture} for why accumulating one is wrong.
     */
    private record MobTumble(float pitch, long lastTick) {
    }


    public static boolean applyImpact(ServerLevel level, StormObject storm, BlockPos pos, BlockGrabSafety.BlockSample sample, TornadoWindField.WindSample wind) {
        if (level == null || storm == null || pos == null || sample == null || wind == null) {
            return false;
        }
        double localWindMph = damageWindMph(storm, wind);
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

    /**
     * Damage-model wind estimate. The tight Rankine vortex core (TornadoWindField)
     * ramps to zero at the very center and decays fast beyond radiusOfMaxWind, so
     * the raw local wind only qualifies blocks in a narrow ring around the core -
     * the reason even violent tornadoes barely picked anything up. Damage instead
     * follows a swath-scale profile: near max wind across the whole damage path,
     * easing toward its edge. Whichever is stronger (tight local vs swath profile)
     * wins, so the funnel core stays violent and the rest of the swath finally
     * counts. The swath profile decays to a 30% floor, which keeps weak blocks
     * (glass, crops) rippable across the tornado's footprint while stone and heavy
     * materials still need a genuinely violent storm.
     */
    static double damageWindMph(StormObject storm, TornadoWindField.WindSample wind) {
        if (wind == null) {
            return 0.0D;
        }
        double tight = Math.max(wind.speedMph(), wind.strongestLocalMph());
        if (storm == null) {
            return tight;
        }
        double maxWind = TornadoWindField.maxWindMph(storm);
        if (maxWind <= 0.0D) {
            return tight;
        }
        double swathRadius = Math.max(12.0D, DynamicStormController.stateFor(storm).damagePathWidth() * 0.5D);
        double distance = Math.max(0.0D, wind.distanceFromCore());
        double fraction = 1.0D - Math.pow(distance / swathRadius, 1.3D) * 0.34D;
        return Math.max(tight, maxWind * Math.max(0.30D, fraction));
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
