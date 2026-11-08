package com.imbet.client;

import com.imbet.Config;
import com.imbet.weather.AddonWeatherEvent;
import com.imbet.weather.BlockGrabSafety;
import com.imbet.weather.ClientAddonWeatherEventStore;
import com.imbet.weather.DynamicStormController;
import com.imbet.weather.DynamicStormState;
import com.imbet.weather.GenesisWhirls;
import com.imbet.weather.HurricaneShield;
import com.imbet.weather.MonsoonService;
import com.imbet.weather.SubVortexType;
import com.imbet.weather.StormSubtype;
import com.imbet.weather.SupercellStructureService;
import com.imbet.weather.TornadoFootprint;
import com.imbet.weather.TornadoWindField;
import com.imbet.survey.ClientDamageSurveyStore;
import com.imbet.survey.DamageSurveyResult;
import extendedrenderer.particle.ParticleRegistry;
import extendedrenderer.particle.entity.EntityRotFX;
import extendedrenderer.particle.entity.ParticleCube;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import weather2.ClientTickHandler;
import weather2.weathersystem.WeatherManagerClient;
import weather2.weathersystem.storm.StormObject;
import weather2.weathersystem.storm.WeatherObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;

public final class ClientWeatherEffects {
    private static final float LOCAL_PARTICLE_WIND_WEIGHT = 9999.0F;
    /**
     * The dust sheet is one sprite shared by many users - the storm's own structure puffs, the ground
     * fog, the debris cloud and the loose dust this class spawns. These two numbers exist so the sheet
     * can be swapped without silently changing the visual weight of every already-tuned dust alpha: the
     * particle alphas below are expressed against the regenerated sheet and scaled by the ratio when the
     * sheet actually in use has a different mean.
     *
     * <p>All three are measured from the shipped PNG (mean alpha over one frame), not estimated - re-measure
     * the regenerated figure whenever {@code tools/TextureGen.java} draws that sprite again. {@code TUNED} is the
     * sheet the alphas below were written against; the regenerated sheet is this mod's own
     * {@code particles/modern/cloud256}; and the original is Weather2's own {@code particles/cloud256}, the sheet
     * every 1.12.2 storm was drawn against, whose frame mean is 13.2/255 = 0.052 with a peak of 81.
     */
    private static final float CLOUD256_SPRITE_MEAN_ALPHA_TUNED = 0.296F;
    private static final float CLOUD256_SPRITE_MEAN_ALPHA_REGENERATED = 0.290F;
    private static final float CLOUD256_SPRITE_MEAN_ALPHA_ORIGINAL = 0.052F;
    /**
     * The vortex funnels used to be the other user of the dust sheet. They now have one of their own
     * ({@code particles/modern/funnel} and the layered funnel's {@code cloud256combined}), drawn in long vertical fibres.
     * This dial can only hold a column's density back, so it stays at full.
     */
    public static final float FUNNEL_SPRITE_DENSITY_ALLOWANCE = 1.0F;
    private static final List<PendingThunder> PENDING_THUNDER = new ArrayList<>();
    /** Refilled every tick by the genesis whirl model; the client ticks on one thread, so one list is enough. */
    private static final List<GenesisWhirls.Whirl> GENESIS_WHIRL_SCRATCH = new ArrayList<>();
    private static float visualRain;
    private static float visualThunder;
    private static long lastSkyUpdateTick = -1000L;
    private static int stickDebrisSpawnedThisTick;
    private static int debrisCloudSpawnedThisTick;
    private static int structureParticlesSpawnedThisTick;

    private ClientWeatherEffects() {
    }

    public static void onClientTick(ClientTickEvent.Post event) {
        // The registry write for the selected art happens here rather than inside the atlas stitch:
        // every mod's stitch handler has run by the time a tick arrives, and the fields are read when a
        // particle is created, so a tick of latency costs nothing. It runs before the level test so a
        // reload on the title screen still lands.
        ParticleArt.applyPending();
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) {
            PENDING_THUNDER.clear();
            visualRain = 0.0F;
            visualThunder = 0.0F;
            StormSkyClient.clear();
            SatelliteTornadoFunnelRenderer.clear();
            AddonVortexParticleRenderer.clear();
            return;
        }
        if (minecraft.isPaused()) {
            return;
        }
        tickPendingThunder(minecraft);
        DamageSurveyResult surveyResult = ClientDamageSurveyStore.consume();
        if (surveyResult != null && Config.SURVEY_OPEN_GUI_ON_SURVEY.get()) {
            minecraft.setScreen(new DamageSurveyScreen(surveyResult));
        }
        if (!Config.VISUALS_ENABLED.get()) {
            SatelliteTornadoFunnelRenderer.clear();
            AddonVortexParticleRenderer.clear();
            ClientStructureFlight.clear();
            LeafEddies.clear();
            // The storm sky is a visual like any other, and a reading left standing would outlive the switch that
            // was meant to turn it off.
            StormSkyClient.clear();
            return;
        }
        SoftPuffs.tick(minecraft);
        stickDebrisSpawnedThisTick = 0;
        debrisCloudSpawnedThisTick = 0;
        structureParticlesSpawnedThisTick = 0;
        ClientAddonWeatherEventStore.tick(minecraft.level.getGameTime());
        spawnAddonWeatherEvents(minecraft.level, minecraft.player.position());
        AddonVortexParticleRenderer.cleanup(minecraft.level.getGameTime());
        WeatherManagerClient manager = ClientTickHandler.weatherManager;
        if (manager == null) {
            SatelliteTornadoFunnelRenderer.clear();
            ClientStructureFlight.clear();
            return;
        }
        // Buildings a tornado has taken: driven here rather than by the storm, because a body of blocks
        // that stays a body has to be moved as one thing.
        ClientStructureFlight.tick(minecraft.level, manager, minecraft.level.getGameTime());
        // The little spirals of leaves an inflow lifts before its storm arrives. Driven here rather than by
        // the storm, because what makes one is the air where the player is standing.
        LeafEddies.tick(minecraft.level, minecraft.player, manager);
        spawnSupercellStructures(minecraft.level, manager, minecraft.player.position());
        StormObject storm = manager.getClosestStormAny(minecraft.player.position(), 700.0D);
        float targetRain = 0.0F;
        float targetThunder = 0.0F;
        float squallLineProximity = squallLineSkyProximity(minecraft.player.position());
        if (squallLineProximity > 0.0F) {
            float tint = Config.SEVERE_SKY_TINT_STRENGTH.get().floatValue() * (0.42F + squallLineProximity * 0.70F);
            targetRain = Math.max(targetRain, tint);
            targetThunder = Math.max(targetThunder, tint * 0.82F);
        }
        if (storm == null) {
            smoothSky(minecraft.level, minecraft.player, manager, targetRain, targetThunder);
            SatelliteTornadoFunnelRenderer.clear();
            return;
        }
        double distance = storm.pos.distanceTo(minecraft.player.position());
        float proximity = Mth.clamp((float) (1.0D - distance / 700.0D), 0.0F, 1.0F);
        // How much cloud and rain is over the player, rather than how near the storm's centre is: the
        // shade then follows the deck's own shape and ends at its edge. The distance ramp survives as
        // the fallback for the config that turns cover off.
        float cover = StormShade.cover(minecraft.level, minecraft.player.position());
        if (cover <= 0.0F && !Config.STORM_COVER_SHADING.get()) {
            cover = proximity;
        }
        if (storm.levelCurIntensityStage >= StormObject.STATE_HAIL || DynamicStormController.isDynamicVortex(storm)) {
            float tint = Config.SEVERE_SKY_TINT_STRENGTH.get().floatValue() * cover;
            targetRain = tint;
            targetThunder = tint;
        }
        smoothSky(minecraft.level, minecraft.player, manager, targetRain, targetThunder);
        for (StormObject vortexStorm : dynamicVorticesInRange(manager, minecraft.player.position(), LodViewDistance.extendSpawnRange(Config.TORNADO_DEBRIS_CLOUD_RENDER_RANGE_BLOCKS.get()))) {
            spawnGenesisWhirlVisuals(minecraft.level, vortexStorm, minecraft.player.position());
            spawnTornadoDebrisCloud(minecraft.level, vortexStorm, minecraft.player.position());
            spawnSubVortexVisuals(minecraft.level, vortexStorm, minecraft.player.position());
            spawnHorizontalVortexVisuals(minecraft.level, vortexStorm, minecraft.player.position());
            spawnMainTornadoStickDebris(minecraft.level, vortexStorm, minecraft.player.position());
            spawnSubVortexStickDebris(minecraft.level, vortexStorm, minecraft.player.position());
            spawnSatelliteSwirls(minecraft.level, vortexStorm);
        }
        SatelliteTornadoFunnelRenderer.cleanup(minecraft.level.getGameTime());
    }

    public static void scheduleThunder(double x, double y, double z, float volume, float pitch) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) {
            return;
        }
        double distanceBlocks = minecraft.player.position().distanceTo(new Vec3(x, y, z));
        double miles = distanceBlocks / 1609.344D;
        int delay = Mth.clamp((int) Math.round(miles * Config.THUNDER_SECONDS_PER_MILE.get() * 20.0D), 0, Config.THUNDER_MAX_DELAY_TICKS.get());
        PENDING_THUNDER.add(new PendingThunder(x, y, z, volume, pitch, delay));
    }

    private static void tickPendingThunder(Minecraft minecraft) {
        Iterator<PendingThunder> iterator = PENDING_THUNDER.iterator();
        while (iterator.hasNext()) {
            PendingThunder thunder = iterator.next();
            thunder.delayTicks--;
            if (thunder.delayTicks <= 0) {
                float distance = (float) minecraft.player.position().distanceTo(new Vec3(thunder.x, thunder.y, thunder.z));
                float pitch = distance < 80.0F ? 1.18F : distance < 320.0F ? 0.92F : 0.68F;
                float volume = distance < 80.0F ? thunder.volume : thunder.volume * Mth.clamp(420.0F / Math.max(80.0F, distance), 0.18F, 0.85F);
                minecraft.level.playLocalSound(thunder.x, thunder.y, thunder.z, SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.WEATHER, volume, pitch, false);
                if (distance < 140.0F) {
                    minecraft.level.playLocalSound(thunder.x, thunder.y, thunder.z, SoundEvents.LIGHTNING_BOLT_IMPACT, SoundSource.WEATHER, 2.0F, 0.7F, false);
                }
                iterator.remove();
            }
        }
    }

    /**
     * Takes away Weather2's floating block cubes: little cubes cut from the ground's own textures that circle inside the
     * funnel. They were never blocks, only particles, and they read as cubes hanging in the air. The blocks a tornado
     * really picks up are entities of their own and are untouched.
     */
    public static void dropFakeDebris(StormObject storm) {
        if (storm == null || storm.listParticlesDebris == null || storm.listParticlesDebris.isEmpty()) {
            return;
        }
        Iterator<EntityRotFX> iterator = storm.listParticlesDebris.iterator();
        while (iterator.hasNext()) {
            EntityRotFX particle = iterator.next();
            if (particle instanceof ParticleCube) {
                particle.remove();
                iterator.remove();
            }
        }
    }

    private static List<StormObject> dynamicVorticesInRange(WeatherManagerClient manager, Vec3 playerPos, double rangeBlocks) {
        List<StormObject> storms = new ArrayList<>();
        double rangeSqr = rangeBlocks * rangeBlocks;
        for (WeatherObject object : new ArrayList<>(manager.getStormObjects())) {
            if (!(object instanceof StormObject storm) || storm.pos == null || DynamicStormController.isAbsorbedOrDecaying(storm) || !DynamicStormController.isDynamicVortex(storm)) {
                continue;
            }
            if (storm.pos.distanceToSqr(playerPos) <= rangeSqr) {
                storms.add(storm);
            }
        }
        storms.sort(Comparator.comparingDouble(storm -> storm.pos.distanceToSqr(playerPos)));
        return storms;
    }

    private static void spawnSupercellStructures(ClientLevel level, WeatherManagerClient manager, Vec3 playerPos) {
        if (!Config.SUPERCELL_STRUCTURE_ENABLED.get() || !Config.SUPERCELL_STRUCTURE_CLIENT_VISUALS.get() || Config.SUPERCELL_STRUCTURE_MAX_PARTICLES_PER_TICK.get() <= 0) {
            return;
        }
        int range = Config.SUPERCELL_STRUCTURE_RENDER_RANGE_BLOCKS.get();
        for (WeatherObject object : new ArrayList<>(manager.getStormObjects())) {
            if (!(object instanceof StormObject storm) || storm.pos == null || storm.pos.distanceTo(playerPos) > range || DynamicStormController.isAbsorbedOrDecaying(storm)) {
                continue;
            }
            DynamicStormState state = DynamicStormController.stateFor(storm);
            SupercellStructureService.tick(level, storm, state);
            if (state.supercellStructureStrength() <= 0.05F || !SupercellStructureService.isEligible(storm)) {
                continue;
            }
            spawnSupercellStructure(level, storm, state);
            if (structureParticlesSpawnedThisTick >= Config.SUPERCELL_STRUCTURE_MAX_PARTICLES_PER_TICK.get()) {
                return;
            }
        }
    }

    /** The storm whose structure is being spawned, for the air kept clear round its funnel. Null outside that. */
    private static StormObject structureFunnelStorm;
    private static double structureClearBelowY;
    private static int structureGroundY;

    /**
     * Nothing of the storm's own may hang in the air round its funnel. Rain curtains, hook puffs, scud and the rest are
     * all laid out round the mesocyclone, and the mesocyclone is where the tornado is, so left alone they pile up on
     * exactly the thing a player has come to look at. Everything from the wall cloud's underside to the ground is kept
     * out of a cylinder round the funnel's axis, wide enough for a puff's own width to miss it as well.
     */
    private static boolean inFunnelsClearAir(Vec3 pos, float scale) {
        StormObject storm = structureFunnelStorm;
        if (storm == null || pos.y >= structureClearBelowY || !storm.isTornadoFormingOrGreater()) {
            return false;
        }
        double topY = DynamicStormController.funnelTopWorldY(storm);
        float up = Double.isNaN(topY) ? 0.5F : (float) Mth.clamp((pos.y - (structureClearBelowY - (storm.pos.y - structureClearBelowY))) / Math.max(1.0D, topY - pos.y + 1.0D), 0.0F, 1.0F);
        Vec3 axis = DynamicStormController.funnelLayerCenterAt(storm, up);
        double wall = Math.max(DynamicStormController.funnelWallRadiusAt(storm, up), DynamicStormController.funnelWallRadiusAt(storm, 0.0F));
        double clear = wall * 1.35D + 26.0D + scale * 0.5D;
        double dx = pos.x - axis.x;
        double dz = pos.z - axis.z;
        return dx * dx + dz * dz < clear * clear;
    }

    private static void spawnSupercellStructure(ClientLevel level, StormObject storm, DynamicStormState state) {
        SupercellStructureService.StructureSample structure = SupercellStructureService.sample(storm, state);
        structureFunnelStorm = storm;
        structureGroundY = stormGroundY(level, storm);
        structureClearBelowY = visualY(level, storm.pos, structure.rainFreeBaseY() - wallCloudDepth(structure, state));
        try {
            spawnSupercellParts(level, storm, state, structure);
        } finally {
            structureFunnelStorm = null;
        }
    }

    private static void spawnSupercellParts(ClientLevel level, StormObject storm, DynamicStormState state, SupercellStructureService.StructureSample structure) {
        float strength = state.supercellStructureStrength();
        if (Config.SUPERCELL_STRUCTURE_ANVIL_PARTICLES.get()) {
            spawnAnvilAndOvershootingTop(level, structure, strength);
        }
        spawnRotatingUpdraftTower(level, storm, structure, state);
        if (Config.SUPERCELL_STRUCTURE_WALL_CLOUD_PARTICLES.get()) {
            spawnWallCloud(level, storm, structure, state);
        }
        spawnRearFlankClearSlot(level, structure, state);
        spawnRainFreeBaseAndInflowTail(level, structure, state);
        if (Config.SUPERCELL_STRUCTURE_FLANKING_LINE_PARTICLES.get()) {
            spawnFlankingLine(level, structure, strength);
        }
        spawnForwardFlankPrecip(level, structure, state.ffdStrength(), state.hailCoreStrength(),
                SupercellStructureService.precipitationCharacter(storm, state));
        spawnHookEchoRainWrapAndHailShaft(level, structure, state);
        if (Config.SUPERCELL_STRUCTURE_GUST_FRONT_PARTICLES.get()) {
            spawnRearFlankGustFront(level, structure, state.rfdStrength());
        }
        if (Config.SUPERCELL_STRUCTURE_VIRGA_MAMMATUS_PARTICLES.get()) {
            spawnVirgaAndMammatus(level, structure, strength, SupercellStructureService.virgaStrength(storm, state));
        }
    }

    /**
     * The anvil and the overshooting top.
     *
     * <p>Where the updraft runs into the tropopause it can climb no further and spreads out flat: the anvil, a broad
     * plume with a flat top that fans out downwind for several storm widths, thick and sagging near the tower and thin
     * at its far edge, with a little of it pushed back upwind over the tower in a strong storm. Over the tower itself the
     * updraft punches through the top anyway, in a bright dome. These are the things a supercell is recognised by from
     * a long way off, so they are drawn big and far.
     */
    private static void spawnAnvilAndOvershootingTop(ClientLevel level, SupercellStructureService.StructureSample structure, float strength) {
        double towerTop = updraftTowerTop(structure);
        double towerRadius = updraftTowerRadius(structure);
        double length = structure.radius() * 3.4D;
        Vec3 origin = structure.updraft().add(structure.forward().scale(towerLean(structure)));
        float wanted = strength * 2.6F;
        int count = (int) wanted + (level.random.nextFloat() < wanted - (int) wanted ? 1 : 0);
        for (int i = 0; i < count; i++) {
            double along = level.random.nextFloat() < 0.12F ? -level.random.nextDouble() * 0.18D : Math.pow(level.random.nextDouble(), 0.75D);
            double reach = Math.max(0.0D, along);
            double halfWidth = towerRadius * 1.15D + reach * structure.radius() * 1.30D;
            double lateral = (level.random.nextDouble() * 2.0D - 1.0D) * halfWidth * Math.sqrt(level.random.nextDouble());
            Vec3 at = origin.add(structure.forward().scale(along * length)).add(structure.right().scale(lateral));
            double thickness = Mth.lerp(reach, 70.0D, 24.0D);
            // Flat on top, where the tropopause stops it, and ragged underneath: most of the depth goes to the underside.
            double depth = Math.pow(level.random.nextDouble(), 1.6D) * thickness;
            boolean underside = depth > thickness * 0.45D;
            // The anvil's top faces the sun and is the brightest cloud in the sky - from far off a whole storm is often just
            // a white anvil on the horizon - and its underside is in its own shadow.
            float lit = underside ? SunShading.lit(level, 0.0D, -1.0D, 0.0D) : SunShading.lit(level, structure.forward().x * 0.4D, 1.0D, structure.forward().z * 0.4D);
            float[] colour = SunShading.cloudColour(level, underside ? 0.66F : 0.98F, lit, underside ? 0.80F : 0.74F);
            Vec3 pos = new Vec3(at.x, visualY(level, at, towerTop + 14.0D - depth), at.z);
            Vec3 velocity = structure.forward().scale(0.040D + strength * 0.020D);
            float size = (float) (60.0D + level.random.nextDouble() * 40.0D + reach * 30.0D);
            float alpha = (float) (0.40D * (1.0D - reach * 0.55D)) * (0.6F + strength * 0.4F);
            spawnStructureParticle(level, pos, velocity, ParticleArt.cloud256(), size, colour[0], colour[1], colour[2],
                    alpha, 300, 0.02F, ParticleTypes.CLOUD);
        }

        int domes = level.random.nextFloat() < strength ? 2 : 1;
        for (int i = 0; i < domes; i++) {
            double angle = level.random.nextDouble() * Math.PI * 2.0D;
            double share = Math.sqrt(level.random.nextDouble());
            double r = towerRadius * 0.45D * share;
            Vec3 at = origin.add(Math.cos(angle) * r, 0.0D, Math.sin(angle) * r);
            double rise = (1.0D - share * share) * 48.0D * level.random.nextDouble();
            Vec3 pos = new Vec3(at.x, visualY(level, at, towerTop + 14.0D + rise), at.z);
            spawnStructureParticle(level, pos, new Vec3(0.0D, 0.020D + strength * 0.012D, 0.0D), ParticleArt.cloud256(),
                    30.0F + level.random.nextFloat() * 16.0F, 0.90F, 0.91F, 0.94F, 0.38F, 160, 0.01F, ParticleTypes.CLOUD);
        }
    }

    private static void spawnWallCloud(ClientLevel level, StormObject storm, SupercellStructureService.StructureSample structure, DynamicStormState state) {
        float strength = state.wallCloudStrength();
        if (!Config.SUPERCELL_STRUCTURE_WALL_CLOUD_LOWERING.get() || strength <= 0.03F) {
            return;
        }
        double rotationSign = DynamicStormController.visualRotationSign(storm);
        // The wall cloud is drawn from big cloud quads, and the ones overhead when you stand under the
        // storm are what filled the screen. They are scaled down as a whole rather than thinned, so the
        // lowered base keeps its shape and rotation and simply reads as finer cloud. Shrinking them
        // costs the wall cloud no density at all: a fresh batch is spawned every tick and each puff
        // lives for seconds, so the lowered base is built out of thousands of puffs rather than dozens.
        float particleSize = Mth.clamp(Config.SUPERCELL_STRUCTURE_WALL_CLOUD_PARTICLE_SIZE.get().floatValue(), 0.05F, 2.0F);
        double radius = Math.max(14.0D, structure.wallCloudDiameter() * 0.5D);
        double lowering = wallCloudDepth(structure, state);
        double loweredBaseOffset = Mth.clamp(structure.rainFreeBaseY() - lowering, 10.0D, structure.rainFreeBaseY());
        int count = Mth.clamp(1 + (int) (strength * 1.5F), 1, 2);
        for (int i = 0; i < count; i++) {
            double angle = level.random.nextDouble() * Math.PI * 2.0D;
            double cosAngle = Math.cos(angle);
            double sinAngle = Math.sin(angle);
            double distance = Math.sqrt(level.random.nextDouble()) * radius;
            Vec3 base = structure.wallCloudCenter().add(cosAngle * distance, 0.0D, sinAngle * distance);
            if (horizontalDistance(base, structure.clearSlotCenter()) < radius * 0.34D && level.random.nextFloat() < 0.55F) {
                continue;
            }
            // A block of cloud rather than a plate: it fills the whole way from the lowered underside up into the base.
            double fill = level.random.nextDouble() * lowering * 0.9D;
            double raggedDrop = lowering * level.random.nextDouble() * 0.14D;
            Vec3 pos = new Vec3(base.x, visualY(level, base, loweredBaseOffset - raggedDrop + fill), base.z);
            Vec3 tangent = new Vec3(-sinAngle * rotationSign, 0.0D, cosAngle * rotationSign);
            Vec3 inflow = structure.mesocyclone().subtract(structure.inflowSource());
            inflow = inflow.horizontalDistanceSqr() < 1.0E-5D ? structure.forward().scale(-1.0D) : new Vec3(inflow.x, 0.0D, inflow.z).normalize();
            Vec3 velocity = tangent.scale(0.026D + state.wallCloudRotationStrength() * 0.070D)
                    .add(inflow.scale(0.010D + state.inflowStrength() * 0.030D))
                    .add(0.0D, 0.002D + strength * 0.008D, 0.0D);
            float puffSize = Mth.clamp((16.0F + strength * 22.0F) * Math.max(0.55F, particleSize), 0.9F, 48.0F);
            spawnStructureParticle(level, pos, velocity, ParticleArt.cloud256(), puffSize, 0.24F, 0.25F, 0.30F, 0.56F, 96, 0.16F, ParticleTypes.CLOUD);
        }

        // Scud: the ragged shreds of cloud torn off the inflow and dragged under the wall cloud. Real scud
        // is small - fists to a few blocks, not a cloud - so this is deliberately many tiny wisps rather
        // than a few big puffs, and the collar reads as shreds because it is made of them. They hang just
        // below the lowered base rather than at it, which is what makes the base look ragged underneath.
        float scudScale = Config.SUPERCELL_STRUCTURE_WALL_CLOUD_SCUD_SIZE.get().floatValue();
        if (Config.SUPERCELL_STRUCTURE_WALL_CLOUD_ROTATION_PARTICLES.get()
                && level.random.nextFloat() < Config.SUPERCELL_STRUCTURE_WALL_CLOUD_SCUD_CHANCE.get().floatValue()) {
            int tags = Mth.clamp(1 + (int) (state.wallCloudRotationStrength() * 3.0F), 1, 4);
            for (int i = 0; i < tags; i++) {
                double angle = level.random.nextDouble() * Math.PI * 2.0D;
                double cosAngle = Math.cos(angle);
                double sinAngle = Math.sin(angle);
                double distance = radius * (0.74D + level.random.nextDouble() * 0.34D);
                Vec3 base = structure.wallCloudCenter().add(cosAngle * distance, 0.0D, sinAngle * distance);
                double hang = 0.6D + level.random.nextDouble() * Math.max(2.2D, lowering * 0.30D);
                Vec3 pos = new Vec3(base.x, visualY(level, base, loweredBaseOffset - hang), base.z);
                Vec3 velocity = new Vec3(-sinAngle * rotationSign, 0.012D + strength * 0.014D, cosAngle * rotationSign)
                        .scale(0.052D + state.wallCloudRotationStrength() * 0.105D);
                float tagSize = Mth.clamp((0.75F + strength * 1.10F) * scudScale, 0.25F, 4.0F);
                spawnStructureParticle(level, pos, velocity, ParticleRegistry.cloud, tagSize, 0.32F, 0.33F, 0.38F, 0.52F, 54, 0.20F, ParticleTypes.CLOUD);
            }
        }
        spawnInflowScud(level, storm, structure, state, loweredBaseOffset, rotationSign, scudScale);
    }

    /**
     * Inflow scud: the shreds born low and upwind that climb toward the lowered wall-cloud base.
     *
     * <p>Distinct from the collar above, which rotates around the base in place. These start downwind of the
     * inflow source - out over the field the storm is eating - and are carried toward the mesocyclone as they
     * rise, which is the motion a viewer actually sees under a wall cloud: ragged fragments streaming up the
     * inflow chute rather than a ring of cloud.
     */
    private static void spawnInflowScud(ClientLevel level, StormObject storm, SupercellStructureService.StructureSample structure,
                                        DynamicStormState state, double loweredBaseOffset, double rotationSign, float scudScale) {
        if (!Config.SUPERCELL_STRUCTURE_WALL_CLOUD_SCUD_FEED_PARTICLES.get()) {
            return;
        }
        float strength = state.wallCloudStrength();
        if (strength <= 0.05F) {
            return;
        }
        Vec3 inflow = structure.mesocyclone().subtract(structure.inflowSource());
        inflow = inflow.horizontalDistanceSqr() < 1.0E-5D
                ? structure.forward().scale(-1.0D)
                : new Vec3(inflow.x, 0.0D, inflow.z).normalize();
        int count = level.random.nextFloat() < state.inflowStrength() ? 1 : 0;
        for (int i = 0; i < count; i++) {
            Vec3 base = structure.inflowSource().lerp(structure.wallCloudCenter(), 0.20D + level.random.nextDouble() * 0.75D)
                    .add(structure.right().scale((level.random.nextDouble() - 0.5D) * 30.0D));
            double drop = 1.6D + level.random.nextDouble() * Math.max(2.6D, wallCloudDepth(structure, state) * 0.55D);
            Vec3 pos = new Vec3(base.x, visualY(level, base, loweredBaseOffset - drop), base.z);
            Vec3 tangent = new Vec3(-inflow.z * rotationSign, 0.0D, inflow.x * rotationSign);
            Vec3 velocity = inflow.scale(0.045D + state.inflowStrength() * 0.095D)
                    .add(tangent.scale(0.018D + state.wallCloudRotationStrength() * 0.050D))
                    .add(0.0D, 0.012D + strength * 0.020D, 0.0D);
            float size = Mth.clamp((0.65F + strength * 1.00F) * scudScale, 0.22F, 3.5F);
            spawnStructureParticle(level, pos, velocity, ParticleRegistry.cloud, size, 0.34F, 0.35F, 0.40F, 0.46F, 46, 0.22F, ParticleTypes.CLOUD);
        }
    }

    private static void spawnRainFreeBaseAndInflowTail(ClientLevel level, SupercellStructureService.StructureSample structure, DynamicStormState state) {
        float strength = state.supercellStructureStrength();
        if (strength <= 0.08F) {
            return;
        }

        int baseCount = Mth.clamp(Math.round(strength * 2.2F), 1, 3);
        // The storm's flat skirt. Real base strata spread well beyond the tower's footprint -
        // the reference look is a base noticeably wider than the stem - so the skirt rings
        // extend out past the wall cloud diameter and cluster in two altitude sheets to read
        // as stratified layers rather than one slab.
        double skirtRadius = Math.max(60.0D, Math.max(structure.wallCloudDiameter() * 1.5D, structure.radius() * 0.72D));
        long skirtSeed = (stormSeedOffset(structure) * 0x9E3779B97F4A7C15L) >>> 16;
        for (int i = 0; i < baseCount; i++) {
            boolean innerSheet = (i & 1) == 0;
            double ring = innerSheet ? 0.30D + level.random.nextDouble() * 0.34D : 0.62D + level.random.nextDouble() * 0.36D;
            double slot = ((skirtSeed + i * 0x2545F4914F6CDD1DL) >>> 11) % 7200L / 7200.0D;
            double angle = slot * Math.PI * 2.0D + level.getGameTime() * 0.004D;
            double cosAngle = Math.cos(angle);
            double sinAngle = Math.sin(angle);
            double along = cosAngle * skirtRadius * ring;
            double lateral = sinAngle * skirtRadius * ring * 0.82D;
            Vec3 base = structure.rainFreeBase()
                    .add(structure.forward().scale(along))
                    .add(structure.right().scale(lateral));
            // Inner sheet hugs the cloud base; outer sheet rides a few blocks lower and darker
            // so the skirt edge reads as a separate stratum overhanging the inflow.
            double sheetLift = innerSheet ? level.random.nextDouble() * 6.0D : 2.0D + level.random.nextDouble() * 4.0D;
            Vec3 pos = new Vec3(base.x, visualY(level, base, structure.rainFreeBaseY() + sheetLift), base.z);
            Vec3 velocity = structure.forward().scale(0.006D + strength * 0.012D).add(structure.right().scale(-0.004D)).add(0.0D, 0.001D, 0.0D);
            float sheetShade = innerSheet ? 0.58F : 0.48F;
            spawnStructureParticle(level, pos, velocity, ParticleArt.cloud256(),
                    26.0F + strength * 24.0F + level.random.nextFloat() * 12.0F,
                    sheetShade * 0.60F, sheetShade * 0.62F, sheetShade * 0.70F, 0.26F, 170, 0.02F, ParticleTypes.CLOUD);
        }

        // The inflow tail: a low smooth band of cloud reaching out from the wall cloud along the air feeding it, thinning
        // with distance. The air it is made of is on its way in, so it drifts inward as it goes.
        float inflow = state.inflowStrength();
        if (inflow > 0.08F) {
            Vec3 toWall = structure.wallCloudCenter().subtract(structure.inflowSource());
            Vec3 inward = toWall.horizontalDistanceSqr() < 1.0E-5D ? structure.forward().scale(-1.0D) : new Vec3(toWall.x, 0.0D, toWall.z).normalize();
            int tailCount = level.random.nextFloat() < inflow ? 1 : 0;
            for (int i = 0; i < tailCount; i++) {
                double t = level.random.nextDouble();
                Vec3 base = structure.wallCloudCenter().lerp(structure.inflowSource(), t)
                        .add(structure.right().scale((level.random.nextDouble() - 0.5D) * (20.0D + t * 34.0D)));
                Vec3 pos = new Vec3(base.x, visualY(level, base, structure.rainFreeBaseY() - wallCloudDepth(structure, state) * 0.35D * (1.0D - t) - level.random.nextDouble() * 5.0D), base.z);
                spawnStructureParticle(level, pos, inward.scale(0.05D + inflow * 0.08D), ParticleArt.cloud256(),
                        16.0F + (float) t * 12.0F + level.random.nextFloat() * 7.0F,
                        0.40F, 0.42F, 0.48F, 0.28F * (1.0F - (float) t * 0.6F), 120, 0.02F, ParticleTypes.CLOUD);
            }
        }
    }

    /** How far the wall cloud hangs below the base. The state's depth was chosen for a base forty-odd blocks up. */
    private static double wallCloudDepth(SupercellStructureService.StructureSample structure, DynamicStormState state) {
        return Math.min(state.wallCloudLoweringDepth() * Mth.clamp(structure.rainFreeBaseY() / 44.0D, 1.0D, 3.0D), structure.rainFreeBaseY() * 0.20D);
    }

    /**
     * How high the storm's rotating tower reaches above the ground, which is where the anvil spreads. A real one tops out
     * ten times higher than its base; this is two or three times, which is as far as a sky can be drawn and still be
     * seen, and enough for the tower to stand well clear of the deck rather than level with it.
     */
    private static double updraftTowerTop(SupercellStructureService.StructureSample structure) {
        return structure.rainFreeBaseY() + Mth.clamp(130.0D + structure.radius() * 0.85D, 200.0D, 400.0D);
    }

    /** How wide the updraft tower is, as a radius. A low-precipitation storm's is narrower and crisper. */
    private static double updraftTowerRadius(SupercellStructureService.StructureSample structure) {
        boolean lp = structure.mode() == SupercellStructureService.SupercellMode.LP;
        return Math.max(44.0D, structure.radius() * (lp ? 0.36D : 0.46D));
    }

    /** How far downwind the top of the tower is carried past its foot, in blocks. */
    private static double towerLean(SupercellStructureService.StructureSample structure) {
        return structure.radius() * 0.30D;
    }

    /** Storm-derived but direction-free seed, so the skirt's deterministic slots differ per storm without leaning on position. */
    private static long stormSeedOffset(SupercellStructureService.StructureSample structure) {
        return Double.doubleToLongBits(structure.updraft().x) ^ Double.doubleToLongBits(structure.updraft().z);
    }

    private static void spawnRotatingUpdraftTower(ClientLevel level, StormObject storm, SupercellStructureService.StructureSample structure, DynamicStormState state) {
        float meso = state.mesocycloneStrength();
        if (meso <= 0.08F) {
            return;
        }
        double rotationSign = DynamicStormController.visualRotationSign(storm);
        double towerRadius = updraftTowerRadius(structure);
        double base = structure.rainFreeBaseY();
        double top = updraftTowerTop(structure);
        int count = Mth.clamp(Math.round(1.0F + meso * 3.5F), 1, 5);
        for (int i = 0; i < count; i++) {
            double band = Math.pow(level.random.nextDouble(), 0.85D);
            // A supercell updraft's outline: a broad, striated mesocyclone at its foot - the "mothership" - narrowing into
            // the knuckled column of the tower, which spreads out again where it runs into the anvil.
            double radiusShare;
            if (band < 0.30D) {
                // The foot is a stack of flat, flared plates, each a little narrower than the one under it, with a thin
                // gap of shadow between them: the "bell-bottom" of a rotating updraft. Spread evenly up the band it was a
                // smooth cone; held to three shelves it reads as the layered base the photographs show.
                int shelf = Math.min(2, (int) (band / 0.10D));
                double within = (band - shelf * 0.10D) / 0.10D;
                band = shelf * 0.10D + within * 0.055D;
                radiusShare = (1.52D - shelf * 0.24D) * (1.0D - 0.10D * within);
            } else if (band < 0.82D) {
                radiusShare = 0.74D + 0.06D * Math.sin(band * 23.0D + storm.ID);
            } else {
                radiusShare = Mth.lerp((band - 0.82D) / 0.18D, 0.74D, 1.10D);
            }
            double angle = level.random.nextDouble() * Math.PI * 2.0D;
            // Most puffs on the skin of the column, where its lumpy edge is, and some inside it so it is solid.
            double skin = level.random.nextFloat() < 0.78F ? 0.82D + level.random.nextDouble() * 0.18D : Math.sqrt(level.random.nextDouble()) * 0.8D;
            double r = towerRadius * radiusShare * skin;
            // It leans downwind as it climbs, the top carried on ahead of the foot.
            Vec3 lean = structure.forward().scale(band * band * towerLean(structure));
            Vec3 at = structure.updraft().add(Math.cos(angle) * r, 0.0D, Math.sin(angle) * r).add(lean);
            Vec3 tangent = new Vec3(-Math.sin(angle) * rotationSign, 0.0D, Math.cos(angle) * rotationSign);
            // The turning shows most at the foot, where the mesocyclone is; the tower above it mostly boils.
            double spin = (0.035D + meso * 0.060D) * (1.0D - band * 0.7D);
            Vec3 velocity = tangent.scale(spin).add(structure.forward().scale(0.006D)).add(0.0D, 0.010D + meso * 0.018D, 0.0D);
            // The striations: shelves of light and dark wound round the lower part of a turning updraft.
            double striation = band < 0.45D ? 0.84D + 0.16D * Math.sin(band * Math.PI * 14.0D + angle * rotationSign * 0.5D) : 1.0D;
            // Lit by the sun from the side it is on: near white toward it with crisp bright edges, blue-grey away from it,
            // the crown lit from above and the foot in the storm's own shadow. Shaded by height alone the tower was the
            // same grey all the way round and read as a flat cut-out.
            float lit = SunShading.lit(level, Math.cos(angle), (band - 0.30D) * 1.4D, Math.sin(angle));
            float height = (float) Mth.lerp(Math.pow(band, 0.6D), 0.62D, 1.0D);
            float[] colour = SunShading.cloudColour(level, (float) (0.97D * height * striation), lit, 0.56F);
            float size = (float) ((34.0D + meso * 18.0D + level.random.nextDouble() * 16.0D) * (0.85D + band * 0.45D));
            Vec3 pos = new Vec3(at.x, visualY(level, at, Mth.lerp(band, base + 4.0D, top)), at.z);
            spawnStructureParticle(level, pos, velocity, ParticleArt.cloud256(), size, colour[0], colour[1], colour[2],
                    0.34F + meso * 0.18F, 220, 0.04F, ParticleTypes.CLOUD);
        }
    }

    private static void spawnRearFlankClearSlot(ClientLevel level, SupercellStructureService.StructureSample structure, DynamicStormState state) {
        float rfd = state.rfdStrength();
        if (rfd <= 0.10F) {
            return;
        }
        int count = level.random.nextFloat() < rfd * 0.5F ? 1 : 0;
        for (int i = 0; i < count; i++) {
            Vec3 base = structure.clearSlotCenter()
                    .add(structure.forward().scale((level.random.nextDouble() - 0.55D) * structure.radius() * 0.22D))
                    .add(structure.right().scale((level.random.nextDouble() - 0.45D) * structure.radius() * 0.18D));
            Vec3 outward = base.subtract(structure.mesocyclone());
            outward = outward.horizontalDistanceSqr() < 1.0E-5D ? structure.forward().add(structure.right()).normalize() : new Vec3(outward.x, 0.0D, outward.z).normalize();
            Vec3 pos = new Vec3(base.x, visualY(level, base, 14.0D + level.random.nextDouble() * structure.rainFreeBaseY() * 0.45D), base.z);
            Vec3 velocity = outward.scale(0.070D + rfd * 0.092D).add(0.0D, -0.018D, 0.0D);
            spawnStructureParticle(level, pos, velocity, ParticleRegistry.cloud,
                    5.5F + rfd * 7.5F, 0.42F, 0.40F, 0.36F, 0.24F, 58, 0.18F, ParticleTypes.CLOUD);
        }
    }

    private static void spawnHookEchoRainWrapAndHailShaft(ClientLevel level, SupercellStructureService.StructureSample structure, DynamicStormState state) {
        float meso = state.mesocycloneStrength();
        if (meso <= 0.08F) {
            return;
        }

        int hookCount = level.random.nextFloat() < meso * 0.5F ? 1 : 0;
        for (int i = 0; i < hookCount; i++) {
            double angle = level.random.nextDouble() * Math.PI * 2.0D;
            double cosAngle = Math.cos(angle);
            double sinAngle = Math.sin(angle);
            double distance = (0.25D + level.random.nextDouble() * 0.75D) * Math.max(12.0D, structure.wallCloudDiameter() * 0.30D);
            Vec3 base = structure.hookEchoNotch().add(cosAngle * distance, 0.0D, sinAngle * distance);
            Vec3 towardMeso = structure.mesocyclone().subtract(base);
            towardMeso = towardMeso.horizontalDistanceSqr() < 1.0E-5D ? structure.forward().scale(-1.0D) : new Vec3(towardMeso.x, 0.0D, towardMeso.z).normalize();
            Vec3 hookCurl = structure.right().scale(-0.040D - meso * 0.060D).add(towardMeso.scale(0.035D + meso * 0.050D));
            Vec3 pos = new Vec3(base.x, visualY(level, base, 12.0D + level.random.nextDouble() * Math.max(24.0D, structure.rainFreeBaseY())), base.z);
            spawnStructureParticle(level, pos, hookCurl.add(0.0D, 0.010D + meso * 0.014D, 0.0D), ParticleRegistry.cloud, 5.6F + meso * 7.8F, 0.30F, 0.32F, 0.37F, 0.22F, 64, 0.20F, ParticleTypes.CLOUD);
        }

        // Rain-wrap: heavy in a heavy-precipitation storm, lighter in a classic one, and none in a dry one.
        float wrap = (structure.mode() == SupercellStructureService.SupercellMode.HP ? 1.0F
                : structure.mode() == SupercellStructureService.SupercellMode.CLASSIC ? 0.40F : 0.0F)
                * Mth.clamp(state.ffdStrength() + state.rfdStrength(), 0.35F, 1.4F);
        if (wrap > 0.05F && structureFunnelStorm != null && structureFunnelStorm.isTornadoFormingOrGreater()) {
            spawnRainWrap(level, structureFunnelStorm, structure, wrap);
        }
        if (structure.mode() == SupercellStructureService.SupercellMode.LP && state.hailCoreStrength() > 0.24F) {
            int hailCount = Mth.clamp(1 + (int) (state.hailCoreStrength() * 5.0F), 1, 6);
            for (int i = 0; i < hailCount; i++) {
                Vec3 base = structure.hailShaft()
                        .add(structure.forward().scale((level.random.nextDouble() - 0.35D) * structure.radius() * 0.36D))
                        .add(structure.right().scale((level.random.nextDouble() - 0.50D) * structure.radius() * 0.28D));
                Vec3 pos = new Vec3(base.x, visualY(level, base, 12.0D + level.random.nextDouble() * structure.rainFreeBaseY() * 0.92D), base.z);
                Vec3 velocity = structure.forward().scale(0.018D).add(structure.right().scale(-0.010D)).add(0.0D, -0.24D - state.hailCoreStrength() * 0.18D, 0.0D);
                spawnStructureParticle(level, pos, velocity, ParticleRegistry.groundSplash, 0.80F + state.hailCoreStrength() * 0.95F, 0.86F, 0.92F, 1.00F, 0.62F, 30, 0.02F, ParticleTypes.RAIN);
            }
        }
    }

    private static final int FLANKING_TOWERS = 5;

    /**
     * The rain the rear-flank downdraft wraps round a tornado.
     *
     * <p>In a heavy-precipitation storm the curtain closes nearly all the way round the funnel, leaving only the notch the
     * warm inflow comes in through, and there is rain right up against the funnel as well: from most sides the tornado is
     * a darker shape in grey rain, or not there at all, which is what makes an HP storm the dangerous one. A classic storm
     * wraps only its back. The curtain is built of heavy rain sheets from the ground to the base, each moving mostly
     * inward so that it faces outward like a wall, swept round cyclonically the way the downdraft carries it, and its
     * far end swings wider into the forward-flank core: the hook. It is the one part of the storm let into the funnel's
     * clear air, because hiding the funnel is the whole of what it does.
     */
    private static void spawnRainWrap(ClientLevel level, StormObject storm, SupercellStructureService.StructureSample structure, float wrap) {
        boolean heavy = structure.mode() == SupercellStructureService.SupercellMode.HP;
        float strength = Mth.clamp(wrap, 0.0F, 1.4F);
        double wall = Math.max(10.0D, DynamicStormController.funnelWallRadiusAt(storm, 0.15F));
        double rotationSign = DynamicStormController.visualRotationSign(storm);
        Vec3 inflow = structure.forward().add(structure.right());
        double notchAngle = Math.atan2(inflow.z, inflow.x);
        double open = Math.toRadians(heavy ? 70.0D : 190.0D);
        double span = Math.PI * 2.0D - open;
        double base = structure.rainFreeBaseY();
        double curtainDepth = structure.radius() * (heavy ? 0.30D : 0.20D);
        int curtain = Mth.clamp(Math.round((heavy ? 7.0F : 3.5F) * strength), 1, 10);
        int veil = heavy ? Mth.clamp(Math.round(3.0F * strength), 1, 4) : 0;
        structureInClearAir = true;
        try {
            for (int i = 0; i < curtain + veil; i++) {
                boolean inner = i >= curtain;
                double along = level.random.nextDouble();
                double angle = notchAngle + rotationSign * (open * 0.5D + along * span);
                double radius = inner
                        ? wall * (1.05D + level.random.nextDouble() * 0.55D) + 4.0D
                        : wall * 1.6D + 10.0D + level.random.nextDouble() * curtainDepth + along * structure.radius() * 0.12D;
                double cos = Math.cos(angle);
                double sin = Math.sin(angle);
                Vec3 at = storm.pos.add(cos * radius, 0.0D, sin * radius);
                // A sheet covers a stretch of the fall; stacked at every height from the ground to the base, they are the curtain.
                Vec3 pos = new Vec3(at.x, visualY(level, at, level.random.nextDouble() * base * 0.92D), at.z);
                Vec3 inward = new Vec3(-cos, 0.0D, -sin);
                Vec3 tangent = new Vec3(-sin * rotationSign, 0.0D, cos * rotationSign);
                Vec3 velocity = inward.scale(0.10D).add(tangent.scale(0.07D + strength * 0.05D)).add(0.0D, -0.05D, 0.0D);
                float shade = inner ? 0.44F : 0.36F + level.random.nextFloat() * 0.06F;
                float alpha = inner ? 0.20F + strength * 0.06F : 0.30F + strength * 0.12F;
                spawnStructureParticle(level, pos, velocity, RainShaftSprites.sheet(level.random, inner ? 0.62F : 0.88F),
                        (inner ? 30.0F : 38.0F) + level.random.nextFloat() * 18.0F, shade, shade * 1.08F, shade * 1.26F,
                        alpha, 70 + level.random.nextInt(26), 0.05F, ParticleTypes.RAIN);
            }
            // The rain foot: where the curtain meets the ground it throws up a low grey mist.
            if (heavy && level.random.nextFloat() < strength * 0.8F) {
                double angle = notchAngle + rotationSign * (open * 0.5D + level.random.nextDouble() * span);
                double radius = wall * 1.6D + 10.0D + level.random.nextDouble() * curtainDepth;
                Vec3 at = storm.pos.add(Math.cos(angle) * radius, 0.0D, Math.sin(angle) * radius);
                Vec3 pos = new Vec3(at.x, visualY(level, at, 1.0D + level.random.nextDouble() * 7.0D), at.z);
                Vec3 sweep = new Vec3(-Math.sin(angle) * rotationSign, 0.0D, Math.cos(angle) * rotationSign).scale(0.08D);
                spawnStructureParticle(level, pos, sweep, ParticleArt.cloud256(), 20.0F + level.random.nextFloat() * 14.0F,
                        0.56F, 0.58F, 0.64F, 0.20F, 90, 0.10F, ParticleTypes.CLOUD);
            }
        } finally {
            structureInClearAir = false;
        }
    }

    /**
     * The flanking line: a row of towering cumulus along the rear-flank gust front, each tower a step taller than the one
     * behind it, climbing to the main updraft. Every tower is its own column with a rounded crown, so the line reads as
     * a staircase of separate towers rather than a smear of cloud.
     */
    private static void spawnFlankingLine(ClientLevel level, SupercellStructureService.StructureSample structure, float strength) {
        if (strength <= 0.2F) {
            return;
        }
        double base = structure.rainFreeBaseY();
        double top = updraftTowerTop(structure);
        int count = level.random.nextFloat() < strength ? 2 : 1;
        for (int i = 0; i < count; i++) {
            int tower = level.random.nextInt(FLANKING_TOWERS);
            // 0 at the far end of the line, 1 beside the main updraft.
            double t = (tower + 0.5D) / FLANKING_TOWERS;
            Vec3 centre = structure.flankingLineStart().lerp(structure.flankingLineEnd(), t);
            double towerTop = base + (top - base) * (0.14D + 0.44D * t * t);
            double towerRadius = 16.0D + t * 28.0D;
            double band = Math.pow(level.random.nextDouble(), 0.7D);
            double angle = level.random.nextDouble() * Math.PI * 2.0D;
            // A column that bulges into its crown.
            double crown = band > 0.72D ? 1.0D + (band - 0.72D) * 1.1D : 0.82D;
            double r = towerRadius * crown * (0.55D + 0.45D * level.random.nextDouble());
            Vec3 at = centre.add(Math.cos(angle) * r, 0.0D, Math.sin(angle) * r);
            Vec3 pos = new Vec3(at.x, visualY(level, at, Mth.lerp(band, base - 4.0D, towerTop)), at.z);
            Vec3 velocity = structure.forward().scale(0.008D).add(0.0D, 0.010D + t * 0.012D, 0.0D);
            float shade = (float) Mth.lerp(band, 0.50D, 0.86D);
            float scale = (float) (18.0D + t * 18.0D + level.random.nextDouble() * 10.0D);
            spawnStructureParticle(level, pos, velocity, ParticleArt.cloud256(), scale, shade * 0.95F, shade * 0.96F, shade,
                    0.30F + (float) t * 0.14F, 200, 0.03F, ParticleTypes.CLOUD);
        }
    }

    /**
     * Forward-flank precipitation. The storm's precipitation character decides how much of it there
     * is: a dry LP supercell is mostly hail and virga with hardly any rain reaching the ground, while
     * an HP supercell buries the forward flank in rain.
     */
    private static void spawnForwardFlankPrecip(ClientLevel level, SupercellStructureService.StructureSample structure, float ffdStrength, float hailCoreStrength, float character) {
        float wet = Mth.clamp(character, 0.0F, 1.0F);
        double base = structure.rainFreeBaseY();
        // The rain core: a grey curtain hanging from the base to the ground under the forward flank, heaviest and darkest
        // beside the updraft where the hail comes down and thinning toward its leading edge. It is built of rain sheets,
        // as the rain-wrap is, half of them facing across the storm and half along it, so it reads from any side. A dry
        // storm hardly has one.
        int sheets = Mth.clamp(Math.round((1.0F + ffdStrength * 4.5F) * Mth.lerp(wet, 0.15F, 1.0F)), 0, 6);
        for (int i = 0; i < sheets; i++) {
            double ahead = level.random.nextDouble();
            Vec3 at = structure.forwardFlank()
                    .add(structure.forward().scale((ahead - 0.35D) * structure.radius() * 0.80D))
                    .add(structure.right().scale((level.random.nextDouble() - 0.55D) * structure.radius() * (0.45D + ahead * 0.35D)));
            Vec3 pos = new Vec3(at.x, visualY(level, at, level.random.nextDouble() * base * 0.92D), at.z);
            Vec3 facing = (i & 1) == 0 ? structure.right().scale(-0.06D) : structure.forward().scale(0.06D);
            Vec3 velocity = facing.add(0.0D, -0.05D, 0.0D);
            float heaviness = (float) (1.0D - ahead * 0.6D);
            // Lighter than the base it hangs from, not darker: a rain core is lit by the daylight scattered through it and
            // seen against the brighter air behind, a soft grey curtain under a dark base in every photograph. Drawn as
            // dark as the base, it merged into it and the storm read as one dark mass to the ground.
            float shade = 0.54F + (1.0F - heaviness) * 0.16F;
            spawnStructureParticle(level, pos, velocity, RainShaftSprites.sheet(level.random, 0.45F + heaviness * 0.45F),
                    40.0F + level.random.nextFloat() * 20.0F, shade, shade * 1.03F, shade * 1.09F,
                    (0.18F + heaviness * 0.20F) * (0.5F + ffdStrength * 0.5F), 90 + level.random.nextInt(30), 0.06F, ParticleTypes.RAIN);
        }
        // Hail bouncing at the foot of the core. Dry air aloft means it survives to the ground more often in an LP storm.
        float hailChance = Mth.clamp(hailCoreStrength * Mth.lerp(0.65F, 0.30F, wet), 0.0F, 0.85F);
        if (hailCoreStrength > 0.30F && level.random.nextFloat() < hailChance * 0.45F) {
            Vec3 at = structure.forwardFlank()
                    .add(structure.forward().scale((level.random.nextDouble() - 0.3D) * structure.radius() * 0.40D))
                    .add(structure.right().scale((level.random.nextDouble() - 0.55D) * structure.radius() * 0.30D));
            Vec3 pos = new Vec3(at.x, visualY(level, at, 1.0D + level.random.nextDouble() * 3.0D), at.z);
            spawnStructureParticle(level, pos, new Vec3(0.0D, -0.20D, 0.0D), ParticleRegistry.groundSplash, 0.65F + hailCoreStrength * 0.75F,
                    0.86F, 0.90F, 0.96F, 0.55F, 26, 0.02F, ParticleTypes.RAIN);
        }
    }

    private static void spawnRearFlankGustFront(ClientLevel level, SupercellStructureService.StructureSample structure, float rfdStrength) {
        int count = level.random.nextFloat() < rfdStrength * 0.12F ? 1 : 0;
        for (int i = 0; i < count; i++) {
            Vec3 base = structure.gustFrontLeft().lerp(structure.gustFrontRight(), level.random.nextDouble());
            Vec3 outward = new Vec3(base.x - structure.mesocyclone().x, 0.0D, base.z - structure.mesocyclone().z);
            if (outward.horizontalDistanceSqr() < 1.0E-5D) {
                outward = structure.forward().add(structure.right()).normalize();
            } else {
                outward = outward.normalize();
            }
            Vec3 pos = new Vec3(base.x, visualY(level, base, 1.2D + level.random.nextDouble() * 4.0D), base.z);
            Vec3 velocity = outward.scale(0.09D + rfdStrength * 0.15D).add(0.0D, 0.012D, 0.0D);
            spawnStructureParticle(level, pos, velocity, ParticleRegistry.cloud, 12.0F + rfdStrength * 8.0F, 0.50F, 0.48F, 0.44F, 0.10F, 60, 0.20F, ParticleTypes.CLOUD);
        }
    }

    private static void spawnSupercellInflow(ClientLevel level, SupercellStructureService.StructureSample structure, float inflowStrength) {
        if (!Config.INFLOW_JETS_ENABLED.get()) {
            return;
        }
        int count = Mth.clamp(1 + (int) (inflowStrength * 4.0F), 1, Math.max(1, Config.INFLOW_JET_PARTICLES_PER_TICK.get()));
        Vec3 direction = structure.mesocyclone().subtract(structure.inflowSource());
        direction = direction.horizontalDistanceSqr() < 1.0E-5D ? structure.forward().scale(-1.0D) : new Vec3(direction.x, 0.0D, direction.z).normalize();
        for (int i = 0; i < count; i++) {
            double t = level.random.nextDouble();
            Vec3 base = structure.inflowSource().lerp(structure.mesocyclone(), t)
                    .add(structure.right().scale((level.random.nextDouble() - 0.5D) * 26.0D));
            Vec3 pos = new Vec3(base.x, visualY(level, base, 1.1D + level.random.nextDouble() * 2.0D), base.z);
            Vec3 velocity = direction.scale(0.18D + inflowStrength * 0.16D).add(0.0D, 0.006D, 0.0D);
            spawnStructureParticle(level, pos, velocity, ParticleRegistry.cloud, 3.2F + inflowStrength * 2.8F, 0.64F, 0.66F, 0.70F, 0.36F, 34, 0.10F, ParticleTypes.CLOUD);
        }
    }

    /**
     * The shafts hanging out of the anvil and the storm's mid-level base, plus the mammatus that
     * follows anvil virga. This is where virga is most often seen on a real storm: the anvil is
     * separated from the ground by the deepest, driest layer of air there is, so its precipitation
     * commonly never arrives. Likely to appear scales with the storm's own virga strength, so a moist
     * storm does not sprout wisps it has no reason to have.
     */
    private static void spawnVirgaAndMammatus(ClientLevel level, SupercellStructureService.StructureSample structure, float strength, float virga) {
        if (virga <= 0.05F || level.random.nextFloat() > (0.20F + strength * 0.30F) * (0.35F + virga)) {
            return;
        }
        Vec3 base = structure.anvilCore()
                .add(structure.forward().scale((level.random.nextDouble() - 0.35D) * structure.radius() * 1.12D))
                .add(structure.right().scale((level.random.nextDouble() - 0.5D) * structure.radius() * 0.86D));
        Vec3 pos = new Vec3(base.x, visualY(level, base, updraftTowerTop(structure) - 44.0D - level.random.nextDouble() * 26.0D), base.z);
        Vec3 velocity = new Vec3(0.0D, -0.035D - level.random.nextDouble() * 0.035D, 0.0D).add(structure.forward().scale(0.008D));
        if (level.random.nextFloat() < 0.62F) {
            // A shaft: the frayed falling-rain texture, so it reads as precipitation dissolving rather
            // than as another puff of cloud.
            spawnStructureParticle(level, pos, velocity, RainShaftSprites.virgaSheet(level.random, 0.30F + virga * 0.30F),
                    7.0F + strength * 7.0F + virga * 6.0F,
                    0.62F, 0.64F, 0.72F, 0.20F + virga * 0.16F, 74, 0.05F, ParticleTypes.CLOUD);
            return;
        }
        spawnStructureParticle(level, pos, velocity, ParticleRegistry.cloud, 3.6F + strength * 5.5F, 0.58F, 0.58F, 0.66F, 0.23F, 72, 0.04F, ParticleTypes.CLOUD);
    }

    private static void smoothSky(ClientLevel level, Player player, WeatherManagerClient manager, float targetRain,
                                  float targetThunder) {
        long gameTime = level.getGameTime();
        if (gameTime - lastSkyUpdateTick < Config.MIN_SKY_UPDATE_TICKS.get()) {
            return;
        }
        lastSkyUpdateTick = gameTime;
        // The wet season's own sky. A monsoon is a season rather than a storm, so its overcast is here with no
        // storm anywhere in range, and it is grey rather than black: this is the whole of its visual identity,
        // and it comes from the calendar and the local air rather than from anything the server sent.
        float season = monsoonSkyShare(level);
        if (season > 0.0F) {
            targetRain = Math.max(targetRain, season);
            targetThunder = Math.max(targetThunder, season * 0.12F);
        }
        if (VolumetricPrecipitationRenderer.shaderCompatibilityActive()) {
            float localDownfall = VolumetricPrecipitationRenderer.localDownfallIntensity();
            targetRain = Math.max(targetRain, localDownfall * 0.72F);
            targetThunder = Math.max(targetThunder, localDownfall * 0.54F);
        }
        float smoothing = Config.SKY_SMOOTHING.get().floatValue();
        visualRain = Mth.lerp(smoothing, visualRain, targetRain);
        visualThunder = Mth.lerp(smoothing, visualThunder, targetThunder);

        boolean activeWeather = level.isRaining() || level.isThundering();
        if (!activeWeather && Config.CLEAR_INTERVALS_ENABLED.get()) {
            float reduction = Config.CLEAR_INTERVAL_RAIN_REDUCTION.get().floatValue();
            visualRain = Math.max(0.0F, visualRain - reduction * Config.MIN_SKY_UPDATE_TICKS.get());
            visualThunder = Math.max(0.0F, visualThunder - reduction * Config.MIN_SKY_UPDATE_TICKS.get());
        }
        keepVanillaRainUnderShaderPack(level);
        // The sky over the player is this mod's own now, and nothing is written into the level to get it: the
        // reading taken here is the deck's own share of the sky and how deep in a hailing core's footprint the
        // player is standing, and the sky's colour, the fog's, the deck's, the world's ambient light and the
        // sun's own alpha all take their darkening from that one reading. A world with a storm over it is still a
        // clear world as far as anything else is concerned, which is what makes a weather mod safe to have beside
        // a machine that rains somewhere else.
        StormSkyClient.tick(level, player, manager, visualRain);
    }

    /**
     * Tells the level it is raining, for the one case where that is about precipitation and not about light.
     *
     * <p>The mod draws its own storm's rain, and cancels vanilla's where it does, but when a shader pack is
     * loaded its own rain program cannot run at all - so the volumetric renderer stands its bypass down and
     * vanilla's rain sheets are what a player sees. Vanilla only draws those when the level's rain level is
     * above nothing, which is what this writes. It is the only place this mod writes the level's weather state
     * now: the darkening that used to come with it is the mod's own reading, and this exists so that a shader
     * pack still gets rain rather than so that a storm gets darker.
     */
    private static void keepVanillaRainUnderShaderPack(ClientLevel level) {
        if (VolumetricPrecipitationRenderer.shaderCompatibilityActive()) {
            level.setRainLevel(Math.max(level.getRainLevel(1.0F), visualRain));
            level.setThunderLevel(Math.max(level.getThunderLevel(1.0F), visualThunder));
        }
    }

    /**
     * The rain the mod's own weather is putting on the player, as the level's own rain level would state it.
     *
     * <p>This is the value this mod used to write into the level to get its sky darkened, kept because it is a
     * real reading and two others were built on it: the mist's own rain air and the morning's weather moisture
     * both used to arrive through that field. They ask for it by name now, so the mod's storm still fogs and still
     * dampens a dawn while the game's own rain level is left to the game.
     */
    public static float stormRainLevel() {
        return Math.min(1.0F, visualRain * Config.STORM_SKY_DARKEN_SCALE.get().floatValue());
    }

    /** The same for the thunder, which is what a storm's own severity was worth to those two readings. */
    public static float stormThunderLevel() {
        return Math.min(1.0F, visualThunder * Config.STORM_SKY_DARKEN_SCALE.get().floatValue());
    }

    /**
     * How much sky the wet season is taking where the player is standing, 0 to 1, on the same scale the storms
     * push. It is read from the level's own day counter and the air over the player, so it needs nothing from
     * the server and cannot disagree with the season the server is flooding in.
     */
    private static float monsoonSkyShare(ClientLevel level) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || !Config.MONSOON_ENABLED.get()) {
            return 0.0F;
        }
        double share = MonsoonService.skyShare(level, minecraft.player.position());
        if (share <= 0.0D) {
            return 0.0F;
        }
        return (float) Math.min(1.0D, share * Config.STORM_SKY_DARKEN_SCALE.get());
    }

    private static void spawnInflowJets(ClientLevel level, StormObject storm) {
        if (storm.motion == null || storm.motion.lengthSqr() < 1.0E-4D) {
            return;
        }
        Vec3 forward = new Vec3(storm.motion.x, 0.0D, storm.motion.z).normalize();
        Vec3 right = new Vec3(-forward.z, 0.0D, forward.x);
        int particles = Config.INFLOW_JET_PARTICLES_PER_TICK.get();
        int length = Config.INFLOW_JET_LENGTH_BLOCKS.get();
        for (int i = 0; i < particles; i++) {
            double along = 8.0D + level.random.nextDouble() * length;
            double lateral = (level.random.nextDouble() - 0.5D) * 22.0D;
            Vec3 pos = storm.pos.add(forward.scale(along)).add(right.scale(lateral));
            int groundY = vortexGroundY(level, pos);
            Vec3 velocity = forward.scale(-0.28D - level.random.nextDouble() * 0.18D);
            Vec3 particlePos = new Vec3(pos.x, groundY + 1.2D + level.random.nextDouble() * 1.5D, pos.z);
            if (!spawnWeather2Particle(level, particlePos, velocity.add(0.0D, 0.01D, 0.0D), ParticleRegistry.cloud, 4.5F, 0.72F, 0.72F, 0.78F, 0.42F, 32, 0.08F)) {
                level.addParticle(ParticleTypes.CLOUD, particlePos.x, particlePos.y, particlePos.z, velocity.x, 0.01D, velocity.z);
            }
        }
    }

    private static void spawnSatelliteSwirls(ClientLevel level, StormObject storm) {
        DynamicStormState state = DynamicStormController.stateFor(storm);
        double rotationSign = DynamicStormController.visualRotationSign(storm);
        for (DynamicStormState.TornadoCirculationState satellite : state.satelliteTornadoes()) {
            if (satellite == null || satellite.absorbed()) {
                continue;
            }
            Vec3 center = satelliteVisualCenter(level, storm, satellite);
            double radius = DynamicStormController.satelliteRadius(storm, satellite);
            double satelliteWind = DynamicStormController.satelliteWindMph(storm, satellite);
            SatelliteTornadoFunnelRenderer.tick(level, storm, satellite, center, satelliteWind, rotationSign);
            float lifecycle = satellite.lifecycleAt(level.getGameTime());
            int stickAttempts = lifecycle <= 0.05F ? 0 : (int) Math.round(2.0D * lifecycle);
            for (int i = 0; i < stickAttempts; i++) {
                double angle = Math.toRadians(satellite.orbitDegrees()) + level.random.nextDouble() * Math.PI * 2.0D;
                double cosAngle = Math.cos(angle);
                double sinAngle = Math.sin(angle);
                double distance = 1.5D + level.random.nextDouble() * radius;
                int groundY = vortexGroundY(level, center);
                Vec3 pos = new Vec3(center.x + cosAngle * distance, groundY + 0.6D + level.random.nextDouble() * 5.0D, center.z + sinAngle * distance);
                Vec3 velocity = new Vec3(-sinAngle * rotationSign, 0.02D, cosAngle * rotationSign).scale(0.12D);
                if (shouldSpawnStickDebris(level, Config.SUB_VORTEX_STICK_DENSITY.get().floatValue() * 0.82F, pos)) {
                    spawnStickDebris(level, pos, velocity.add(0.0D, 0.08D + level.random.nextDouble() * 0.10D, 0.0D));
                }
            }
        }
    }

    private static Vec3 satelliteVisualCenter(ClientLevel level, StormObject storm, DynamicStormState.TornadoCirculationState satellite) {
        Vec3 center = DynamicStormController.satelliteCenter(storm, satellite, storm.pos.y);
        int groundY = vortexGroundY(level, center);
        return new Vec3(center.x, groundY + 0.65D, center.z);
    }

    private static void spawnTornadoDebrisCloud(ClientLevel level, StormObject storm, Vec3 playerPos) {
        if (!Config.TORNADO_DEBRIS_CLOUD_ENABLED.get() || Config.TORNADO_DEBRIS_CLOUD_MAX_PARTICLES_PER_TICK.get() <= 0 || storm == null || storm.pos == null) {
            return;
        }
        if (storm.pos.distanceTo(playerPos) > LodViewDistance.extendSpawnRange(Config.TORNADO_DEBRIS_CLOUD_RENDER_RANGE_BLOCKS.get())) {
            return;
        }
        double maxWind = TornadoWindField.maxWindMph(storm);
        DustCloudContext context = DustCloudContext.forStorm(level, storm);
        boolean water = context.water();
        float humidity = context.humidity();
        float humidityClamped = Mth.clamp(humidity, 0.0F, 1.0F);
        DynamicStormState state = DynamicStormController.stateFor(storm);
        // A lifting funnel leaves its dust on the ground. The cloud is held up a while by the wind that raised it, so it does
        // not go out with the first drop in the wind, and it stays low and thins out instead of climbing a column that is
        // no longer there.
        float offGround = DynamicStormController.funnelOffTheGround(storm);
        maxWind = state.groundDustWindMph((float) maxWind, offGround);
        // Formation dust whirl. A forming tornado turns up a whirl at the surface while the condensation
        // is still descending and while the surface wind is far too weak to raise dust on its own, which
        // is how real tornadoes announce themselves before there is any visible funnel. It is handled
        // separately from the wind-driven cloud below so it can appear under a threshold wind speed, and
        // it flattens out as the funnel comes down and the ordinary debris cloud takes over.
        long gameTime = level.getGameTime();
        float genesisWhirl = state.genesisSurfaceWhirl(gameTime);
        float condensationReach = state.genesisCondensationReach(gameTime);
        // Moisture binds loose surface dust: dry storms raise dust at ~36 mph,
        // but once the storm is saturated (humidity 1.0 = raining) the ground is
        // wet and the tornado is rain-wrapped, so dust only appears at violent
        // wind speeds. Waterspouts are spray, not dust, so they ignore humidity.
        double dustWindThreshold = water ? 40.0D : Mth.lerp(36.0D, 80.0D, humidityClamped * humidityClamped);
        if (maxWind < dustWindThreshold && genesisWhirl <= 0.02F) {
            return;
        }
        // Smooth fade-in as the circulation strengthens past the threshold, and
        // fade-out as it ropes out and the wind drops back below it. The whirl term keeps the forming
        // tornado visible below that threshold without ever matching a mature debris cloud.
        double formation = Math.max(
                Mth.clamp((maxWind - dustWindThreshold) / 28.0D, 0.0D, 1.0D),
                genesisWhirl * 0.55D);
        float dryness = 1.0F - humidityClamped;
        float sizeFactor = (float) Mth.lerp(0.78D, 1.30D, dryness);
        float densityFactor = (float) Mth.lerp(0.62D, 1.18D, dryness);
        // A surface whirl is squat: it only reaches full height once the condensation is on the ground.
        float heightFactor = (float) Mth.lerp(0.78D, 1.24D, dryness)
                * (float) Mth.lerp(0.55D, 1.0D, condensationReach);
        // How high the dust reaches, apart from how big its puffs are: under a lifted funnel it stays low but no smaller.
        float lowering = Mth.lerp(offGround, 1.0F, DUST_HEIGHT_LIFTED);
        double visibleRadius = Math.max(6.0D, state.visibleFunnelWidth() * 0.5D);
        double radiusOfMaxWind = Math.max(4.0D, DynamicStormController.radiusOfMaxWind(storm));
        double clearRadius = Mth.clamp(Math.max(visibleRadius + 3.0D, radiusOfMaxWind * 0.28D), 6.0D, 512.0D);
        double cloudRadius = clearRadius + Mth.clamp(Math.max(12.0D, visibleRadius * 0.70D + maxWind / 24.0D), 12.0D, 90.0D) * sizeFactor;
        // A forming tornado's whirl is thin and faint; it only fills out into the mature debris cloud as
        // the condensation comes down. At full reach this is 1.0, so a formed tornado's dust cloud is
        // exactly what it was before the genesis timeline existed.
        float genesisDimming = 0.30F + 0.70F * condensationReach;
        float density = Config.TORNADO_DEBRIS_CLOUD_DENSITY.get().floatValue() * context.gust() * densityFactor;
        int attempts = Mth.clamp((int) Math.round((12.0D + maxWind / 17.0D + (cloudRadius - clearRadius) / 3.6D) * density * genesisDimming * 0.27D), 3, 20);
        // Under a lifted funnel the dust is thinner, never gone. It used to be cut outright the tick a multi-vortex episode
        // lifted the parent, before the foot had even started to rise, and put back the tick the episode ended.
        float groundShare = 1.0F - DUST_THINNING_LIFTED * offGround;
        attempts = Math.max(1, Math.round(attempts * groundShare));
        double coreRadius = Mth.clamp(Math.max(visibleRadius * 0.76D, radiusOfMaxWind * 0.42D), 4.0D, 96.0D);
        int coreAttempts = Mth.clamp((int) Math.round((8.0D + maxWind / 20.0D + coreRadius / 6.0D) * density * genesisDimming * 0.19D * groundShare), 1, 6);
        spawnDebrisCloudCore(level, storm, storm.pos, coreRadius, maxWind, coreAttempts, (float) formation, heightFactor, lowering, genesisDimming, context);
        spawnDebrisCloudAround(level, storm, storm.pos, clearRadius, cloudRadius, maxWind, attempts, (float) formation, heightFactor, lowering, genesisDimming, context);
        // The shroud climbs the outside of the funnel, so it goes as the funnel's foot leaves the ground.
        if (maxWind >= DUST_WRAP_MIN_MPH && !water && offGround < 0.95F) {
            spawnDustWrap(level, storm, maxWind, genesisDimming * (1.0F - offGround), context);
        }
        if (!water) {
            spawnSurfaceDirtKickup(level, storm, storm.pos, coreRadius, clearRadius, maxWind, formation, dryness);
        }

        for (DynamicStormState.TornadoCirculationState satellite : state.satelliteTornadoes()) {
            Vec3 center = satelliteVisualCenter(level, storm, satellite);
            if (center.distanceTo(playerPos) > LodViewDistance.extendSpawnRange(Config.TORNADO_DEBRIS_CLOUD_RENDER_RANGE_BLOCKS.get())) {
                continue;
            }
            spawnSatelliteDebrisCloud(level, storm, satellite, center, context);
        }
    }

    /** How high the ground dust reaches under a funnel that has lifted off, against how high it climbs a planted one. */
    private static final float DUST_HEIGHT_LIFTED = 0.32F;
    /** How much of the ground dust thins away under a funnel that has lifted off. */
    private static final float DUST_THINNING_LIFTED = 0.35F;

    /** Below this a tornado's dust is a skirt at its foot; above it, the funnel is wrapped in it. EF4 begins at 166 mph. */
    private static final double DUST_WRAP_MIN_MPH = 160.0D;

    /**
     * The dust shroud of a violent tornado: soil and debris thrown up the outside of the funnel, wrapping its lower part
     * in a turning sheath of brown haze. It fades in through the top of EF3, is full by EF5, hugs the wall rather than
     * standing off it, and thins with height, so the funnel is still seen inside it.
     */
    private static void spawnDustWrap(ClientLevel level, StormObject storm, double maxWind, float genesisDimming, DustCloudContext context) {
        double funnelTopY = DynamicStormController.funnelTopWorldY(storm);
        if (Double.isNaN(funnelTopY)) {
            return;
        }
        float violence = (float) Mth.clamp((maxWind - DUST_WRAP_MIN_MPH) / 90.0D, 0.0D, 1.0D);
        double rotationSign = DynamicStormController.visualRotationSign(storm);
        int attempts = Mth.clamp(Math.round((3.0F + violence * 7.0F) * genesisDimming), 1, 10);
        int ground = dustGroundY(level, storm, storm.pos);
        double funnelHeight = Math.max(16.0D, funnelTopY - ground);
        double reach = 0.30D + violence * 0.22D;
        DustTone tone = toneFor(level, storm.pos, false, true, context);
        for (int i = 0; i < attempts; i++) {
            if (debrisCloudSpawnedThisTick >= Config.TORNADO_DEBRIS_CLOUD_MAX_PARTICLES_PER_TICK.get()) {
                return;
            }
            double angle = level.random.nextDouble() * Math.PI * 2.0D;
            double up = Math.pow(level.random.nextDouble(), 1.4D) * reach;
            double wall = Math.max(4.0D, DynamicStormController.funnelWallRadiusAt(storm, (float) up));
            double height = up * funnelHeight;
            int maxAge = 70 + level.random.nextInt(50);
            float scale = (float) Mth.clamp(wall * 0.34D + 3.0D, 4.0D, 26.0D);
            float alpha = (0.10F + violence * 0.06F) * (float) (1.0D - up / reach * 0.6D);
            spawnSpiralDust(level, storm, angle, wall * 1.05D, ground + 0.3D, height, funnelHeight,
                    dustWallSpeed(maxWind, wall), rotationSign, 0.03D, Math.max(0.0D, reach * funnelHeight - height) * 0.5D / maxAge,
                    1.02D + level.random.nextDouble() * 0.12D, reach * funnelHeight, scale, alpha, tone, maxAge);
        }
    }

    private static float debrisCloudOpacity() {
        return Config.TORNADO_DEBRIS_CLOUD_OPACITY.get().floatValue();
    }

    private static void spawnSatelliteDebrisCloud(ClientLevel level, StormObject storm, DynamicStormState.TornadoCirculationState satellite, Vec3 center, DustCloudContext context) {
        float lifecycle = satellite.lifecycleAt(level.getGameTime());
        if (lifecycle <= 0.03F) {
            return;
        }
        double rotationSign = DynamicStormController.visualRotationSign(storm);
        double satelliteWind = DynamicStormController.satelliteWindMph(storm, satellite);
        float humidity = context.humidity();
        float humidityClamped = Mth.clamp(humidity, 0.0F, 1.0F);
        float dryness = 1.0F - humidityClamped;
        // Same moisture rule as the parent: dry air raises satellite dust early, humid air nearly never does.
        double dustWindThreshold = Mth.lerp(30.0D, 62.0D, humidityClamped * humidityClamped);
        if (satelliteWind < dustWindThreshold) {
            return;
        }
        double formation = Mth.clamp((satelliteWind - dustWindThreshold) / 18.0D, 0.0D, 1.0D);
        double clearRadius = Mth.clamp(satellite.visibleFunnelWidth() + 3.0D, 3.0D, 60.0D);
        double cloudRadius = (clearRadius + Mth.clamp(DynamicStormController.satelliteRadius(storm, satellite) * 0.30D, 5.0D, 30.0D)) * Mth.lerp(0.82D, 1.18D, dryness);
        // A satellite is a circulation inside the parent's field, so its dust takes the parent's shape:
        // same track, same lobes, same lopsided skirt rather than a second ring of its own.
        long gameTime = level.getGameTime();
        double footprintBearing = TornadoFootprint.motionBearing(storm.motion);
        boolean water = context.water();
        float density = Config.TORNADO_DEBRIS_CLOUD_DENSITY.get().floatValue() * context.gust()
                * (0.55F + 0.45F * (float) formation) * Mth.lerp(0.70F, 1.08F, dryness);
        int attempts = Mth.clamp((int) Math.round((2.5D + satelliteWind / 70.0D + (cloudRadius - clearRadius) / 9.0D) * density * lifecycle), 1, 9);
        for (int i = 0; i < attempts; i++) {
            if (debrisCloudSpawnedThisTick >= Config.TORNADO_DEBRIS_CLOUD_MAX_PARTICLES_PER_TICK.get()) {
                return;
            }
            boolean loftedSwirl = level.random.nextFloat() < 0.42F;
            double angle = TornadoFootprint.pickBearing(storm.ID, footprintBearing, gameTime, level.random);
            double cosAngle = Math.cos(angle);
            double sinAngle = Math.sin(angle);
            double reach = TornadoFootprint.reachShare(storm.ID, angle, footprintBearing, gameTime);
            double distance = loftedSwirl
                    ? clearRadius * (0.75D + level.random.nextDouble() * 0.45D)
                    : Math.pow(level.random.nextDouble(), 0.80D) * clearRadius * reach;
            Vec3 groundSample = center.add(cosAngle * distance, 0.0D, sinAngle * distance);
            int groundY = vortexGroundY(level, groundSample);
            double nearCore = Mth.clamp(1.0D - distance / Math.max(1.0D, clearRadius), 0.0D, 1.0D);
            boolean raining = level.isRainingAt(BlockPos.containing(groundSample.x, groundY + 1, groundSample.z));
            float rainFactor = raining ? 0.50F : 1.0F;
            double height = loftedSwirl
                    ? 1.0D + Math.pow(level.random.nextDouble(), 1.15D) * Mth.clamp(2.0D + satelliteWind / 34.0D, 2.5D, 12.0D)
                    : Math.pow(level.random.nextDouble(), 1.95D) * Mth.clamp(2.2D + satelliteWind / 30.0D + nearCore * 3.0D, 2.5D, 9.0D);
            Vec3 pos = new Vec3(groundSample.x, groundY + 0.25D + height, groundSample.z);
            Vec3 radial = new Vec3(cosAngle, 0.0D, sinAngle);
            Vec3 tangent = new Vec3(-sinAngle * rotationSign, 0.0D, cosAngle * rotationSign);
            Vec3 velocity = tangent.scale(loftedSwirl ? 0.10D + satelliteWind * 0.0008D : 0.05D + satelliteWind * 0.0005D + nearCore * 0.030D)
                    .add(radial.scale(-0.012D - nearCore * 0.016D))
                    .add(0.0D, loftedSwirl ? 0.016D + satelliteWind * 0.00022D : 0.008D + nearCore * 0.014D, 0.0D);
            float scale = (float) Mth.clamp((loftedSwirl ? 5.5D : 9.0D) + clearRadius * 0.34D + nearCore * 7.0D + level.random.nextDouble() * 6.0D, 5.0D, 30.0D) * (float) Mth.lerp(0.86D, 1.10D, dryness);
            float alpha = (float) Mth.clamp((loftedSwirl ? 0.26D : 0.32D + nearCore * 0.20D) + satelliteWind / 1900.0D, 0.0F, 0.62F) * lifecycle * context.gust() * rainFactor * (float) formation;
            if (water) {
                scale *= 0.82F;
                alpha *= 0.86F;
            }
            alpha *= debrisCloudOpacity();
            DustTone tone = toneFor(level, groundSample, !loftedSwirl, loftedSwirl, context);
            spawnDebrisCloudParticle(level, pos, velocity, scale, alpha, tone, 24 + level.random.nextInt(40));
        }

        if (!water) {
            int kickupAttempts = Mth.clamp((int) Math.round((1.0D + satelliteWind / 90.0D) * formation * (0.25D + 0.75D * dryness)), 0, 4);
            for (int i = 0; i < kickupAttempts; i++) {
                if (debrisCloudSpawnedThisTick >= Config.TORNADO_DEBRIS_CLOUD_MAX_PARTICLES_PER_TICK.get()) {
                    return;
                }
                double angle = TornadoFootprint.pickBearing(storm.ID, footprintBearing, gameTime, level.random);
                double cosAngle = Math.cos(angle);
                double sinAngle = Math.sin(angle);
                double reach = TornadoFootprint.reachShare(storm.ID, angle, footprintBearing, gameTime);
                double distance = Mth.lerp(level.random.nextDouble(), clearRadius * 0.60D, Math.max(clearRadius + 1.0D, clearRadius * 1.25D)) * reach;
                Vec3 groundSample = center.add(cosAngle * distance, 0.0D, sinAngle * distance);
                int groundY = vortexGroundY(level, groundSample);
                if (level.isRainingAt(BlockPos.containing(groundSample.x, groundY + 1, groundSample.z))) {
                    continue;
                }
                Vec3 pos = new Vec3(groundSample.x, groundY + 0.30D + level.random.nextDouble() * 0.7D, groundSample.z);
                Vec3 radial = new Vec3(cosAngle, 0.0D, sinAngle);
                Vec3 tangent = new Vec3(-sinAngle * rotationSign, 0.0D, cosAngle * rotationSign);
                Vec3 velocity = tangent.scale(0.04D + level.random.nextDouble() * 0.08D)
                        .add(radial.scale(0.02D + level.random.nextDouble() * 0.04D))
                        .add(0.0D, 0.08D + level.random.nextDouble() * 0.12D, 0.0D);
                spawnKickedDirt(level, pos, velocity, surfaceDustTone(level, groundSample, 0.0F));
            }
        }
    }

    /**
     * The debris sheath: dust wrapped around the LOWER FUNNEL WALL and climbing it, the way real
     * tornado debris clouds read in photos - a conical collar hugging the condensation and fading
     * as it rises, not a disc sitting on the ground.
     *
     * <p>The old version capped dust at an absolute 13 blocks while the ring ran out to 145+, so
     * under a tall funnel the whole cloud flattened into a pancake. Now the column's height is a
     * fraction of the funnel's REAL world height (read from Weather2's own top anchor), the radius
     * at each dust particle's height follows {@code funnelWallRadiusAt} so the sheath flares with
     * the cone, and the centre at height follows {@code funnelLayerCenterAt} so it stays on a bent
     * or leaning funnel. Density thins with height so the base stays the dark debris foot photos
     * show while the top feathers out into the condensation.</p>
     */
    private static void spawnDebrisCloudCore(ClientLevel level, StormObject storm, Vec3 center, double coreRadius, double maxWind, int attempts, float intensityScale, float heightFactor, float lowering, float genesisDimming, DustCloudContext context) {
        double rotationSign = DynamicStormController.visualRotationSign(storm);
        // The collar is drawn from the field's own shape: the wall it hugs is round, the amount of dust
        // standing on it is not, so the sheath is thicker on the side the wind is carrying it.
        long gameTime = level.getGameTime();
        double footprintBearing = TornadoFootprint.motionBearing(storm.motion);
        boolean water = context.water();
        // Funnel real-world height: ground under the storm to Weather2's cloud-base anchor. NaN when
        // the funnel hasn't built layers yet (genesis) - those particles keep the old short absolute
        // cap, which is correct there: a forming tornado's whirl is a squat ground column.
        double funnelTopY = DynamicStormController.funnelTopWorldY(storm);
        boolean funnelShaped = !Double.isNaN(funnelTopY);
        for (int i = 0; i < attempts; i++) {
            if (debrisCloudSpawnedThisTick >= Config.TORNADO_DEBRIS_CLOUD_MAX_PARTICLES_PER_TICK.get()) {
                return;
            }
            double angle = TornadoFootprint.pickBearing(storm.ID, footprintBearing, gameTime, level.random);
            double cosAngle = Math.cos(angle);
            double sinAngle = Math.sin(angle);
            // Dust hugs the funnel wall as a thin veil; the innermost core stays clear enough to see the condensation funnel.
            double wallBias = level.random.nextFloat() < 0.72F ? 0.60D + 0.40D * level.random.nextDouble() : 0.22D + 0.38D * level.random.nextDouble();
            // How far up the funnel this dust particle sits: concentrated near the ground, but the
            // luckiest particles climb 55-75% of the funnel - the tall collar in the reference.
            double climbCap = funnelShaped ? (0.10D + wallBias * 0.18D) * heightFactor * lowering : 0.0D;
            double heightFraction = Math.pow(level.random.nextDouble(), 2.1D) * Mth.clamp(climbCap, 0.03D, 0.30D);
            double wallRadiusAt = funnelShaped
                    ? Math.max(2.0D, DynamicStormController.funnelWallRadiusAt(storm, (float) heightFraction))
                    : Math.max(1.0D, coreRadius);
            Vec3 axisAt = funnelShaped && heightFraction > 0.02D
                    ? DynamicStormController.funnelLayerCenterAt(storm, (float) heightFraction)
                    : null;
            double axisX = axisAt != null ? axisAt.x : center.x;
            double axisZ = axisAt != null ? axisAt.z : center.z;
            double distance = wallBias * wallRadiusAt;
            double sampleX = axisX + cosAngle * distance;
            double sampleZ = axisZ + sinAngle * distance;
            Vec3 groundSample = new Vec3(sampleX, center.y, sampleZ);
            int groundY = dustGroundY(level, storm, groundSample);
            boolean raining = level.isRainingAt(BlockPos.containing(sampleX, groundY + 1, sampleZ));
            float rainFactor = raining ? 0.50F : 1.0F;
            double wall = Mth.clamp(wallBias, 0.0D, 1.0D);
            // Height above LOCAL ground: with the funnel, a fraction of the real funnel height so the
            // sheath scales with the storm; without it, the old short cap for the genesis whirl.
            double height = funnelShaped
                    ? Math.max(0.4D, (funnelTopY - groundY) * heightFraction)
                    : Math.pow(level.random.nextDouble(), 1.9D) * Mth.clamp(3.0D + maxWind / 34.0D + wall * 3.6D, 3.6D, 13.5D) * heightFactor * lowering;
            boolean dustyFoot = heightFraction < 0.08D || wall > 0.85D;
            // Puffs grow slightly as they climb - the sheath feathers out instead of staying a tube.
            float scale = (float) Mth.clamp(wallRadiusAt * (0.42D + wall * 0.26D) + heightFraction * 10.0D + level.random.nextDouble() * 3.0D, 2.5D, 22.0D) * heightFactor;
            // Denser at the foot, thinning with height: the dark debris collar fading into condensation.
            float altitudeFade = (float) Mth.clamp(1.0D - heightFraction * 0.55D, 0.45D, 1.0D);
            float alpha = (float) Mth.clamp((0.24D + wall * 0.14D + maxWind / 1600.0D + (dustyFoot ? 0.10D : 0.0D)) * altitudeFade * intensityScale * context.gust() * rainFactor, 0.16D, 0.48D);
            if (water) {
                scale *= 0.72F;
                alpha *= 0.86F;
            }
            alpha *= debrisCloudOpacity() * genesisDimming * 0.70F;
            DustTone tone = toneFor(level, groundSample, dustyFoot, false, context);
            int maxAge = 44 + level.random.nextInt(52);
            // A share of the climb left to do, over the life it has to do it in, so the luckiest puffs get furthest up.
            double funnelHeight = funnelShaped ? Math.max(8.0D, funnelTopY - groundY) : 28.0D;
            double climbTo = funnelHeight * Mth.clamp(climbCap, 0.03D, 0.30D);
            spawnSpiralDust(level, storm, angle, distance, groundY + 0.28D, height, funnelHeight,
                    dustWallSpeed(maxWind, wallRadiusAt), rotationSign, 2.4D / maxAge, Math.max(0.0D, climbTo - height) * 1.3D / maxAge,
                    0.74D + wall * 0.34D, Math.max(height, climbTo) * 1.15D, scale, alpha, tone, maxAge);
        }
    }

    /**
     * The skirt: dust rolling outward around the sheath's feet. Real debris clouds rise toward the
     * funnel - a bowl that climbs the condensation and falls away outward - so the height here is a
     * FRACTION of the funnel's real height at the ring's inner edge, decaying to a low ground haze
     * at the outer edge, instead of the old absolute caps that flattened everything into a disc.
     */
    private static void spawnDebrisCloudAround(ClientLevel level, StormObject storm, Vec3 center, double clearRadius, double cloudRadius, double maxWind, int attempts, float intensityScale, float heightFactor, float lowering, float genesisDimming, DustCloudContext context) {
        double rotationSign = DynamicStormController.visualRotationSign(storm);
        double ringWidth = Math.max(2.0D, cloudRadius - clearRadius);
        long gameTime = level.getGameTime();
        double footprintBearing = TornadoFootprint.motionBearing(storm.motion);
        boolean water = context.water();
        double funnelTopY = DynamicStormController.funnelTopWorldY(storm);
        boolean funnelShaped = !Double.isNaN(funnelTopY);
        for (int i = 0; i < attempts; i++) {
            if (debrisCloudSpawnedThisTick >= Config.TORNADO_DEBRIS_CLOUD_MAX_PARTICLES_PER_TICK.get()) {
                return;
            }
            double angle = TornadoFootprint.pickBearing(storm.ID, footprintBearing, gameTime, level.random);
            double cosAngle = Math.cos(angle);
            double sinAngle = Math.sin(angle);
            // The skirt's outer edge IS the edge of the wind field, so the ring rolls out as far as the
            // wind reaches on this bearing: the dust cloud is a lobed, trailed shape rather than a ring
            // of dust with the funnel standing in the middle of it.
            double bearingRing = Math.max(2.0D,
                    ringWidth * TornadoFootprint.reachShare(storm.ID, angle, footprintBearing, gameTime));
            boolean lowGroundBowl = level.random.nextFloat() < 0.58F;
            boolean loftedCurtain = !lowGroundBowl && level.random.nextFloat() < 0.55F;
            // Mass the skirt near the funnel wall so the collar visibly rolls outward instead of floating evenly.
            double distance = level.random.nextFloat() < 0.62F
                    ? clearRadius + Math.pow(level.random.nextDouble(), 1.75D) * bearingRing * 0.52D
                    : clearRadius + Math.sqrt(level.random.nextDouble()) * bearingRing;
            Vec3 groundSample = center.add(cosAngle * distance, 0.0D, sinAngle * distance);
            int groundY = dustGroundY(level, storm, groundSample);
            boolean raining = level.isRainingAt(BlockPos.containing(groundSample.x, groundY + 1, groundSample.z));
            float rainFactor = raining ? 0.50F : 1.0F;
            double nearWall = Mth.clamp(1.0D - (distance - clearRadius) / ringWidth, 0.0D, 1.0D);
            double outerShelf = Mth.clamp((distance - clearRadius) / ringWidth, 0.0D, 1.0D);
            // Bowl profile: the closer to the funnel wall, the taller the dust climbs - measured as a
            // fraction of the funnel's real height when we have it (inner edge reaches ~1/3 up the
            // funnel, falling to ~1/20 at the rim), or the old absolute caps during genesis.
            double heightLimit;
            if (funnelShaped) {
                double funnelHeight = Math.max(8.0D, funnelTopY - groundY);
                double bowlTop = (lowGroundBowl ? 0.10D : loftedCurtain ? 0.34D : 0.20D) * heightFactor * lowering;
                heightLimit = Math.max(2.0D, funnelHeight * Mth.lerp(outerShelf, bowlTop, 0.05D));
            } else {
                heightLimit = (lowGroundBowl ? Mth.clamp(maxWind / 42.0D, 1.6D, 6.0D) : loftedCurtain ? Mth.clamp(maxWind / 9.5D, 10.0D, 34.0D) : Mth.clamp(maxWind / 18.0D, 4.0D, 18.0D)) * heightFactor * lowering;
            }
            if (water) {
                heightLimit = Math.max(heightLimit, 6.0D);
            }
            double height = Math.pow(level.random.nextDouble(), lowGroundBowl ? 2.3D : loftedCurtain ? 1.08D : 1.35D) * heightLimit;
            float scale = (float) Mth.clamp(clearRadius * (lowGroundBowl ? 0.42D : loftedCurtain ? 0.34D : 0.26D) + outerShelf * 4.0D + level.random.nextDouble() * 4.0D, 3.0D, 20.0D) * heightFactor;
            float alpha = (float) Mth.clamp(((lowGroundBowl ? 0.34D : loftedCurtain ? 0.22D : 0.20D) + nearWall * 0.13D - outerShelf * 0.10D + maxWind / 1500.0D) * intensityScale * context.gust() * rainFactor, 0.12D, 0.52D);
            if (lowGroundBowl) {
                scale = Mth.clamp(scale * 1.15F, 3.0F, 23.0F);
                alpha = Mth.clamp(alpha + 0.07F, 0.16F, 0.60F);
            }
            if (water) {
                scale *= 0.80F;
            }
            alpha *= debrisCloudOpacity() * genesisDimming;
            DustTone tone = toneFor(level, groundSample, lowGroundBowl, loftedCurtain, context);
            int maxAge = 48 + level.random.nextInt(64);
            double funnelHeight = funnelShaped ? Math.max(8.0D, funnelTopY - groundY) : 28.0D;
            // Slow enough that the ones born at the edge die part of the way in, so there is always a skirt out there
            // and it is always on its way toward the wall.
            spawnSpiralDust(level, storm, angle, distance, groundY + 0.35D, height, funnelHeight,
                    dustWallSpeed(maxWind, clearRadius), rotationSign, 1.5D / maxAge, heightLimit * (lowGroundBowl ? 0.5D : 1.6D) / maxAge,
                    1.05D, Math.max(height, heightLimit) * 1.3D, scale, alpha, tone, maxAge);
        }
    }

    /**
     * Surface dirt kicked up off the ground just outside the funnel wall: small,
     * short-lived dirt clods that pop upward in the circulation. Humidity gates
     * this hard - dry storms throw loose soil, rain-soaked ground doesn't, and
     * rain falling on the sample spot suppresses it too.
     */
    private static void spawnSurfaceDirtKickup(ClientLevel level, StormObject storm, Vec3 center, double coreRadius, double clearRadius, double maxWind, double formation, float dryness) {
        double kickupFactor = formation * (0.30D + 0.70D * dryness) * Config.TORNADO_DEBRIS_CLOUD_KICKUP_DENSITY.get();
        int attempts = Mth.clamp((int) Math.round((2.5D + maxWind / 26.0D) * kickupFactor * 0.35D), 0, 5);
        double rotationSign = DynamicStormController.visualRotationSign(storm);
        long gameTime = level.getGameTime();
        double footprintBearing = TornadoFootprint.motionBearing(storm.motion);
        for (int i = 0; i < attempts; i++) {
            if (debrisCloudSpawnedThisTick >= Config.TORNADO_DEBRIS_CLOUD_MAX_PARTICLES_PER_TICK.get()) {
                return;
            }
            double angle = TornadoFootprint.pickBearing(storm.ID, footprintBearing, gameTime, level.random);
            double cosAngle = Math.cos(angle);
            double sinAngle = Math.sin(angle);
            // Dirt pops off the ground between the funnel wall and the dust skirt, and the skirt itself is
            // the field's own shape, so the clods follow it out.
            double reach = TornadoFootprint.reachShare(storm.ID, angle, footprintBearing, gameTime);
            double distance = Mth.lerp(level.random.nextDouble(), coreRadius * 0.55D, Math.max(coreRadius + 2.0D, clearRadius * 1.15D)) * reach;
            Vec3 groundSample = center.add(cosAngle * distance, 0.0D, sinAngle * distance);
            int groundY = vortexGroundY(level, groundSample);
            if (level.isRainingAt(BlockPos.containing(groundSample.x, groundY + 1, groundSample.z))) {
                continue; // rain-soaked ground doesn't kick up dirt
            }
            Vec3 pos = new Vec3(groundSample.x, groundY + 0.30D + level.random.nextDouble() * 0.8D, groundSample.z);
            Vec3 radial = new Vec3(cosAngle, 0.0D, sinAngle);
            Vec3 tangent = new Vec3(-sinAngle * rotationSign, 0.0D, cosAngle * rotationSign);
            Vec3 velocity = tangent.scale(0.05D + level.random.nextDouble() * 0.10D)
                    .add(radial.scale(0.02D + level.random.nextDouble() * 0.05D))
                    .add(0.0D, 0.10D + level.random.nextDouble() * 0.14D, 0.0D);
            DustTone tone = surfaceDustTone(level, groundSample, 0.0F);
            spawnKickedDirt(level, pos, velocity, tone);
        }
    }

    private static void spawnKickedDirt(ClientLevel level, Vec3 pos, Vec3 velocity, DustTone tone) {
        if (debrisCloudSpawnedThisTick >= Config.TORNADO_DEBRIS_CLOUD_MAX_PARTICLES_PER_TICK.get()) {
            return;
        }
        debrisCloudSpawnedThisTick++;
        if (ParticleArt.cloud256() == null) {
            level.addParticle(ParticleTypes.POOF, pos.x, pos.y, pos.z, velocity.x, velocity.y, velocity.z);
            return;
        }
        int maxAge = 16 + level.random.nextInt(14);
        EntityRotFX particle = new EntityRotFX(level, pos.x, pos.y, pos.z, velocity.x, velocity.y, velocity.z);
        particle.setSprite(ParticleArt.cloud256());
        particle.setScale(Mth.clamp(0.7F + level.random.nextFloat() * 1.5F, 0.05F, 8.0F));
        particle.setColor(tone.red(), tone.green(), tone.blue());
        // The compensation can now exceed 1 (the soft sheet is lighter per frame), so the product is
        // capped instead of being allowed past full opacity.
        float clampedAlpha = Mth.clamp(Mth.clamp(0.42F + level.random.nextFloat() * 0.24F, 0.20F, 0.78F)
                * debrisCloudOpacity() * cloud256AlphaCompensation(), 0.0F, 0.96F);
        particle.setAlpha(clampedAlpha);
        particle.setFullAlphaTarget(clampedAlpha);
        particle.setTicksFadeInMax(5.0F);
        particle.setTicksFadeOutMax(8.0F);
        particle.setMaxAge(maxAge);
        particle.setGravity(0.0F);
        particle.setCanCollide(false);
        particle.setFacePlayer(true);
        particle.facePlayerYaw = true;
        particle.isTransparent = true;
        particle.renderOrder = 1;
        particle.windWeight = LOCAL_PARTICLE_WIND_WEIGHT;
        particle.spawnAsWeatherEffect();
    }

    private static void spawnDebrisCloudParticle(ClientLevel level, Vec3 pos, Vec3 velocity, float scale, float alpha, DustTone tone, int maxAge) {
        spawnDustCloudParticle(level, pos, velocity, scale, alpha, tone, maxAge, ParticleTypes.POOF);
    }

    /**
     * How fast dust goes round at the funnel's wall, in blocks a tick. A turn takes about the same time on any funnel of
     * a given strength up to thirty blocks or so, and past that a wider funnel turns more slowly, because a mile-wide
     * wedge going round in two seconds looks like a toy.
     */
    private static double dustWallSpeed(double maxWind, double wallRadius) {
        double perTick = (0.055D + maxWind * 0.00040D) * Mth.clamp(30.0D / Math.max(1.0D, wallRadius), 0.42D, 1.0D);
        return perTick * Math.max(1.0D, wallRadius);
    }

    /** One puff of a tornado's dust on its way round the foot. See {@link SpiralPuff}. */
    private static void spawnSpiralDust(ClientLevel level, StormObject storm, double angle, double radius, double groundY, double height, double funnelHeight,
                                        double wallSpeed, double rotationSign, double inflow, double climb, double wallShare,
                                        double ceiling, float scale, float alpha, DustTone tone, int maxAge) {
        if (debrisCloudSpawnedThisTick >= Config.TORNADO_DEBRIS_CLOUD_MAX_PARTICLES_PER_TICK.get() || ParticleArt.cloud256() == null || SoftPuffs.groundDustCrowded()) {
            return;
        }
        debrisCloudSpawnedThisTick++;
        float soilGrey = (tone.red() + tone.green() + tone.blue()) / 3.0F;
        DustTone muted = storm.isFirenado ? tone : new DustTone(
                Math.min(1.0F, Mth.lerp(FUNNEL_FOOT_GREYING, tone.red(), soilGrey) * DUST_LIFT), Math.min(1.0F, Mth.lerp(FUNNEL_FOOT_GREYING, tone.green(), soilGrey) * DUST_LIFT), Math.min(1.0F, Mth.lerp(FUNNEL_FOOT_GREYING, tone.blue(), soilGrey) * DUST_LIFT));
        dressDustPuff(new SpiralPuff(level, storm, angle, radius, groundY, height, funnelHeight, wallSpeed, rotationSign, inflow, climb, wallShare, ceiling),
                scale, alpha, muted, maxAge);
    }

    private static void spawnDustCloudParticle(ClientLevel level, Vec3 pos, Vec3 velocity, float scale, float alpha, DustTone tone, int maxAge, ParticleOptions fallback) {
        if (debrisCloudSpawnedThisTick >= Config.TORNADO_DEBRIS_CLOUD_MAX_PARTICLES_PER_TICK.get()) {
            return;
        }
        debrisCloudSpawnedThisTick++;
        if (ParticleArt.cloud256() == null) {
            level.addParticle(fallback, pos.x, pos.y, pos.z, velocity.x, velocity.y, velocity.z);
            return;
        }
        dressDustPuff(new EntityRotFX(level, pos.x, pos.y, pos.z, velocity.x, velocity.y, velocity.z), scale, alpha, tone, maxAge);
    }

    private static void dressDustPuff(EntityRotFX particle, float scale, float alpha, DustTone tone, int maxAge) {
        particle.setSprite(ParticleArt.cloud256());
        particle.setScale(Mth.clamp(scale, 0.05F, 180.0F));
        particle.setColor(tone.red(), tone.green(), tone.blue());
        float clampedAlpha = Mth.clamp((Mth.clamp(alpha * 1.10F + 0.04F, 0.08F, 0.90F)
                * cloud256AlphaCompensation()), 0.0F, 0.90F);
        particle.setAlpha(clampedAlpha);
        particle.setFullAlphaTarget(clampedAlpha);
        particle.setTicksFadeInMax(Math.min(16.0F, maxAge * 0.30F));
        particle.setTicksFadeOutMax(Math.max(10.0F, maxAge * 0.42F));
        particle.setMaxAge(maxAge);
        particle.setGravity(0.0F);
        particle.setCanCollide(false);
        particle.setFacePlayer(true);
        particle.facePlayerYaw = true;
        particle.isTransparent = true;
        particle.renderOrder = 1;
        particle.windWeight = LOCAL_PARTICLE_WIND_WEIGHT;
        particle.spawnAsWeatherEffect();
    }

    /**
     * The small vortices that turn on the ground while a tornado is still forming - the swirls real
     * storms show under the wall cloud before the condensation reaches the ground. Their positions,
     * sizes and lifetimes come from the genesis timeline, so nothing about them has to be synced, and
     * each is drawn as a short dust column with a little airborne dust of its own on the ground.
     */
    private static void spawnGenesisWhirlVisuals(ClientLevel level, StormObject storm, Vec3 playerPos) {
        if (!Config.GENESIS_ENABLED.get() || !Config.GENESIS_PRE_TOUCHDOWN_WHIRLS.get() || !Config.VISUALS_ENABLED.get()) {
            return;
        }
        DynamicStormState state = DynamicStormController.stateFor(storm);
        if (state.funnelBirthTick() == Long.MIN_VALUE || state.genesisDurationTicks() <= 0) {
            return;
        }
        long gameTime = level.getGameTime();
        double motherRadius = Math.max(6.0D, DynamicStormController.radiusOfMaxWind(storm));
        state.collectGenesisWhirls(gameTime, motherRadius, GENESIS_WHIRL_SCRATCH);
        if (GENESIS_WHIRL_SCRATCH.isEmpty()) {
            return;
        }
        double rotationSign = DynamicStormController.rotationSign(storm);
        DustCloudContext context = DustCloudContext.forStorm(level, storm);
        // Moist air wets the soil, so there is far less to lift: the whirls of a rain-wrapped storm are
        // nearly invisible, exactly as they are in life. A waterspout is spray rather than dust and
        // always shows.
        float dryness = 1.0F - Mth.clamp(context.humidity(), 0.0F, 1.0F);
        float whirlDust = context.water() ? 1.0F : Mth.lerp(0.30F, 1.0F, dryness);
        boolean debrisCloud = Config.TORNADO_DEBRIS_CLOUD_ENABLED.get();
        for (GenesisWhirls.Whirl whirl : GENESIS_WHIRL_SCRATCH) {
            double x = storm.pos.x + Math.cos(whirl.bearingRadians()) * whirl.distance();
            double z = storm.pos.z + Math.sin(whirl.bearingRadians()) * whirl.distance();
            Vec3 sample = new Vec3(x, storm.pos.y, z);
            int groundY = vortexGroundY(level, sample);
            Vec3 center = new Vec3(x, groundY + 0.55D, z);
            SatelliteTornadoFunnelRenderer.tickGenesisWhirl(level, storm, whirl.index(), center,
                    55.0D + 95.0D * whirl.strength(), rotationSign, whirl.radius(), whirl.height(), whirl.strength());
            if (!debrisCloud || whirlDust <= 0.05F) {
                continue;
            }
            DustTone tone = toneFor(level, sample, true, false, context);
            int dustAttempts = Mth.clamp((int) Math.round((1.0D + whirl.strength() * 3.5D) * whirlDust), 0, 5);
            for (int i = 0; i < dustAttempts; i++) {
                if (debrisCloudSpawnedThisTick >= Config.TORNADO_DEBRIS_CLOUD_MAX_PARTICLES_PER_TICK.get()) {
                    return;
                }
                double angle = level.random.nextDouble() * Math.PI * 2.0D;
                double cosAngle = Math.cos(angle);
                double sinAngle = Math.sin(angle);
                double distance = Math.sqrt(level.random.nextDouble()) * whirl.radius() * 1.15D;
                double lift = Math.pow(level.random.nextDouble(), 1.6D) * whirl.height() * 0.42D;
                Vec3 pos = new Vec3(x + cosAngle * distance, groundY + 0.30D + lift, z + sinAngle * distance);
                Vec3 radial = new Vec3(cosAngle, 0.0D, sinAngle);
                Vec3 tangent = new Vec3(-sinAngle * rotationSign, 0.0D, cosAngle * rotationSign);
                Vec3 velocity = tangent.scale(0.05D + whirl.strength() * 0.09D)
                        .add(radial.scale(-0.010D - whirl.strength() * 0.014D))
                        .add(0.0D, 0.010D + whirl.strength() * 0.024D, 0.0D);
                float scale = (float) Mth.clamp(3.2D + whirl.radius() * 1.6D + whirl.strength() * 7.0D, 3.0D, 22.0D);
                float alpha = Mth.clamp(0.16F + whirl.strength() * 0.30F, 0.10F, 0.50F) * whirlDust * context.gust();
                spawnDustCloudParticle(level, pos, velocity, scale, alpha, tone, 18 + level.random.nextInt(22), ParticleTypes.CLOUD);
                debrisCloudSpawnedThisTick++;
            }
            if (!context.water() && level.random.nextFloat() < whirl.strength() * 0.55F * whirlDust) {
                double angle = level.random.nextDouble() * Math.PI * 2.0D;
                double cosAngle = Math.cos(angle);
                double sinAngle = Math.sin(angle);
                double distance = Math.sqrt(level.random.nextDouble()) * whirl.radius();
                Vec3 pos = new Vec3(x + cosAngle * distance, groundY + 0.25D + level.random.nextDouble() * 0.6D, z + sinAngle * distance);
                Vec3 tangent = new Vec3(-sinAngle * rotationSign, 0.0D, cosAngle * rotationSign);
                Vec3 velocity = tangent.scale(0.06D + whirl.strength() * 0.10D).add(0.0D, 0.16D + level.random.nextDouble() * 0.16D, 0.0D);
                spawnKickedDirt(level, pos, velocity, surfaceDustTone(level, pos, 0.0F));
            }
        }
    }

    private static void spawnSubVortexVisuals(ClientLevel level, StormObject storm, Vec3 playerPos) {
        if (!Config.MULTIVORTEX_ENABLED.get() || !Config.VISUALS_ENABLED.get()) {
            return;
        }
        if (storm.pos.distanceTo(playerPos) > LodViewDistance.extendSpawnRange(Config.TORNADO_DEBRIS_CLOUD_RENDER_RANGE_BLOCKS.get())) {
            return;
        }
        DynamicStormState state = DynamicStormController.stateFor(storm);
        double rotationSign = DynamicStormController.rotationSign(storm);
        double parentWind = TornadoWindField.maxWindMph(storm);
        DustCloudContext context = DustCloudContext.forStorm(level, storm);
        // Moist air keeps the fine subvortex dust pinned low and faint, dry air lets it swirl visibly.
        float subVortexDryness = 1.0F - Mth.clamp(context.humidity(), 0.0F, 1.0F);
        for (DynamicStormState.SubVortex vortex : state.subVortices()) {
            Vec3 center = TornadoWindField.subVortexVisualCenter(level, storm, vortex, storm.pos.y);
            if (center.distanceTo(playerPos) > LodViewDistance.extendSpawnRange(Config.TORNADO_DEBRIS_CLOUD_RENDER_RANGE_BLOCKS.get())) {
                continue;
            }
            double intensity = Math.max(0.05D, vortex.intensityFraction());
            double subVortexWind = DynamicStormController.subVortexWindMph(storm, vortex);
            SatelliteTornadoFunnelRenderer.tickSubVortex(level, storm, vortex, center, subVortexWind, rotationSign);
            if (!Config.TORNADO_DEBRIS_CLOUD_ENABLED.get()) {
                continue;
            }
            // A breakdown bubble travelling high on the funnel is a condensation feature: it pinches
            // the funnel but raises no ground dust. Only once it drops into ground contact does it
            // start kicking up debris like any other sub-vortex.
            if (!vortex.isGroundContact(level.getGameTime())) {
                continue;
            }
            // Tight inner-core spots churn a small, dense dust column; wide peripheral vortices
            // drag a broader, fainter skirt.
            double typeDust = vortex.type() == SubVortexType.CORE ? 0.75D
                    : vortex.type() == SubVortexType.PERIPHERAL ? 1.35D
                    : vortex.type() == SubVortexType.DUST_WHIRL ? 1.55D : 1.0D;
            double subvortexRadius = Mth.clamp(DynamicStormController.radiusOfMaxWind(storm) * vortex.radiusFraction() * 0.30D * typeDust, 2.5D, 32.0D);
            int cloudAttempts = Mth.clamp((int) Math.round((3 + intensity * 7.0D + subVortexWind / 95.0D) * typeDust), 3, 16);
            for (int i = 0; i < cloudAttempts; i++) {
                if (debrisCloudSpawnedThisTick >= Config.TORNADO_DEBRIS_CLOUD_MAX_PARTICLES_PER_TICK.get()) {
                    // Break, not return: this guard used to leave the whole vortex loop, so every
                    // vortex after the one that exhausted the shared debris budget also skipped its
                    // tickSubVortex funnel tick - and SatelliteTornadoFunnelRenderer.cleanup() fades
                    // any funnel not ticked for 2 ticks, so half the swarm visibly vanished whenever
                    // the main funnel's dust cloud won the race for the budget that tick.
                    break;
                }
                double angle = Math.toRadians(vortex.orbitDegrees()) + level.random.nextDouble() * Math.PI * 2.0D;
                double cosAngle = Math.cos(angle);
                double sinAngle = Math.sin(angle);
                double distance = 0.8D + Math.sqrt(level.random.nextDouble()) * subvortexRadius;
                double helixTurn = angle + level.random.nextDouble() * 1.4D;
                double cosHelixTurn = Math.cos(helixTurn);
                double sinHelixTurn = Math.sin(helixTurn);
                double height = Math.pow(level.random.nextDouble(), 1.45D) * Mth.clamp(4.0D + subVortexWind / 18.0D, 5.0D, 24.0D);
                Vec3 pos = center.add(cosHelixTurn * distance, 0.45D + height, sinHelixTurn * distance);
                Vec3 radial = new Vec3(cosHelixTurn, 0.0D, sinHelixTurn);
                Vec3 tangent = new Vec3(-sinHelixTurn * rotationSign, 0.0D, cosHelixTurn * rotationSign);
                Vec3 velocity = tangent.scale(0.080D + intensity * 0.070D + subVortexWind * 0.00045D)
                        .add(radial.scale(-0.012D - intensity * 0.010D))
                        .add(0.0D, 0.018D + intensity * 0.034D, 0.0D);
                float scale = (float) Mth.clamp(6.8D + subvortexRadius * 0.74D + intensity * 10.5D + subVortexWind / 34.0D + level.random.nextDouble() * 5.5D, 5.8D, 30.0D);
                float alpha = (float) Mth.clamp(0.34D + intensity * 0.24D + subVortexWind / 1800.0D, 0.28D, 0.66D) * Mth.lerp(0.55F, 1.0F, subVortexDryness);
                spawnSubVortexCloudParticle(level, pos, velocity, scale, alpha, debrisTone(level, pos, true, false), 26 + level.random.nextInt(34));
            }
        }
    }

    private static void spawnSubVortexCloudParticle(ClientLevel level, Vec3 pos, Vec3 velocity, float scale, float alpha, DustTone tone, int maxAge) {
        // A loose effect like any other, so it stops at the loose ceiling. It never did, and with the episode's columns it
        // filled the queue past the point where the parent's own dust cloud could spawn at all.
        if (SoftPuffs.crowded()) {
            return;
        }
        spawnDustCloudParticle(level, pos, velocity, scale, alpha, tone, maxAge, ParticleTypes.CLOUD);
    }

    private static void spawnHorizontalVortexVisuals(ClientLevel level, StormObject storm, Vec3 playerPos) {
        if (!Config.HORIZONTAL_VORTICES_ENABLED.get() || !Config.VISUALS_ENABLED.get()) {
            return;
        }
        if (storm.pos.distanceTo(playerPos) > LodViewDistance.extendSpawnRange(Config.TORNADO_DEBRIS_CLOUD_RENDER_RANGE_BLOCKS.get())) {
            return;
        }
        DynamicStormState state = DynamicStormController.stateFor(storm);
        double rotationSign = DynamicStormController.rotationSign(storm);
        double parentWind = TornadoWindField.maxWindMph(storm);
        double parentRadius = Math.max(4.0D, DynamicStormController.rawBaseRadiusForWind(storm) * 0.5D);
        for (DynamicStormState.HorizontalVortex vortex : state.horizontalVortices()) {
            Vec3 center = TornadoWindField.horizontalVortexGroundCenter(level, storm, vortex, storm.pos.y);
            if (center.distanceTo(playerPos) > LodViewDistance.extendSpawnRange(Config.TORNADO_DEBRIS_CLOUD_RENDER_RANGE_BLOCKS.get())) {
                continue;
            }
            SatelliteTornadoFunnelRenderer.tickHorizontalVortex(level, storm, vortex, center, parentRadius, parentWind, rotationSign);
        }
    }

    private static void spawnMainTornadoStickDebris(ClientLevel level, StormObject storm, Vec3 playerPos) {
        if (!Config.STICK_DEBRIS_ENABLED.get() || storm.pos.distanceTo(playerPos) > Config.STICK_DEBRIS_RENDER_RANGE_BLOCKS.get()) {
            return;
        }
        if (DynamicStormController.suppressMultivortexParentDust(storm)) {
            return;
        }
        double radius = Math.max(4.0D, DynamicStormController.radiusOfMaxWind(storm));
        // The stick band follows the damage path on wide storms, so a wedge's debris field spans
        // its swath instead of clustering around the funnel while the outer footprint looks calm.
        DynamicStormState stormState = DynamicStormController.stateFor(storm);
        double maxRadius = Math.max(Math.max(radius + 2.0D, DynamicStormController.baseRadiusForWind(storm) * 0.72D),
                Math.min(stormState.damagePathWidth() * 0.5D, 512.0D));
        int attempts = Mth.clamp((int) (TornadoWindField.maxWindMph(storm) / 85.0D), 1, 4);
        float density = Config.SUB_VORTEX_STICK_DENSITY.get().floatValue() * 0.20F;
        double rotationSign = DynamicStormController.rotationSign(storm);
        for (int i = 0; i < attempts; i++) {
            double angle = level.random.nextDouble() * Math.PI * 2.0D;
            double cosAngle = Math.cos(angle);
            double sinAngle = Math.sin(angle);
            double distance = radius + level.random.nextDouble() * Math.max(1.0D, maxRadius - radius);
            int groundY = vortexGroundY(level, storm.pos);
            Vec3 pos = new Vec3(storm.pos.x + cosAngle * distance, groundY + 1.0D + level.random.nextDouble() * 18.0D, storm.pos.z + sinAngle * distance);
            Vec3 velocity = new Vec3(-sinAngle * rotationSign, 0.12D + level.random.nextDouble() * 0.18D, cosAngle * rotationSign).scale(0.16D + TornadoWindField.maxWindMph(storm) * 0.0011D);
            if (shouldSpawnStickDebris(level, density, pos)) {
                spawnStickDebris(level, pos, velocity);
            }
        }
    }

    private static void spawnSubVortexStickDebris(ClientLevel level, StormObject storm, Vec3 playerPos) {
        if (!Config.STICK_DEBRIS_ENABLED.get() || storm.pos.distanceTo(playerPos) > Config.STICK_DEBRIS_RENDER_RANGE_BLOCKS.get()) {
            return;
        }
        DynamicStormState state = DynamicStormController.stateFor(storm);
        double rotationSign = DynamicStormController.rotationSign(storm);
        for (DynamicStormState.SubVortex vortex : state.subVortices()) {
            Vec3 center = TornadoWindField.subVortexGroundCenter(level, storm, vortex, storm.pos.y);
            if (center.distanceTo(playerPos) > Config.STICK_DEBRIS_RENDER_RANGE_BLOCKS.get()) {
                continue;
            }
            int groundY = Mth.floor(center.y);
            int attempts = Mth.clamp(1 + (int) (vortex.intensityFraction() * 3.0F), 1, 4);
            for (int i = 0; i < attempts; i++) {
                double angle = Math.toRadians(vortex.orbitDegrees()) + level.random.nextDouble() * Math.PI * 2.0D;
                double cosAngle = Math.cos(angle);
                double sinAngle = Math.sin(angle);
                double distance = 1.0D + level.random.nextDouble() * Math.max(2.0D, vortex.orbitRadius() * 0.22D);
                Vec3 pos = new Vec3(center.x + cosAngle * distance, groundY + 0.45D + level.random.nextDouble() * 7.0D, center.z + sinAngle * distance);
                Vec3 velocity = new Vec3(-sinAngle * rotationSign, 0.15D + level.random.nextDouble() * 0.20D, cosAngle * rotationSign).scale(0.24D + vortex.intensityFraction() * 0.18D);
                if (shouldSpawnStickDebris(level, Config.SUB_VORTEX_STICK_DENSITY.get().floatValue(), pos)) {
                    spawnStickDebris(level, pos, velocity);
                }
            }
        }
    }

    private static void spawnAddonWeatherEvents(ClientLevel level, Vec3 playerPos) {
        for (AddonWeatherEvent event : ClientAddonWeatherEventStore.events()) {
            double renderRange = event.type() == StormSubtype.SQUALL_LINE
                    ? Math.max(768.0D, Config.SUPERCELL_STRUCTURE_RENDER_RANGE_BLOCKS.get())
                    : isCycloneFamily(event.type())
                    ? Math.max(1600.0D, event.radius() * 2.4D)
                    : 512.0D;
            if (distanceToEvent(event, playerPos) > renderRange) {
                continue;
            }
            if (event.type() == StormSubtype.SQUALL_LINE) {
                spawnSquallLineVisual(level, event, playerPos);
                continue;
            }
            if (event.type() == StormSubtype.HURRICANE) {
                spawnCycloneVisual(level, event, playerPos, 1.0D);
                continue;
            }
            if (event.type() == StormSubtype.CYCLONE) {
                // Same broad-band storm, opposite spin: an anticyclone's bands sweep clockwise
                // (in the northern hemisphere's convention) where a hurricane's sweep counter.
                spawnCycloneVisual(level, event, playerPos, -1.0D);
                continue;
            }
            if (event.type() == StormSubtype.WILDFIRE) {
                spawnWildfireVisual(level, event);
                continue;
            }
            if (event.type() == StormSubtype.FIRE_WHIRL) {
                // The whirl's dust and smoke are drawn by the loop below like any whirl's; this is the fire in it.
                spawnFireWhirlFlames(level, event);
            }
            if (event.type() == StormSubtype.FLAMMAGENITUS) {
                spawnFlammagenitusVisual(level, event);
                continue;
            }
            int count = particleCountFor(event);
            for (int i = 0; i < count; i++) {
                Vec3 pos = event.isLine() ? randomLineParticle(level, event) : randomVortexParticle(level, event);
                Vec3 velocity = event.isLine() ? lineVelocity(level, event) : swirlVelocity(event, pos);
                // Most of a dust whirl's body is its own rings. These are only the loose edge of it, and at the old rate they
                // heaped up round the foot into a solid brown dome.
                if (event.type() == StormSubtype.DUST_DEVIL || event.type() == StormSubtype.GUSTNADO) {
                    continue;
                }
                if (event.type() == StormSubtype.WATERSPOUT) {
                    Vec3 sprayVelocity = new Vec3(velocity.x * 0.45D, 0.035D + level.random.nextDouble() * 0.035D, velocity.z * 0.45D);
                    // Spray is the brightest thing in the scene, so it takes the lighter sheet.
                    spawnWeather2ParticleDirect(level, pos, sprayVelocity, ParticleArt.funnelSprite(), 1.35F + level.random.nextFloat() * 0.85F, 0.94F, 0.97F, 1.00F, 0.34F, 22, 0.02F);
                    Vec3 mistPos = pos.add(0.0D, 0.18D + level.random.nextDouble() * 0.65D, 0.0D);
                    Vec3 mistVelocity = new Vec3(velocity.x * 0.22D, 0.018D, velocity.z * 0.22D);
                    spawnWeather2ParticleDirect(level, mistPos, mistVelocity, ParticleArt.funnelSprite(), 2.1F + level.random.nextFloat() * 1.7F, 0.90F, 0.93F, 0.98F, 0.20F, 28, 0.03F);
                } else if (event.type() == StormSubtype.DUST_DEVIL) {
                    Vec3 dustVelocity = new Vec3(velocity.x, 0.02D + level.random.nextDouble() * 0.03D, velocity.z);
                    DustTone tone = dustTone(level, pos);
                    if (!spawnWeather2Particle(level, pos, dustVelocity, ParticleArt.cloud256(), 1.8F + level.random.nextFloat() * 1.6F, Math.min(1.0F, tone.red() * DUST_LIFT), Math.min(1.0F, tone.green() * DUST_LIFT), Math.min(1.0F, tone.blue() * DUST_LIFT), 0.48F, 60, 0.12F)) {
                        level.addParticle(ParticleTypes.POOF, pos.x, pos.y, pos.z, dustVelocity.x, dustVelocity.y, dustVelocity.z);
                    }
                } else if (event.type() == StormSubtype.SNOW_DEVIL) {
                    Vec3 snowVelocity = new Vec3(velocity.x * 0.82D, 0.020D + level.random.nextDouble() * 0.035D, velocity.z * 0.82D);
                    if (!spawnWeather2Particle(level, pos, snowVelocity, ParticleArt.cloud256(), 3.8F + level.random.nextFloat() * 3.2F, 0.86F, 0.90F, 0.96F, 0.46F, 52, 0.08F)) {
                        level.addParticle(ParticleTypes.SNOWFLAKE, pos.x, pos.y, pos.z, snowVelocity.x, snowVelocity.y, snowVelocity.z);
                    }
                } else if (event.type() == StormSubtype.GUSTNADO) {
                    Vec3 forward = event.motion() == null || event.motion().horizontalDistanceSqr() < 1.0E-5D
                            ? Vec3.ZERO
                            : new Vec3(event.motion().x, 0.0D, event.motion().z).normalize().scale(0.09D + event.maxWindMph() * 0.0012D);
                    Vec3 gustVelocity = new Vec3(velocity.x * 0.42D + forward.x, 0.010D + level.random.nextDouble() * 0.026D, velocity.z * 0.42D + forward.z);
                    float darkBlend = level.random.nextFloat();
                    float red = Mth.lerp(darkBlend, 0.46F, 0.70F);
                    float green = Mth.lerp(darkBlend, 0.42F, 0.62F);
                    float blue = Mth.lerp(darkBlend, 0.36F, 0.52F);
                    if (!spawnWeather2Particle(level, pos, gustVelocity, ParticleArt.cloud256(), 3.6F + level.random.nextFloat() * 3.4F, red, green, blue, 0.22F, 70, 0.22F)) {
                        level.addParticle(ParticleTypes.POOF, pos.x, pos.y, pos.z, gustVelocity.x, gustVelocity.y, gustVelocity.z);
                    }
                } else if (event.type() == StormSubtype.FIRE_WHIRL) {
                    // The whirl's smoke: dark grey to black, spun up above the flames. The flame itself is drawn by
                    // spawnFireWhirlFlames, so low in the column the smoke is thin and lets the fire show; drawn there in
                    // rust colours it was a solid brown column with the fire hidden inside it.
                    double above = pos.y - event.position().y;
                    if (above < event.radius() * 2.6D && level.random.nextFloat() < 0.7F) {
                        continue;
                    }
                    Vec3 fireVelocity = new Vec3(velocity.x * 0.76D, 0.045D + level.random.nextDouble() * 0.085D, velocity.z * 0.76D);
                    float soot = level.random.nextFloat();
                    if (!spawnWeather2Particle(level, pos, fireVelocity, ParticleArt.cloud256(), 4.2F + level.random.nextFloat() * 4.6F, Mth.lerp(soot, 0.16F, 0.42F), Mth.lerp(soot, 0.15F, 0.40F), Mth.lerp(soot, 0.14F, 0.38F), 0.40F, 54, 0.18F)) {
                        level.addParticle(ParticleTypes.LARGE_SMOKE, pos.x, pos.y, pos.z, fireVelocity.x, fireVelocity.y, fireVelocity.z);
                    }
                    if (level.random.nextFloat() < 0.18F) {
                        level.addParticle(ParticleTypes.FLAME, pos.x, pos.y, pos.z, fireVelocity.x * 0.35D, fireVelocity.y * 0.35D, fireVelocity.z * 0.35D);
                    }
                } else if (event.type() == StormSubtype.FLOATER_TORNADO) {
                    Vec3 floaterVelocity = new Vec3(velocity.x, 0.018D + level.random.nextDouble() * 0.035D, velocity.z);
                    spawnCloud256Particle(level, pos, floaterVelocity, 5.8F + level.random.nextFloat() * 5.6F, 0.58F, 0.60F, 0.64F, 0.48F, 58, 0.12F);
                } else if (event.type() == StormSubtype.SQUALL_LINE) {
                    Vec3 linePos = new Vec3(pos.x, pos.y + 1.2D + level.random.nextDouble() * 4.0D, pos.z);
                    if (!spawnWeather2Particle(level, linePos, velocity, ParticleRegistry.cloud, 6.0F + level.random.nextFloat() * 4.0F, 0.48F, 0.50F, 0.56F, 0.52F, 46, 0.18F)) {
                        level.addParticle(ParticleTypes.CLOUD, linePos.x, linePos.y, linePos.z, velocity.x, 0.0D, velocity.z);
                    }
                } else {
                    Vec3 landspoutVelocity = new Vec3(velocity.x, 0.03D, velocity.z);
                    spawnCloud256Particle(level, pos, landspoutVelocity, 5.6F + level.random.nextFloat() * 3.8F, 0.56F, 0.54F, 0.50F, 0.52F, 48, 0.14F);
                }
                if (shouldSpawnStickDebris(level, densityFor(event), pos)) {
                    Vec3 debrisVelocity = event.isLine()
                            ? velocity.add(0.0D, 0.05D + level.random.nextDouble() * 0.04D, 0.0D)
                            : velocity.add(0.0D, 0.10D + level.random.nextDouble() * 0.16D, 0.0D).scale(1.25D);
                    spawnStickDebris(level, pos, debrisVelocity);
                }
            }
        }
    }

    /**
     * A wildfire, drawn from photographs of real ones: a front of flame along the edge it is burning out into, embers
     * popping off it, and a smoke plume that is dense and tan-brown close to the fire, paler grey-tan as it climbs, and
     * leaning off with the wind as it spreads - the column a fire puts up, rather than a cluster of dark puffs sitting on
     * it. At night the smoke just above the front is lit orange from below.
     */
    private static void spawnWildfireVisual(ClientLevel level, AddonWeatherEvent event) {
        double radius = Math.max(10.0D, event.radius());
        Vec3 centre = event.position();
        Vec3 wind = event.motion() != null && event.motion().horizontalDistanceSqr() > 1.0E-6D
                ? new Vec3(event.motion().x, 0.0D, event.motion().z).normalize()
                : new Vec3(Math.cos(event.id() * 0.618D), 0.0D, Math.sin(event.id() * 0.618D));
        Vec3 across = new Vec3(-wind.z, 0.0D, wind.x);

        // The flame front: tongues of fire along the edge it is burning out into - knee high at the edge of a grass fire,
        // taller where a big fire is running - flickering, leaning with the wind, and their own light at night. Sparks and
        // the odd ember are thrown up off it. The game's own flame was a speck a few pixels across and was not drawn at
        // all past thirty-two blocks, so a fire front read as a thin orange line or as nothing.
        int flames = Mth.clamp((int) (radius / 2.4D), 14, 60);
        double flameScale = Mth.clamp(1.0D + radius / 70.0D, 1.0D, 2.4D);
        for (int i = 0; i < flames; i++) {
            double angle = level.random.nextDouble() * Math.PI * 2.0D;
            // Hotter and deeper on the downwind side, where the fire is running.
            double downwind = Math.max(0.0D, Math.cos(angle) * wind.x + Math.sin(angle) * wind.z);
            double distance = radius * (0.82D + 0.18D * level.random.nextDouble());
            Vec3 base = new Vec3(centre.x + Math.cos(angle) * distance, centre.y, centre.z + Math.sin(angle) * distance);
            double size = (1.1D + level.random.nextDouble() * 1.4D) * flameScale * (1.0D + 0.6D * downwind);
            double y = visualY(level, base, size * 0.42D);
            level.addAlwaysVisibleParticle(com.imbet.registry.ModParticles.WILDFIRE_FLAME.get(), true,
                    base.x, y, base.z, wind.x * 0.015D, -size, wind.z * 0.015D);
            if (level.random.nextFloat() < 0.18F) {
                level.addParticle(ParticleTypes.SMALL_FLAME, base.x, y, base.z, wind.x * 0.02D, 0.05D + level.random.nextDouble() * 0.06D, wind.z * 0.02D);
            }
            if (level.random.nextFloat() < 0.06F) {
                level.addParticle(ParticleTypes.LAVA, base.x, y + 0.5D, base.z, 0.0D, 0.0D, 0.0D);
            }
        }

        // The plume, built up its whole height rather than left to rise from the ground, so a fire stands a column over
        // it from the first moment it is seen: lower and tighter for a small fire, taller and wider for a big one.
        double top = Mth.clamp(60.0D + radius * 0.65D, 60.0D, 160.0D);
        int smoke = Mth.clamp((int) (radius / 16.0D), 3, 10);
        for (int i = 0; i < smoke; i++) {
            double rise = Math.pow(level.random.nextDouble(), 1.3D);
            double height = 2.0D + rise * top;
            double spread = radius * (0.35D + 0.5D * level.random.nextDouble()) * (0.6D + rise * 0.8D);
            double angle = level.random.nextDouble() * Math.PI * 2.0D;
            Vec3 base = centre.add(Math.cos(angle) * spread, 0.0D, Math.sin(angle) * spread)
                    .add(wind.scale(height * 0.65D))
                    .add(across.scale((level.random.nextDouble() - 0.5D) * height * 0.3D));
            Vec3 pos = new Vec3(base.x, visualY(level, base, height), base.z);
            Vec3 velocity = wind.scale(0.05D + level.random.nextDouble() * 0.04D).add(0.0D, 0.05D + 0.06D * (1.0D - rise), 0.0D);
            float lit = SunShading.lit(level, Math.cos(angle), 0.3D + rise * 0.5D, Math.sin(angle));
            float[] grey = SunShading.cloudColour(level, Mth.lerp((float) rise, 0.62F, 0.86F), lit, 0.58F);
            float size = (float) (10.0D + rise * 34.0D + level.random.nextDouble() * 10.0D);
            float alpha = Mth.lerp((float) rise, 0.50F, 0.30F);
            spawnStructureParticle(level, pos, velocity, ParticleArt.cloud256(), size,
                    grey[0], grey[1] * 0.92F, grey[2] * 0.80F, alpha, 160 + level.random.nextInt(80), 0.08F, ParticleTypes.LARGE_SMOKE);
        }

        // Low over the front the smoke takes the fire's own light, which at night is the fire's glow on its plume.
        for (int i = 0; i < 2; i++) {
            double angle = level.random.nextDouble() * Math.PI * 2.0D;
            double distance = radius * (0.78D + 0.22D * level.random.nextDouble());
            Vec3 base = new Vec3(centre.x + Math.cos(angle) * distance, centre.y, centre.z + Math.sin(angle) * distance);
            Vec3 pos = new Vec3(base.x, visualY(level, base, 1.5D + level.random.nextDouble() * 4.0D), base.z);
            spawnStructureParticle(level, pos, new Vec3(0.0D, 0.05D, 0.0D), ParticleArt.cloud256(), 6.0F + level.random.nextFloat() * 6.0F,
                    1.0F, 0.56F, 0.30F, 0.32F, 40, 0.04F, ParticleTypes.SMOKE);
        }
    }

    /**
     * The fire in a fire whirl: flame drawn up into a spinning column, thick and wide at the foot and tapering into the
     * smoke above it, whipped round with the spin and climbing as it goes - the flame tornado a fire this hot can put
     * up, rather than a dust devil in the colours of a fire.
     */
    private static void spawnFireWhirlFlames(ClientLevel level, AddonWeatherEvent event) {
        Vec3 centre = event.position();
        double radius = Math.max(1.5D, event.radius());
        double height = Mth.clamp(radius * 5.0D + event.maxWindMph() * 0.18D, 14.0D, 48.0D);
        double fireHeight = height * 0.55D;
        double spin = 0.06D + event.maxWindMph() * 0.0012D;
        long time = level.getGameTime();
        int count = Mth.clamp((int) (10 + event.maxWindMph() * 0.12D), 10, 26);
        for (int i = 0; i < count; i++) {
            double rise = Math.pow(level.random.nextDouble(), 1.4D);
            double h = rise * fireHeight;
            // Wide at the foot where it is drawing the fire in, narrowing up the column.
            double r = radius * (1.15D - 0.70D * rise) * (0.7D + 0.3D * level.random.nextDouble());
            double angle = level.random.nextDouble() * Math.PI * 2.0D + time * spin;
            Vec3 base = new Vec3(centre.x + Math.cos(angle) * r, centre.y, centre.z + Math.sin(angle) * r);
            double y = visualY(level, base, h + 0.4D);
            double size = (1.4D + level.random.nextDouble() * 1.6D) * (1.2D - 0.5D * rise) * Mth.clamp(radius / 3.0D, 0.8D, 2.2D);
            double tangentX = -Math.sin(angle) * spin * 1.4D;
            double tangentZ = Math.cos(angle) * spin * 1.4D;
            level.addAlwaysVisibleParticle(com.imbet.registry.ModParticles.WILDFIRE_FLAME.get(), true,
                    base.x, y, base.z, tangentX, -size, tangentZ);
        }
        // Burning debris carried round and flung out of it.
        if (level.random.nextFloat() < 0.4F) {
            double angle = level.random.nextDouble() * Math.PI * 2.0D;
            level.addParticle(ParticleTypes.LAVA, centre.x + Math.cos(angle) * radius, visualY(level, centre, level.random.nextDouble() * fireHeight), centre.z + Math.sin(angle) * radius, 0.0D, 0.0D, 0.0D);
        }
    }

    private static void spawnFlammagenitusVisual(ClientLevel level, AddonWeatherEvent event) {
        int count = Mth.clamp((int) (event.radius() / 70.0F), 1, 4);
        for (int i = 0; i < count; i++) {
            double height = 18.0D + Math.pow(level.random.nextDouble(), 0.62D) * 120.0D;
            Vec3 pos = randomFireWeatherParticle(level, event, height, event.radius());
            Vec3 velocity = new Vec3((level.random.nextDouble() - 0.5D) * 0.030D, 0.016D + level.random.nextDouble() * 0.055D, (level.random.nextDouble() - 0.5D) * 0.030D);
            // A pyrocumulus: the cloud a big fire's heat builds over its smoke - bright white cauliflower where the sun
            // is on its top, grey-tan and smoky lower down. Drawn dark grey all through it was a cloud of soot.
            float up = (float) Mth.clamp((height - 18.0D) / 120.0D, 0.0D, 1.0D);
            double angle = level.random.nextDouble() * Math.PI * 2.0D;
            float lit = SunShading.lit(level, Math.cos(angle), 0.2D + up * 0.8D, Math.sin(angle));
            float[] colour = SunShading.cloudColour(level, Mth.lerp(up, 0.62F, 0.97F), lit, 0.60F);
            float smoky = 1.0F - up;
            spawnStructureParticle(level, pos, velocity, ParticleArt.cloud256(), 18.0F + level.random.nextFloat() * 20.0F,
                    colour[0], colour[1] * (1.0F - 0.06F * smoky), colour[2] * (1.0F - 0.16F * smoky), 0.40F, 130, 0.02F, ParticleTypes.CLOUD);
        }
    }

    private static Vec3 randomFireWeatherParticle(ClientLevel level, AddonWeatherEvent event, double heightOffset, double radius) {
        double angle = level.random.nextDouble() * Math.PI * 2.0D;
        double cosAngle = Math.cos(angle);
        double sinAngle = Math.sin(angle);
        double distance = Math.sqrt(level.random.nextDouble()) * radius;
        Vec3 base = new Vec3(event.position().x + cosAngle * distance, event.position().y, event.position().z + sinAngle * distance);
        return new Vec3(base.x, visualY(level, base, heightOffset), base.z);
    }

    /**
     * The line's precipitation: the rain curtain that falls out of the front, and the rain behind it.
     *
     * <p>The line's <em>cloud</em> is deliberately not drawn here. A squall line's shelf is Weather2's
     * own storm cloud - the deck each of the line's storm cells spawns in its own deck loop - and this
     * mod used to lay a second, hand-made field of shelf, lip, striation and scud puffs over the top of
     * it. That field was the thing that read as a single dark ceiling: it was drawn across the whole
     * front at every height from the cloud base down, so it roofed the sky over the player whatever the
     * storm objects underneath it were doing, and none of it was Weather2's own cloud. With it gone the
     * line's shape comes from the cells themselves - see
     * {@code com.imbet.mixin.StormObjectSquallDeckMixin} - and what is left here is the falling water,
     * which is the one part of a line's look a cloud field was never going to supply.
     */
    private static void spawnSquallLineVisual(ClientLevel level, AddonWeatherEvent event, Vec3 playerPos) {
        Vec3 start = event.lineStart();
        Vec3 end = event.lineEnd();
        if (start == null || end == null) {
            return;
        }
        Vec3 line = end.subtract(start);
        if (line.horizontalDistanceSqr() < 1.0E-5D) {
            return;
        }
        Vec3 axis = new Vec3(line.x, 0.0D, line.z).normalize();
        Vec3 forward = event.motion() != null && event.motion().horizontalDistanceSqr() > 1.0E-5D
                ? new Vec3(event.motion().x, 0.0D, event.motion().z).normalize()
                : new Vec3(-axis.z, 0.0D, axis.x);
        double lineLength = Math.sqrt(line.horizontalDistanceSqr());
        double segmentDistance = distanceToLineSegment(playerPos, start, end);
        float proximity = Mth.clamp((float) (1.0D - segmentDistance / Math.max(260.0D, event.radius() * 2.4D)), 0.0F, 1.0F);
        double centerT = lineSampleT(playerPos, start, end);
        int curtainCount = Mth.clamp(24 + (int) (lineLength / 64.0D) + (int) (proximity * 20.0F), 26, 60);
        int rainCount = Mth.clamp(14 + (int) (lineLength / 90.0D), 14, 34);
        spawnSquallShelf(level, event, start, end, axis, forward, centerT, lineLength, proximity);

        // The convective line's heavy rain: a dark, dense curtain right behind the gust front.
        for (int i = 0; i < curtainCount; i++) {
            double t = visibleLineT(level, centerT, i, curtainCount, lineLength, 16.0D);
            double rearOffset = -event.radius() * (0.05D + level.random.nextDouble() * 0.55D);
            Vec3 base = pointAlongShelf(level, start, end, axis, forward, t, rearOffset, 34.0D);
            // Rain falls from the cloud base down: it hangs under the shelf and the deck, not up through them. Reaching a
            // hundred and more blocks up, the curtains built a grey dome over the whole line that hid the shelf in front of
            // it and the towers above.
            double height = 6.0D + level.random.nextDouble() * 58.0D;
            Vec3 pos = new Vec3(base.x, visualY(level, base, height), base.z);
            Vec3 velocity = forward.scale(0.075D + event.maxWindMph() * 0.00075D)
                    .add(axis.scale((level.random.nextDouble() - 0.5D) * 0.025D))
                    .add(0.0D, -0.34D - level.random.nextDouble() * 0.23D, 0.0D);
            TextureAtlasSprite rainSprite = RainShaftSprites.sheet(
                    level.random, Mth.clamp(0.45F + proximity * 0.25F + event.maxWindMph() * 0.0030F, 0.0F, 1.0F));
            float depth = level.random.nextFloat();
            float grey = Mth.lerp(depth, 0.28F, 0.48F);
            if (!spawnWeather2Particle(level, pos, velocity, rainSprite,
                    46.0F + level.random.nextFloat() * 46.0F,
                    grey * 0.78F, grey * 0.84F, grey * 0.96F, 0.68F, 62, 0.24F)) {
                level.addParticle(ParticleTypes.RAIN, pos.x, pos.y, pos.z, velocity.x, velocity.y, velocity.z);
            }
        }

        // The trailing stratiform region: lighter, steadier rain falling out of the deck far behind the line, the long grey
        // tail that keeps raining after the gust front and the heavy core have gone past.
        for (int i = 0; i < rainCount; i++) {
            double t = visibleLineT(level, centerT, i, rainCount, lineLength, 40.0D);
            double rearOffset = -event.radius() * (0.60D + level.random.nextDouble() * 1.60D);
            Vec3 base = pointAlongShelf(level, start, end, axis, forward, t, rearOffset, 26.0D);
            Vec3 pos = new Vec3(base.x, visualY(level, base, 4.0D + level.random.nextDouble() * 70.0D), base.z);
            Vec3 velocity = forward.scale(0.030D + event.maxWindMph() * 0.00030D)
                    .add(axis.scale((level.random.nextDouble() - 0.5D) * 0.018D))
                    .add(0.0D, -0.16D - level.random.nextDouble() * 0.10D, 0.0D);
            TextureAtlasSprite rainSprite = RainShaftSprites.sheet(level.random, 0.30F + level.random.nextFloat() * 0.15F);
            float grey = 0.46F + level.random.nextFloat() * 0.10F;
            spawnStructureParticle(level, pos, velocity, rainSprite,
                    44.0F + level.random.nextFloat() * 52.0F,
                    grey, grey * 1.04F, grey * 1.14F, 0.32F, 70, 0.18F, ParticleTypes.RAIN);
        }

        Vec3 debrisCheck = start.lerp(end, level.random.nextDouble());
        if (shouldSpawnStickDebris(level, densityFor(event), debrisCheck)) {
            Vec3 debrisPos = pointAlongShelf(level, start, end, axis, forward, level.random.nextDouble(), 8.0D + level.random.nextDouble() * 34.0D, 10.0D);
            Vec3 velocity = forward.scale(0.17D + event.maxWindMph() * 0.0014D).add(0.0D, 0.06D + level.random.nextDouble() * 0.08D, 0.0D);
            spawnStickDebris(level, new Vec3(debrisPos.x, visualY(level, debrisPos, 1.0D + level.random.nextDouble() * 6.0D), debrisPos.z), velocity);
        }
    }

    /**
     * The cloud along a squall line's leading edge.
     *
     * <p>The gust front - the cold outflow spreading ahead of the rain - lifts the warm air in front of it, and that air
     * condenses into a shelf cloud: a long, low, tiered wedge lowest and lightest at its leading lip and rising backward
     * into the storm's base, with the dark, smooth underside behind it that chasers call the whale's mouth. Ahead of it at
     * the ground the outflow rolls dust forward, and above it the line's own convective towers stand over the deck.
     */
    private static void spawnSquallShelf(ClientLevel level, AddonWeatherEvent event, Vec3 start, Vec3 end, Vec3 axis, Vec3 forward,
                                         double centerT, double lineLength, float proximity) {
        double reach = event.radius();
        double gustSpeed = 0.06D + event.maxWindMph() * 0.0006D;
        int shelf = Mth.clamp(10 + Math.round(proximity * 10.0F), 10, 20);
        for (int i = 0; i < shelf; i++) {
            double t = visibleLineT(level, centerT, i, shelf, lineLength, 22.0D);
            int tier = level.random.nextInt(3);
            double ahead = reach * (0.10D + level.random.nextDouble() * 0.30D);
            double lip = 1.0D - (ahead - reach * 0.10D) / (reach * 0.30D);
            Vec3 base = pointAlongShelf(level, start, end, axis, forward, t, ahead, 18.0D);
            // Each tier a little higher, and the whole shelf climbing backward from its lip into the base.
            double height = 24.0D + tier * 10.0D + lip * 36.0D;
            Vec3 pos = new Vec3(base.x, visualY(level, base, height), base.z);
            // Lightest at the leading lip and the top tier, which face the open sky ahead; darkest low and back, running
            // into the whale's mouth. Shaded the other way round the shelf was the same grey as the rain wall behind it,
            // and vanished into it.
            float shade = (float) Mth.clamp(0.40D + tier * 0.10D + lip * 0.18D, 0.38D, 0.80D);
            spawnStructureParticle(level, pos, forward.scale(gustSpeed).add(0.0D, 0.004D, 0.0D), ParticleArt.cloud256(),
                    30.0F + level.random.nextFloat() * 16.0F, shade * 0.95F, shade * 0.97F, shade * 1.04F,
                    0.40F + tier * 0.05F, 160, 0.04F, ParticleTypes.CLOUD);
        }
        // The whale's mouth: the dark, smooth underside between the shelf and the rain.
        for (int i = 0; i < 5; i++) {
            double t = visibleLineT(level, centerT, i, 5, lineLength, 48.0D);
            Vec3 base = pointAlongShelf(level, start, end, axis, forward, t, reach * (-0.05D + level.random.nextDouble() * 0.15D), 24.0D);
            Vec3 pos = new Vec3(base.x, visualY(level, base, 52.0D + level.random.nextDouble() * 12.0D), base.z);
            float shade = 0.22F + level.random.nextFloat() * 0.06F;
            spawnStructureParticle(level, pos, forward.scale(gustSpeed * 0.8D), ParticleArt.cloud256(),
                    44.0F + level.random.nextFloat() * 20.0F, shade, shade * 1.02F, shade * 1.10F, 0.42F, 180, 0.03F, ParticleTypes.CLOUD);
        }
        // The outflow at the ground, rolling dust out ahead of the line.
        if (event.maxWindMph() > 50.0D) {
            for (int i = 0; i < 2; i++) {
                double t = visibleLineT(level, centerT, i, 2, lineLength, 60.0D);
                Vec3 base = pointAlongShelf(level, start, end, axis, forward, t, reach * (0.35D + level.random.nextDouble() * 0.20D), 30.0D);
                Vec3 pos = new Vec3(base.x, visualY(level, base, 0.5D + level.random.nextDouble() * 5.5D), base.z);
                DustTone tone = dustTone(level, pos);
                spawnStructureParticle(level, pos, forward.scale(0.22D + event.maxWindMph() * 0.0012D).add(0.0D, 0.02D, 0.0D), ParticleArt.cloud256(),
                        10.0F + level.random.nextFloat() * 8.0F, Math.min(1.0F, tone.red() * DUST_LIFT), Math.min(1.0F, tone.green() * DUST_LIFT),
                        Math.min(1.0F, tone.blue() * DUST_LIFT), 0.20F, 50, 0.20F, ParticleTypes.POOF);
            }
            // Over dry ground the outflow lifts so much dust that it walls the gust front in: a haboob, a rolling brown
            // front hundreds of blocks long and tens high, running ahead of the rain.
            // The ground's biome, not the biome of the air the line's points are carried in up at cloud height.
            Vec3 front = pointAlongShelf(level, start, end, axis, forward, centerT, reach * 0.45D, 0.0D);
            int frontX = Mth.floor(front.x);
            int frontZ = Mth.floor(front.z);
            BlockPos frontPos = new BlockPos(frontX, level.getHeight(Heightmap.Types.WORLD_SURFACE, frontX, frontZ), frontZ);
            Player viewer = Minecraft.getInstance().player;
            if (!level.isLoaded(frontPos) && viewer != null) {
                // Past the loaded chunks the client knows no biome; the country the viewer is standing in is the guess.
                frontPos = level.getHeightmapPos(Heightmap.Types.WORLD_SURFACE, viewer.blockPosition());
            }
            if (!level.getBiome(frontPos).value().hasPrecipitation()) {
                double strength = Mth.clamp((event.maxWindMph() - 50.0D) / 30.0D, 0.0D, 1.0D);
                int wall = 4 + (int) Math.round(strength * 6.0D);
                for (int i = 0; i < wall; i++) {
                    double t = visibleLineT(level, centerT, i, wall, lineLength, 40.0D);
                    // Only the stretch of the front near the viewer is drawn, so the wall thins out toward the ends of that
                    // stretch rather than stopping on a straight edge.
                    float fromMiddle = Math.abs(i - (wall - 1) * 0.5F) / Math.max(1.0F, (wall - 1) * 0.5F);
                    float endFade = 1.0F - 0.75F * fromMiddle * fromMiddle;
                    double ahead = reach * (0.40D + level.random.nextDouble() * 0.22D);
                    Vec3 base = pointAlongShelf(level, start, end, axis, forward, t, ahead, 26.0D);
                    double rise = level.random.nextDouble();
                    // Tallest just behind its leading face, where the outflow head is deepest.
                    Vec3 pos = new Vec3(base.x, visualY(level, base, 1.0D + rise * (24.0D + strength * 36.0D)), base.z);
                    // The fine desert dust a haboob is made of, tan to orange whatever is underfoot, mixed with the ground's own.
                    DustTone tone = dustTone(level, pos);
                    float lift = (float) Mth.lerp(rise, 0.86D, 1.04D);
                    float red = Mth.lerp(0.55F, tone.red() * DUST_LIFT, 0.74F) * lift;
                    float green = Mth.lerp(0.55F, tone.green() * DUST_LIFT, 0.56F) * lift;
                    float blue = Mth.lerp(0.55F, tone.blue() * DUST_LIFT, 0.38F) * lift;
                    spawnStructureParticle(level, pos, forward.scale(0.18D + event.maxWindMph() * 0.0011D).add(0.0D, 0.010D, 0.0D), ParticleArt.cloud256(),
                            30.0F + level.random.nextFloat() * 20.0F, Math.min(1.0F, red), Math.min(1.0F, green), Math.min(1.0F, blue),
                            0.42F * endFade, 110, 0.10F, ParticleTypes.POOF);
                }
            }
        }
        // Ahead of the line, in the warm air it has not reached yet: small fair-weather cumulus, bright and flat-based,
        // and over them the thin forward edge of the anvil.
        for (int i = 0; i < 2; i++) {
            double t = visibleLineT(level, centerT, i, 2, lineLength, 120.0D);
            Vec3 base = pointAlongShelf(level, start, end, axis, forward, t, reach * (0.9D + level.random.nextDouble() * 1.6D), 90.0D);
            double puff = level.random.nextDouble();
            Vec3 pos = new Vec3(base.x, visualY(level, base, 46.0D + puff * 14.0D), base.z);
            float shade = 0.84F + level.random.nextFloat() * 0.10F;
            spawnStructureParticle(level, pos, forward.scale(gustSpeed * 0.25D).add(0.0D, 0.004D, 0.0D), ParticleArt.cloud256(),
                    14.0F + level.random.nextFloat() * 12.0F, shade * 0.98F, shade * 0.99F, shade, 0.30F, 200, 0.05F, ParticleTypes.CLOUD);
        }
        // The anvil. The line's updrafts run into the tropopause and spread: a little forward over the warm air ahead, where
        // it is the thin cirrus seen long before the line arrives, and most of it back over the trailing rain, where it is
        // the high, flat top of the whole deck.
        for (int i = 0; i < 3; i++) {
            double t = visibleLineT(level, centerT, i, 3, lineLength, 110.0D);
            boolean forwardAnvil = level.random.nextFloat() < 0.35F;
            double spread = forwardAnvil ? reach * level.random.nextDouble() * 1.8D : -reach * level.random.nextDouble() * 2.6D;
            Vec3 base = pointAlongShelf(level, start, end, axis, forward, t, spread, 70.0D);
            double reachShare = Math.abs(spread) / (reach * (forwardAnvil ? 1.8D : 2.6D));
            double thickness = Mth.lerp(reachShare, 40.0D, 14.0D);
            double depth = level.random.nextDouble() * thickness;
            boolean underside = depth > thickness * 0.55D;
            Vec3 pos = new Vec3(base.x, visualY(level, base, 236.0D - depth), base.z);
            float shade = underside ? 0.62F : 0.86F;
            float alpha = (float) ((forwardAnvil ? 0.24D : 0.34D) * (1.0D - reachShare * 0.55D));
            spawnStructureParticle(level, pos, forward.scale(forwardAnvil ? gustSpeed * 0.9D : gustSpeed * 0.3D), ParticleArt.cloud256(),
                    (float) (56.0D + level.random.nextDouble() * 36.0D + reachShare * 24.0D), shade * 0.97F, shade * 0.98F, shade * 1.02F,
                    alpha, 300, 0.02F, ParticleTypes.CLOUD);
        }
        // The line's convective towers, standing over the deck along the leading edge.
        for (int i = 0; i < 3; i++) {
            double t = visibleLineT(level, centerT, i, 3, lineLength, 90.0D);
            Vec3 base = pointAlongShelf(level, start, end, axis, forward, t, reach * (-0.10D + level.random.nextDouble() * 0.25D), 40.0D);
            double rise = level.random.nextDouble();
            Vec3 pos = new Vec3(base.x, visualY(level, base, 110.0D + rise * rise * 130.0D), base.z);
            float shade = (float) Mth.lerp(rise, 0.55D, 0.86D);
            spawnStructureParticle(level, pos, forward.scale(gustSpeed * 0.5D).add(0.0D, 0.010D, 0.0D), ParticleArt.cloud256(),
                    34.0F + level.random.nextFloat() * 22.0F, shade * 0.95F, shade * 0.96F, shade, 0.34F, 220, 0.03F, ParticleTypes.CLOUD);
        }
    }

    /**
     * The broad-band tropical-cyclone visual, shared by the hurricane and the cyclone. The spin sign
     * is the only thing that differs: a hurricane rotates cyclonically (counter-clockwise here), the
     * cyclone anticyclonically, so its bands sweep the opposite way around the same eye and eyewall.
     */
    private static void spawnCycloneVisual(ClientLevel level, AddonWeatherEvent event, Vec3 playerPos, double spinSign) {
        Vec3 center = event.position();
        double radius = Math.max(180.0D, event.radius());
        // The eye and the eyewall come from the shield's own geometry, so these bands start where the deck's
        // eye ends instead of at a second, private eye - which used to sit inside the real one and put rain
        // bands across the calm core.
        double eyeRadius = Math.max(34.0D, HurricaneShield.eyeRadius(radius));
        double eyewallRadius = Math.max(82.0D, eyeRadius * 1.6D);
        double playerDistance = horizontalDistance(playerPos, center);
        if (playerDistance > radius * 2.9D) {
            return;
        }

        float proximity = Mth.clamp((float) (1.0D - Math.max(0.0D, playerDistance - radius * 1.10D) / Math.max(1.0D, radius * 1.70D)), 0.0F, 1.0F);
        int spiralCount = Mth.clamp(24 + (int) (radius / 38.0D) + (int) (proximity * 34.0F), 28, 92);
        int eyewallCount = Mth.clamp(12 + (int) (radius / 78.0D) + (int) (proximity * 18.0F), 14, 42);
        int rainCount = Mth.clamp(16 + (int) (radius / 54.0D) + (int) (proximity * 24.0F), 18, 66);
        double time = level.getGameTime() * 0.035D + event.id() * 0.00037D;

        for (int i = 0; i < spiralCount; i++) {
            double angle = level.random.nextDouble() * Math.PI * 2.0D;
            double cosAngle = Math.cos(angle);
            double sinAngle = Math.sin(angle);
            double bandRadius = Mth.lerp(Math.pow(level.random.nextDouble(), 0.55D), eyeRadius * 1.45D, radius * 1.42D);
            double spiralWave = Math.sin(angle * 3.4D + bandRadius * 0.035D - time * 1.35D * spinSign);
            if (spiralWave < 0.08D && level.random.nextFloat() < 0.72F) {
                continue;
            }
            double wrappedAngle = angle + bandRadius * 0.0088D * spinSign + time * 0.16D * spinSign + spiralWave * 0.18D;
            Vec3 base = center.add(Math.cos(wrappedAngle) * bandRadius, 0.0D, Math.sin(wrappedAngle) * bandRadius);
            double height = Mth.lerp(level.random.nextDouble(), 46.0D, 148.0D);
            Vec3 pos = new Vec3(base.x, visualY(level, base, height), base.z);
            Vec3 velocity = cycloneBandVelocity(event, pos, 0.035D, -0.012D, spinSign).add(0.0D, -0.004D + level.random.nextDouble() * 0.008D, 0.0D);
            float shade = 0.76F + level.random.nextFloat() * 0.24F;
            spawnStructureParticle(level, pos, velocity, ParticleArt.cloud256(),
                    34.0F + level.random.nextFloat() * 54.0F,
                    0.25F * shade, 0.27F * shade, 0.33F * shade, 0.50F + proximity * 0.10F, 172, 0.10F, ParticleTypes.CLOUD);
        }

        for (int i = 0; i < eyewallCount; i++) {
            double angle = level.random.nextDouble() * Math.PI * 2.0D + time * 0.22D * spinSign;
            double cosAngle = Math.cos(angle);
            double sinAngle = Math.sin(angle);
            double bandRadius = Mth.lerp(level.random.nextDouble(), eyeRadius * 1.05D, eyewallRadius);
            Vec3 base = center.add(cosAngle * bandRadius, 0.0D, sinAngle * bandRadius);
            double height = Mth.lerp(level.random.nextDouble(), 22.0D, 142.0D);
            Vec3 pos = new Vec3(base.x, visualY(level, base, height), base.z);
            Vec3 velocity = cycloneBandVelocity(event, pos, 0.070D, -0.020D, spinSign).add(0.0D, -0.012D - level.random.nextDouble() * 0.012D, 0.0D);
            float depth = level.random.nextFloat();
            spawnStructureParticle(level, pos, velocity, ParticleArt.cloud256(),
                    42.0F + level.random.nextFloat() * 62.0F,
                    Mth.lerp(depth, 0.16F, 0.34F), Mth.lerp(depth, 0.18F, 0.36F), Mth.lerp(depth, 0.23F, 0.44F),
                    0.58F + proximity * 0.10F, 150, 0.16F, ParticleTypes.CLOUD);
        }

        TextureAtlasSprite rainSprite = RainShaftSprites.sheet(
                level.random, Mth.clamp(0.62F + proximity * 0.35F, 0.0F, 1.0F));
        for (int i = 0; i < rainCount; i++) {
            double angle = level.random.nextDouble() * Math.PI * 2.0D;
            double cosAngle = Math.cos(angle);
            double sinAngle = Math.sin(angle);
            double bandRadius = Mth.lerp(level.random.nextDouble(), eyewallRadius * 0.86D, radius * 1.62D);
            double spiralWave = Math.sin(angle * 2.8D + bandRadius * 0.025D - time * 1.18D * spinSign);
            if (spiralWave < -0.24D && level.random.nextFloat() < 0.58F) {
                continue;
            }
            double wrappedAngle = angle + bandRadius * 0.0065D * spinSign + time * 0.12D * spinSign;
            Vec3 base = center.add(Math.cos(wrappedAngle) * bandRadius, 0.0D, Math.sin(wrappedAngle) * bandRadius);
            double height = Mth.lerp(level.random.nextDouble(), 8.0D, 116.0D);
            Vec3 pos = new Vec3(base.x, visualY(level, base, height), base.z);
            Vec3 velocity = cycloneBandVelocity(event, pos, 0.045D, -0.010D, spinSign).add(0.0D, -0.20D - level.random.nextDouble() * 0.16D, 0.0D);
            float haze = level.random.nextFloat();
            spawnStructureParticle(level, pos, velocity, rainSprite,
                    54.0F + level.random.nextFloat() * 74.0F,
                    Mth.lerp(haze, 0.28F, 0.46F), Mth.lerp(haze, 0.32F, 0.50F), Mth.lerp(haze, 0.44F, 0.62F),
                    0.48F + proximity * 0.16F, 74, 0.18F, ParticleTypes.RAIN);
        }
    }

    /** Both members of the broad rotating storm family share the cyclone visual machinery. */
    private static boolean isCycloneFamily(StormSubtype type) {
        return type == StormSubtype.HURRICANE || type == StormSubtype.CYCLONE;
    }

    /**
     * Band velocity for the cyclone family. The tangent direction carries the spin sign, so the
     * bands of an anticyclone sweep opposite to a hurricane's; the inflow, rain curtain and storm
     * motion are identical, because those are not handed.
     */
    private static Vec3 cycloneBandVelocity(AddonWeatherEvent event, Vec3 pos, double spin, double inflow, double spinSign) {
        Vec3 center = event.position();
        Vec3 delta = pos.subtract(center);
        double len = Math.max(0.1D, Math.sqrt(delta.x * delta.x + delta.z * delta.z));
        Vec3 tangent = new Vec3(-delta.z / len, 0.0D, delta.x / len).scale((spin + event.maxWindMph() * 0.00055D) * spinSign);
        Vec3 radial = new Vec3(delta.x / len, 0.0D, delta.z / len).scale(inflow);
        Vec3 motion = event.motion() == null ? Vec3.ZERO : new Vec3(event.motion().x, 0.0D, event.motion().z).scale(0.35D);
        return tangent.add(radial).add(motion);
    }

    private static Vec3 pointAlongShelf(ClientLevel level, Vec3 start, Vec3 end, Vec3 axis, Vec3 forward, double t, double forwardOffset, double lateralJitter) {
        double bowOffset = squallLineBowOffset(t, start.distanceTo(end));
        Vec3 base = start.lerp(end, Mth.clamp(t, 0.0D, 1.0D));
        return base.add(axis.scale((level.random.nextDouble() - 0.5D) * lateralJitter)).add(forward.scale(forwardOffset + bowOffset));
    }

    private static double visibleLineT(ClientLevel level, double centerT, int index, int count, double lineLength, double spacingBlocks) {
        double spacing = spacingBlocks / Math.max(1.0D, lineLength);
        double centered = index - (count - 1.0D) * 0.5D;
        double jitter = (level.random.nextDouble() - 0.5D) * spacing * 0.62D;
        return Mth.clamp(centerT + centered * spacing + jitter, 0.0D, 1.0D);
    }

    private static double lineSampleT(Vec3 point, Vec3 start, Vec3 end) {
        Vec3 segment = end.subtract(start);
        double lenSqr = segment.x * segment.x + segment.z * segment.z;
        if (lenSqr < 1.0E-5D) {
            return 0.5D;
        }
        return Mth.clamp(((point.x - start.x) * segment.x + (point.z - start.z) * segment.z) / lenSqr, 0.0D, 1.0D);
    }

    private static double squallLineBowOffset(double t, double lineLength) {
        double centerBow = Math.sin(Math.PI * Mth.clamp(t, 0.0D, 1.0D));
        return Math.min(130.0D, Math.max(20.0D, lineLength * 0.085D)) * centerBow;
    }

    private static double distanceToEvent(AddonWeatherEvent event, Vec3 pos) {
        if (event.isLine() && event.lineStart() != null && event.lineEnd() != null) {
            return distanceToLineSegment(pos, event.lineStart(), event.lineEnd());
        }
        return event.position().distanceTo(pos);
    }

    private static float squallLineSkyProximity(Vec3 playerPos) {
        float strongest = 0.0F;
        for (AddonWeatherEvent event : ClientAddonWeatherEventStore.events()) {
            if (event.type() != StormSubtype.SQUALL_LINE || event.lineStart() == null || event.lineEnd() == null) {
                continue;
            }
            double distance = distanceToLineSegment(playerPos, event.lineStart(), event.lineEnd());
            float proximity = Mth.clamp((float) (1.0D - distance / Math.max(360.0D, event.radius() * 2.75D)), 0.0F, 1.0F);
            strongest = Math.max(strongest, proximity);
        }
        return strongest;
    }

    private static double distanceToLineSegment(Vec3 point, Vec3 start, Vec3 end) {
        Vec3 segment = end.subtract(start);
        double lenSqr = segment.x * segment.x + segment.z * segment.z;
        if (lenSqr < 1.0E-5D) {
            return point.distanceTo(start);
        }
        double t = Mth.clamp(((point.x - start.x) * segment.x + (point.z - start.z) * segment.z) / lenSqr, 0.0D, 1.0D);
        double x = start.x + segment.x * t;
        double z = start.z + segment.z * t;
        double dx = point.x - x;
        double dz = point.z - z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    private static Vec3 randomVortexParticle(ClientLevel level, AddonWeatherEvent event) {
        double angle = level.random.nextDouble() * Math.PI * 2.0D;
        double cosAngle = Math.cos(angle);
        double sinAngle = Math.sin(angle);
        double height = vortexParticleHeight(level, event);
        double radiusMultiplier = event.type() == StormSubtype.GUSTNADO ? 2.85D : event.type() == StormSubtype.FIRE_WHIRL ? 1.45D : event.type() == StormSubtype.DUST_DEVIL || event.type() == StormSubtype.SNOW_DEVIL ? 1.15D : 1.0D;
        double radius = Math.sqrt(level.random.nextDouble()) * event.radius() * radiusMultiplier;
        double y = vortexGroundY(level, event.position());
        double baseOffset = event.type() == StormSubtype.LANDSPOUT ? 0.45D : event.type() == StormSubtype.FLOATER_TORNADO ? Math.max(12.0D, event.position().y - y) : 0.2D;
        Vec3 horizontal = new Vec3(cosAngle * radius, 0.0D, sinAngle * radius);
        if (event.type() == StormSubtype.GUSTNADO && event.motion() != null && event.motion().horizontalDistanceSqr() > 1.0E-5D) {
            Vec3 forward = new Vec3(event.motion().x, 0.0D, event.motion().z).normalize();
            horizontal = horizontal.add(forward.scale(height * 0.62D + level.random.nextDouble() * event.radius() * 2.2D));
        }
        return new Vec3(event.position().x + horizontal.x, y + baseOffset + height, event.position().z + horizontal.z);
    }

    private static Vec3 randomLineParticle(ClientLevel level, AddonWeatherEvent event) {
        Vec3 start = event.lineStart() == null ? event.position() : event.lineStart();
        Vec3 end = event.lineEnd() == null ? event.position() : event.lineEnd();
        Vec3 line = end.subtract(start);
        Vec3 normal = line.horizontalDistanceSqr() < 1.0E-5D ? new Vec3(1.0D, 0.0D, 0.0D) : new Vec3(-line.z, 0.0D, line.x).normalize();
        Vec3 pos = start.lerp(end, level.random.nextDouble()).add(normal.scale((level.random.nextDouble() - 0.5D) * event.radius()));
        int y = vortexGroundY(level, pos);
        return new Vec3(pos.x, y, pos.z);
    }

    private static Vec3 swirlVelocity(AddonWeatherEvent event, Vec3 pos) {
        Vec3 delta = pos.subtract(event.position());
        double len = Math.max(0.1D, Math.sqrt(delta.x * delta.x + delta.z * delta.z));
        return new Vec3(-delta.z / len, 0.0D, delta.x / len).scale(0.08D + event.maxWindMph() * 0.0014D);
    }

    private static Vec3 lineVelocity(ClientLevel level, AddonWeatherEvent event) {
        Vec3 motion = event.motion();
        if (motion == null || motion.horizontalDistanceSqr() < 1.0E-5D) {
            Vec3 start = event.lineStart() == null ? event.position() : event.lineStart();
            Vec3 end = event.lineEnd() == null ? event.position().add(1.0D, 0.0D, 0.0D) : event.lineEnd();
            Vec3 line = end.subtract(start);
            return line.horizontalDistanceSqr() < 1.0E-5D ? new Vec3(0.16D, 0.0D, 0.0D) : new Vec3(-line.z, 0.0D, line.x).normalize().scale(0.16D);
        }
        return new Vec3(motion.x, 0.0D, motion.z).normalize().scale(0.16D + level.random.nextDouble() * 0.12D);
    }

    private static DustTone dustTone(ClientLevel level, Vec3 pos) {
        return surfaceDustTone(level, pos, 0.0F);
    }

    private static DustTone debrisTone(ClientLevel level, Vec3 pos, boolean lowGroundBowl, boolean loftedCurtain) {
        DustTone base = surfaceDustTone(level, pos, loftedCurtain ? 0.14F : lowGroundBowl ? -0.04F : 0.04F);
        float contrast = lowGroundBowl ? 0.92F : loftedCurtain ? 1.08F : 1.0F;
        return new DustTone(
                Mth.clamp(base.red() * contrast, 0.05F, 0.90F),
                Mth.clamp(base.green() * contrast, 0.05F, 0.90F),
                Mth.clamp(base.blue() * contrast, 0.05F, 0.90F)
        );
    }

    private static DustTone tornadoFootTone(ClientLevel level, Vec3 pos) {
        DustTone base = surfaceDustTone(level, pos, -0.08F);
        return new DustTone(
                Mth.clamp(base.red() * 0.88F + 0.030F, 0.04F, 0.78F),
                Mth.clamp(base.green() * 0.82F + 0.020F, 0.04F, 0.72F),
                Mth.clamp(base.blue() * 0.72F + 0.012F, 0.04F, 0.66F)
        );
    }

    private static DustTone surfaceDustTone(ClientLevel level, Vec3 pos, float lift) {
        BlockState state = surfaceState(level, pos);
        DustTone tone;
        if (state.getFluidState().is(FluidTags.WATER)) {
            tone = new DustTone(0.34F, 0.39F, 0.42F);
        } else if (state.is(net.minecraft.world.level.block.Blocks.WATER)
                || level.getFluidState(BlockPos.containing(pos.x, vortexGroundY(level, pos), pos.z).below()).is(FluidTags.WATER)) {
            tone = new DustTone(0.34F, 0.39F, 0.42F);
        } else if (state.is(net.minecraft.world.level.block.Blocks.SAND)) {
            tone = new DustTone(0.66F, 0.54F, 0.34F);
        } else if (state.is(net.minecraft.world.level.block.Blocks.RED_SAND)) {
            tone = new DustTone(0.64F, 0.38F, 0.24F);
        } else if (state.is(net.minecraft.world.level.block.Blocks.SNOW)
                || state.is(net.minecraft.world.level.block.Blocks.SNOW_BLOCK)
                || state.is(net.minecraft.world.level.block.Blocks.POWDER_SNOW)
                || state.is(net.minecraft.world.level.block.Blocks.ICE)
                || state.is(net.minecraft.world.level.block.Blocks.PACKED_ICE)
                || state.is(net.minecraft.world.level.block.Blocks.BLUE_ICE)) {
            tone = new DustTone(0.70F, 0.73F, 0.76F);
        } else if (state.is(net.minecraft.world.level.block.Blocks.MUD)) {
            tone = new DustTone(0.25F, 0.20F, 0.16F);
        } else if (state.is(net.minecraft.world.level.block.Blocks.DIRT)
                || state.is(net.minecraft.world.level.block.Blocks.COARSE_DIRT)
                || state.is(net.minecraft.world.level.block.Blocks.ROOTED_DIRT)
                || state.is(net.minecraft.world.level.block.Blocks.GRASS_BLOCK)
                || state.is(net.minecraft.world.level.block.Blocks.PODZOL)) {
            tone = new DustTone(0.42F, 0.32F, 0.20F);
        } else {
            tone = new DustTone(0.50F, 0.42F, 0.32F);
        }
        return new DustTone(
                Mth.clamp(tone.red() + lift, 0.05F, 0.92F),
                Mth.clamp(tone.green() + lift, 0.05F, 0.92F),
                Mth.clamp(tone.blue() + lift, 0.05F, 0.92F)
        );
    }

    private static BlockState surfaceState(ClientLevel level, Vec3 pos) {
        BlockPos surface = BlockPos.containing(pos.x, vortexGroundY(level, pos), pos.z);
        BlockState state = level.getBlockState(surface);
        if (!state.isAir() || state.getFluidState().is(FluidTags.WATER)) {
            return state;
        }
        return level.getBlockState(surface.below());
    }

    private static double horizontalDistance(Vec3 first, Vec3 second) {
        double dx = first.x - second.x;
        double dz = first.z - second.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    private static double visualY(ClientLevel level, Vec3 base, double offset) {
        // While a storm's structure is being laid out, everything is measured from the ground under that storm. A storm
        // spreads well past the loaded chunks, and an unloaded column answers the bottom of the world, which was putting
        // whole sheets of its cloud at head height. A base that is flat is also what a base should be.
        int groundY = structureFunnelStorm != null ? structureGroundY : vortexGroundY(level, base);
        // Not held under the build limit: a supercell's tower and anvil stand far above any block, and held at the limit
        // they were pressed into the hundred blocks over the cloud base, so the tallest thing in the sky was no taller
        // than the deck. A particle up there is lit by the open sky like any other.
        return Mth.clamp(groundY + offset, level.getMinBuildHeight() + 1.0D, level.getMaxBuildHeight() + SKY_HEADROOM_BLOCKS);
    }

    /** The ground a puff of dust stands on: under it where that is loaded, under the storm where it is not. */
    private static int dustGroundY(ClientLevel level, StormObject storm, Vec3 sample) {
        int ground = vortexGroundY(level, sample);
        return ground > level.getMinBuildHeight() + 1 ? ground : stormGroundY(level, storm);
    }

    /** The ground under a storm, taken from its funnel's foot where the chunk under it is not loaded. */
    private static int stormGroundY(ClientLevel level, StormObject storm) {
        int ground = vortexGroundY(level, storm.pos);
        if (ground > level.getMinBuildHeight() + 1) {
            return ground;
        }
        // Weather2's own ground under the storm, which the server measures and sends. The funnel's foot is no stand-in for
        // it: Weather2 raises that toward the cloud through a tornado's forming stage, and dust placed from it went up too.
        if (storm.currentTopYBlock > level.getMinBuildHeight() + 1) {
            return storm.currentTopYBlock;
        }
        var funnel = storm.getTornadoFunnelSimple();
        return funnel != null && funnel.pos != null && funnel.pos.y > level.getMinBuildHeight() + 1 ? Mth.floor(funnel.pos.y) : level.getSeaLevel();
    }

    private static int vortexGroundY(ClientLevel level, Vec3 pos) {
        BlockPos at = BlockPos.containing(pos);
        if (!level.isLoaded(at)) {
            // A column the client holds no chunk for answers the bottom of the world, and everything measured up from it
            // was laid out a hundred-odd blocks underground: a squall line seen from past the render distance had its
            // shelf, its anvil and its dust all below the horizon. Out there the ground under the camera is the best
            // guess there is.
            Player viewer = Minecraft.getInstance().player;
            return viewer != null ? clientGroundY(level, viewer.getBlockX(), viewer.getBlockZ()) : level.getSeaLevel();
        }
        return clientGroundY(level, at.getX(), at.getZ());
    }

    /**
     * The ground in a loaded column, as the client can know it. The server keeps a motion-blocking-without-leaves map but
     * never sends it, and the client's own copy is primed once and never kept current - read on the client it answered
     * the bottom of the world even under the camera, and everything measured up from it went underground. The surface
     * map is the one the client is sent and keeps up, so the ground is read from it, stepping down past plants and leaves
     * to the first block that would stop something falling, or water.
     */
    private static int clientGroundY(ClientLevel level, int x, int z) {
        int surface = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
        int floor = Math.max(level.getMinBuildHeight(), surface - 48);
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos(x, surface - 1, z);
        for (int y = surface - 1; y >= floor; y--) {
            cursor.setY(y);
            BlockState state = level.getBlockState(cursor);
            if (!state.getFluidState().isEmpty() || (state.blocksMotion() && !state.is(BlockTags.LEAVES))) {
                return y + 1;
            }
        }
        return surface;
    }

    /** How far above the build limit a storm's structure may stand, in blocks. */
    private static final double SKY_HEADROOM_BLOCKS = 640.0D;

    /** True while rain that is meant to hide the funnel is being spawned: it is let into the funnel's clear air. */
    private static boolean structureInClearAir;

    private static void spawnStructureParticle(ClientLevel level, Vec3 pos, Vec3 velocity, TextureAtlasSprite sprite, float scale, float red, float green, float blue, float alpha, int maxAge, float windWeight, ParticleOptions fallback) {
        // The storm's own structure has the same room in the queue as a tornado's ground dust: it is the storm, not a loose
        // effect laid over it, and at the loose ceiling a violent tornado's funnel left it none.
        if (structureParticlesSpawnedThisTick >= Config.SUPERCELL_STRUCTURE_MAX_PARTICLES_PER_TICK.get() || SoftPuffs.groundDustCrowded()
                || (!structureInClearAir && inFunnelsClearAir(pos, scale))) {
            return;
        }
        structureParticlesSpawnedThisTick++;
        if (!spawnWeather2Particle(level, pos, velocity, sprite, scale, red, green, blue, alpha, maxAge, windWeight)) {
            level.addParticle(fallback, pos.x, pos.y, pos.z, velocity.x, velocity.y, velocity.z);
        }
    }

    private static boolean spawnWeather2Particle(ClientLevel level, Vec3 pos, Vec3 velocity, TextureAtlasSprite sprite, float scale, float red, float green, float blue, float alpha, int maxAge, float windWeight) {
        if (!Config.EXTENDED_RENDERING_ENABLED.get() || sprite == null) {
            return false;
        }
        spawnWeather2ParticleDirect(level, pos, velocity, sprite, scale, red, green, blue, alpha, maxAge, windWeight);
        return true;
    }

    private static void spawnCloud256Particle(ClientLevel level, Vec3 pos, Vec3 velocity, float scale, float red, float green, float blue, float alpha, int maxAge, float windWeight) {
        if (ParticleArt.cloud256() == null) {
            return;
        }
        spawnWeather2ParticleDirect(level, pos, velocity, ParticleArt.cloud256(), scale, red, green, blue, alpha, maxAge, windWeight);
    }

    private static void spawnWeather2ParticleDirect(ClientLevel level, Vec3 pos, Vec3 velocity, TextureAtlasSprite sprite, float scale, float red, float green, float blue, float alpha, int maxAge, float windWeight) {
        EntityRotFX particle = new EntityRotFX(level, pos.x, pos.y, pos.z, velocity.x, velocity.y, velocity.z);
        particle.setSprite(sprite);
        particle.setScale(Mth.clamp(scale, 0.05F, 180.0F));
        particle.setColor(red, green, blue);
        boolean downfallSheet = RainShaftSprites.isShaftSprite(sprite);
        boolean cloudSheet = sprite == ParticleRegistry.cloud || sprite == ParticleArt.cloud256();
        float clampedAlpha = weatherParticleAlpha(sprite, alpha);
        particle.setAlpha(clampedAlpha);
        particle.setFullAlphaTarget(clampedAlpha);
        particle.setMaxAge(maxAge);
        particle.setGravity(0.0F);
        particle.setCanCollide(false);
        particle.setFacePlayer(!downfallSheet);
        particle.facePlayerYaw = !downfallSheet;
        particle.isTransparent = true;
        particle.renderOrder = downfallSheet ? 2 : cloudSheet ? 1 : particle.renderOrder;
        particle.windWeight = effectiveWindWeight(windWeight);
        if (downfallSheet) {
            boolean streaked = RainShaftSprites.isStreakedSprite(sprite);
            float yaw = (float) Math.toDegrees(Math.atan2(velocity.x, velocity.z));
            particle.prevRotationYaw = yaw;
            particle.rotationYaw = yaw;
            // A streaked sheet is a standing rain curtain. Weather2 recomputes a slanted particle's
            // pitch from its fall direction every tick, and the textures carry their streaks
            // vertically, so leaving it slanted is what puts vertical rain in the world instead of a
            // flat haze layer. Weather2's own soft sheet keeps its original near-horizontal tilt.
            float pitch = streaked ? 0.0F : 82.0F;
            particle.prevRotationPitch = pitch;
            particle.rotationPitch = pitch;
            particle.quatControl = true;
            particle.useRotationAroundCenter = true;
            particle.setSlantParticleToWind(streaked);
        }
        particle.spawnAsWeatherEffect();
    }

    /**
     * The ratio between the mean alpha of the sheet this class' dust alphas were tuned against and the
     * mean of the sheet actually selected. About 1 while this mod's regenerated sheet is in use and about 5.7
     * while Weather2's own 1.12.2 sheet is, and every call site clamps the product to full opacity, so the
     * faint sheet recovers the weight it would otherwise lose. Read live, so flipping {@code storm_look.originalTextures} changes
     * this on the next particle.
     */
    public static float cloud256AlphaCompensation() {
        return CLOUD256_SPRITE_MEAN_ALPHA_TUNED
                / (Config.originalParticleArt() ? CLOUD256_SPRITE_MEAN_ALPHA_ORIGINAL : CLOUD256_SPRITE_MEAN_ALPHA_REGENERATED);
    }

    private static float effectiveWindWeight(float windWeight) {
        return windWeight <= 0.0F ? LOCAL_PARTICLE_WIND_WEIGHT : windWeight;
    }

    private static float weatherParticleAlpha(TextureAtlasSprite sprite, float alpha) {
        boolean downfallSheet = RainShaftSprites.isShaftSprite(sprite);
        boolean cloudSheet = sprite == ParticleRegistry.cloud || sprite == ParticleArt.cloud256();
        if (downfallSheet) {
            return Mth.clamp(alpha * 1.16F + 0.08F, 0.20F, 0.82F);
        }
        if (cloudSheet) {
            // cloud256 doubles as the puffy dust/vapour sprite, so whichever body it has is ratioed
            // back out here; Weather2's own cloud.png is a different, unchanged sheet.
            float density = sprite == ParticleArt.cloud256() ? cloud256AlphaCompensation() : 1.0F;
            return Mth.clamp((alpha * 1.32F + 0.12F) * density, 0.12F, 0.96F);
        }
        return Mth.clamp(alpha, 0.04F, 0.96F);
    }

    private static void spawnCloudShadowParticle(ClientLevel level, Vec3 pos, float scale, float shade, float alpha, int maxAge) {
        EntityRotFX particle = new EntityRotFX(level, pos.x, pos.y, pos.z, 0.0D, 0.0D, 0.0D);
        particle.setSprite(ParticleArt.cloud256());
        particle.setScale(Mth.clamp(scale, 14.0F, 168.0F));
        particle.setColor(shade, shade * 1.04F, shade * 1.12F);
        float clampedAlpha = Mth.clamp(Mth.clamp(alpha, 0.14F, 0.44F) * cloud256AlphaCompensation(), 0.0F, 0.96F);
        particle.setAlpha(clampedAlpha);
        particle.setFullAlphaTarget(clampedAlpha);
        particle.setTicksFadeInMax(18.0F);
        particle.setTicksFadeOutMax(34.0F);
        particle.setMaxAge(maxAge);
        particle.setGravity(0.0F);
        particle.setCanCollide(false);
        particle.setFacePlayer(false);
        particle.facePlayerYaw = false;
        particle.isTransparent = true;
        particle.renderOrder = 0;
        particle.windWeight = LOCAL_PARTICLE_WIND_WEIGHT;
        particle.quatControl = true;
        particle.useRotationAroundCenter = true;
        float yaw = level.random.nextFloat() * 360.0F;
        particle.prevRotationYaw = yaw;
        particle.rotationYaw = yaw;
        particle.prevRotationPitch = 90.0F;
        particle.rotationPitch = 90.0F;
        particle.setRenderDistanceCull(Config.SUPERCELL_STRUCTURE_RENDER_RANGE_BLOCKS.get());
        particle.spawnAsWeatherEffect();
    }

    private static float densityFor(AddonWeatherEvent event) {
        if (event.type() == StormSubtype.DUST_DEVIL) {
            return Config.DUST_DEVIL_STICK_DENSITY.get().floatValue();
        }
        if (event.type() == StormSubtype.SNOW_DEVIL) {
            // Its own figure rather than the dust devil's: cold ground holds far less loose material, and
            // what it does hold is snow.
            return Config.SNOW_DEVIL_STICK_DENSITY.get().floatValue();
        }
        if (event.type() == StormSubtype.LANDSPOUT) {
            return Config.LANDSPOUT_STICK_DENSITY.get().floatValue();
        }
        if (event.type() == StormSubtype.WATERSPOUT) {
            return Config.WATERSPOUT_STICK_DENSITY.get().floatValue();
        }
        if (event.type() == StormSubtype.GUSTNADO) {
            return Config.DUST_DEVIL_STICK_DENSITY.get().floatValue() * 0.38F;
        }
        if (event.type() == StormSubtype.FIRE_WHIRL) {
            return Config.DUST_DEVIL_STICK_DENSITY.get().floatValue() * 0.25F;
        }
        if (event.type() == StormSubtype.SQUALL_LINE) {
            return Config.SQUALL_LINE_STICK_DENSITY.get().floatValue();
        }
        return 0.0F;
    }

    private static int particleCountFor(AddonWeatherEvent event) {
        if (event.type() == StormSubtype.WATERSPOUT) {
            return 4;
        }
        if (event.type() == StormSubtype.DUST_DEVIL) {
            return 9;
        }
        if (event.type() == StormSubtype.SNOW_DEVIL) {
            return 6;
        }
        if (event.type() == StormSubtype.GUSTNADO) {
            return 14;
        }
        if (event.type() == StormSubtype.FIRE_WHIRL) {
            return 16;
        }
        if (event.type() == StormSubtype.FLOATER_TORNADO) {
            return 10;
        }
        if (event.type() == StormSubtype.SQUALL_LINE) {
            return 8;
        }
        return 9;
    }

    private static double vortexParticleHeight(ClientLevel level, AddonWeatherEvent event) {
        if (event.type() == StormSubtype.DUST_DEVIL) {
            return Math.pow(level.random.nextDouble(), 1.25D) * 42.0D;
        }
        if (event.type() == StormSubtype.SNOW_DEVIL) {
            return Math.pow(level.random.nextDouble(), 1.30D) * 30.0D;
        }
        if (event.type() == StormSubtype.WATERSPOUT) {
            return level.random.nextDouble() * 7.5D;
        }
        if (event.type() == StormSubtype.GUSTNADO) {
            return Math.pow(level.random.nextDouble(), 1.45D) * 22.0D;
        }
        if (event.type() == StormSubtype.FIRE_WHIRL) {
            return Math.pow(level.random.nextDouble(), 1.10D) * 34.0D;
        }
        if (event.type() == StormSubtype.FLOATER_TORNADO) {
            return Math.pow(level.random.nextDouble(), 1.20D) * 28.0D;
        }
        return level.random.nextDouble() * 14.0D;
    }

    private static boolean shouldSpawnStickDebris(ClientLevel level, float chance, Vec3 pos) {
        if (!Config.STICK_DEBRIS_ENABLED.get() || Config.MAX_STICK_DEBRIS_PER_TICK.get() <= 0 || stickDebrisSpawnedThisTick >= Config.MAX_STICK_DEBRIS_PER_TICK.get()) {
            return false;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null && minecraft.player.position().distanceTo(pos) > Config.STICK_DEBRIS_RENDER_RANGE_BLOCKS.get()) {
            return false;
        }
        return level.random.nextFloat() < chance;
    }

    /**
     * Loose debris: the pieces a storm tears off the ground and throws. It throws the ground itself, so a
     * chunk is cut from the surface block under the point it is thrown from, and a debris field reads as the
     * country it came out of - a scoured field throws grass, a desert throws sand, a gravel path throws
     * gravel - rather than as one grey sprite over all of them.
     *
     * <p>Named for the switch it serves ({@code stickDebris}), which is still what the field is called in the
     * config, and for what it falls back to: a column with no ground in it to cut a chunk out of has nothing
     * to throw but a loose stick.
     */
    private static void spawnStickDebris(ClientLevel level, Vec3 pos, Vec3 velocity) {
        if (stickDebrisSpawnedThisTick >= Config.MAX_STICK_DEBRIS_PER_TICK.get()) {
            return;
        }
        stickDebrisSpawnedThisTick++;
        double wobbleX = (level.random.nextDouble() - 0.5D) * 0.05D;
        double wobbleZ = (level.random.nextDouble() - 0.5D) * 0.05D;
        level.addParticle(
                new ItemParticleOption(ParticleTypes.ITEM, new ItemStack(Items.STICK)),
                pos.x,
                pos.y,
                pos.z,
                velocity.x + wobbleX,
                velocity.y,
                velocity.z + wobbleZ
        );
    }

    private record DustTone(float red, float green, float blue) {
    }

    private record DustCloudContext(boolean fire, boolean water, float gust, float humidity) {
        private static DustCloudContext forStorm(ClientLevel level, StormObject storm) {
            double phase = (storm.ID % 90L) * 0.69D;
            float gust = (float) (0.78D + 0.30D * Math.sin(level.getGameTime() * 0.07D + phase));
            // Fire whirls are addon events on this branch, so StormObject vortices never render fire-toned dust here.
            return new DustCloudContext(false, storm.attrib_waterSpout, gust, stormHumidity(storm));
        }
    }

    /**
     * Storm moisture proxy. Weather2 accumulates a per-storm water level from
     * biome humidity (swamp/jungle/river biomes gain it fast) and precipitates
     * once it crosses the raining threshold (levelWaterStartRaining = 100). The
     * value is NBT-synced to the client, so the ratio doubles as the storm's
     * relative humidity: ~0 bone dry, 1.0 = saturated/raining, above = actively
     * precipitating. Dust needs dry soil, so this drives whether the tornado's
     * dust cloud can form and how big it grows.
     */
    private static float stormHumidity(StormObject storm) {
        if (!Config.TORNADO_DEBRIS_CLOUD_HUMIDITY_GATE.get()) {
            return 0.25F; // humidity response off: treat as dry, dust always forms
        }
        int startRaining = storm.levelWaterStartRaining;
        if (startRaining <= 0) {
            return 0.35F;
        }
        float scale = Config.TORNADO_DEBRIS_CLOUD_HUMIDITY_SCALE.get().floatValue();
        return Mth.clamp(storm.levelWater / (float) startRaining * scale, 0.0F, 2.0F);
    }

    private static DustTone toneFor(ClientLevel level, Vec3 pos, boolean lowGroundBowl, boolean loftedCurtain, DustCloudContext context) {
        if (context.fire()) {
            return fireDustTone(level);
        }
        if (context.water()) {
            return waterDustTone();
        }
        DustTone base = lowGroundBowl ? tornadoFootTone(level, pos) : debrisTone(level, pos, false, loftedCurtain);
        float wet = Mth.clamp(context.humidity(), 0.0F, 1.0F);
        if (wet <= 0.01F) {
            return base;
        }
        // Moisture wets the soil and the lofted dust: the bright tan turns into a
        // darker, muddier gray-brown instead of a dry dusty haze.
        return new DustTone(
                Mth.clamp(Mth.lerp(wet, base.red(), base.red() * 0.62F + 0.11F), 0.04F, 0.90F),
                Mth.clamp(Mth.lerp(wet, base.green(), base.green() * 0.58F + 0.09F), 0.04F, 0.90F),
                Mth.clamp(Mth.lerp(wet, base.blue(), base.blue() * 0.54F + 0.11F), 0.04F, 0.90F)
        );
    }

    /**
     * The tone the funnel's dusty foot is painted with, resolved once per storm per tick.
     *
     * <p>The foot is not a separate object: it is the same dust the ground cloud is made of, so it
     * uses the same tone path - the local soil colour lifted, dried or muddied by the storm's own
     * humidity. A desert storm therefore gets a tan foot and a rain-soaked one a dark grey-brown,
     * and the two halves of the tornado read as one continuous column.
     */
    private static FunnelTint funnelFootTone(ClientLevel level, StormObject storm, Vec3 footPos) {
        DustCloudContext context = DustCloudContext.forStorm(level, storm);
        DustTone tone = context.water() ? waterDustTone() : toneFor(level, footPos, true, false, context);
        return new FunnelTint(tone.red(), tone.green(), tone.blue());
    }

    /**
     * How much of the foot's dust colour survives at this point on the funnel: full at the ground,
     * gone by {@link Config#FUNNEL_DUST_GRADIENT_HEIGHT} of the visible funnel, and washed out of a
     * saturated storm (a raining tornado's foot is grey water-laden cloud, not lifted soil).
     */
    private static float funnelDustAmount(StormObject storm, float heightFraction, float formation) {
        if (!Config.FUNNEL_DUST_GRADIENT_ENABLED.get() || !Config.TORNADO_DEBRIS_CLOUD_ENABLED.get()
                || Config.classicLook()) {
            return 0.0F; // classic look keeps Weather2's own condensation colouring on the funnel
        }
        if (storm == null || storm.isFirenado) {
            return 0.0F; // never fight Weather2's own fire colours
        }
        float dustHeight = (float) Mth.clamp(Config.FUNNEL_DUST_GRADIENT_HEIGHT.get() * FUNNEL_DIRT_HEIGHT_SHARE, 0.05D, 0.90D);
        float t = Mth.clamp(heightFraction / dustHeight, 0.0F, 1.0F);
        float falloff = 1.0F - t * t * (3.0F - 2.0F * t);
        float wet = Mth.clamp(stormHumidity(storm), 0.0F, 1.0F);
        float wetFade = 1.0F - Mth.clamp((wet - 0.55F) / 0.45F, 0.0F, 1.0F);
        return falloff * wetFade * Mth.clamp(formation, 0.0F, 1.0F)
                * (float) Mth.clamp(Config.FUNNEL_DUST_TINT_STRENGTH.get() * FUNNEL_DIRT_TINT_SHARE, 0.0D, 1.0D);
    }

    /**
     * Colour for one funnel particle: the condensation grey it would otherwise be carrying, blended
     * toward the foot's dust tone by how far down the funnel it sits.
     *
     * <p>Weather2 tints each funnel particle once, at creation, with a random neutral grey between
     * 0.1 and 0.7, and that grey is what gives the column its mottling. {@code shade} reproduces it
     * so the tint keeps the mottle instead of flattening the funnel into one colour, and the dust
     * tone is modulated by the same shade for the same reason.
     */
    public static FunnelTint funnelColumnTint(ClientLevel level, StormObject storm, Vec3 footPos, float heightFraction, float formation, float shade) {
        float s = Mth.clamp(shade, 0.0F, 1.0F);
        float dust = funnelDustAmount(storm, heightFraction, formation);
        if (dust <= 0.001F) {
            return new FunnelTint(s, s, s);
        }
        // The foot tone needs the soil and biome under the funnel, which is far too costly to look up
        // per particle, and it cannot change within a tick - so it is resolved once per storm per tick.
        long gameTime = level.getGameTime();
        if (funnelTintCacheTone == null || funnelTintCacheTick != gameTime || funnelTintCacheStorm != storm.ID) {
            funnelTintCacheTick = gameTime;
            funnelTintCacheStorm = storm.ID;
            funnelTintCacheTone = funnelFootTone(level, storm, footPos);
        }
        FunnelTint footTone = funnelTintCacheTone;
        float mottle = (0.80F + 0.20F * s) * FUNNEL_FOOT_TONE_SCALE;
        // Soil in the air is a dirty grey with some brown in it. At full strength a funnel's foot turns bright rust.
        float soilGrey = (footTone.red() + footTone.green() + footTone.blue()) / 3.0F;
        return new FunnelTint(
                Mth.lerp(dust, s, Mth.lerp(FUNNEL_FOOT_GREYING, footTone.red(), soilGrey) * mottle),
                Mth.lerp(dust, s, Mth.lerp(FUNNEL_FOOT_GREYING, footTone.green(), soilGrey) * mottle),
                Mth.lerp(dust, s, Mth.lerp(FUNNEL_FOOT_GREYING, footTone.blue(), soilGrey) * mottle)
        );
    }

    /**
     * What the soil's colour is multiplied by on the funnel's foot, before the sheet's own grey multiplies it again.
     */
    private static final float FUNNEL_FOOT_TONE_SCALE = 1.05F;
    /** How far up the configured height the funnel's dirt actually climbs, and how strong it is when it gets there. */
    private static final double FUNNEL_DIRT_HEIGHT_SHARE = 0.55D;
    private static final double FUNNEL_DIRT_TINT_SHARE = 0.60D;
    private static final float FUNNEL_FOOT_GREYING = 0.55F;
    /** Dust in the air is pale. A tint can only multiply the sheet's mid grey, so the tone has to start higher than the soil's. */
    private static final float DUST_LIFT = 1.85F;

    /** Per-particle colour of a funnel billboard. */
    public record FunnelTint(float red, float green, float blue) {
    }

    /**
     * Stands in for the random grey Weather2 assigns a funnel particle at creation (0.1 to 0.7).
     *
     * <p>Reading the particle's own colour back is not possible from a funnel renderer, and it does
     * not need to be: a value derived from object identity reproduces the same distribution, keeps
     * the funnel's mottling, and - the point of using identity rather than a fresh random - never
     * shimmers between frames, because an object's identity hash cannot change while it is alive.
     */
    public static float funnelParticleShade(Object particle) {
        int hash = System.identityHashCode(particle);
        hash ^= hash >>> 17;
        hash *= 0x9E3779B1;
        hash ^= hash >>> 13;
        // A narrower spread than Weather2's own 0.1 to 0.7: wide, the mottle was a salt-and-pepper speckle over the
        // whole funnel; narrow, it is a texture on a smooth tube, and the light below gives the tube its form.
        return 0.66F + ((hash >>> 8) & 0xFFFF) / 65535.0F * 0.24F;
    }

    /**
     * The light on one funnel particle, as a colour to multiply its tint by: the side of the funnel facing the sun pale
     * and a little warm, the side away from it blue-grey, and faint helical bands winding up it and turning with the
     * spin, so the rotation reads even in a still frame. Lit only by height and a random grey, a funnel had no form at
     * all and read as a flat grey cut-out.
     *
     * @param outwardX the particle's horizontal direction out from the funnel's axis
     * @param outwardZ likewise
     * @param heightFraction how far up the funnel, 0 at the foot
     * @param spinSign which way the funnel turns
     */
    public static float[] funnelLight(ClientLevel level, double outwardX, double outwardZ, float heightFraction, double spinSign) {
        double length = Math.sqrt(outwardX * outwardX + outwardZ * outwardZ);
        double nx = length > 1.0E-4D ? outwardX / length : 0.0D;
        double nz = length > 1.0E-4D ? outwardZ / length : 0.0D;
        float lit = SunShading.lit(level, nx, 0.15D, nz);
        double angle = Math.atan2(nz, nx);
        float band = 0.93F + 0.07F * (float) Math.sin(angle - heightFraction * 16.0D * spinSign + level.getGameTime() * 0.12D * spinSign);
        return SunShading.cloudColour(level, band, lit, 0.62F);
    }

    /** One-entry memo for {@link #funnelColumnTint}: the foot tone can only change once per tick. */
    private static long funnelTintCacheTick = Long.MIN_VALUE;
    private static long funnelTintCacheStorm = Long.MIN_VALUE;
    private static FunnelTint funnelTintCacheTone;

    private static DustTone fireDustTone(ClientLevel level) {
        if (level.random.nextFloat() < 0.16F) {
            return new DustTone(0.94F, 0.48F, 0.18F);
        }
        return new DustTone(0.26F, 0.23F, 0.21F);
    }

    private static DustTone waterDustTone() {
        return new DustTone(0.58F, 0.66F, 0.74F);
    }

    private static final class PendingThunder {
        private final double x;
        private final double y;
        private final double z;
        private final float volume;
        private final float pitch;
        private int delayTicks;

        private PendingThunder(double x, double y, double z, float volume, float pitch, int delayTicks) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.volume = volume;
            this.pitch = pitch;
            this.delayTicks = delayTicks;
        }
    }
}
