package com.imbet.client;

import com.imbet.Config;
import com.imbet.weather.AddonWeatherEvent;
import com.imbet.weather.ClientAddonWeatherEventStore;
import com.imbet.weather.DynamicStormController;
import com.imbet.weather.DynamicStormState;
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
import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent.ClientTickEvent;
import weather2.ClientTickHandler;
import weather2.weathersystem.WeatherManagerClient;
import weather2.weathersystem.storm.StormObject;
import weather2.weathersystem.storm.WeatherObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public final class ClientWeatherEffects {
    private static final List<PendingThunder> PENDING_THUNDER = new ArrayList<>();
    private static float visualRain;
    private static float visualThunder;
    private static long lastSkyUpdateTick = -1000L;
    private static int stickDebrisSpawnedThisTick;
    private static int structureParticlesSpawnedThisTick;

    private ClientWeatherEffects() {
    }

    public static void onClientTick(ClientTickEvent event) {
        if (event.phase != net.minecraftforge.event.TickEvent.Phase.END) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) {
            PENDING_THUNDER.clear();
            visualRain = 0.0F;
            visualThunder = 0.0F;
            return;
        }
        tickPendingThunder(minecraft);
        DamageSurveyResult surveyResult = ClientDamageSurveyStore.consume();
        if (surveyResult != null && Config.SURVEY_OPEN_GUI_ON_SURVEY.get()) {
            minecraft.setScreen(new DamageSurveyScreen(surveyResult));
        }
        if (!Config.VISUALS_ENABLED.get()) {
            return;
        }
        stickDebrisSpawnedThisTick = 0;
        structureParticlesSpawnedThisTick = 0;
        spawnAddonWeatherEvents(minecraft.level, minecraft.player.position());
        WeatherManagerClient manager = ClientTickHandler.weatherManager;
        if (manager == null) {
            return;
        }
        spawnSupercellStructures(minecraft.level, manager, minecraft.player.position());
        StormObject storm = manager.getClosestStormAny(minecraft.player.position(), 700.0D);
        float targetRain = 0.0F;
        float targetThunder = 0.0F;
        if (storm == null) {
            smoothSky(minecraft.level, targetRain, targetThunder);
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
        if (Config.INFLOW_JETS_ENABLED.get() && DynamicStormController.isDynamicVortex(storm)) {
            spawnInflowJets(minecraft.level, storm);
        }
        if (DynamicStormController.isDynamicVortex(storm)) {
            spawnMainTornadoStickDebris(minecraft.level, storm, minecraft.player.position());
            spawnSubVortexStickDebris(minecraft.level, storm, minecraft.player.position());
            spawnSatelliteSwirls(minecraft.level, storm);
        }
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
        if (Config.SUPERCELL_STRUCTURE_WALL_CLOUD_PARTICLES.get()) {
            spawnWallCloud(level, storm, structure, state);
        }
        if (Config.SUPERCELL_STRUCTURE_FLANKING_LINE_PARTICLES.get()) {
            spawnFlankingLine(level, structure, strength);
        }
        spawnForwardFlankPrecip(level, structure, state.ffdStrength(), state.hailCoreStrength());
        if (Config.SUPERCELL_STRUCTURE_GUST_FRONT_PARTICLES.get()) {
            spawnRearFlankGustFront(level, structure, state.rfdStrength());
        }
        spawnSupercellInflow(level, structure, state.inflowStrength());
        if (Config.SUPERCELL_STRUCTURE_VIRGA_MAMMATUS_PARTICLES.get()) {
            spawnVirgaAndMammatus(level, structure, strength);
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
        double rotationSign = DynamicStormController.rotationSign(storm);
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
            spawnStructureParticle(level, pos, velocity, ParticleRegistry.cloud256, 12.0F + strength * 18.0F, 0.24F, 0.25F, 0.30F, 0.56F, 96, 0.16F, ParticleTypes.CLOUD);
        }

        if (Config.SUPERCELL_STRUCTURE_WALL_CLOUD_ROTATION_PARTICLES.get()) {
            int tags = Mth.clamp(1 + (int) (state.wallCloudRotationStrength() * 6.0F), 1, 7);
            for (int i = 0; i < tags; i++) {
                double angle = level.random.nextDouble() * Math.PI * 2.0D;
                double distance = radius * (0.74D + level.random.nextDouble() * 0.34D);
                Vec3 base = structure.wallCloudCenter().add(Math.cos(angle) * distance, 0.0D, Math.sin(angle) * distance);
                Vec3 pos = new Vec3(base.x, visualY(level, base, loweredBaseOffset - level.random.nextDouble() * Math.max(4.0D, state.wallCloudLoweringDepth() * 0.55D)), base.z);
                Vec3 velocity = new Vec3(-Math.sin(angle) * rotationSign, 0.018D + strength * 0.018D, Math.cos(angle) * rotationSign)
                        .scale(0.052D + state.wallCloudRotationStrength() * 0.105D);
                spawnStructureParticle(level, pos, velocity, ParticleRegistry.cloud, 5.0F + strength * 7.0F, 0.30F, 0.31F, 0.36F, 0.58F, 66, 0.18F, ParticleTypes.CLOUD);
            }
        }

        if (Config.SUPERCELL_STRUCTURE_WALL_CLOUD_SCUD_FEED_PARTICLES.get()) {
            int scud = Mth.clamp(1 + (int) (state.inflowStrength() * 5.0F), 1, 6);
            for (int i = 0; i < scud; i++) {
                double t = level.random.nextDouble();
                Vec3 base = structure.inflowSource().lerp(structure.wallCloudCenter(), 0.32D + t * 0.58D)
                        .add(structure.right().scale((level.random.nextDouble() - 0.5D) * radius * 0.55D));
                Vec3 target = structure.wallCloudCenter().subtract(base);
                target = target.horizontalDistanceSqr() < 1.0E-5D ? structure.forward().scale(-1.0D) : new Vec3(target.x, 0.0D, target.z).normalize();
                double yOffset = 4.0D + level.random.nextDouble() * Math.max(9.0D, loweredBaseOffset * 0.48D);
                Vec3 pos = new Vec3(base.x, visualY(level, base, yOffset), base.z);
                Vec3 velocity = target.scale(0.080D + state.inflowStrength() * 0.080D).add(0.0D, 0.035D + strength * 0.045D, 0.0D);
                spawnStructureParticle(level, pos, velocity, ParticleRegistry.cloud, 3.8F + strength * 5.2F, 0.36F, 0.36F, 0.40F, 0.48F, 52, 0.20F, ParticleTypes.CLOUD);
            }
        }
    }

    private static void spawnFlankingLine(ClientLevel level, SupercellStructureService.StructureSample structure, float strength) {
        int count = Mth.clamp(1 + (int) (strength * 4.0F), 1, 4);
        for (int i = 0; i < count; i++) {
            double t = level.random.nextDouble();
            Vec3 base = structure.flankingLineStart().lerp(structure.flankingLineEnd(), t)
                    .add(structure.right().scale((level.random.nextDouble() - 0.5D) * 18.0D));
            Vec3 pos = new Vec3(base.x, visualY(level, base, 32.0D + level.random.nextDouble() * 42.0D), base.z);
            Vec3 velocity = structure.forward().scale(0.010D).add(structure.right().scale(-0.006D)).add(0.0D, 0.012D, 0.0D);
            spawnStructureParticle(level, pos, velocity, ParticleRegistry.cloud256, 7.0F + level.random.nextFloat() * 8.0F, 0.72F, 0.72F, 0.76F, 0.35F, 92, 0.03F, ParticleTypes.CLOUD);
        }
    }

    private static void spawnForwardFlankPrecip(ClientLevel level, SupercellStructureService.StructureSample structure, float ffdStrength, float hailCoreStrength) {
        int count = Mth.clamp(1 + (int) (ffdStrength * 5.0F), 1, 5);
        for (int i = 0; i < count; i++) {
            Vec3 base = structure.forwardFlank()
                    .add(structure.forward().scale((level.random.nextDouble() - 0.3D) * structure.radius() * 0.62D))
                    .add(structure.right().scale((level.random.nextDouble() - 0.55D) * structure.radius() * 0.48D));
            Vec3 pos = new Vec3(base.x, visualY(level, base, 30.0D + level.random.nextDouble() * 46.0D), base.z);
            Vec3 velocity = structure.forward().scale(0.035D + ffdStrength * 0.045D).add(0.0D, -0.18D - ffdStrength * 0.07D, 0.0D);
            if (hailCoreStrength > 0.45F && level.random.nextFloat() < hailCoreStrength * 0.35F) {
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

    private static void spawnVirgaAndMammatus(ClientLevel level, SupercellStructureService.StructureSample structure, float strength) {
        if (level.random.nextFloat() > 0.55F + strength * 0.30F) {
            return;
        }
        Vec3 base = structure.anvilCore()
                .add(structure.forward().scale((level.random.nextDouble() - 0.35D) * structure.radius() * 1.12D))
                .add(structure.right().scale((level.random.nextDouble() - 0.5D) * structure.radius() * 0.86D));
        Vec3 pos = new Vec3(base.x, visualY(level, base, 74.0D + level.random.nextDouble() * 26.0D), base.z);
        Vec3 velocity = new Vec3(0.0D, -0.035D - level.random.nextDouble() * 0.035D, 0.0D).add(structure.forward().scale(0.008D));
        spawnStructureParticle(level, pos, velocity, ParticleRegistry.cloud, 3.6F + strength * 5.5F, 0.58F, 0.58F, 0.66F, 0.23F, 72, 0.04F, ParticleTypes.CLOUD);
    }

    private static void smoothSky(ClientLevel level, float targetRain, float targetThunder) {
        long gameTime = level.getGameTime();
        if (gameTime - lastSkyUpdateTick < Config.MIN_SKY_UPDATE_TICKS.get()) {
            return;
        }
        lastSkyUpdateTick = gameTime;
        float smoothing = Config.SKY_SMOOTHING.get().floatValue();
        visualRain = Mth.lerp(smoothing, visualRain, targetRain);
        visualThunder = Mth.lerp(smoothing, visualThunder, targetThunder);

        boolean activeWeather = level.isRaining() || level.isThundering();
        if (!activeWeather && Config.CLEAR_INTERVALS_ENABLED.get()) {
            float reduction = Config.CLEAR_INTERVAL_RAIN_REDUCTION.get().floatValue();
            visualRain = Math.max(0.0F, visualRain - reduction * Config.MIN_SKY_UPDATE_TICKS.get());
            visualThunder = Math.max(0.0F, visualThunder - reduction * Config.MIN_SKY_UPDATE_TICKS.get());
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
            int groundY = level.getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, net.minecraft.core.BlockPos.containing(pos)).getY();
            Vec3 velocity = forward.scale(-0.28D - level.random.nextDouble() * 0.18D);
            Vec3 particlePos = new Vec3(pos.x, groundY + 1.2D + level.random.nextDouble() * 1.5D, pos.z);
            if (!spawnWeather2Particle(level, particlePos, velocity.add(0.0D, 0.01D, 0.0D), ParticleRegistry.cloud, 4.5F, 0.72F, 0.72F, 0.78F, 0.42F, 32, 0.08F)) {
                level.addParticle(ParticleTypes.CLOUD, particlePos.x, particlePos.y, particlePos.z, velocity.x, 0.01D, velocity.z);
            }
        }
    }

    private static void spawnSatelliteSwirls(ClientLevel level, StormObject storm) {
        DynamicStormState state = DynamicStormController.stateFor(storm);
        double rotationSign = DynamicStormController.rotationSign(storm);
        for (DynamicStormState.TornadoCirculationState satellite : state.satelliteTornadoes()) {
            Vec3 center = DynamicStormController.satelliteCenter(storm, satellite, storm.pos.y);
            double radius = DynamicStormController.satelliteRadius(storm, satellite);
            for (int i = 0; i < 2; i++) {
                double angle = Math.toRadians(satellite.orbitDegrees()) + level.random.nextDouble() * Math.PI * 2.0D;
                double distance = 1.5D + level.random.nextDouble() * radius;
                int groundY = level.getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, net.minecraft.core.BlockPos.containing(center)).getY();
                Vec3 pos = new Vec3(center.x + Math.cos(angle) * distance, groundY + 0.6D + level.random.nextDouble() * 5.0D, center.z + Math.sin(angle) * distance);
                Vec3 velocity = new Vec3(-Math.sin(angle) * rotationSign, 0.02D, Math.cos(angle) * rotationSign).scale(0.12D);
                if (!spawnWeather2Particle(level, pos, velocity, ParticleRegistry.cloud, 5.0F, 0.58F, 0.58F, 0.64F, 0.46F, 44, 0.10F)) {
                    level.addParticle(ParticleTypes.CLOUD, pos.x, pos.y, pos.z, velocity.x, velocity.y, velocity.z);
                }
                if (shouldSpawnStickDebris(level, Config.SUB_VORTEX_STICK_DENSITY.get().floatValue() * 0.82F, pos)) {
                    spawnStickDebris(level, pos, velocity.add(0.0D, 0.08D + level.random.nextDouble() * 0.10D, 0.0D));
                }
            }
        }
    }

    private static void spawnMainTornadoStickDebris(ClientLevel level, StormObject storm, Vec3 playerPos) {
        if (!Config.STICK_DEBRIS_ENABLED.get() || storm.pos.distanceTo(playerPos) > Config.STICK_DEBRIS_RENDER_RANGE_BLOCKS.get()) {
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
            int groundY = level.getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, net.minecraft.core.BlockPos.containing(storm.pos)).getY();
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
            Vec3 center = TornadoWindField.subVortexCenter(storm, vortex, storm.pos.y);
            if (center.distanceTo(playerPos) > Config.STICK_DEBRIS_RENDER_RANGE_BLOCKS.get()) {
                continue;
            }
            int groundY = level.getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, net.minecraft.core.BlockPos.containing(center)).getY();
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
            if (event.position().distanceTo(playerPos) > 512.0D) {
                continue;
            }
            int count = particleCountFor(event);
            for (int i = 0; i < count; i++) {
                Vec3 pos = event.isLine() ? randomLineParticle(level, event) : randomVortexParticle(level, event);
                Vec3 velocity = event.isLine() ? lineVelocity(level, event) : swirlVelocity(event, pos);
                if (event.type() == StormSubtype.WATERSPOUT) {
                    Vec3 sprayVelocity = new Vec3(velocity.x * 0.45D, 0.035D + level.random.nextDouble() * 0.035D, velocity.z * 0.45D);
                    if (!spawnWeather2Particle(level, pos, sprayVelocity, ParticleRegistry.groundSplash, 0.85F + level.random.nextFloat() * 0.45F, 0.72F, 0.78F, 0.86F, 0.42F, 18, 0.02F)) {
                        level.addParticle(ParticleTypes.SPLASH, pos.x, pos.y, pos.z, sprayVelocity.x, sprayVelocity.y, sprayVelocity.z);
                    }
                    Vec3 mistPos = pos.add(0.0D, 0.18D + level.random.nextDouble() * 0.65D, 0.0D);
                    Vec3 mistVelocity = new Vec3(velocity.x * 0.22D, 0.018D, velocity.z * 0.22D);
                    if (!spawnWeather2Particle(level, mistPos, mistVelocity, ParticleRegistry.cloud, 1.45F + level.random.nextFloat() * 1.35F, 0.62F, 0.66F, 0.74F, 0.24F, 24, 0.03F)) {
                        level.addParticle(ParticleTypes.CLOUD, mistPos.x, mistPos.y, mistPos.z, mistVelocity.x, mistVelocity.y, mistVelocity.z);
                    }
                } else if (event.type() == StormSubtype.DUST_DEVIL) {
                    Vec3 dustVelocity = new Vec3(velocity.x, 0.02D + level.random.nextDouble() * 0.03D, velocity.z);
                    if (!spawnWeather2Particle(level, pos, dustVelocity, randomDebrisSprite(level), 3.0F + level.random.nextFloat() * 2.2F, 0.62F, 0.48F, 0.32F, 0.72F, 40, 0.12F)) {
                        level.addParticle(ParticleTypes.POOF, pos.x, pos.y, pos.z, dustVelocity.x, dustVelocity.y, dustVelocity.z);
                    }
                } else if (event.type() == StormSubtype.SQUALL_LINE) {
                    Vec3 linePos = new Vec3(pos.x, pos.y + 1.2D + level.random.nextDouble() * 4.0D, pos.z);
                    if (!spawnWeather2Particle(level, linePos, velocity, ParticleRegistry.cloud, 6.0F + level.random.nextFloat() * 4.0F, 0.48F, 0.50F, 0.56F, 0.52F, 46, 0.18F)) {
                        level.addParticle(ParticleTypes.CLOUD, linePos.x, linePos.y, linePos.z, velocity.x, 0.0D, velocity.z);
                    }
                } else {
                    Vec3 landspoutVelocity = new Vec3(velocity.x, 0.03D, velocity.z);
                    if (!spawnWeather2Particle(level, pos, landspoutVelocity, ParticleRegistry.cloud, 4.8F + level.random.nextFloat() * 3.2F, 0.56F, 0.54F, 0.50F, 0.52F, 44, 0.14F)) {
                        level.addParticle(ParticleTypes.CLOUD, pos.x, pos.y, pos.z, landspoutVelocity.x, landspoutVelocity.y, landspoutVelocity.z);
                    }
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

    private static Vec3 randomVortexParticle(ClientLevel level, AddonWeatherEvent event) {
        double angle = level.random.nextDouble() * Math.PI * 2.0D;
        double radius = Math.sqrt(level.random.nextDouble()) * event.radius();
        double y = level.getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, net.minecraft.core.BlockPos.containing(event.position())).getY();
        double height = vortexParticleHeight(level, event);
        return new Vec3(event.position().x + Math.cos(angle) * radius, y + 0.2D + height, event.position().z + Math.sin(angle) * radius);
    }

    private static Vec3 randomLineParticle(ClientLevel level, AddonWeatherEvent event) {
        Vec3 start = event.lineStart() == null ? event.position() : event.lineStart();
        Vec3 end = event.lineEnd() == null ? event.position() : event.lineEnd();
        Vec3 line = end.subtract(start);
        Vec3 normal = line.horizontalDistanceSqr() < 1.0E-5D ? new Vec3(1.0D, 0.0D, 0.0D) : new Vec3(-line.z, 0.0D, line.x).normalize();
        Vec3 pos = start.lerp(end, level.random.nextDouble()).add(normal.scale((level.random.nextDouble() - 0.5D) * event.radius()));
        int y = level.getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, net.minecraft.core.BlockPos.containing(pos)).getY();
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

    private static double horizontalDistance(Vec3 first, Vec3 second) {
        double dx = first.x - second.x;
        double dz = first.z - second.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    private static double visualY(ClientLevel level, Vec3 base, double offset) {
        int groundY = level.getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, net.minecraft.core.BlockPos.containing(base)).getY();
        return Mth.clamp(groundY + offset, level.getMinBuildHeight() + 1.0D, level.getMaxBuildHeight() - 4.0D);
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
        if (sprite == null) {
            return false;
        }
        EntityRotFX particle = new EntityRotFX(level, pos.x, pos.y, pos.z, velocity.x, velocity.y, velocity.z);
        particle.setSprite(sprite);
        particle.setScale(scale);
        particle.setColor(red, green, blue);
        particle.setAlpha(alpha);
        particle.setMaxAge(maxAge);
        particle.setGravity(0.0F);
        particle.setCanCollide(false);
        particle.setFacePlayer(true);
        particle.facePlayerYaw = true;
        particle.windWeight = windWeight;
        particle.spawnAsWeatherEffect();
        return true;
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
            return 6;
        }
        if (event.type() == StormSubtype.SQUALL_LINE) {
            return 8;
        }
        return 9;
    }

    private static double vortexParticleHeight(ClientLevel level, AddonWeatherEvent event) {
        if (event.type() == StormSubtype.DUST_DEVIL) {
            return level.random.nextDouble() * 6.0D;
        }
        if (event.type() == StormSubtype.WATERSPOUT) {
            return level.random.nextDouble() * 7.5D;
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
