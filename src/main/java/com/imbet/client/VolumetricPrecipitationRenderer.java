package com.imbet.client;

import com.imbet.Config;
import com.imbet.weather.CompatHooks;
import com.imbet.weather.DynamicStormController;
import com.imbet.weather.DynamicStormState;
import com.imbet.weather.PrecipitationField;
import com.imbet.weather.SupercellStructureService;
import extendedrenderer.particle.ParticleRegistry;
import extendedrenderer.particle.entity.EntityRotFX;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import weather2.ClientTickHandler;
import weather2.config.ConfigMisc;
import weather2.weathersystem.WeatherManagerClient;
import weather2.weathersystem.storm.StormObject;
import weather2.weathersystem.storm.WeatherObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class VolumetricPrecipitationRenderer {
    private static final double TWO_PI = Math.PI * 2.0D;
    private static long lastParticleTick = Long.MIN_VALUE;

    /**
     * How many draws each sheet is allowed when sampling the precipitation field. Bounded because a
     * storm whose field is mostly thin has no rain to place, and looking harder for it does not make
     * any: eight tries leaves under a tenth of a percent of sheets unplaced in a typical core.
     */
    private static final int PRECIPITATION_SAMPLE_TRIES = 8;

    /** Share of the ring around a vortex that has to be wet before it counts as rain-wrapped. */
    private static final float RAIN_WRAP_SHARE = 0.45F;

    /** How much more readily the thin margins of a rain area evaporate than its core does. */
    private static final double VIRGA_FRINGE_GAIN = 0.75D;

    private VolumetricPrecipitationRenderer() {
    }

    /**
     * The rain volumes are on only when the player asked for them, the classic look is off, and the
     * vanilla-rain mode is off. Both read from here so the three call sites - the pass, the
     * vanilla-rain bypass and the intensity probe the rest of the client asks for - can never
     * disagree about whether rain is being drawn.
     *
     * <p>The vanilla-rain mode belongs in this one predicate rather than beside it: it means vanilla's
     * sheets are the rain, so the volumes must not draw a second rain over them <em>and</em> the
     * cancel that normally hides vanilla's rain near a cell must stand down, and both of those are
     * exactly the two answers this flag already gives.
     */
    private static boolean volumetricDownfallActive() {
        return Config.VOLUMETRIC_DOWNFALL_ENABLED.get() && !Config.classicLook() && !VanillaRainMode.active();
    }

    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        // Classic look: precipitation is Weather2's own downfall particles, so the localized rain
        // volumes stand down with the rest of the reshaping layer.
        //
        // And when the shader volume is drawing this storm's rain, these sheets stand down for it: they
        // are two drawings of the same rain, so both on at once would be rain counted twice. Only the
        // sheets are given up - the vanilla-rain bypass and the downfall probe above deliberately still
        // read volumetricDownfallActive, because the rain the volume draws is still this mod's rain and
        // vanilla's global downpour still has to be suppressed under it.
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_WEATHER || !volumetricDownfallActive()
                || RainVolumeShader.active()) {
            return;
        }
        // Iris runs every level stage again for its shadow map; this belongs to the visible frame only.
        if (RenderingCompat.isRenderingShadowPass()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        Player player = minecraft.player;
        WeatherManagerClient manager = ClientTickHandler.weatherManager;
        if (level == null || player == null || manager == null || Config.VOLUMETRIC_DOWNFALL_MAX_CELLS_RENDERED.get() <= 0 || Config.VOLUMETRIC_DOWNFALL_MAX_VERTICES_PER_FRAME.get() <= 0) {
            return;
        }

        long gameTime = level.getGameTime();
        if (lastParticleTick == gameTime) {
            return;
        }
        lastParticleTick = gameTime;

        Vec3 playerPos = player.position();
        List<StormObject> storms = renderableStorms(manager, playerPos, Config.VOLUMETRIC_DOWNFALL_RENDER_RANGE_BLOCKS.get());
        if (storms.isEmpty()) {
            return;
        }

        int[] budget = {particleBudget()};
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        for (StormObject storm : storms.stream().limit(Config.VOLUMETRIC_DOWNFALL_MAX_CELLS_RENDERED.get()).toList()) {
            spawnStormParticles(level, storm, playerPos, gameTime, partial, budget);
            if (budget[0] <= 0) {
                break;
            }
        }
    }

    public static boolean shouldBypassVanillaRain() {
        if (!volumetricDownfallActive() || !Config.VOLUMETRIC_DOWNFALL_BYPASS_VANILLA_NEAR_CELLS.get()) {
            return false;
        }
        if (shaderCompatibilityActive()) {
            return false;
        }
        Minecraft minecraft = Minecraft.getInstance();
        Player player = minecraft.player;
        WeatherManagerClient manager = ClientTickHandler.weatherManager;
        if (minecraft.level == null || player == null || manager == null) {
            return false;
        }
        // Any storm cell nearby takes over precipitation rendering, not just the ones currently
        // drawing sheets: vanilla's rain is global, so a dry LP supercell has to suppress it here
        // too or it would rain out of a storm that produces no rain at all.
        return !collectStorms(manager, player.position(), Math.min(Config.VOLUMETRIC_DOWNFALL_RENDER_RANGE_BLOCKS.get(), 512), false).isEmpty();
    }

    public static boolean shaderCompatibilityActive() {
        String mode = Config.VOLUMETRIC_DOWNFALL_SHADER_COMPATIBILITY_MODE.get();
        if ("on".equalsIgnoreCase(mode)) {
            return true;
        }
        if ("off".equalsIgnoreCase(mode)) {
            return false;
        }
        return CompatHooks.shaderPackLoaded();
    }

    public static float localDownfallIntensity() {
        if (!volumetricDownfallActive() || !shaderCompatibilityActive()) {
            return 0.0F;
        }
        Minecraft minecraft = Minecraft.getInstance();
        Player player = minecraft.player;
        WeatherManagerClient manager = ClientTickHandler.weatherManager;
        if (minecraft.level == null || player == null || manager == null) {
            return 0.0F;
        }
        List<StormObject> storms = renderableStorms(manager, player.position(), Math.min(Config.VOLUMETRIC_DOWNFALL_RENDER_RANGE_BLOCKS.get(), 512));
        if (storms.isEmpty()) {
            return 0.0F;
        }
        double best = 0.0D;
        for (StormObject storm : storms) {
            double distance = Math.sqrt(horizontalDistanceSqr(storm.pos, player.position()));
            double radius = Math.max(48.0D, stormRadius(storm));
            double proximity = Mth.clamp(1.0D - distance / (radius + 80.0D), 0.0D, 1.0D);
            double stage = Mth.clamp((storm.levelCurIntensityStage - StormObject.STATE_NORMAL + 1.0D) / 7.0D, 0.18D, 1.0D);
            best = Math.max(best, proximity * stage);
        }
        return Mth.clamp((float) best, 0.0F, 1.0F);
    }

    private static List<StormObject> renderableStorms(WeatherManagerClient manager, Vec3 playerPos, int range) {
        return collectStorms(manager, playerPos, range, true);
    }

    /** Nearby storm cells, optionally narrowed to the ones that are actually putting rain down. */
    private static List<StormObject> collectStorms(WeatherManagerClient manager, Vec3 playerPos, int range, boolean requireDownfall) {
        List<StormObject> storms = new ArrayList<>();
        double rangeSqr = (double) range * range;
        for (WeatherObject object : new ArrayList<>(manager.getStormObjects())) {
            if (!(object instanceof StormObject storm) || storm.pos == null || storm.isDead || DynamicStormController.isAbsorbedOrDecaying(storm)) {
                continue;
            }
            if (requireDownfall && !shouldRenderDownfall(storm)) {
                continue;
            }
            double radius = stormRadius(storm);
            double distanceSqr = horizontalDistanceSqr(storm.pos, playerPos);
            // The range's own root is taken once for this storm rather than twice for its two sides.
            double reach = Math.sqrt(rangeSqr) + radius;
            if (distanceSqr <= reach * reach) {
                storms.add(storm);
            }
        }
        storms.sort(Comparator.comparingDouble(storm -> horizontalDistanceSqr(storm.pos, playerPos)));
        return storms;
    }

    private static void spawnStormParticles(ClientLevel level, StormObject storm, Vec3 playerPos, long gameTime, float partial, int[] budget) {
        double radius = stormRadius(storm);
        double distance = Math.sqrt(horizontalDistanceSqr(storm.pos, playerPos));
        DynamicStormState state = DynamicStormController.stateFor(storm);
        SupercellStructureService.StructureSample structure = SupercellStructureService.sample(storm, state);
        Vec3 forward = horizontalOrDefault(structure.forward(), motionDirection(storm));
        Vec3 right = horizontalOrDefault(structure.right(), new Vec3(-forward.z, 0.0D, forward.x));
        float character = precipitationCharacter(storm);
        // Hail reads strongest in the dry, LP-type storm where little rain dilutes the core.
        boolean hail = hailCore(storm) >= Mth.lerp(0.60F, Config.VOLUMETRIC_DOWNFALL_HAIL_TINT_THRESHOLD.get().floatValue(), character);
        // The storm's precipitation as a field: where its rain actually is, part by part. This is what
        // replaces the blob that used to be drawn at a point, and it is the difference between a rain
        // curtain with a hook wrapping the vortex and a clear slot beside it, and a grey mass sitting on
        // the storm.
        PrecipitationField.Geometry field = SupercellStructureService.precipitationGeometry(storm, state);
        // Rain-wrapped is a fact about *this* vortex rather than about how wet the storm is on average:
        // it is the share of a ring around the funnel that has rain in it, so a storm pouring rain out
        // of its forward flank while its tornado stands in clear air is not treated as wrapped, and a
        // moderate storm whose hook has closed around its funnel is.
        double wrapShare = SupercellStructureService.rainWrapShare(storm, state, Math.max(14.0D, radius * 0.13D));
        boolean rainWrappedVortex = isRainWrappedVortex(storm) && wrapShare >= RAIN_WRAP_SHARE;
        // Virga: a dry layer of air under the cloud base evaporates the precipitation before it can
        // land, so the shafts hang and thin out instead of arriving. Computed from synced storm data,
        // which is what lets the same number also keep the ground from flooding below.
        float virga = SupercellStructureService.virgaStrength(storm, state);
        if (hail) {
            // Hail is large enough to survive the fall that eats the rain.
            virga *= 0.5F;
        }
        float intensity = Mth.clamp((storm.levelCurIntensityStage - StormObject.STATE_THUNDER + 1) / 6.0F, 0.2F, 1.0F);
        int count = Mth.clamp((int) (8.0D + radius / 24.0D + intensity * 18.0F), 8, hail ? 54 : 44);
        if (rainWrappedVortex && !hail) {
            count = Math.max(count, Mth.clamp((int) (38.0D + radius / 13.0D + intensity * 52.0F), 52, 136));
        }
        // The character scales how much rain this storm actually puts down: a dry LP supercell
        // keeps a sparse hail core, a torrential HP supercell doubles up on sheets.
        count = Mth.clamp(Math.round(count * Mth.lerp(0.22F, 1.45F, character)), 4, 200);
        if (distance > 520.0D) {
            count = Math.max(5, Math.round(count * 0.48F));
        } else if (distance > 300.0D) {
            count = Math.max(6, Math.round(count * 0.68F));
        }

        double rotationSign = DynamicStormController.rotationSign(storm);
        Vec3 stormMotion = storm.motion == null ? Vec3.ZERO : storm.motion;
        Vec3 motionDrift = new Vec3(stormMotion.x, 0.0D, stormMotion.z).scale(0.12D);
        double top = Math.min(level.getMaxBuildHeight() - 4.0D, topY(storm));
        double[] candidate = new double[3];

        for (int index = 0; index < count && budget[0] > 0; index++) {
            // Where the rain is, asked of the field instead of assumed: a candidate point is drawn and
            // kept in proportion to how wet the field says it is there. The core is dense, the hook is a
            // band, the notch comes back dry because the field says so, and the stratiform tail is thin -
            // all from one rule rather than from a spread per case.
            if (!samplePrecipitation(level, storm.pos, field, forward, right, radius, candidate)) {
                continue;
            }
            double density = candidate[2];
            double x = candidate[0];
            double z = candidate[1];
            BlockPos samplePos = BlockPos.containing(x, 0.0D, z);
            if (!level.isLoaded(samplePos)) {
                continue;
            }
            double floor = floorY(level, x, z);
            double minY = Math.min(top - 4.0D, floor + (rainWrappedVortex ? 3.0D : 8.0D));
            // Virga needs depth to work with: a shallow column under a low cloud has no time to
            // evaporate anything, so the dry layer's effect scales with how far the precipitation
            // actually has to fall before it lands.
            double columnHeight = Math.max(1.0D, top - minY);
            double depthFactor = Mth.clamp(
                    columnHeight / Math.max(24.0D, Config.VIRGA_MAX_FALL_DEPTH_BLOCKS.get()), 0.0D, 1.0D);
            // The dry air eats the margins of a rain area before its middle, because a thin shaft is
            // mostly surface: that is why the outside of a curtain frays into wisps while the core lands.
            double fringe = 1.0D + VIRGA_FRINGE_GAIN * (1.0D - density);
            float virgaHere = (float) Mth.clamp(virga * depthFactor * fringe, 0.0F, 1.0F);
            // How much of the column the dry air eats away. Clamped so at least minFallDepthBlocks of
            // rain still hangs below the cloud base even at full strength - virga is a shaft that
            // never lands, not a storm with no precipitation at all.
            double eaten = virgaHere <= 0.01F
                    ? 0.0D
                    : Math.min(
                            Math.max(0.0D, columnHeight - Config.VIRGA_MIN_FALL_DEPTH_BLOCKS.get()),
                            columnHeight * Math.pow(virgaHere, 1.5D));
            double columnBottom = minY + eaten;
            double localTop = rainWrappedVortex
                    ? Math.min(top - 4.0D, floor + Mth.lerp(level.random.nextDouble(), 58.0D, 138.0D + intensity * 34.0D))
                    : top;
            double heightBlend = Math.pow(level.random.nextDouble(), rainWrappedVortex ? 1.28D : 0.88D);
            double y = Mth.lerp(heightBlend, columnBottom, Math.max(columnBottom + 2.0D, localTop));
            Vec3 particlePos = new Vec3(x, y, z);
            Vec3 inward = new Vec3(storm.pos.x - x, 0.0D, storm.pos.z - z);
            if (inward.horizontalDistanceSqr() > 1.0E-4D) {
                inward = inward.normalize();
            }
            Vec3 tangent = new Vec3(-inward.z * rotationSign, 0.0D, inward.x * rotationSign);
            double fallSpeed = hail ? -0.66D : -0.48D;
            Vec3 velocity = forward.scale(0.026D + intensity * 0.034D)
                    .add(tangent.scale(rainWrappedVortex ? 0.018D + intensity * 0.020D : 0.006D))
                    .add(inward.scale(hail ? 0.012D : rainWrappedVortex ? 0.022D : 0.008D))
                    .add(motionDrift)
                    .add(0.0D, fallSpeed - level.random.nextDouble() * 0.16D, 0.0D);
            double distantFactor = Mth.clamp((distance - 180.0D) / 620.0D, 0.0D, 1.0D);
            // The big, dim sheets belong to thin rain and the dense ones to the core, which is the same
            // distinction the field is making: a curtain of few large sheets at its edge and a wall of
            // fine ones where it is heaviest.
            double curtainFactor = Mth.clamp(1.0D - density, 0.0D, 1.0D);
            // Where this sheet sits between the cloud base (0) and the evaporation edge (1). A virga
            // shaft does not switch off where it stops, it fades out, so the sheets lower down get
            // progressively thinner on top of the ragged tips their texture already draws.
            double evaporationT = eaten <= 0.5D
                    ? 0.0D
                    : Mth.clamp((top - y) / Math.max(1.0D, top - columnBottom), 0.0D, 1.0D);
            float evaporationFade = (float) Mth.lerp(evaporationT, 1.0D, 1.0D - 0.60D * virgaHere);
            spawnDownfallParticle(level, particlePos, velocity, hail, rainWrappedVortex, intensity, distantFactor, curtainFactor, character, virgaHere, evaporationFade, density, budget);

            if (!hail && distantFactor < 0.20D && level.random.nextFloat() < (0.18F + intensity * 0.18F) * density && budget[0] > 0) {
                Vec3 mistPos = particlePos.add(0.0D, -level.random.nextDouble() * 5.0D, 0.0D);
                spawnMistParticle(level, mistPos, tangent.scale(0.018D).add(inward.scale(0.018D)), intensity, budget);
            }
            if (evaporationT > 0.70D && level.random.nextFloat() < 0.26F * virgaHere && budget[0] > 0) {
                // The scruffy damp haze right where the shafts give up is how virga is recognised from
                // the ground, so the evaporation edge gets its own wisps rather than ending in air.
                Vec3 edgePos = particlePos.add(0.0D, -level.random.nextDouble() * 4.0D, 0.0D);
                spawnMistParticle(level, edgePos, tangent.scale(0.012D).add(inward.scale(0.012D)), intensity * 0.75F, budget);
            }
        }
    }

    private static void spawnDownfallParticle(ClientLevel level, Vec3 pos, Vec3 velocity, boolean hail, boolean rainWrappedVortex, float intensity, double distantFactor, double curtainFactor, float character, float virga, float evaporationFade, double localDensity, int[] budget) {
        TextureAtlasSprite sprite;
        if (hail) {
            sprite = level.random.nextFloat() < 0.34F ? ParticleRegistry.hail : ParticleArt.rainWhite();
        } else {
            float shaftDensity = shaftDensity(character, intensity, distantFactor, localDensity);
            // Only shafts that are genuinely dissolving get the frayed texture; a storm that mostly
            // reaches the ground keeps ordinary rain that merely dims near the bottom.
            sprite = virga > 0.45F
                    ? RainShaftSprites.virgaSheet(level.random, shaftDensity)
                    : RainShaftSprites.sheet(level.random, shaftDensity);
        }
        float sheetFactor = (float) Mth.clamp(Math.max(distantFactor, curtainFactor * 0.65D), 0.0D, 1.0D);
        // The sheets are deliberately much smaller than the volume they imply: at full size they are
        // 16-40 blocks across and hundreds of them stack into a blinding wall when the player is
        // close, especially inside a rain-wrapped tornado where they wrap the funnel itself.
        float sheetSize = Mth.clamp(Config.VOLUMETRIC_DOWNFALL_SHEET_SIZE_MULTIPLIER.get().floatValue(), 0.10F, 2.0F);
        float scale = hail
                ? 0.58F + level.random.nextFloat() * 0.46F
                : Mth.lerp(
                        sheetFactor,
                        rainWrappedVortex ? 18.0F + level.random.nextFloat() * (16.0F + intensity * 10.0F) : 16.0F + level.random.nextFloat() * (12.0F + intensity * 8.0F),
                        rainWrappedVortex ? 34.0F + level.random.nextFloat() * (24.0F + intensity * 16.0F) : 28.0F + level.random.nextFloat() * (18.0F + intensity * 12.0F)
                ) * sheetSize * Mth.lerp(virga, 1.0F, 0.88F);
        float density = Mth.lerp(0.55F, 1.25F, character);
        float alpha = hail
                ? Mth.clamp(Config.VOLUMETRIC_DOWNFALL_HAIL_OPACITY.get().floatValue() * 0.58F * density, 0.12F, 0.52F)
                : Mth.clamp((float) Config.rainOpacity(Config.VOLUMETRIC_DOWNFALL_RAIN_OPACITY.get())
                        * (rainWrappedVortex ? 0.96F : 0.82F) * density,
                        rainWrappedVortex ? 0.30F : 0.16F, rainWrappedVortex ? 0.58F : 0.44F);
        float red = hail ? 0.82F : Mth.lerp(sheetFactor, rainWrappedVortex ? 0.30F : 0.36F, rainWrappedVortex ? 0.18F : 0.26F);
        float green = hail ? 0.96F : Mth.lerp(sheetFactor, rainWrappedVortex ? 0.32F : 0.38F, rainWrappedVortex ? 0.20F : 0.28F);
        float blue = hail ? 0.88F : Mth.lerp(sheetFactor, rainWrappedVortex ? 0.38F : 0.46F, rainWrappedVortex ? 0.28F : 0.36F);
        alpha *= evaporationFade;
        int maxAge = hail ? 26 + level.random.nextInt(14) : 24 + level.random.nextInt(24) + Math.round(sheetFactor * 30.0F);
        float windWeight = hail ? 0.08F : Mth.lerp(sheetFactor, 0.22F, 0.10F);
        spawnWeather2Particle(level, pos, velocity, sprite, scale, red, green, blue, alpha, maxAge, windWeight, budget);
    }

    /**
     * How hard it is raining where this sheet is, 0 = a thin shower, 1 = a torrential core, used to
     * pick which of the three rain textures the sheet gets.
     *
     * <p>Read off the precipitation field at the sheet's own position, so the texture follows the
     * weather: the heart of a forward-flank core draws as heavy rain, the thin edge of the same storm
     * as a shower, and the two are adjacent because the field says they are.
     */
    private static float shaftDensity(float character, float intensity, double distantFactor, double localDensity) {
        float wet = Mth.lerp(0.35F, 1.0F, Mth.clamp(character, 0.0F, 1.0F));
        float local = Mth.lerp(0.45F, 1.25F, (float) Mth.clamp(localDensity, 0.0D, 1.0D));
        return Mth.clamp(wet * local * (0.72F + intensity * 0.42F) - (float) distantFactor * 0.30F, 0.0F, 1.0F);
    }

    /**
     * Draws one point of rain from the storm's precipitation field, rejecting thin air.
     *
     * <p>Rejection sampling rather than a shape per case: a uniform candidate is thrown inside the
     * storm's disc and kept with probability equal to the field's density there. That is what makes the
     * field the single description of the rain - the hook's band, the notch's slot and the core's mass
     * all come out of it, with no spread constants to keep in agreement with the geometry.
     *
     * <p>Returns false when the candidates all land in dry air, which for a low-precipitation storm's
     * flank is the honest answer rather than a reason to find something to draw.
     */
    private static boolean samplePrecipitation(ClientLevel level, Vec3 center, PrecipitationField.Geometry field, Vec3 forward, Vec3 right, double radius, double[] out) {
        double reach = Math.max(48.0D, radius) * 1.15D;
        for (int attempt = 0; attempt < PRECIPITATION_SAMPLE_TRIES; attempt++) {
            // Uniform over the disc, so the density of what is placed is the field's own density.
            double angle = level.random.nextDouble() * TWO_PI;
            double distance = Math.sqrt(level.random.nextDouble()) * reach;
            double localForward = Math.cos(angle) * distance;
            double localRight = Math.sin(angle) * distance;
            double density = PrecipitationField.densityAtLocal(field, localForward, localRight);
            if (level.random.nextDouble() >= density) {
                continue;
            }
            out[0] = center.x + localForward * forward.x + localRight * right.x;
            out[1] = center.z + localForward * forward.z + localRight * right.z;
            out[2] = density;
            return true;
        }
        return false;
    }

    private static void spawnMistParticle(ClientLevel level, Vec3 pos, Vec3 velocity, float intensity, int[] budget) {
        // Mist rides with the sheets toward the circulation, so it shrinks with them.
        float mistSize = Mth.clamp(Config.VOLUMETRIC_DOWNFALL_SHEET_SIZE_MULTIPLIER.get().floatValue(), 0.10F, 2.0F);
        float scale = (2.4F + level.random.nextFloat() * (2.4F + intensity * 3.0F)) * mistSize;
        float alpha = 0.08F + intensity * 0.10F;
        spawnWeather2Particle(level, pos, velocity.add(0.0D, -0.06D, 0.0D), ParticleRegistry.cloud, scale, 0.48F, 0.52F, 0.58F, alpha, 34 + level.random.nextInt(24), 0.12F, budget);
    }

    private static void spawnWeather2Particle(ClientLevel level, Vec3 pos, Vec3 velocity, TextureAtlasSprite sprite, float scale, float red, float green, float blue, float alpha, int maxAge, float windWeight, int[] budget) {
        if (budget[0] <= 0) {
            return;
        }
        budget[0]--;
        if (!Config.EXTENDED_RENDERING_ENABLED.get() || sprite == null) {
            level.addParticle(ParticleTypes.RAIN, pos.x, pos.y, pos.z, velocity.x, velocity.y, velocity.z);
            return;
        }
        EntityRotFX particle = new EntityRotFX(level, pos.x, pos.y, pos.z, velocity.x, velocity.y, velocity.z);
        particle.setSprite(sprite);
        particle.setScale(Mth.clamp(scale, 0.15F, 180.0F));
        particle.setColor(Mth.clamp(red, 0.0F, 1.0F), Mth.clamp(green, 0.0F, 1.0F), Mth.clamp(blue, 0.0F, 1.0F));
        boolean downfallSheet = RainShaftSprites.isShaftSprite(sprite);
        boolean cloudSheet = sprite == ParticleRegistry.cloud || sprite == ParticleArt.cloud256();
        float clampedAlpha = downfallSheet
                ? Mth.clamp(alpha * 1.18F + 0.08F, 0.22F, 0.80F)
                : cloudSheet
                ? Mth.clamp(alpha * 1.22F + 0.08F, 0.14F, 0.96F)
                : Mth.clamp(alpha, 0.04F, 0.96F);
        particle.setAlpha(clampedAlpha);
        particle.setFullAlphaTarget(clampedAlpha);
        particle.setTicksFadeInMax(downfallSheet ? 2.0F : 4.0F);
        particle.setTicksFadeOutMax(downfallSheet ? 6.0F : 8.0F);
        particle.setMaxAge(Math.max(8, maxAge));
        particle.setGravity(0.0F);
        particle.setCanCollide(false);
        particle.setFacePlayer(!downfallSheet);
        particle.facePlayerYaw = !downfallSheet;
        particle.isTransparent = true;
        particle.renderOrder = downfallSheet ? 2 : cloudSheet ? 1 : 2;
        particle.windWeight = windWeight;
        particle.spinTowardsMotionDirection = true;
        particle.spinFast = true;
        particle.spinFastRate = 0.018F + level.random.nextFloat() * 0.035F;
        particle.setSlantParticleToWind(true);
        if (downfallSheet) {
            float yaw = (float) Math.toDegrees(Math.atan2(velocity.x, velocity.z));
            particle.prevRotationYaw = yaw;
            particle.rotationYaw = yaw;
            // Start upright for a streaked sheet: the particle is already slanted to the wind, which
            // recomputes this pitch every tick, so only the spawn frame would otherwise be tilted.
            float pitch = RainShaftSprites.isStreakedSprite(sprite) ? 0.0F : 82.0F;
            particle.prevRotationPitch = pitch;
            particle.rotationPitch = pitch;
            particle.quatControl = true;
            particle.useRotationAroundCenter = true;
            particle.spinFast = false;
        }
        particle.setRenderDistanceCull(Config.VOLUMETRIC_DOWNFALL_RENDER_RANGE_BLOCKS.get() + 160.0F);
        particle.spawnAsWeatherEffect();
    }

    private static int particleBudget() {
        return Mth.clamp(Config.VOLUMETRIC_DOWNFALL_MAX_VERTICES_PER_FRAME.get() / 180, 24, 180);
    }

    /**
     * Whether a storm is putting precipitation down at all - and therefore whether its rain is drawn.
     *
     * <p>Shared with the volumetric rain rather than duplicated there: the sheets and the volume are two
     * drawings of one storm, so a dry LP supercell that the sheets deliberately leave almost rainless has
     * to be left rainless by the volume too, or the same storm is dry from one and wet from the other.
     */
    static boolean shouldRenderDownfall(StormObject storm) {
        if (isLowPrecipitationVortex(storm)) {
            return false;
        }
        // Dry LP-type supercells put almost nothing on the ground: the only precipitation that
        // makes it down is the hail core, which is exactly the LP look on radar and in the sky.
        if (Config.SUPERCELL_PRECIPITATION_ENABLED.get()
                && precipitationCharacter(storm) < SupercellStructureService.lowPrecipitationThreshold()
                && hailCore(storm) < 0.45F) {
            return false;
        }
        if (isRainWrappedVortex(storm)) {
            return true;
        }
        return storm.attrib_precipitation
                || storm.isPrecipitating()
                || storm.levelCurIntensityStage >= StormObject.STATE_THUNDER
                || storm.levelCurIntensityStage >= StormObject.STATE_HAIL;
    }

    private static boolean isRainWrappedVortex(StormObject storm) {
        if (!isTornadicVortex(storm)) {
            return false;
        }
        DynamicStormState state = DynamicStormController.stateFor(storm);
        if (Config.SUPERCELL_PRECIPITATION_ENABLED.get() && state.precipitationCharacter() >= 0.0F) {
            return SupercellStructureService.isHighPrecipitation(storm, state);
        }
        return !isLowPrecipitationVortex(storm)
                && (storm.isPrecipitating()
                || storm.attrib_precipitation
                || storm.levelCurIntensityStage >= StormObject.STATE_HAIL
                || state.ffdStrength() >= 0.34F
                || state.hailCoreStrength() >= 0.28F);
    }

    private static boolean isLowPrecipitationVortex(StormObject storm) {
        if (!isTornadicVortex(storm)) {
            return false;
        }
        DynamicStormState state = DynamicStormController.stateFor(storm);
        if (Config.SUPERCELL_PRECIPITATION_ENABLED.get() && state.precipitationCharacter() >= 0.0F) {
            return SupercellStructureService.isLowPrecipitation(storm, state);
        }
        float precipitationScore = state.ffdStrength() * 0.55F
                + state.hailCoreStrength() * 0.34F
                + (storm.isPrecipitating() || storm.attrib_precipitation ? 0.22F : 0.0F)
                + (storm.levelCurIntensityStage >= StormObject.STATE_HAIL ? 0.20F : 0.0F);
        return precipitationScore < 0.36F;
    }

    /** How much rain this storm produces, 0 = dry LP, 0.5 = classic, 1 = torrential HP. */
    private static float precipitationCharacter(StormObject storm) {
        return SupercellStructureService.precipitationCharacter(storm, DynamicStormController.stateFor(storm));
    }

    private static boolean isTornadicVortex(StormObject storm) {
        return DynamicStormController.isDynamicVortex(storm);
    }

    private static Vec3 horizontalOrDefault(Vec3 vector, Vec3 fallback) {
        Vec3 candidate = vector == null ? Vec3.ZERO : new Vec3(vector.x, 0.0D, vector.z);
        if (candidate.horizontalDistanceSqr() > 1.0E-5D) {
            return candidate.normalize();
        }
        Vec3 fallbackHorizontal = fallback == null ? Vec3.ZERO : new Vec3(fallback.x, 0.0D, fallback.z);
        return fallbackHorizontal.horizontalDistanceSqr() > 1.0E-5D ? fallbackHorizontal.normalize() : new Vec3(1.0D, 0.0D, 0.0D);
    }

    private static Vec3 motionDirection(StormObject storm) {
        if (storm != null && storm.motion != null && storm.motion.horizontalDistanceSqr() > 1.0E-5D) {
            return new Vec3(storm.motion.x, 0.0D, storm.motion.z).normalize();
        }
        float angle = storm == null ? 0.0F : storm.getAdjustedAngle();
        double radians = Math.toRadians(angle);
        Vec3 fallback = new Vec3(Math.cos(radians), 0.0D, Math.sin(radians));
        return fallback.horizontalDistanceSqr() > 1.0E-5D ? fallback.normalize() : new Vec3(1.0D, 0.0D, 0.0D);
    }

    private static double stormRadius(StormObject storm) {
        // Shared with the radar so a return on the scope is the storm you can see in the sky.
        return DynamicStormController.stormPrecipRadius(storm);
    }

    /** The top of a storm's rain column, in world blocks. Shared with the volumetric rain. */
    static double topY(StormObject storm) {
        int cloudHeight = Math.max(80, ConfigMisc.Cloud_Layer0_Height);
        return Math.max(cloudHeight, storm.currentTopYBlock > 0 ? storm.currentTopYBlock : cloudHeight);
    }

    /**
     * The ground under a point, from the world's heightmap. Shared with the volumetric rain, which needs
     * one ground for the whole storm rather than one per particle.
     */
    static double floorY(ClientLevel level, double x, double z) {
        int step = Math.max(4, Config.VOLUMETRIC_DOWNFALL_HEIGHTMAP_SAMPLE_STEP_BLOCKS.get());
        int sampleX = Mth.floor(x / step) * step;
        int sampleZ = Mth.floor(z / step) * step;
        BlockPos sample = new BlockPos(sampleX, 0, sampleZ);
        if (!level.isLoaded(sample)) {
            return level.getMinBuildHeight();
        }
        return level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, sample).getY() + 0.05D;
    }

    private static float hailCore(StormObject storm) {
        if (storm.levelCurIntensityStage >= StormObject.STATE_HAIL) {
            return 1.0F;
        }
        DynamicStormState state = DynamicStormController.stateFor(storm);
        return state.hailCoreStrength();
    }

    private static double horizontalDistanceSqr(Vec3 first, Vec3 second) {
        double dx = first.x - second.x;
        double dz = first.z - second.z;
        return dx * dx + dz * dz;
    }
}
