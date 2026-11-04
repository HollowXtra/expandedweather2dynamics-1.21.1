package com.imbet.weather;

import com.imbet.Config;
import com.imbet.network.LowPressureSyncPayload.LowPressureSnapshot;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

/**
 * A real synoptic low: a broad cyclonic circulation with two fronts of its own, born out of a
 * baroclinic zone and steered by the same atmospheric flow the storms ride.
 *
 * <p>Before this, "low pressure" was a summary of whatever storms happened to be clustered within
 * 1425 blocks of each other: it had no life, no motion of its own and no weather of its own, and in
 * a calm region there was nothing to summarise. A low now has all four phases a real one has -
 * genesis, deepening, occlusion and decay - and the warm and cold front it publishes move, pinch and
 * shorten with it, which is why the forecaster can plot a warm sector rather than two fixed arms.
 *
 * <p>The life cycle is deliberately measured in world ticks and the pressure deficit the barometer
 * reads comes from {@link #intensity()}, so the instrument, the plate and the forecast readout all
 * describe the same system.
 */
public final class SynopticLow {
    /** Of the life: deepening takes the first third, maturity runs to the occlusion point. */
    private static final float DEEPENING_FRACTION = 0.30F;
    /** After this, the cold front has caught the warm one and the warm sector pinches shut. */
    private static final float OCCLUSION_FRACTION = 0.58F;
    /**
     * Drift is clamped to an honest band for a synoptic system: one block is one metre, so at twenty
     * ticks a second this is 2.2 to 12.5 mph. A low rides the deep-layer flow slower than the
     * thunderstorms it organises, and the clamp is what keeps a gale-force cloud wind from turning it
     * into a race car.
     */
    private static final double MIN_MOTION_BLOCKS_PER_TICK = 0.050D;
    private static final double MAX_MOTION_BLOCKS_PER_TICK = 0.280D;
    /** A low turns and changes speed far more slowly than a thunderstorm does. */
    private static final double MOTION_RESPONSE_PER_100_TICKS = 0.06D;

    /** The warm front leads the circulation; the cold front trails it. Angles are from the heading. */
    public static final float WARM_FRONT_ANGLE_DEGREES = 42.0F;
    public static final float COLD_FRONT_ANGLE_DEGREES = 148.0F;
    /** Occlusion swings the warm front back into the cold one and pinches the warm sector shut. */
    private static final float OCCLUDED_WARM_FRONT_ANGLE_DEGREES = 118.0F;
    private static final float OCCLUDED_COLD_FRONT_ANGLE_DEGREES = 166.0F;
    /** Front lengths as a fraction of the circulation radius, before and during occlusion. */
    private static final float WARM_FRONT_FRACTION = 0.85F;
    private static final float OCCLUDED_WARM_FRONT_FRACTION = 0.30F;
    private static final float COLD_FRONT_FRACTION = 1.05F;
    private static final float OCCLUDED_COLD_FRONT_FRACTION = 1.22F;

    private final long id;
    private final long seed;
    private double x;
    private double z;
    private double motionX;
    private double motionZ;
    private float radius;
    private float intensity;
    private float peakIntensity;
    private float environmentFactor = 1.0F;
    /** The belt this low was born in. Readout only: it never changes what the low does. */
    private String bornIn = "";
    private int ageTicks;
    private int lifeTicks;

    private SynopticLow(long id, long seed, double x, double z, double motionX, double motionZ, float peakIntensity, int lifeTicks) {
        this.id = id;
        this.seed = seed;
        this.x = x;
        this.z = z;
        this.motionX = motionX;
        this.motionZ = motionZ;
        this.peakIntensity = Mth.clamp(peakIntensity, 0.12F, 1.0F);
        this.lifeTicks = Math.max(1200, lifeTicks);
        this.radius = 560.0F;
        this.intensity = this.peakIntensity * 0.18F;
    }

    /**
     * A low being born. {@code energy} is the environment score that allowed it (0..1): a stronger
     * baroclinic zone is a deeper low and a longer-lived one, and the jitter keeps two lows in the
     * same air mass from being twins.
     */
    public static SynopticLow born(long id, Vec3 position, Vec3 heading, double energy, int baseLifeTicks, RandomSource random) {
        double directionX = heading == null || heading.horizontalDistanceSqr() <= 1.0E-9D ? 0.923D : heading.x;
        double directionZ = heading == null || heading.horizontalDistanceSqr() <= 1.0E-9D ? -0.382D : heading.z;
        double length = Math.sqrt(directionX * directionX + directionZ * directionZ);
        double speed = MIN_MOTION_BLOCKS_PER_TICK + (MAX_MOTION_BLOCKS_PER_TICK - MIN_MOTION_BLOCKS_PER_TICK) * 0.35D;
        float peak = (float) Mth.clamp(0.22D + energy * 0.86D, 0.22D, 1.0D);
        int life = (int) (baseLifeTicks * (0.78D + random.nextDouble() * 0.55D));
        return new SynopticLow(
                id,
                random.nextLong(),
                position.x,
                position.z,
                directionX / length * speed,
                directionZ / length * speed,
                peak,
                life
        );
    }

    public long id() {
        return id;
    }

    public double x() {
        return x;
    }

    public double z() {
        return z;
    }

    public Vec3 position() {
        return new Vec3(x, 0.0D, z);
    }

    public Vec3 motion() {
        return new Vec3(motionX, 0.0D, motionZ);
    }

    public float radius() {
        return radius;
    }

    public float intensity() {
        return intensity;
    }

    public int ageTicks() {
        return ageTicks;
    }

    public int lifeTicks() {
        return lifeTicks;
    }

    public boolean finished() {
        return ageTicks >= lifeTicks;
    }

    public void setEnvironmentFactor(float factor) {
        environmentFactor = (float) Mth.clamp(factor, 0.35D, 1.35D);
    }

    public float environmentFactor() {
        return environmentFactor;
    }

    /** The belt this low was born in, for a readout. Empty when it was not recorded. */
    public String bornIn() {
        return bornIn == null ? "" : bornIn;
    }

    /**
     * Records the belt of birth. The belt is a property of where the system came from, not something it
     * carries with it: a low that drifts out of the corridor is still the low the corridor made, which
     * is why this is set once and never recomputed from its current latitude.
     */
    public void setBornIn(String belt) {
        bornIn = belt == null ? "" : belt;
    }

    /** 0 while the warm sector is still open, 1 once the cold front has fully caught the warm one. */
    public float occlusionProgress() {
        float progress = ageTicks / (float) lifeTicks;
        return Mth.clamp((progress - OCCLUSION_FRACTION) / (1.0F - OCCLUSION_FRACTION), 0.0F, 1.0F);
    }

    public boolean occluded() {
        return occlusionProgress() > 0.0F;
    }

    public float warmFrontAngleDegrees() {
        return Mth.lerp(occlusionProgress(), WARM_FRONT_ANGLE_DEGREES, OCCLUDED_WARM_FRONT_ANGLE_DEGREES);
    }

    public float coldFrontAngleDegrees() {
        return Mth.lerp(occlusionProgress(), COLD_FRONT_ANGLE_DEGREES, OCCLUDED_COLD_FRONT_ANGLE_DEGREES);
    }

    /** The warm front is what occlusion eats: as the cold front overtakes it, it is cut back. */
    public float warmFrontLengthBlocks() {
        return radius * Mth.lerp(occlusionProgress(), WARM_FRONT_FRACTION, OCCLUDED_WARM_FRONT_FRACTION);
    }

    public float coldFrontLengthBlocks() {
        return radius * Mth.lerp(occlusionProgress(), COLD_FRONT_FRACTION, OCCLUDED_COLD_FRONT_FRACTION);
    }

    public String label() {
        float strength = intensity;
        if (occluded()) {
            return strength >= 0.72F ? "Occluded Cyclone" : "Occluded Low";
        }
        if (strength >= 0.80F) {
            return "Cyclone Low";
        }
        return strength >= 0.52F ? "Deep Low" : "Low";
    }

    /**
     * One step of the life cycle. Motion is integrated over the ticks that actually elapsed, so a
     * low drifts the same distance whether the scan runs every two seconds or every minute, and the
     * heading only turns as fast as the steering flow it is riding.
     */
    public void tick(long gameTime, Vec3 steering, int elapsedTicks) {
        int elapsed = Math.max(1, elapsedTicks);
        ageTicks += elapsed;

        if (steering != null && steering.horizontalDistanceSqr() > 1.0E-9D) {
            Vec3 heading = new Vec3(steering.x, 0.0D, steering.z).normalize();
            heading = rotate(heading, meanderDegrees(gameTime));
            double speed = Mth.clamp(
                    steering.horizontalDistance() * Config.LOW_PRESSURE_STEERING_COEFFICIENT.get(),
                    MIN_MOTION_BLOCKS_PER_TICK,
                    MAX_MOTION_BLOCKS_PER_TICK
            );
            Vec3 target = heading.scale(speed);
            Vec3 current = new Vec3(motionX, 0.0D, motionZ);
            double response = Mth.clamp(MOTION_RESPONSE_PER_100_TICKS * elapsed / 100.0D, 0.0D, 0.5D);
            Vec3 smoothed = current.horizontalDistanceSqr() <= 1.0E-9D ? target : current.lerp(target, response);
            motionX = smoothed.x;
            motionZ = smoothed.z;
        }
        x += motionX * elapsed;
        z += motionZ * elapsed;

        float progress = Mth.clamp(ageTicks / (float) lifeTicks, 0.0F, 1.0F);
        float shape;
        if (progress < DEEPENING_FRACTION) {
            shape = progress / DEEPENING_FRACTION;
        } else if (progress < OCCLUSION_FRACTION) {
            shape = 1.0F;
        } else {
            // Filling: the system loses most of its depth as the occlusion runs its course.
            shape = 1.0F - 0.88F * (progress - OCCLUSION_FRACTION) / (1.0F - OCCLUSION_FRACTION);
        }
        float target = Mth.clamp(peakIntensity * shape * environmentFactor, 0.04F, 1.0F);
        intensity = Mth.lerp((float) Mth.clamp(0.09D * elapsed / 100.0D, 0.0D, 0.35D), intensity, target);

        // The circulation widens as the low deepens and as its centre fills with cloud.
        radius = Mth.clamp(560.0F + intensity * 1500.0F + (occluded() ? 120.0F : 0.0F), 560.0F, 2600.0F);
    }

    public LowPressureSnapshot snapshot() {
        return new LowPressureSnapshot(
                id,
                x,
                z,
                radius,
                intensity,
                motionX,
                motionZ,
                warmFrontLengthBlocks(),
                coldFrontLengthBlocks(),
                warmFrontAngleDegrees(),
                coldFrontAngleDegrees(),
                occluded(),
                label()
        );
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putLong("Id", id);
        tag.putLong("Seed", seed);
        tag.putDouble("X", x);
        tag.putDouble("Z", z);
        tag.putDouble("MotionX", motionX);
        tag.putDouble("MotionZ", motionZ);
        tag.putFloat("Radius", radius);
        tag.putFloat("Intensity", intensity);
        tag.putFloat("Peak", peakIntensity);
        tag.putFloat("Environment", environmentFactor);
        tag.putInt("Age", ageTicks);
        tag.putInt("Life", lifeTicks);
        if (!bornIn.isEmpty()) {
            tag.putString("BornIn", bornIn);
        }
        return tag;
    }

    public static SynopticLow load(CompoundTag tag) {
        SynopticLow low = new SynopticLow(
                tag.getLong("Id"),
                tag.getLong("Seed"),
                tag.getDouble("X"),
                tag.getDouble("Z"),
                tag.getDouble("MotionX"),
                tag.getDouble("MotionZ"),
                tag.getFloat("Peak"),
                tag.getInt("Life")
        );
        low.radius = Mth.clamp(tag.getFloat("Radius"), 560.0F, 2600.0F);
        low.intensity = Mth.clamp(tag.getFloat("Intensity"), 0.0F, 1.0F);
        low.environmentFactor = (float) Mth.clamp(tag.getFloat("Environment") == 0.0F ? 1.0F : tag.getFloat("Environment"), 0.35D, 1.35D);
        low.ageTicks = Math.max(0, tag.getInt("Age"));
        low.bornIn = tag.getString("BornIn");
        return low;
    }

    /** A very slow heading wobble, so a low arcs across the map the way a real one does. */
    private double meanderDegrees(long gameTime) {
        double phase = seed * 0.173D;
        return Math.sin(gameTime * 1.2E-4D + phase) * 26.0D + Math.sin(gameTime * 3.1E-5D + phase * 0.61D) * 14.0D;
    }

    private static Vec3 rotate(Vec3 vector, double degrees) {
        double radians = Math.toRadians(degrees);
        double cos = Math.cos(radians);
        double sin = Math.sin(radians);
        return new Vec3(vector.x * cos - vector.z * sin, 0.0D, vector.x * sin + vector.z * cos);
    }
}
