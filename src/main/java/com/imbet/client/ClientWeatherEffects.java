package com.imbet.client;

import com.imbet.Config;
import com.imbet.weather.AddonWeatherEvent;
import com.imbet.weather.BlockGrabSafety;
import com.imbet.weather.ClientAddonWeatherEventStore;
import com.imbet.weather.DynamicStormController;
import com.imbet.weather.DynamicStormState;
import com.imbet.weather.GenesisWhirls;
import com.imbet.weather.SubVortexType;
import com.imbet.weather.StormSubtype;
import com.imbet.weather.SupercellStructureService;
import com.imbet.weather.TornadoWindField;
import com.imbet.survey.ClientDamageSurveyStore;
import com.imbet.survey.DamageSurveyResult;
import extendedrenderer.particle.ParticleRegistry;
import extendedrenderer.particle.entity.EntityRotFX;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
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
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) {
            PENDING_THUNDER.clear();
            visualRain = 0.0F;
            visualThunder = 0.0F;
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
            return;
        }
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
            smoothSky(minecraft.level, targetRain, targetThunder);
            SatelliteTornadoFunnelRenderer.clear();
            return;
        }
        double distance = storm.pos.distanceTo(minecraft.player.position());
        float proximity = Mth.clamp((float) (1.0D - distance / 700.0D), 0.0F, 1.0F);
        if (storm.levelCurIntensityStage >= StormObject.STATE_HAIL || DynamicStormController.isDynamicVortex(storm)) {
            float tint = Config.SEVERE_SKY_TINT_STRENGTH.get().floatValue() * proximity;
            targetRain = tint;
            targetThunder = tint;
            spawnThickClouds(minecraft.level, minecraft.player.position(), proximity);
        }
        smoothSky(minecraft.level, targetRain, targetThunder);
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

    private static void spawnThickClouds(ClientLevel level, Vec3 playerPos, float proximity) {
        int count = Math.max(1, (int) (proximity * 6.0F));
        for (int i = 0; i < count; i++) {
            double x = playerPos.x + (level.random.nextDouble() - 0.5D) * 60.0D;
            double y = playerPos.y + 14.0D + level.random.nextDouble() * 14.0D;
            double z = playerPos.z + (level.random.nextDouble() - 0.5D) * 60.0D;
            Vec3 pos = new Vec3(x, y, z);
            Vec3 velocity = new Vec3(0.0D, -0.01D, 0.0D);
            if (!spawnWeather2Particle(level, pos, velocity, ParticleRegistry.cloud256, 18.0F + proximity * 18.0F, 0.36F, 0.36F, 0.42F, 0.34F, 90, 0.04F)) {
                level.addParticle(ParticleTypes.CLOUD, x, y, z, velocity.x, velocity.y, velocity.z);
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

    private static void spawnSupercellStructure(ClientLevel level, StormObject storm, DynamicStormState state) {
        SupercellStructureService.StructureSample structure = SupercellStructureService.sample(storm, state);
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

    private static void spawnAnvilAndOvershootingTop(ClientLevel level, SupercellStructureService.StructureSample structure, float strength) {
        int count = Mth.clamp(2 + (int) (strength * 5.0F), 2, 7);
        for (int i = 0; i < count; i++) {
            double along = (level.random.nextDouble() - 0.18D) * structure.radius() * 1.55D;
            double lateral = (level.random.nextDouble() - 0.5D) * structure.radius() * 1.18D;
            Vec3 base = structure.anvilCore().add(structure.forward().scale(along)).add(structure.right().scale(lateral));
            Vec3 pos = new Vec3(base.x, visualY(level, base, 96.0D + level.random.nextDouble() * 42.0D), base.z);
            Vec3 velocity = structure.forward().scale(0.018D + strength * 0.014D).add(0.0D, -0.002D, 0.0D);
            spawnStructureParticle(level, pos, velocity, ParticleRegistry.cloud256, 22.0F + level.random.nextFloat() * 22.0F, 0.72F, 0.72F, 0.78F, 0.24F, 150, 0.02F, ParticleTypes.CLOUD);
        }

        Vec3 top = structure.updraft();
        Vec3 topPos = new Vec3(top.x, visualY(level, top, 126.0D + level.random.nextDouble() * 28.0D), top.z);
        spawnStructureParticle(level, topPos, new Vec3(0.0D, 0.026D + strength * 0.014D, 0.0D), ParticleRegistry.cloud256, 18.0F + strength * 13.0F, 0.82F, 0.82F, 0.88F, 0.30F, 130, 0.01F, ParticleTypes.CLOUD);

        Vec3 back = structure.backShearedAnvil();
        Vec3 backPos = new Vec3(back.x, visualY(level, back, 92.0D + level.random.nextDouble() * 30.0D), back.z);
        spawnStructureParticle(level, backPos, structure.forward().scale(-0.012D), ParticleRegistry.cloud256, 18.0F + strength * 11.0F, 0.66F, 0.66F, 0.74F, 0.22F, 140, 0.02F, ParticleTypes.CLOUD);
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
        double loweredBaseOffset = Mth.clamp(structure.rainFreeBaseY() - state.wallCloudLoweringDepth(), 10.0D, structure.rainFreeBaseY());
        int count = Mth.clamp(3 + (int) (strength * 9.0F), 3, 12);
        for (int i = 0; i < count; i++) {
            double angle = level.random.nextDouble() * Math.PI * 2.0D;
            double distance = Math.sqrt(level.random.nextDouble()) * radius;
            Vec3 base = structure.wallCloudCenter().add(Math.cos(angle) * distance, 0.0D, Math.sin(angle) * distance);
            if (horizontalDistance(base, structure.clearSlotCenter()) < radius * 0.34D && level.random.nextFloat() < 0.55F) {
                continue;
            }
            double raggedDrop = state.wallCloudLoweringDepth() * (0.18D + level.random.nextDouble() * 0.28D);
            Vec3 pos = new Vec3(base.x, visualY(level, base, loweredBaseOffset - raggedDrop), base.z);
            Vec3 tangent = new Vec3(-Math.sin(angle) * rotationSign, 0.0D, Math.cos(angle) * rotationSign);
            Vec3 inflow = structure.mesocyclone().subtract(structure.inflowSource());
            inflow = inflow.horizontalDistanceSqr() < 1.0E-5D ? structure.forward().scale(-1.0D) : new Vec3(inflow.x, 0.0D, inflow.z).normalize();
            Vec3 velocity = tangent.scale(0.026D + state.wallCloudRotationStrength() * 0.070D)
                    .add(inflow.scale(0.010D + state.inflowStrength() * 0.030D))
                    .add(0.0D, 0.002D + strength * 0.008D, 0.0D);
            float puffSize = Mth.clamp((12.0F + strength * 18.0F) * particleSize, 0.9F, 40.0F);
            spawnStructureParticle(level, pos, velocity, ParticleRegistry.cloud256, puffSize, 0.24F, 0.25F, 0.30F, 0.56F, 96, 0.16F, ParticleTypes.CLOUD);
        }

        if (Config.SUPERCELL_STRUCTURE_WALL_CLOUD_ROTATION_PARTICLES.get()
                && level.random.nextFloat() < Config.SUPERCELL_STRUCTURE_WALL_CLOUD_SCUD_CHANCE.get().floatValue()) {
            int tags = Mth.clamp(1 + (int) (state.wallCloudRotationStrength() * 6.0F), 1, 7);
            for (int i = 0; i < tags; i++) {
                double angle = level.random.nextDouble() * Math.PI * 2.0D;
                double distance = radius * (0.74D + level.random.nextDouble() * 0.34D);
                Vec3 base = structure.wallCloudCenter().add(Math.cos(angle) * distance, 0.0D, Math.sin(angle) * distance);
                Vec3 pos = new Vec3(base.x, visualY(level, base, loweredBaseOffset - level.random.nextDouble() * Math.max(4.0D, state.wallCloudLoweringDepth() * 0.55D)), base.z);
                Vec3 velocity = new Vec3(-Math.sin(angle) * rotationSign, 0.018D + strength * 0.018D, Math.cos(angle) * rotationSign)
                        .scale(0.052D + state.wallCloudRotationStrength() * 0.105D);
                float tagSize = Mth.clamp((5.0F + strength * 7.0F) * particleSize, 0.6F, 18.0F);
                spawnStructureParticle(level, pos, velocity, ParticleRegistry.cloud, tagSize, 0.30F, 0.31F, 0.36F, 0.58F, 66, 0.18F, ParticleTypes.CLOUD);
            }
        }

    }

    private static void spawnRainFreeBaseAndInflowTail(ClientLevel level, SupercellStructureService.StructureSample structure, DynamicStormState state) {
        float strength = state.supercellStructureStrength();
        if (strength <= 0.08F) {
            return;
        }

        int baseCount = Mth.clamp(1 + (int) (strength * 3.0F), 1, 4);
        double baseRadius = Math.max(18.0D, structure.wallCloudDiameter() * 0.44D);
        for (int i = 0; i < baseCount; i++) {
            double along = (level.random.nextDouble() - 0.55D) * baseRadius;
            double lateral = (level.random.nextDouble() - 0.50D) * baseRadius * 0.72D;
            Vec3 base = structure.rainFreeBase()
                    .add(structure.forward().scale(along))
                    .add(structure.right().scale(lateral));
            Vec3 pos = new Vec3(base.x, visualY(level, base, structure.rainFreeBaseY() + level.random.nextDouble() * 8.0D), base.z);
            Vec3 velocity = structure.forward().scale(0.006D + strength * 0.012D).add(structure.right().scale(-0.004D)).add(0.0D, 0.002D, 0.0D);
            spawnStructureParticle(level, pos, velocity, ParticleRegistry.cloud256, 13.0F + strength * 15.0F, 0.62F, 0.64F, 0.70F, 0.16F, 110, 0.02F, ParticleTypes.CLOUD);
        }

    }

    private static void spawnRotatingUpdraftTower(ClientLevel level, StormObject storm, SupercellStructureService.StructureSample structure, DynamicStormState state) {
        float meso = state.mesocycloneStrength();
        if (meso <= 0.08F) {
            return;
        }
        double rotationSign = DynamicStormController.visualRotationSign(storm);
        int count = Mth.clamp(2 + (int) (meso * 7.0F), 2, 9);
        double towerRadius = Math.max(18.0D, structure.wallCloudDiameter() * (structure.mode() == SupercellStructureService.SupercellMode.LP ? 0.38D : 0.48D));
        double top = Mth.clamp(112.0D + structure.radius() * 0.24D, 108.0D, 176.0D);
        for (int i = 0; i < count; i++) {
            double layer = level.random.nextDouble();
            double angle = level.getGameTime() * 0.018D * rotationSign + layer * Math.PI * 5.8D + level.random.nextDouble() * 0.90D;
            double radius = towerRadius * (0.22D + layer * 0.72D);
            Vec3 base = structure.updraft()
                    .add(Math.cos(angle) * radius, 0.0D, Math.sin(angle) * radius)
                    .add(structure.forward().scale((level.random.nextDouble() - 0.55D) * structure.radius() * 0.08D));
            double height = Mth.lerp(layer, structure.rainFreeBaseY() + 12.0D, top);
            Vec3 tangent = new Vec3(-Math.sin(angle) * rotationSign, 0.0D, Math.cos(angle) * rotationSign);
            Vec3 velocity = tangent.scale(0.026D + meso * 0.060D)
                    .add(structure.forward().scale(0.006D))
                    .add(0.0D, 0.018D + meso * 0.026D, 0.0D);
            float shade = (float) Mth.lerp(layer, 0.44D, 0.76D);
            Vec3 pos = new Vec3(base.x, visualY(level, base, height), base.z);
            spawnStructureParticle(level, pos, velocity, ParticleRegistry.cloud256,
                    16.0F + meso * 17.0F + level.random.nextFloat() * 12.0F,
                    shade * 0.58F, shade * 0.60F, shade * 0.68F, 0.34F + meso * 0.22F, 136, 0.08F, ParticleTypes.CLOUD);
        }
    }

    private static void spawnRearFlankClearSlot(ClientLevel level, SupercellStructureService.StructureSample structure, DynamicStormState state) {
        float rfd = state.rfdStrength();
        if (rfd <= 0.10F) {
            return;
        }
        int count = Mth.clamp(1 + (int) (rfd * 4.0F), 1, 5);
        for (int i = 0; i < count; i++) {
            Vec3 base = structure.clearSlotCenter()
                    .add(structure.forward().scale((level.random.nextDouble() - 0.55D) * structure.radius() * 0.22D))
                    .add(structure.right().scale((level.random.nextDouble() - 0.45D) * structure.radius() * 0.18D));
            Vec3 outward = base.subtract(structure.mesocyclone());
            outward = outward.horizontalDistanceSqr() < 1.0E-5D ? structure.forward().add(structure.right()).normalize() : new Vec3(outward.x, 0.0D, outward.z).normalize();
            Vec3 pos = new Vec3(base.x, visualY(level, base, 14.0D + level.random.nextDouble() * 34.0D), base.z);
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

        int hookCount = Mth.clamp(1 + (int) (meso * 5.0F), 1, 6);
        for (int i = 0; i < hookCount; i++) {
            double angle = level.random.nextDouble() * Math.PI * 2.0D;
            double distance = (0.25D + level.random.nextDouble() * 0.75D) * Math.max(12.0D, structure.wallCloudDiameter() * 0.30D);
            Vec3 base = structure.hookEchoNotch().add(Math.cos(angle) * distance, 0.0D, Math.sin(angle) * distance);
            Vec3 towardMeso = structure.mesocyclone().subtract(base);
            towardMeso = towardMeso.horizontalDistanceSqr() < 1.0E-5D ? structure.forward().scale(-1.0D) : new Vec3(towardMeso.x, 0.0D, towardMeso.z).normalize();
            Vec3 hookCurl = structure.right().scale(-0.040D - meso * 0.060D).add(towardMeso.scale(0.035D + meso * 0.050D));
            Vec3 pos = new Vec3(base.x, visualY(level, base, 12.0D + level.random.nextDouble() * Math.max(24.0D, structure.rainFreeBaseY())), base.z);
            spawnStructureParticle(level, pos, hookCurl.add(0.0D, 0.010D + meso * 0.014D, 0.0D), ParticleRegistry.cloud, 5.6F + meso * 7.8F, 0.30F, 0.32F, 0.37F, 0.50F, 64, 0.20F, ParticleTypes.CLOUD);
        }

        if (structure.mode() == SupercellStructureService.SupercellMode.HP) {
            int wrapCount = Mth.clamp(2 + (int) ((state.ffdStrength() + state.rfdStrength()) * 5.0F), 2, 9);
            for (int i = 0; i < wrapCount; i++) {
                Vec3 base = structure.hookEchoNotch().lerp(structure.rainWrapCurtain(), level.random.nextDouble())
                        .add(structure.forward().scale((level.random.nextDouble() - 0.45D) * structure.radius() * 0.24D))
                        .add(structure.right().scale((level.random.nextDouble() - 0.50D) * structure.radius() * 0.18D));
                Vec3 pos = new Vec3(base.x, visualY(level, base, 8.0D + level.random.nextDouble() * 52.0D), base.z);
                Vec3 velocity = structure.forward().scale(0.038D + state.ffdStrength() * 0.050D)
                        .add(structure.right().scale(-0.024D - state.rfdStrength() * 0.040D))
                        .add(0.0D, -0.042D - state.ffdStrength() * 0.050D, 0.0D);
                spawnStructureParticle(level, pos, velocity, ParticleRegistry.cloud256, 10.0F + state.ffdStrength() * 13.0F, 0.24F, 0.27F, 0.34F, 0.56F, 72, 0.16F, ParticleTypes.RAIN);
            }
        } else if (structure.mode() == SupercellStructureService.SupercellMode.LP && state.hailCoreStrength() > 0.24F) {
            int hailCount = Mth.clamp(1 + (int) (state.hailCoreStrength() * 5.0F), 1, 6);
            for (int i = 0; i < hailCount; i++) {
                Vec3 base = structure.hailShaft()
                        .add(structure.forward().scale((level.random.nextDouble() - 0.35D) * structure.radius() * 0.36D))
                        .add(structure.right().scale((level.random.nextDouble() - 0.50D) * structure.radius() * 0.28D));
                Vec3 pos = new Vec3(base.x, visualY(level, base, 32.0D + level.random.nextDouble() * 62.0D), base.z);
                Vec3 velocity = structure.forward().scale(0.018D).add(structure.right().scale(-0.010D)).add(0.0D, -0.24D - state.hailCoreStrength() * 0.18D, 0.0D);
                spawnStructureParticle(level, pos, velocity, ParticleRegistry.groundSplash, 0.80F + state.hailCoreStrength() * 0.95F, 0.86F, 0.92F, 1.00F, 0.62F, 30, 0.02F, ParticleTypes.RAIN);
            }
        }
    }

    private static void spawnFlankingLine(ClientLevel level, SupercellStructureService.StructureSample structure, float strength) {
        int count = Mth.clamp(1 + (int) (strength * 4.0F), 1, 4);
        for (int i = 0; i < count; i++) {
            double t = level.random.nextDouble();
            Vec3 base = structure.flankingLineStart().lerp(structure.flankingLineEnd(), t)
                    .add(structure.right().scale((level.random.nextDouble() - 0.5D) * (14.0D + t * 18.0D)));
            double towerHeight = 22.0D + t * 58.0D + level.random.nextDouble() * 26.0D;
            Vec3 pos = new Vec3(base.x, visualY(level, base, towerHeight), base.z);
            Vec3 velocity = structure.forward().scale(0.010D).add(structure.right().scale(-0.006D)).add(0.0D, 0.010D + t * 0.012D, 0.0D);
            float scale = (float) (6.0D + t * 12.0D + level.random.nextDouble() * 7.0D);
            spawnStructureParticle(level, pos, velocity, ParticleRegistry.cloud256, scale, 0.70F, 0.70F, 0.76F, 0.30F + (float) t * 0.18F, 96, 0.03F, ParticleTypes.CLOUD);
        }
    }

    /**
     * Forward-flank precipitation. The storm's precipitation character decides how much of it there
     * is: a dry LP supercell is mostly hail and virga with hardly any rain reaching the ground, while
     * an HP supercell buries the forward flank in rain.
     */
    private static void spawnForwardFlankPrecip(ClientLevel level, SupercellStructureService.StructureSample structure, float ffdStrength, float hailCoreStrength, float character) {
        float density = Mth.lerp(0.30F, 1.40F, Mth.clamp(character, 0.0F, 1.0F));
        int count = Mth.clamp((int) Math.round((1 + (int) (ffdStrength * 5.0F)) * density), character < 0.30F ? 0 : 1, 7);
        if (count <= 0) {
            return;
        }
        // Dry air aloft means the hail shaft survives to the ground more often in an LP storm.
        float hailChance = Mth.clamp(hailCoreStrength * Mth.lerp(0.65F, 0.30F, Mth.clamp(character, 0.0F, 1.0F)), 0.0F, 0.85F);
        for (int i = 0; i < count; i++) {
            Vec3 base = structure.forwardFlank()
                    .add(structure.forward().scale((level.random.nextDouble() - 0.3D) * structure.radius() * 0.62D))
                    .add(structure.right().scale((level.random.nextDouble() - 0.55D) * structure.radius() * 0.48D));
            Vec3 pos = new Vec3(base.x, visualY(level, base, 30.0D + level.random.nextDouble() * 46.0D), base.z);
            Vec3 velocity = structure.forward().scale(0.035D + ffdStrength * 0.045D).add(0.0D, -0.18D - ffdStrength * 0.07D, 0.0D);
            if (hailCoreStrength > 0.30F && level.random.nextFloat() < hailChance * 0.45F) {
                spawnStructureParticle(level, pos, velocity, ParticleRegistry.groundSplash, 0.65F + hailCoreStrength * 0.75F, 0.86F, 0.90F, 0.96F, 0.55F, 26, 0.02F, ParticleTypes.RAIN);
            } else {
                spawnStructureParticle(level, pos, velocity, ParticleRegistry.cloud, 3.0F + ffdStrength * 4.0F, 0.46F, 0.50F, 0.58F, 0.46F, 46, 0.12F, ParticleTypes.RAIN);
            }
        }
    }

    private static void spawnRearFlankGustFront(ClientLevel level, SupercellStructureService.StructureSample structure, float rfdStrength) {
        int count = Mth.clamp(1 + (int) (rfdStrength * 4.0F), 1, 5);
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
            spawnStructureParticle(level, pos, velocity, ParticleRegistry.cloud, 5.0F + rfdStrength * 6.0F, 0.50F, 0.48F, 0.44F, 0.44F, 54, 0.20F, ParticleTypes.CLOUD);
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
        Vec3 pos = new Vec3(base.x, visualY(level, base, 74.0D + level.random.nextDouble() * 26.0D), base.z);
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

    private static void smoothSky(ClientLevel level, float targetRain, float targetThunder) {
        long gameTime = level.getGameTime();
        if (gameTime - lastSkyUpdateTick < Config.MIN_SKY_UPDATE_TICKS.get()) {
            return;
        }
        lastSkyUpdateTick = gameTime;
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
        if (VolumetricPrecipitationRenderer.shaderCompatibilityActive()) {
            level.setRainLevel(Math.max(level.getRainLevel(1.0F), visualRain));
            level.setThunderLevel(Math.max(level.getThunderLevel(1.0F), visualThunder));
        }
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
                double distance = 1.5D + level.random.nextDouble() * radius;
                int groundY = vortexGroundY(level, center);
                Vec3 pos = new Vec3(center.x + Math.cos(angle) * distance, groundY + 0.6D + level.random.nextDouble() * 5.0D, center.z + Math.sin(angle) * distance);
                Vec3 velocity = new Vec3(-Math.sin(angle) * rotationSign, 0.02D, Math.cos(angle) * rotationSign).scale(0.12D);
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
        double visibleRadius = Math.max(6.0D, state.visibleFunnelWidth() * 0.5D);
        double radiusOfMaxWind = Math.max(4.0D, DynamicStormController.radiusOfMaxWind(storm));
        double clearRadius = Mth.clamp(Math.max(visibleRadius + 10.0D, radiusOfMaxWind * 0.28D), 9.0D, 512.0D);
        double cloudRadius = clearRadius + Mth.clamp(Math.max(22.0D, visibleRadius * 0.64D + maxWind / 14.0D), 22.0D, 145.0D) * sizeFactor;
        // A forming tornado's whirl is thin and faint; it only fills out into the mature debris cloud as
        // the condensation comes down. At full reach this is 1.0, so a formed tornado's dust cloud is
        // exactly what it was before the genesis timeline existed.
        float genesisDimming = 0.30F + 0.70F * condensationReach;
        float density = Config.TORNADO_DEBRIS_CLOUD_DENSITY.get().floatValue() * context.gust() * densityFactor;
        int attempts = Mth.clamp((int) Math.round((12.0D + maxWind / 17.0D + (cloudRadius - clearRadius) / 3.6D) * density * genesisDimming), 6, 74);
        if (!DynamicStormController.suppressMultivortexParentDust(storm)) {
            double coreRadius = Mth.clamp(Math.max(visibleRadius * 0.76D, radiusOfMaxWind * 0.42D), 4.0D, 96.0D);
            int coreAttempts = Mth.clamp((int) Math.round((8.0D + maxWind / 20.0D + coreRadius / 6.0D) * density * genesisDimming), 4, 44);
            spawnDebrisCloudCore(level, storm, storm.pos, coreRadius, maxWind, coreAttempts, (float) formation, heightFactor, genesisDimming, context);
            spawnDebrisCloudAround(level, storm, storm.pos, clearRadius, cloudRadius, maxWind, attempts, (float) formation, heightFactor, genesisDimming, context);
            if (!water) {
                spawnSurfaceDirtKickup(level, storm, storm.pos, coreRadius, clearRadius, maxWind, formation, dryness);
            }
        }

        for (DynamicStormState.TornadoCirculationState satellite : state.satelliteTornadoes()) {
            Vec3 center = satelliteVisualCenter(level, storm, satellite);
            if (center.distanceTo(playerPos) > LodViewDistance.extendSpawnRange(Config.TORNADO_DEBRIS_CLOUD_RENDER_RANGE_BLOCKS.get())) {
                continue;
            }
            spawnSatelliteDebrisCloud(level, storm, satellite, center, context);
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
        boolean water = context.water();
        float density = Config.TORNADO_DEBRIS_CLOUD_DENSITY.get().floatValue() * context.gust()
                * (0.55F + 0.45F * (float) formation) * Mth.lerp(0.70F, 1.08F, dryness);
        int attempts = Mth.clamp((int) Math.round((2.5D + satelliteWind / 70.0D + (cloudRadius - clearRadius) / 9.0D) * density * lifecycle), 1, 9);
        for (int i = 0; i < attempts; i++) {
            if (debrisCloudSpawnedThisTick >= Config.TORNADO_DEBRIS_CLOUD_MAX_PARTICLES_PER_TICK.get()) {
                return;
            }
            boolean loftedSwirl = level.random.nextFloat() < 0.42F;
            double angle = level.random.nextDouble() * Math.PI * 2.0D;
            double distance = loftedSwirl
                    ? clearRadius * (0.75D + level.random.nextDouble() * 0.45D)
                    : Math.pow(level.random.nextDouble(), 0.80D) * clearRadius;
            Vec3 groundSample = center.add(Math.cos(angle) * distance, 0.0D, Math.sin(angle) * distance);
            int groundY = vortexGroundY(level, groundSample);
            double nearCore = Mth.clamp(1.0D - distance / Math.max(1.0D, clearRadius), 0.0D, 1.0D);
            boolean raining = level.isRainingAt(BlockPos.containing(groundSample.x, groundY + 1, groundSample.z));
            float rainFactor = raining ? 0.50F : 1.0F;
            double height = loftedSwirl
                    ? 1.0D + Math.pow(level.random.nextDouble(), 1.15D) * Mth.clamp(2.0D + satelliteWind / 34.0D, 2.5D, 12.0D)
                    : Math.pow(level.random.nextDouble(), 1.95D) * Mth.clamp(2.2D + satelliteWind / 30.0D + nearCore * 3.0D, 2.5D, 9.0D);
            Vec3 pos = new Vec3(groundSample.x, groundY + 0.25D + height, groundSample.z);
            Vec3 radial = new Vec3(Math.cos(angle), 0.0D, Math.sin(angle));
            Vec3 tangent = new Vec3(-Math.sin(angle) * rotationSign, 0.0D, Math.cos(angle) * rotationSign);
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
                double angle = level.random.nextDouble() * Math.PI * 2.0D;
                double distance = Mth.lerp(level.random.nextDouble(), clearRadius * 0.60D, Math.max(clearRadius + 1.0D, clearRadius * 1.25D));
                Vec3 groundSample = center.add(Math.cos(angle) * distance, 0.0D, Math.sin(angle) * distance);
                int groundY = vortexGroundY(level, groundSample);
                if (level.isRainingAt(BlockPos.containing(groundSample.x, groundY + 1, groundSample.z))) {
                    continue;
                }
                Vec3 pos = new Vec3(groundSample.x, groundY + 0.30D + level.random.nextDouble() * 0.7D, groundSample.z);
                Vec3 radial = new Vec3(Math.cos(angle), 0.0D, Math.sin(angle));
                Vec3 tangent = new Vec3(-Math.sin(angle) * rotationSign, 0.0D, Math.cos(angle) * rotationSign);
                Vec3 velocity = tangent.scale(0.04D + level.random.nextDouble() * 0.08D)
                        .add(radial.scale(0.02D + level.random.nextDouble() * 0.04D))
                        .add(0.0D, 0.08D + level.random.nextDouble() * 0.12D, 0.0D);
                spawnKickedDirt(level, pos, velocity, surfaceDustTone(level, groundSample, 0.0F));
            }
        }
    }

    private static void spawnDebrisCloudCore(ClientLevel level, StormObject storm, Vec3 center, double coreRadius, double maxWind, int attempts, float intensityScale, float heightFactor, float genesisDimming, DustCloudContext context) {
        double rotationSign = DynamicStormController.visualRotationSign(storm);
        boolean water = context.water();
        for (int i = 0; i < attempts; i++) {
            if (debrisCloudSpawnedThisTick >= Config.TORNADO_DEBRIS_CLOUD_MAX_PARTICLES_PER_TICK.get()) {
                return;
            }
            double angle = level.random.nextDouble() * Math.PI * 2.0D;
            // Dust hugs the funnel wall as a thin veil; the innermost core stays clear enough to see the condensation funnel.
            double wallBias = level.random.nextFloat() < 0.72F ? 0.60D + 0.40D * level.random.nextDouble() : 0.22D + 0.38D * level.random.nextDouble();
            double distance = wallBias * Math.max(1.0D, coreRadius);
            Vec3 groundSample = center.add(Math.cos(angle) * distance, 0.0D, Math.sin(angle) * distance);
            int groundY = vortexGroundY(level, groundSample);
            boolean raining = level.isRainingAt(BlockPos.containing(groundSample.x, groundY + 1, groundSample.z));
            float rainFactor = raining ? 0.50F : 1.0F;
            double wall = Mth.clamp(distance / Math.max(1.0D, coreRadius), 0.0D, 1.0D);
            double heightLimit = Mth.clamp(3.0D + maxWind / 34.0D + wall * 3.6D, 3.6D, 13.5D) * heightFactor;
            double height = Math.pow(level.random.nextDouble(), 1.9D) * heightLimit;
            Vec3 pos = new Vec3(groundSample.x, groundY + 0.28D + height, groundSample.z);
            boolean dustyFoot = height < 2.6D || wall > 0.85D;
            Vec3 radial = new Vec3(Math.cos(angle), 0.0D, Math.sin(angle));
            Vec3 tangent = new Vec3(-Math.sin(angle) * rotationSign, 0.0D, Math.cos(angle) * rotationSign);
            Vec3 velocity = tangent.scale(0.055D + maxWind * 0.00070D + wall * 0.045D)
                    .add(radial.scale(-0.012D - wall * 0.016D))
                    .add(0.0D, dustyFoot ? 0.008D + wall * 0.020D : 0.014D + wall * 0.030D, 0.0D);
            float scale = (float) Mth.clamp(8.0D + coreRadius * 0.20D + wall * 12.0D + level.random.nextDouble() * 9.0D, 7.0D, 30.0D) * heightFactor;
            float alpha = (float) Mth.clamp((0.24D + wall * 0.14D + maxWind / 1600.0D + (dustyFoot ? 0.10D : 0.0D)) * intensityScale * context.gust() * rainFactor, 0.16D, 0.48D);
            if (water) {
                scale *= 0.72F;
                alpha *= 0.86F;
            }
            alpha *= debrisCloudOpacity() * genesisDimming;
            DustTone tone = toneFor(level, groundSample, dustyFoot, false, context);
            spawnDebrisCloudParticle(level, pos, velocity, scale, alpha, tone, 36 + level.random.nextInt(48));
        }
    }

    private static void spawnDebrisCloudAround(ClientLevel level, StormObject storm, Vec3 center, double clearRadius, double cloudRadius, double maxWind, int attempts, float intensityScale, float heightFactor, float genesisDimming, DustCloudContext context) {
        double rotationSign = DynamicStormController.visualRotationSign(storm);
        double ringWidth = Math.max(2.0D, cloudRadius - clearRadius);
        boolean water = context.water();
        for (int i = 0; i < attempts; i++) {
            if (debrisCloudSpawnedThisTick >= Config.TORNADO_DEBRIS_CLOUD_MAX_PARTICLES_PER_TICK.get()) {
                return;
            }
            double angle = level.random.nextDouble() * Math.PI * 2.0D;
            boolean lowGroundBowl = level.random.nextFloat() < 0.58F;
            boolean loftedCurtain = !lowGroundBowl && level.random.nextFloat() < 0.55F;
            // Mass the skirt near the funnel wall so the collar visibly rolls outward instead of floating evenly.
            double distance = level.random.nextFloat() < 0.62F
                    ? clearRadius + Math.pow(level.random.nextDouble(), 1.75D) * ringWidth * 0.52D
                    : clearRadius + Math.sqrt(level.random.nextDouble()) * ringWidth;
            Vec3 groundSample = center.add(Math.cos(angle) * distance, 0.0D, Math.sin(angle) * distance);
            int groundY = vortexGroundY(level, groundSample);
            boolean raining = level.isRainingAt(BlockPos.containing(groundSample.x, groundY + 1, groundSample.z));
            float rainFactor = raining ? 0.50F : 1.0F;
            double heightLimit = (lowGroundBowl ? Mth.clamp(maxWind / 42.0D, 1.6D, 6.0D) : loftedCurtain ? Mth.clamp(maxWind / 9.5D, 10.0D, 34.0D) : Mth.clamp(maxWind / 18.0D, 4.0D, 18.0D)) * heightFactor;
            if (water) {
                heightLimit = Math.max(heightLimit, 6.0D);
            }
            double height = Math.pow(level.random.nextDouble(), lowGroundBowl ? 2.3D : loftedCurtain ? 1.08D : 1.35D) * heightLimit;
            Vec3 pos = new Vec3(groundSample.x, groundY + 0.35D + height, groundSample.z);
            double nearWall = Mth.clamp(1.0D - (distance - clearRadius) / ringWidth, 0.0D, 1.0D);
            double outerShelf = Mth.clamp((distance - clearRadius) / ringWidth, 0.0D, 1.0D);
            Vec3 radialOut = new Vec3(Math.cos(angle), 0.0D, Math.sin(angle));
            Vec3 tangent = new Vec3(-Math.sin(angle) * rotationSign, 0.0D, Math.cos(angle) * rotationSign);
            Vec3 velocity = tangent.scale(0.040D + maxWind * 0.00052D + nearWall * 0.032D)
                    .add(radialOut.scale(0.010D + outerShelf * 0.020D))
                    .add(0.0D, lowGroundBowl ? 0.006D + nearWall * 0.014D : loftedCurtain ? 0.024D + nearWall * 0.032D : 0.016D + nearWall * 0.030D, 0.0D);
            float scale = (float) Mth.clamp((lowGroundBowl ? 13.0D : loftedCurtain ? 10.0D : 7.0D) + ringWidth * 0.22D + outerShelf * 8.0D + level.random.nextDouble() * 10.0D, 7.0D, 40.0D) * heightFactor;
            float alpha = (float) Mth.clamp(((lowGroundBowl ? 0.34D : loftedCurtain ? 0.22D : 0.20D) + nearWall * 0.13D - outerShelf * 0.10D + maxWind / 1500.0D) * intensityScale * context.gust() * rainFactor, 0.12D, 0.52D);
            if (lowGroundBowl) {
                scale = Mth.clamp(scale * 1.15F, 9.0F, 46.0F);
                alpha = Mth.clamp(alpha + 0.07F, 0.16F, 0.60F);
            }
            if (water) {
                scale *= 0.80F;
            }
            alpha *= debrisCloudOpacity() * genesisDimming;
            DustTone tone = toneFor(level, groundSample, lowGroundBowl, loftedCurtain, context);
            spawnDebrisCloudParticle(level, pos, velocity, scale, alpha, tone, 30 + level.random.nextInt(52));
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
        int attempts = Mth.clamp((int) Math.round((2.5D + maxWind / 26.0D) * kickupFactor), 1, 14);
        double rotationSign = DynamicStormController.visualRotationSign(storm);
        for (int i = 0; i < attempts; i++) {
            if (debrisCloudSpawnedThisTick >= Config.TORNADO_DEBRIS_CLOUD_MAX_PARTICLES_PER_TICK.get()) {
                return;
            }
            double angle = level.random.nextDouble() * Math.PI * 2.0D;
            // Dirt pops off the ground between the funnel wall and the dust skirt.
            double distance = Mth.lerp(level.random.nextDouble(), coreRadius * 0.55D, Math.max(coreRadius + 2.0D, clearRadius * 1.15D));
            Vec3 groundSample = center.add(Math.cos(angle) * distance, 0.0D, Math.sin(angle) * distance);
            int groundY = vortexGroundY(level, groundSample);
            if (level.isRainingAt(BlockPos.containing(groundSample.x, groundY + 1, groundSample.z))) {
                continue; // rain-soaked ground doesn't kick up dirt
            }
            Vec3 pos = new Vec3(groundSample.x, groundY + 0.30D + level.random.nextDouble() * 0.8D, groundSample.z);
            Vec3 radial = new Vec3(Math.cos(angle), 0.0D, Math.sin(angle));
            Vec3 tangent = new Vec3(-Math.sin(angle) * rotationSign, 0.0D, Math.cos(angle) * rotationSign);
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
        if (ParticleRegistry.cloud256 == null) {
            level.addParticle(ParticleTypes.POOF, pos.x, pos.y, pos.z, velocity.x, velocity.y, velocity.z);
            return;
        }
        int maxAge = 16 + level.random.nextInt(14);
        EntityRotFX particle = new EntityRotFX(level, pos.x, pos.y, pos.z, velocity.x, velocity.y, velocity.z);
        particle.setSprite(ParticleRegistry.cloud256);
        particle.setScale(Mth.clamp(0.7F + level.random.nextFloat() * 1.5F, 0.05F, 8.0F));
        particle.setColor(tone.red(), tone.green(), tone.blue());
        float clampedAlpha = Mth.clamp(0.42F + level.random.nextFloat() * 0.24F, 0.20F, 0.78F) * debrisCloudOpacity();
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

    private static void spawnDustCloudParticle(ClientLevel level, Vec3 pos, Vec3 velocity, float scale, float alpha, DustTone tone, int maxAge, ParticleOptions fallback) {
        if (debrisCloudSpawnedThisTick >= Config.TORNADO_DEBRIS_CLOUD_MAX_PARTICLES_PER_TICK.get()) {
            return;
        }
        debrisCloudSpawnedThisTick++;
        if (ParticleRegistry.cloud256 == null) {
            level.addParticle(fallback, pos.x, pos.y, pos.z, velocity.x, velocity.y, velocity.z);
            return;
        }
        EntityRotFX particle = new EntityRotFX(level, pos.x, pos.y, pos.z, velocity.x, velocity.y, velocity.z);
        particle.setSprite(ParticleRegistry.cloud256);
        particle.setScale(Mth.clamp(scale, 0.05F, 180.0F));
        particle.setColor(tone.red(), tone.green(), tone.blue());
        float clampedAlpha = Mth.clamp(alpha * 1.32F + 0.12F, 0.24F, 0.96F);
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
                double distance = Math.sqrt(level.random.nextDouble()) * whirl.radius() * 1.15D;
                double lift = Math.pow(level.random.nextDouble(), 1.6D) * whirl.height() * 0.42D;
                Vec3 pos = new Vec3(x + Math.cos(angle) * distance, groundY + 0.30D + lift, z + Math.sin(angle) * distance);
                Vec3 radial = new Vec3(Math.cos(angle), 0.0D, Math.sin(angle));
                Vec3 tangent = new Vec3(-Math.sin(angle) * rotationSign, 0.0D, Math.cos(angle) * rotationSign);
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
                double distance = Math.sqrt(level.random.nextDouble()) * whirl.radius();
                Vec3 pos = new Vec3(x + Math.cos(angle) * distance, groundY + 0.25D + level.random.nextDouble() * 0.6D, z + Math.sin(angle) * distance);
                Vec3 tangent = new Vec3(-Math.sin(angle) * rotationSign, 0.0D, Math.cos(angle) * rotationSign);
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
                    return;
                }
                double angle = Math.toRadians(vortex.orbitDegrees()) + level.random.nextDouble() * Math.PI * 2.0D;
                double distance = 0.8D + Math.sqrt(level.random.nextDouble()) * subvortexRadius;
                double helixTurn = angle + level.random.nextDouble() * 1.4D;
                double height = Math.pow(level.random.nextDouble(), 1.45D) * Mth.clamp(4.0D + subVortexWind / 18.0D, 5.0D, 24.0D);
                Vec3 pos = center.add(Math.cos(helixTurn) * distance, 0.45D + height, Math.sin(helixTurn) * distance);
                Vec3 radial = new Vec3(Math.cos(helixTurn), 0.0D, Math.sin(helixTurn));
                Vec3 tangent = new Vec3(-Math.sin(helixTurn) * rotationSign, 0.0D, Math.cos(helixTurn) * rotationSign);
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
        double maxRadius = Math.max(radius + 2.0D, DynamicStormController.baseRadiusForWind(storm) * 0.72D);
        int attempts = Mth.clamp((int) (TornadoWindField.maxWindMph(storm) / 85.0D), 1, 4);
        float density = Config.SUB_VORTEX_STICK_DENSITY.get().floatValue() * 0.55F;
        double rotationSign = DynamicStormController.rotationSign(storm);
        for (int i = 0; i < attempts; i++) {
            double angle = level.random.nextDouble() * Math.PI * 2.0D;
            double distance = radius + level.random.nextDouble() * Math.max(1.0D, maxRadius - radius);
            int groundY = vortexGroundY(level, storm.pos);
            Vec3 pos = new Vec3(storm.pos.x + Math.cos(angle) * distance, groundY + 1.0D + level.random.nextDouble() * 18.0D, storm.pos.z + Math.sin(angle) * distance);
            Vec3 velocity = new Vec3(-Math.sin(angle) * rotationSign, 0.12D + level.random.nextDouble() * 0.18D, Math.cos(angle) * rotationSign).scale(0.16D + TornadoWindField.maxWindMph(storm) * 0.0011D);
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
                double distance = 1.0D + level.random.nextDouble() * Math.max(2.0D, vortex.orbitRadius() * 0.22D);
                Vec3 pos = new Vec3(center.x + Math.cos(angle) * distance, groundY + 0.45D + level.random.nextDouble() * 7.0D, center.z + Math.sin(angle) * distance);
                Vec3 velocity = new Vec3(-Math.sin(angle) * rotationSign, 0.15D + level.random.nextDouble() * 0.20D, Math.cos(angle) * rotationSign).scale(0.24D + vortex.intensityFraction() * 0.18D);
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
            if (event.type() == StormSubtype.FLAMMAGENITUS) {
                spawnFlammagenitusVisual(level, event);
                continue;
            }
            int count = particleCountFor(event);
            for (int i = 0; i < count; i++) {
                Vec3 pos = event.isLine() ? randomLineParticle(level, event) : randomVortexParticle(level, event);
                Vec3 velocity = event.isLine() ? lineVelocity(level, event) : swirlVelocity(event, pos);
                if (event.type() == StormSubtype.WATERSPOUT) {
                    Vec3 sprayVelocity = new Vec3(velocity.x * 0.45D, 0.035D + level.random.nextDouble() * 0.035D, velocity.z * 0.45D);
                    spawnCloud256Particle(level, pos, sprayVelocity, 1.35F + level.random.nextFloat() * 0.85F, 0.62F, 0.70F, 0.80F, 0.38F, 22, 0.02F);
                    Vec3 mistPos = pos.add(0.0D, 0.18D + level.random.nextDouble() * 0.65D, 0.0D);
                    Vec3 mistVelocity = new Vec3(velocity.x * 0.22D, 0.018D, velocity.z * 0.22D);
                    spawnCloud256Particle(level, mistPos, mistVelocity, 2.1F + level.random.nextFloat() * 1.7F, 0.62F, 0.66F, 0.74F, 0.24F, 28, 0.03F);
                } else if (event.type() == StormSubtype.DUST_DEVIL) {
                    Vec3 dustVelocity = new Vec3(velocity.x, 0.02D + level.random.nextDouble() * 0.03D, velocity.z);
                    DustTone tone = dustTone(level, pos);
                    if (!spawnWeather2Particle(level, pos, dustVelocity, ParticleRegistry.cloud256, 4.6F + level.random.nextFloat() * 3.8F, tone.red(), tone.green(), tone.blue(), 0.48F, 60, 0.12F)) {
                        level.addParticle(ParticleTypes.POOF, pos.x, pos.y, pos.z, dustVelocity.x, dustVelocity.y, dustVelocity.z);
                    }
                } else if (event.type() == StormSubtype.SNOW_DEVIL) {
                    Vec3 snowVelocity = new Vec3(velocity.x * 0.82D, 0.020D + level.random.nextDouble() * 0.035D, velocity.z * 0.82D);
                    if (!spawnWeather2Particle(level, pos, snowVelocity, ParticleRegistry.cloud256, 3.8F + level.random.nextFloat() * 3.2F, 0.86F, 0.90F, 0.96F, 0.46F, 52, 0.08F)) {
                        level.addParticle(ParticleTypes.SNOWFLAKE, pos.x, pos.y, pos.z, snowVelocity.x, snowVelocity.y, snowVelocity.z);
                    }
                } else if (event.type() == StormSubtype.GUSTNADO) {
                    Vec3 forward = event.motion() == null || event.motion().horizontalDistanceSqr() < 1.0E-5D
                            ? Vec3.ZERO
                            : new Vec3(event.motion().x, 0.0D, event.motion().z).normalize().scale(0.09D + event.maxWindMph() * 0.0012D);
                    Vec3 gustVelocity = new Vec3(velocity.x * 0.42D + forward.x, 0.010D + level.random.nextDouble() * 0.026D, velocity.z * 0.42D + forward.z);
                    float darkBlend = level.random.nextFloat();
                    float red = Mth.lerp(darkBlend, 0.22F, 0.52F);
                    float green = Mth.lerp(darkBlend, 0.22F, 0.46F);
                    float blue = Mth.lerp(darkBlend, 0.21F, 0.39F);
                    if (!spawnWeather2Particle(level, pos, gustVelocity, ParticleRegistry.cloud256, 8.5F + level.random.nextFloat() * 7.5F, red, green, blue, 0.58F, 70, 0.22F)) {
                        level.addParticle(ParticleTypes.POOF, pos.x, pos.y, pos.z, gustVelocity.x, gustVelocity.y, gustVelocity.z);
                    }
                } else if (event.type() == StormSubtype.FIRE_WHIRL) {
                    Vec3 fireVelocity = new Vec3(velocity.x * 0.76D, 0.045D + level.random.nextDouble() * 0.085D, velocity.z * 0.76D);
                    float heat = level.random.nextFloat();
                    if (!spawnWeather2Particle(level, pos, fireVelocity, ParticleRegistry.cloud256, 5.8F + level.random.nextFloat() * 6.8F, Mth.lerp(heat, 0.28F, 0.82F), Mth.lerp(heat, 0.22F, 0.43F), Mth.lerp(heat, 0.18F, 0.24F), 0.58F, 54, 0.18F)) {
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

    private static void spawnWildfireVisual(ClientLevel level, AddonWeatherEvent event) {
        int count = Mth.clamp((int) (event.radius() / 8.0F), 6, 26);
        for (int i = 0; i < count; i++) {
            Vec3 pos = randomFireWeatherParticle(level, event, 0.0D, Math.max(10.0D, event.radius()));
            Vec3 velocity = new Vec3((level.random.nextDouble() - 0.5D) * 0.035D, 0.035D + level.random.nextDouble() * 0.095D, (level.random.nextDouble() - 0.5D) * 0.035D);
            if (!spawnWeather2Particle(level, pos, velocity, ParticleRegistry.cloud256, 8.0F + level.random.nextFloat() * 12.0F, 0.16F, 0.15F, 0.14F, 0.52F, 80, 0.05F)) {
                level.addParticle(ParticleTypes.LARGE_SMOKE, pos.x, pos.y, pos.z, velocity.x, velocity.y, velocity.z);
            }
            if (level.random.nextFloat() < 0.24F) {
                level.addParticle(ParticleTypes.FLAME, pos.x, pos.y + 0.15D, pos.z, velocity.x * 0.30D, velocity.y * 0.18D, velocity.z * 0.30D);
            }
        }
    }

    private static void spawnFlammagenitusVisual(ClientLevel level, AddonWeatherEvent event) {
        int count = Mth.clamp((int) (event.radius() / 10.0F), 10, 34);
        for (int i = 0; i < count; i++) {
            double height = 18.0D + Math.pow(level.random.nextDouble(), 0.62D) * 120.0D;
            Vec3 pos = randomFireWeatherParticle(level, event, height, event.radius());
            Vec3 velocity = new Vec3((level.random.nextDouble() - 0.5D) * 0.030D, 0.016D + level.random.nextDouble() * 0.055D, (level.random.nextDouble() - 0.5D) * 0.030D);
            float tone = level.random.nextFloat();
            spawnStructureParticle(level, pos, velocity, ParticleRegistry.cloud256, 18.0F + level.random.nextFloat() * 20.0F, Mth.lerp(tone, 0.30F, 0.62F), Mth.lerp(tone, 0.30F, 0.58F), Mth.lerp(tone, 0.32F, 0.62F), 0.34F, 130, 0.02F, ParticleTypes.CLOUD);
        }
    }

    private static Vec3 randomFireWeatherParticle(ClientLevel level, AddonWeatherEvent event, double heightOffset, double radius) {
        double angle = level.random.nextDouble() * Math.PI * 2.0D;
        double distance = Math.sqrt(level.random.nextDouble()) * radius;
        Vec3 base = new Vec3(event.position().x + Math.cos(angle) * distance, event.position().y, event.position().z + Math.sin(angle) * distance);
        return new Vec3(base.x, visualY(level, base, heightOffset), base.z);
    }

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
        int curtainCount = Mth.clamp(28 + (int) (lineLength / 58.0D) + (int) (proximity * 24.0F), 30, 72);
        int shelfCount = Mth.clamp(14 + (int) (lineLength / 105.0D) + (int) (proximity * 10.0F), 14, 34);
        int lipCount = Mth.clamp(8 + (int) (lineLength / 150.0D), 8, 18);
        int scudCount = Mth.clamp(5 + (int) (lineLength / 180.0D), 5, 14);
        int rainCount = Mth.clamp(18 + (int) (lineLength / 82.0D), 18, 42);
        int striationCount = Mth.clamp(5 + (int) (lineLength / 210.0D), 5, 12);

        for (int i = 0; i < curtainCount; i++) {
            double t = visibleLineT(level, centerT, i, curtainCount, lineLength, 16.0D);
            double rearOffset = -event.radius() * (0.22D + level.random.nextDouble() * 1.35D);
            Vec3 base = pointAlongShelf(level, start, end, axis, forward, t, rearOffset, 34.0D);
            double height = 12.0D + level.random.nextDouble() * 106.0D;
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

        for (int i = 0; i < shelfCount; i++) {
            double t = visibleLineT(level, centerT, i, shelfCount, lineLength, 28.0D);
            Vec3 base = pointAlongShelf(level, start, end, axis, forward, t, -event.radius() * (0.10D + level.random.nextDouble() * 0.52D), 30.0D);
            double wave = Math.sin(level.getGameTime() * 0.018D + i * 0.51D + base.x * 0.006D + base.z * 0.005D);
            Vec3 pos = new Vec3(base.x, visualY(level, base, 38.0D + level.random.nextDouble() * 74.0D + wave * 2.5D), base.z);
            Vec3 velocity = forward.scale(0.026D + event.maxWindMph() * 0.00055D)
                    .add(axis.scale((level.random.nextDouble() - 0.5D) * 0.034D))
                    .add(0.0D, -0.004D + wave * 0.0015D, 0.0D);
            float shade = 0.78F + level.random.nextFloat() * 0.22F;
            spawnStructureParticle(level, pos, velocity, ParticleRegistry.cloud256,
                    32.0F + level.random.nextFloat() * 34.0F,
                    0.20F * shade, 0.21F * shade, 0.25F * shade, 0.60F, 154, 0.16F, ParticleTypes.CLOUD);
        }

        for (int i = 0; i < lipCount; i++) {
            double t = visibleLineT(level, centerT, i, lipCount, lineLength, 36.0D);
            Vec3 base = pointAlongShelf(level, start, end, axis, forward, t, 5.0D + level.random.nextDouble() * 26.0D, 18.0D);
            Vec3 pos = new Vec3(base.x, visualY(level, base, 26.0D + level.random.nextDouble() * 28.0D), base.z);
            Vec3 velocity = forward.scale(0.055D + event.maxWindMph() * 0.00075D)
                    .add(axis.scale((level.random.nextDouble() - 0.5D) * 0.028D))
                    .add(0.0D, -0.022D, 0.0D);
            float warmEdge = level.random.nextFloat();
            spawnStructureParticle(level, pos, velocity, ParticleRegistry.cloud256,
                    14.0F + level.random.nextFloat() * 20.0F,
                    Mth.lerp(warmEdge, 0.22F, 0.42F), Mth.lerp(warmEdge, 0.22F, 0.34F), Mth.lerp(warmEdge, 0.22F, 0.29F), 0.60F, 96, 0.20F, ParticleTypes.CLOUD);
        }

        for (int i = 0; i < striationCount; i++) {
            double t = visibleLineT(level, centerT, i, striationCount, lineLength, 54.0D);
            Vec3 base = pointAlongShelf(level, start, end, axis, forward, t, -event.radius() * (0.52D + level.random.nextDouble() * 0.58D), 34.0D);
            Vec3 pos = new Vec3(base.x, visualY(level, base, 42.0D + level.random.nextDouble() * 34.0D), base.z);
            Vec3 velocity = forward.scale(0.070D + event.maxWindMph() * 0.00062D)
                    .add(axis.scale((level.random.nextDouble() - 0.5D) * 0.075D))
                    .add(0.0D, -0.010D, 0.0D);
            spawnStructureParticle(level, pos, velocity, ParticleRegistry.cloud256,
                    18.0F + level.random.nextFloat() * 24.0F,
                    0.18F, 0.19F, 0.23F, 0.46F, 112, 0.18F, ParticleTypes.CLOUD);
        }

        for (int i = 0; i < scudCount; i++) {
            double t = visibleLineT(level, centerT, i, scudCount, lineLength, 58.0D);
            Vec3 base = pointAlongShelf(level, start, end, axis, forward, t, -event.radius() * (0.02D + level.random.nextDouble() * 0.42D), 22.0D);
            Vec3 pos = new Vec3(base.x, visualY(level, base, 18.0D + level.random.nextDouble() * 30.0D), base.z);
            Vec3 velocity = forward.scale(0.072D + event.maxWindMph() * 0.00060D)
                    .add(axis.scale((level.random.nextDouble() - 0.5D) * 0.040D))
                    .add(0.0D, -0.020D + level.random.nextDouble() * 0.010D, 0.0D);
            spawnStructureParticle(level, pos, velocity, ParticleRegistry.cloud,
                    6.0F + level.random.nextFloat() * 8.0F,
                    0.20F, 0.21F, 0.24F, 0.58F, 58, 0.22F, ParticleTypes.CLOUD);
        }

        for (int i = 0; i < rainCount; i++) {
            double t = visibleLineT(level, centerT, i, rainCount, lineLength, 40.0D);
            double rearOffset = -event.radius() * (0.50D + level.random.nextDouble() * 1.10D);
            Vec3 base = pointAlongShelf(level, start, end, axis, forward, t, rearOffset, 26.0D);
            Vec3 pos = new Vec3(base.x, visualY(level, base, 4.0D + level.random.nextDouble() * 58.0D), base.z);
            Vec3 velocity = forward.scale(0.040D + event.maxWindMph() * 0.00045D)
                    .add(axis.scale((level.random.nextDouble() - 0.5D) * 0.018D))
                    .add(0.0D, -0.20D - level.random.nextDouble() * 0.13D, 0.0D);
            TextureAtlasSprite rainSprite = RainShaftSprites.sheet(
                    level.random, Mth.clamp(0.42F + event.maxWindMph() * 0.0035F, 0.0F, 1.0F));
            spawnStructureParticle(level, pos, velocity, rainSprite,
                    44.0F + level.random.nextFloat() * 52.0F,
                    0.24F, 0.28F, 0.36F, 0.62F, 62, 0.18F, ParticleTypes.RAIN);
        }

        int jetCount = event.maxWindMph() >= 64.0F ? Mth.clamp(2 + (int) ((event.maxWindMph() - 58.0F) / 7.0F), 2, 8) : 0;
        for (int i = 0; i < jetCount; i++) {
            double t = Mth.clamp(0.50D + (level.random.nextDouble() - 0.5D) * 0.36D, 0.04D, 0.96D);
            Vec3 base = pointAlongShelf(level, start, end, axis, forward, t, -event.radius() * (0.92D + level.random.nextDouble() * 0.46D), 18.0D);
            Vec3 pos = new Vec3(base.x, visualY(level, base, 32.0D + level.random.nextDouble() * 26.0D), base.z);
            Vec3 velocity = forward.scale(0.16D + event.maxWindMph() * 0.0012D).add(0.0D, -0.012D, 0.0D);
            spawnStructureParticle(level, pos, velocity, ParticleRegistry.cloud256,
                    7.0F + level.random.nextFloat() * 8.0F,
                    0.18F, 0.20F, 0.25F, 0.44F, 52, 0.24F, ParticleTypes.CLOUD);
        }

        Vec3 debrisCheck = start.lerp(end, level.random.nextDouble());
        if (shouldSpawnStickDebris(level, densityFor(event), debrisCheck)) {
            Vec3 debrisPos = pointAlongShelf(level, start, end, axis, forward, level.random.nextDouble(), 8.0D + level.random.nextDouble() * 34.0D, 10.0D);
            Vec3 velocity = forward.scale(0.17D + event.maxWindMph() * 0.0014D).add(0.0D, 0.06D + level.random.nextDouble() * 0.08D, 0.0D);
            spawnStickDebris(level, new Vec3(debrisPos.x, visualY(level, debrisPos, 1.0D + level.random.nextDouble() * 6.0D), debrisPos.z), velocity);
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
        double eyeRadius = Math.max(34.0D, radius * 0.13D);
        double eyewallRadius = Math.max(82.0D, radius * 0.32D);
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
            spawnStructureParticle(level, pos, velocity, ParticleRegistry.cloud256,
                    34.0F + level.random.nextFloat() * 54.0F,
                    0.25F * shade, 0.27F * shade, 0.33F * shade, 0.50F + proximity * 0.10F, 172, 0.10F, ParticleTypes.CLOUD);
        }

        for (int i = 0; i < eyewallCount; i++) {
            double angle = level.random.nextDouble() * Math.PI * 2.0D + time * 0.22D * spinSign;
            double bandRadius = Mth.lerp(level.random.nextDouble(), eyeRadius * 1.05D, eyewallRadius);
            Vec3 base = center.add(Math.cos(angle) * bandRadius, 0.0D, Math.sin(angle) * bandRadius);
            double height = Mth.lerp(level.random.nextDouble(), 22.0D, 142.0D);
            Vec3 pos = new Vec3(base.x, visualY(level, base, height), base.z);
            Vec3 velocity = cycloneBandVelocity(event, pos, 0.070D, -0.020D, spinSign).add(0.0D, -0.012D - level.random.nextDouble() * 0.012D, 0.0D);
            float depth = level.random.nextFloat();
            spawnStructureParticle(level, pos, velocity, ParticleRegistry.cloud256,
                    42.0F + level.random.nextFloat() * 62.0F,
                    Mth.lerp(depth, 0.16F, 0.34F), Mth.lerp(depth, 0.18F, 0.36F), Mth.lerp(depth, 0.23F, 0.44F),
                    0.58F + proximity * 0.10F, 150, 0.16F, ParticleTypes.CLOUD);
        }

        TextureAtlasSprite rainSprite = RainShaftSprites.sheet(
                level.random, Mth.clamp(0.62F + proximity * 0.35F, 0.0F, 1.0F));
        for (int i = 0; i < rainCount; i++) {
            double angle = level.random.nextDouble() * Math.PI * 2.0D;
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
        double height = vortexParticleHeight(level, event);
        double radiusMultiplier = event.type() == StormSubtype.GUSTNADO ? 2.85D : event.type() == StormSubtype.FIRE_WHIRL ? 1.45D : event.type() == StormSubtype.DUST_DEVIL || event.type() == StormSubtype.SNOW_DEVIL ? 1.15D : 1.0D;
        double radius = Math.sqrt(level.random.nextDouble()) * event.radius() * radiusMultiplier;
        double y = vortexGroundY(level, event.position());
        double baseOffset = event.type() == StormSubtype.LANDSPOUT ? Math.max(5.5D, event.radius() * 0.72D) : event.type() == StormSubtype.FLOATER_TORNADO ? Math.max(12.0D, event.position().y - y) : 0.2D;
        Vec3 horizontal = new Vec3(Math.cos(angle) * radius, 0.0D, Math.sin(angle) * radius);
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

    private static TextureAtlasSprite randomDebrisSprite(ClientLevel level) {
        return switch (level.random.nextInt(3)) {
            case 0 -> ParticleRegistry.debris_1;
            case 1 -> ParticleRegistry.debris_2;
            default -> ParticleRegistry.debris_3;
        };
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
        int groundY = vortexGroundY(level, base);
        return Mth.clamp(groundY + offset, level.getMinBuildHeight() + 1.0D, level.getMaxBuildHeight() - 4.0D);
    }

    private static int vortexGroundY(ClientLevel level, Vec3 pos) {
        BlockPos ground = BlockGrabSafety.vortexGroundSurface(level, BlockPos.containing(pos));
        if (ground != null) {
            return ground.getY();
        }
        return level.getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, BlockPos.containing(pos)).getY();
    }

    private static void spawnStructureParticle(ClientLevel level, Vec3 pos, Vec3 velocity, TextureAtlasSprite sprite, float scale, float red, float green, float blue, float alpha, int maxAge, float windWeight, ParticleOptions fallback) {
        if (structureParticlesSpawnedThisTick >= Config.SUPERCELL_STRUCTURE_MAX_PARTICLES_PER_TICK.get()) {
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
        if (ParticleRegistry.cloud256 == null) {
            return;
        }
        spawnWeather2ParticleDirect(level, pos, velocity, ParticleRegistry.cloud256, scale, red, green, blue, alpha, maxAge, windWeight);
    }

    private static void spawnWeather2ParticleDirect(ClientLevel level, Vec3 pos, Vec3 velocity, TextureAtlasSprite sprite, float scale, float red, float green, float blue, float alpha, int maxAge, float windWeight) {
        EntityRotFX particle = new EntityRotFX(level, pos.x, pos.y, pos.z, velocity.x, velocity.y, velocity.z);
        particle.setSprite(sprite);
        particle.setScale(Mth.clamp(scale, 0.05F, 180.0F));
        particle.setColor(red, green, blue);
        boolean downfallSheet = RainShaftSprites.isShaftSprite(sprite);
        boolean cloudSheet = sprite == ParticleRegistry.cloud || sprite == ParticleRegistry.cloud256;
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

    private static float effectiveWindWeight(float windWeight) {
        return windWeight <= 0.0F ? LOCAL_PARTICLE_WIND_WEIGHT : windWeight;
    }

    private static float weatherParticleAlpha(TextureAtlasSprite sprite, float alpha) {
        boolean downfallSheet = RainShaftSprites.isShaftSprite(sprite);
        boolean cloudSheet = sprite == ParticleRegistry.cloud || sprite == ParticleRegistry.cloud256;
        if (downfallSheet) {
            return Mth.clamp(alpha * 1.16F + 0.08F, 0.20F, 0.82F);
        }
        if (cloudSheet) {
            return Mth.clamp(alpha * 1.32F + 0.12F, 0.24F, 0.96F);
        }
        return Mth.clamp(alpha, 0.04F, 0.96F);
    }

    private static void spawnCloudShadowParticle(ClientLevel level, Vec3 pos, float scale, float shade, float alpha, int maxAge) {
        EntityRotFX particle = new EntityRotFX(level, pos.x, pos.y, pos.z, 0.0D, 0.0D, 0.0D);
        particle.setSprite(ParticleRegistry.cloud256);
        particle.setScale(Mth.clamp(scale, 14.0F, 168.0F));
        particle.setColor(shade, shade * 1.04F, shade * 1.12F);
        float clampedAlpha = Mth.clamp(alpha, 0.14F, 0.44F);
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

    private static void spawnStickDebris(ClientLevel level, Vec3 pos, Vec3 velocity) {
        if (stickDebrisSpawnedThisTick >= Config.MAX_STICK_DEBRIS_PER_TICK.get()) {
            return;
        }
        stickDebrisSpawnedThisTick++;
        // Dry ground coats the sticks in dust: deserts throw dusty-tan debris while
        // green fields keep the clean wooden look. Rain falling on the spot scrubs it.
        BlockPos groundPos = BlockPos.containing(pos.x, vortexGroundY(level, pos) + 1, pos.z);
        if (!level.isRainingAt(groundPos) && groundDryness(level, pos) >= 0.30F && spawnDustyStickDebris(level, pos, velocity)) {
            return;
        }
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

    /**
     * Dust-coated stick debris: a tinted Weather2 twig sprite instead of the clean
     * vanilla stick item. Only used when the extended renderer is available.
     */
    private static boolean spawnDustyStickDebris(ClientLevel level, Vec3 pos, Vec3 velocity) {
        if (!Config.EXTENDED_RENDERING_ENABLED.get() || ParticleRegistry.debris_1 == null) {
            return false;
        }
        TextureAtlasSprite sprite = switch (level.random.nextInt(3)) {
            case 1 -> ParticleRegistry.debris_2;
            case 2 -> ParticleRegistry.debris_3;
            default -> ParticleRegistry.debris_1;
        };
        DustTone dusty = dustyCoatTone(level, pos);
        float jitter = 0.82F + level.random.nextFloat() * 0.30F;
        EntityRotFX particle = new EntityRotFX(level, pos.x, pos.y, pos.z, velocity.x, velocity.y, velocity.z);
        particle.setSprite(sprite);
        particle.setScale(0.30F + level.random.nextFloat() * 0.28F);
        particle.setColor(dusty.red() * jitter, dusty.green() * jitter, dusty.blue() * jitter);
        particle.setAlpha(0.92F);
        particle.setFullAlphaTarget(0.92F);
        particle.setMaxAge(26 + level.random.nextInt(24));
        particle.setGravity(0.045F);
        particle.setCanCollide(false);
        particle.setFacePlayer(false);
        particle.rotationYaw = level.random.nextInt(360);
        particle.rotationPitch = level.random.nextInt(360);
        particle.spinFast = true;
        particle.spinFastRate = 8.0F + level.random.nextFloat() * 14.0F;
        particle.windWeight = 1.2F;
        particle.spawnAsWeatherEffect();
        return true;
    }

    /**
     * How much dust the ground at a position can coat debris with, 0 (wet/frozen/
     * green) to 1 (desert). Rain is handled separately at spawn time.
     */
    private static float groundDryness(ClientLevel level, Vec3 pos) {
        BlockState state = surfaceState(level, pos);
        if (state.getFluidState().is(FluidTags.WATER)
                || state.is(net.minecraft.world.level.block.Blocks.SNOW)
                || state.is(net.minecraft.world.level.block.Blocks.SNOW_BLOCK)
                || state.is(net.minecraft.world.level.block.Blocks.POWDER_SNOW)
                || state.is(net.minecraft.world.level.block.Blocks.ICE)
                || state.is(net.minecraft.world.level.block.Blocks.PACKED_ICE)
                || state.is(net.minecraft.world.level.block.Blocks.BLUE_ICE)
                || state.is(net.minecraft.world.level.block.Blocks.MUD)) {
            return 0.0F; // wet, frozen, or muddy ground: sticks stay clean
        }
        if (state.is(net.minecraft.world.level.block.Blocks.SAND)
                || state.is(net.minecraft.world.level.block.Blocks.RED_SAND)) {
            return 1.0F;
        }
        if (state.is(net.minecraft.world.level.block.Blocks.TERRACOTTA)
                || state.is(net.minecraft.world.level.block.Blocks.RED_TERRACOTTA)
                || state.is(net.minecraft.world.level.block.Blocks.ORANGE_TERRACOTTA)
                || state.is(net.minecraft.world.level.block.Blocks.YELLOW_TERRACOTTA)
                || state.is(net.minecraft.world.level.block.Blocks.BROWN_TERRACOTTA)) {
            return 0.9F;
        }
        if (state.is(net.minecraft.world.level.block.Blocks.GRAVEL)) {
            return 0.55F;
        }
        if (state.is(net.minecraft.world.level.block.Blocks.DIRT)
                || state.is(net.minecraft.world.level.block.Blocks.COARSE_DIRT)
                || state.is(net.minecraft.world.level.block.Blocks.ROOTED_DIRT)
                || state.is(net.minecraft.world.level.block.Blocks.PODZOL)
                || state.is(net.minecraft.world.level.block.Blocks.FARMLAND)) {
            return 0.5F;
        }
        if (state.is(net.minecraft.world.level.block.Blocks.STONE)
                || state.is(net.minecraft.world.level.block.Blocks.COBBLESTONE)
                || state.is(net.minecraft.world.level.block.Blocks.GRANITE)
                || state.is(net.minecraft.world.level.block.Blocks.DIORITE)
                || state.is(net.minecraft.world.level.block.Blocks.ANDESITE)
                || state.is(net.minecraft.world.level.block.Blocks.TUFF)) {
            return 0.35F;
        }
        if (state.is(net.minecraft.world.level.block.Blocks.GRASS_BLOCK)
                || state.is(net.minecraft.world.level.block.Blocks.MOSS_BLOCK)) {
            return 0.0F; // green fields: clean wood
        }
        return 0.10F;
    }

    /**
     * Dust-coat color for wooden debris: the local ground dust tone lifted bright
     * and desaturated, blended against clean wood by how dry the ground is.
     */
    private static DustTone dustyCoatTone(ClientLevel level, Vec3 pos) {
        DustTone base = surfaceDustTone(level, pos, 0.10F);
        float dryness = groundDryness(level, pos);
        return new DustTone(
                Mth.lerp(dryness, 0.72F, Mth.clamp(base.red() * 1.25F + 0.10F, 0.15F, 0.95F)),
                Mth.lerp(dryness, 0.56F, Mth.clamp(base.green() * 1.32F + 0.12F, 0.15F, 0.92F)),
                Mth.lerp(dryness, 0.40F, Mth.clamp(base.blue() * 1.45F + 0.18F, 0.15F, 0.88F))
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
