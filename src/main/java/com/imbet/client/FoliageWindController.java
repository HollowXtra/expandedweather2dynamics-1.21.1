package com.imbet.client;

import com.imbet.Config;
import com.mojang.blaze3d.shaders.Uniform;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Drives the foliage sway shader: how hard the wind is blowing, and what has been walking through the grass.
 *
 * <p>The sway itself is done by the modified cutout vertex shaders, which bend the plants the mixin has
 * marked. This class only measures and hands over: it samples the wind once a tick, smooths it, and pushes
 * the numbers the shader needs as uniforms. Pushing them is safe at any point in the frame, because a
 * uniform in this version only records its value and marks itself dirty - it is uploaded when the shader is
 * next applied.
 */
public final class FoliageWindController {

    /** How many tracks can lie grass over at once. The shader has exactly this many slots. */
    private static final int TRAIL_SLOTS = 4;

    /** The slowest movement that lays grass over, in blocks per tick. Something standing still leaves nothing. */
    private static final double TRAIL_MIN_SPEED = 0.02D;

    /** How much of a mover's speed becomes print strength. A player walking lands just under three quarters. */
    private static final double TRAIL_POWER_PER_SPEED = 3.4D;

    /** How far above or below the player something can be and still be standing in the same grass. */
    private static final double TRAIL_VERTICAL_RANGE = 3.0D;

    /** A wind speed in mph as the blocks per second the gust bands travel at. One block is one metre. */
    private static final double BLOCKS_PER_SECOND_PER_MPH = 1.0D / 2.23694D;

    /**
     * How hard the foliage answers to wind speed, as an exponent on the normalized wind.
     *
     * <p>A plant answers to a change in the wind far more than it answers to the wind itself: it takes a
     * gale to lay a meadow flat, but it takes almost nothing to make it move, and it is the moving that a
     * player actually sees. A straight line against the thrash speed leaves a real breeze - the weather most
     * of a session is spent in - at a fifth of the travel, which is under two centimetres at the tip and
     * reads as frozen. This exponent is well under 1 for that reason: it lifts the low end hard so ordinary
     * weather moves the field, while staying monotone and landing on exactly the ceiling at the thrash speed,
     * so a storm still scales the motion up and the tips still cannot pass the configured maximum. The price
     * is dynamic range - a gale is no longer several times a breeze - and it is the right price, because the
     * alternative is an effect that only appears inside a tornado.
     */
    private static final float WIND_RESPONSE = 0.45F;

    private static Vec3 smoothedWind = Vec3.ZERO;
    private static float smoothedStrength;
    private static float smoothedGust;
    private static float smoothedMph;

    /** One print per mover, keyed by entity id, so a track follows the entity that made it. */
    private static final Map<Integer, TrailPrint> TRAIL_PRINTS = new HashMap<>();

    /** The strongest prints, rebuilt once a tick and pushed to the shader every frame. */
    private static List<TrailPrint> activeTrailPrints = List.of();

    private FoliageWindController() {
    }

    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null || !enabled()) {
            smoothedWind = smoothedWind.scale(0.82D);
            smoothedStrength = Mth.lerp(0.18F, smoothedStrength, 0.0F);
            smoothedGust = Mth.lerp(0.18F, smoothedGust, 0.0F);
            smoothedMph = Mth.lerp(0.18F, smoothedMph, 0.0F);
            TRAIL_PRINTS.clear();
            activeTrailPrints = List.of();
            return;
        }

        WindCandidate target = sampleWind(minecraft.level, minecraft.player);
        float smoothing = Mth.clamp(Config.FOLIAGE_WIND_SMOOTHING.get().floatValue(), 0.001F, 1.0F);
        smoothedWind = smoothedWind.lerp(target.wind(), smoothing);
        smoothedStrength = Mth.lerp(smoothing, smoothedStrength, target.strength());
        smoothedGust = Mth.lerp(smoothing * 0.65F, smoothedGust, target.gust());
        smoothedMph = Mth.lerp(smoothing, smoothedMph, (float) target.wind().length());
        updateTrailPrints(minecraft.level, minecraft.player);
    }

    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_SKY) {
            return;
        }
        // Iris runs every level stage again for its shadow map; this belongs to the visible frame only.
        if (RenderingCompat.isRenderingShadowPass()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || !enabled()) {
            applyUniforms(0.0F, Vec3.ZERO, 0.0F, 0.0F, Vec3.ZERO);
            return;
        }
        float time = (minecraft.level.getGameTime() + event.getPartialTick().getGameTimeDeltaPartialTick(false)) / 20.0F;
        applyUniforms(time, smoothedWind, smoothedStrength, smoothedGust, event.getCamera().getPosition());
    }

    public static boolean enabled() {
        if (!Config.VISUALS_ENABLED.get() || !Config.FOLIAGE_WIND_SHADER.get()) {
            return false;
        }
        // Sodium renders chunks with its own shaders (our rendertype_cutout override never runs on them),
        // and an active Iris shaderpack replaces the vanilla core programs the sway shader hooks into.
        return !RenderingCompat.isSodiumLoaded() && !RenderingCompat.isShaderPackActive();
    }

    /**
     * The wind this shader should sway leaves with.
     *
     * <p>The reading itself comes from {@link ClientWindSampler}, the same one the ocean's waves read, so the
     * leaves and the sea cannot disagree about the wind; this only converts it into the normalized strength
     * and gust the sway program wants, against this feature's own thrash threshold, and mixes in the Physics
     * mod's sample when that is present.
     */
    private static WindCandidate sampleWind(ClientLevel level, Player player) {
        Vec3 playerPos = player.position();
        WindCandidate best = fromWind(ClientWindSampler.sample(level, playerPos, Config.FOLIAGE_WIND_RENDER_RANGE_BLOCKS.get()));
        return best.withPhysicsSample(PhysicsModFoliageBridge.sample(level, playerPos, Config.FOLIAGE_WIND_THRASH_MPH.get().floatValue()));
    }

    private static WindCandidate fromWind(ClientWindSampler.Wind wind) {
        float thrash = Math.max(1.0F, Config.FOLIAGE_WIND_THRASH_MPH.get().floatValue());
        float baseline = Mth.clamp(Config.FOLIAGE_WIND_BASELINE_MPH.get().floatValue(), 0.0F, 60.0F);
        double sampled = wind == null ? 0.0D : wind.speedMph();
        if (sampled <= 0.01D && baseline <= 0.01F) {
            return WindCandidate.CALM;
        }
        // The baseline is a floor under the reading, not a wind of its own: Weather2 already knows what the
        // ambient air is doing and the sampler reads it, so on any day with a real breeze that reading stands
        // and this changes nothing. It only takes over when the world is genuinely still - and it has to
        // exist, because a leaf sway scaled by a wind of zero does not move at all, which is what makes the
        // whole feature look broken in calm weather rather than merely quiet.
        double speed = Math.max(sampled, baseline);
        Vec3 vector = wind != null && sampled > 0.01D && wind.vectorMph().horizontalDistanceSqr() > 1.0E-6D
                ? wind.vectorMph().normalize().scale(speed)
                : new Vec3(1.0D, 0.0D, 0.0D).scale(speed);
        float strength = (float) Math.pow(Mth.clamp((float) (speed / thrash), 0.0F, 1.0F), WIND_RESPONSE);
        float gust = Mth.clamp((float) ((speed - 18.0D) / Math.max(1.0F, thrash - 18.0F)), 0.0F, 1.0F);
        return new WindCandidate(vector, strength, gust);
    }

    /**
     * Ages every print and refreshes the ones something is currently pushing through.
     *
     * <p>A print is not "the entity is here": it is the mark left in the grass. Each tick every print loses a
     * little of its strength, which is the grass standing back up, and anything moving through the grass now
     * stamps itself back to the strength its speed earns. That is what makes a path close behind you over
     * about a second instead of snapping straight the moment you step out of it.
     *
     * <p>Only the strongest few prints are sent on. The shader has a fixed number of slots - uniforms are not
     * free, and a field is not made better by tracking the two hundredth mob - so the ones that win are the
     * ones laying the grass over hardest, which in practice are the ones nearest the camera.
     */
    private static void updateTrailPrints(ClientLevel level, Player player) {
        if (!Config.FOLIAGE_WIND_TRAIL.get()) {
            TRAIL_PRINTS.clear();
            activeTrailPrints = List.of();
            return;
        }

        float decay = (float) Math.exp(-1.0D / Math.max(1, Config.FOLIAGE_WIND_TRAIL_LINGER_TICKS.get()));
        TRAIL_PRINTS.values().forEach(print -> print.power *= decay);
        TRAIL_PRINTS.entrySet().removeIf(entry -> entry.getValue().power < 0.02F);

        double range = Config.FOLIAGE_WIND_TRAIL_RANGE_BLOCKS.get();
        double rangeSqr = range * range;
        float radius = Config.FOLIAGE_WIND_TRAIL_RADIUS_BLOCKS.get().floatValue();
        Vec3 playerPos = player.position();

        for (Entity entity : level.entitiesForRendering()) {
            if (entity == null || !entity.isAlive()) {
                continue;
            }
            Vec3 motion = entity.getDeltaMovement();
            double speed = Math.sqrt(motion.x * motion.x + motion.z * motion.z);
            if (speed < TRAIL_MIN_SPEED) {
                continue;
            }
            Vec3 position = entity.position();
            if (Math.abs(position.y - playerPos.y) > TRAIL_VERTICAL_RANGE) {
                continue;
            }
            double dx = position.x - playerPos.x;
            double dz = position.z - playerPos.z;
            if (dx * dx + dz * dz > rangeSqr) {
                continue;
            }
            // Wider things part more grass. A player is the baseline; a horse or a ravager leaves a swathe.
            double width = Math.max(1.0D, entity.getBbWidth());
            float power = (float) Mth.clamp(speed * TRAIL_POWER_PER_SPEED * width, 0.15D, 1.0D);

            TrailPrint print = TRAIL_PRINTS.computeIfAbsent(entity.getId(), id -> new TrailPrint());
            print.x = position.x;
            print.z = position.z;
            print.directionX = motion.x / speed;
            print.directionZ = motion.z / speed;
            print.radius = radius * width;
            print.power = Math.max(print.power, power);
        }

        if (TRAIL_PRINTS.isEmpty()) {
            activeTrailPrints = List.of();
            return;
        }
        List<TrailPrint> prints = new ArrayList<>(TRAIL_PRINTS.values());
        prints.sort(Comparator.comparingDouble((TrailPrint print) -> -print.power));
        activeTrailPrints = prints.size() > TRAIL_SLOTS ? prints.subList(0, TRAIL_SLOTS) : prints;
    }

    /** How fast the gust bands cross the ground: the wind's own speed, so a gust travels with the air that made it. */
    private static float gustSpeedBlocksPerSecond() {
        double scale = Math.max(0.0D, Config.FOLIAGE_WIND_GUST_SPEED_SCALE.get());
        return (float) Mth.clamp(smoothedMph * BLOCKS_PER_SECOND_PER_MPH * scale, 0.0D, 16.0D);
    }

    private static void applyUniforms(float time, Vec3 wind, float strength, float gust, Vec3 camera) {
        ShaderInstance cutout = GameRenderer.getRendertypeCutoutShader();
        ShaderInstance cutoutMipped = GameRenderer.getRendertypeCutoutMippedShader();
        applyUniforms(cutout, time, wind, strength, gust, camera);
        applyUniforms(cutoutMipped, time, wind, strength, gust, camera);
    }

    private static void applyUniforms(ShaderInstance shader, float time, Vec3 wind, float strength, float gust, Vec3 camera) {
        if (shader == null) {
            return;
        }
        Vec3 horizontal = new Vec3(wind.x, 0.0D, wind.z);
        if (horizontal.horizontalDistanceSqr() > 1.0E-5D) {
            horizontal = horizontal.normalize();
        } else {
            horizontal = new Vec3(1.0D, 0.0D, 0.0D);
        }
        setUniform(shader, "FoliageWindVector", (float) horizontal.x, (float) horizontal.z);
        setUniform(shader, "FoliageWindStrength", Mth.clamp(strength, 0.0F, 1.0F));
        setUniform(shader, "FoliageWindTime", time);
        setUniform(shader, "FoliageWindMaxOffset", Config.FOLIAGE_WIND_MAX_OFFSET_BLOCKS.get().floatValue());
        setUniform(shader, "FoliageWindGust", Mth.clamp(gust, 0.0F, 1.0F));
        setUniform(shader, "FoliageGustWavelength", Config.FOLIAGE_WIND_GUST_WAVELENGTH_BLOCKS.get().floatValue());
        setUniform(shader, "FoliageGustSpeed", gustSpeedBlocksPerSecond());
        setUniform(shader, "FoliageRestLean", Mth.clamp(Config.FOLIAGE_WIND_REST_LEAN.get().floatValue(), 0.0F, 1.0F));
        setUniform(shader, "FoliageSkyFloor", Mth.clamp(Config.FOLIAGE_WIND_SKY_FLOOR.get().floatValue(), 0.0F, 1.0F));
        // Handed over in degrees because that is how the knob is written; the shader converts.
        setUniform(shader, "FoliageWaveLag",
                Mth.clamp(Config.FOLIAGE_WIND_WAVE_LAG_DEGREES_PER_BLOCK.get().floatValue(), 0.0F, 360.0F));
        // The shader is handed the frame's camera because chunk vertices arrive relative to it: without this
        // the gust bands and every plant's identity would be nailed to the camera and would travel with you.
        setUniform(shader, "FoliageWorldAnchor", (float) camera.x, (float) camera.y, (float) camera.z);
        float trailStrength = Config.FOLIAGE_WIND_TRAIL.get()
                ? Mth.clamp(Config.FOLIAGE_WIND_TRAIL_STRENGTH.get().floatValue(), 0.0F, 1.5F) : 0.0F;
        setUniform(shader, "FoliageTrailStrength", trailStrength);
        for (int slot = 0; slot < TRAIL_SLOTS; slot++) {
            TrailPrint print = slot < activeTrailPrints.size() ? activeTrailPrints.get(slot) : null;
            if (print == null) {
                setUniform(shader, "FoliageTrailP" + slot, 0.0F, 0.0F, 0.0F, 0.0F);
                setUniform(shader, "FoliageTrailD" + slot, 0.0F, 0.0F);
                continue;
            }
            setUniform(shader, "FoliageTrailP" + slot, (float) print.x, (float) print.z, (float) print.radius, print.power);
            setUniform(shader, "FoliageTrailD" + slot, (float) print.directionX, (float) print.directionZ);
        }
    }

    private static void setUniform(ShaderInstance shader, String name, float value) {
        Uniform uniform = shader.getUniform(name);
        if (uniform != null) {
            uniform.set(value);
        }
    }

    private static void setUniform(ShaderInstance shader, String name, float first, float second) {
        Uniform uniform = shader.getUniform(name);
        if (uniform != null) {
            uniform.set(first, second);
        }
    }

    private static void setUniform(ShaderInstance shader, String name, float first, float second, float third) {
        Uniform uniform = shader.getUniform(name);
        if (uniform != null) {
            uniform.set(first, second, third);
        }
    }

    private static void setUniform(ShaderInstance shader, String name, float first, float second, float third, float fourth) {
        Uniform uniform = shader.getUniform(name);
        if (uniform != null) {
            uniform.set(first, second, third, fourth);
        }
    }

    /**
     * The mark left in the grass where something is pushing through it.
     *
     * <p>The direction is the mover's own, not the direction away from it: grass is laid over in the
     * direction of travel, so what is behind you reads as a path rather than a crater.
     */
    private static final class TrailPrint {
        private double x;
        private double z;
        private double directionX;
        private double directionZ;
        private double radius;
        private float power;
    }

    private record WindCandidate(Vec3 wind, float strength, float gust) {
        private static final WindCandidate CALM = new WindCandidate(Vec3.ZERO, 0.0F, 0.0F);

        private WindCandidate withPhysicsSample(PhysicsModFoliageBridge.PhysicsWindSample sample) {
            if (sample == null || sample.strength() <= 0.001F || sample.wind().horizontalDistanceSqr() <= 1.0E-6D) {
                return this;
            }
            if (strength <= 0.001F || wind.horizontalDistanceSqr() <= 1.0E-6D) {
                return new WindCandidate(sample.wind(), sample.strength(), sample.gust());
            }
            Vec3 weatherDir = wind.normalize();
            Vec3 physicsDir = sample.wind().normalize();
            Vec3 mixedDir = weatherDir.scale(0.58D).add(physicsDir.scale(0.42D));
            if (mixedDir.horizontalDistanceSqr() <= 1.0E-6D) {
                mixedDir = weatherDir;
            } else {
                mixedDir = mixedDir.normalize();
            }
            double speed = Math.max(wind.length(), sample.wind().length() * 0.65D);
            return new WindCandidate(mixedDir.scale(speed), Math.max(strength, sample.strength() * 0.85F), Math.max(gust, sample.gust()));
        }
    }
}
