package com.imbet.weather;

import com.imbet.registry.ModContent;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * A snow devil: a fair-weather whirl of loose snow over frozen ground.
 *
 * <p>It is a member of the same family as the dust devil and the dust devil's own class, which is what
 * gives it a body: the client draws the vortex family's volumetric column from the entity every tick, so
 * a snow devil with no entity is a snow devil with no column - which is exactly the bug that left them
 * almost invisible while every other whirl in the mod was drawn properly. What makes it a snow devil and
 * not a dust devil is its kind, read off this class in {@code AddonVortexParticleRenderer}, and its own
 * gentler numbers below.
 *
 * <p>Gentler is the physical claim, not a hedge. A snow devil is a shallow, weak circulation over a
 * surface that holds far less loose material than a desert does, so it moves what it lifts more slowly
 * and lifts less of it: the force on an entity is a third of a dust devil's per unit of wind, the lift
 * is lower, and the suction box is smaller. It still stirs the air, because a whirl that does nothing at
 * all is a decoration - and a decoration is what a snow devil was, since without an entity it had no
 * force on anything.
 */
public class SnowDevilEntity extends DustDevilEntity {

    /**
     * A hard cap on the wind reported for one.
     *
     * <p>A snow devil is a fair-weather whirl over a frozen surface, which is the quietest country this
     * mod has; one measuring a severe thunderstorm's wind would be a different phenomenon wearing its
     * name. The spawner stays well under this, so the cap is a guard rail rather than a dial.
     */
    public static final float MAX_SNOW_DEVIL_WIND_MPH = 58.0F;

    public SnowDevilEntity(EntityType<? extends SnowDevilEntity> type, Level level) {
        super(type, level);
    }

    public SnowDevilEntity(Level level, Vec3 position, int lifeTicks, float radius, float maxWindMph) {
        super(ModContent.SNOW_DEVIL_ENTITY.get(), level, position, lifeTicks, radius,
                Math.min(MAX_SNOW_DEVIL_WIND_MPH, maxWindMph));
    }

    /** How much of a dust devil's force this carries: cold, shallow air moves things slowly. */
    @Override
    protected double entityForceMultiplier() {
        return 0.34D;
    }

    /** Less lift than a dust devil's, because loose snow is heavier than the dust it is standing in. */
    @Override
    protected double liftScale() {
        return 0.62D;
    }

    /** A smaller box than a dust devil's, so the gentler force is also a narrower one. */
    @Override
    protected double suctionRangeMultiplier() {
        return 2.1D;
    }

    @Override
    protected double verticalSuctionRange() {
        return 1.9D;
    }

    /** Drifts and slows a little more slowly than a dust devil: it is a lazier circulation. */
    @Override
    protected double motionDrag() {
        return 0.992D;
    }
}
