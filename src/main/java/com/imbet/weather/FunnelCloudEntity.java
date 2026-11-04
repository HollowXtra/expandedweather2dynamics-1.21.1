package com.imbet.weather;

import com.imbet.Config;
import com.imbet.registry.ModContent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * A funnel cloud: a rotating condensation funnel that hangs under a cumulus or thunderstorm base
 * with no circulation on the ground at all.
 *
 * <p>This is the other half of the family the mod's tornadoes belong to. A tornado is defined by
 * having its circulation <em>at the surface</em>, whether or not the condensation reaches down; a
 * funnel cloud is the same vortex with nothing on the ground yet, which is why one can look
 * identical to the other and only the ground tells them apart. The cheapest, commonest kind is a
 * cold-air funnel: a spring or autumn shower, weak instability, a funnel that spins for a couple of
 * minutes under the shower base and rots away without ever touching down.
 *
 * <p>Everything about this entity is arranged so the ground is genuinely untouched rather than
 * merely spared:
 *
 * <ul>
 *   <li>its Y is clamped {@link Config#FUNNEL_CLOUD_TIP_ALTITUDE_BLOCKS} above the terrain, and the
 *       client hangs the funnel from there upward, so the ragged tip simply stops in mid-air;
 *   <li>every force this class could exert is multiplied by zero - no entity impulse, no suction,
 *       no block pickup - because a surface circulation is exactly what it does not have; and
 *   <li>its event is exempt from the per-tick surface snap, so the vortex the instruments and the
 *       radar sample is the one in the air, not an imaginary one on the ground beneath it.
 * </ul>
 */
public class FunnelCloudEntity extends DustDevilEntity {
    /**
     * Cold-air funnels are weak: they can look violent and still do nothing at all.
     *
     * <p>The ceiling is a hard cap on the wind reported for one, because a funnel cloud that
     * measured 150 mph on the ground would be a tornado by definition.
     */
    public static final float MAX_FUNNEL_CLOUD_WIND_MPH = 78.0F;

    public FunnelCloudEntity(EntityType<? extends FunnelCloudEntity> type, Level level) {
        super(type, level);
    }

    public FunnelCloudEntity(Level level, Vec3 position, int lifeTicks, float radius, float maxWindMph) {
        super(ModContent.FUNNEL_CLOUD_ENTITY.get(), level, position, lifeTicks, radius,
                Math.min(MAX_FUNNEL_CLOUD_WIND_MPH, maxWindMph));
    }

    /**
     * Holds the vortex at its hanging altitude.
     *
     * <p>The band is above the configured tip so a funnel cloud cannot be dragged down by terrain it
     * drifts over - a mountain under it does not put it on the ground, it puts it closer to the
     * ground, and the clamp is what keeps even that from reaching the surface.
     */
    @Override
    protected void clampToBoundary(ServerLevel level) {
        double tip = Math.max(24.0D, Config.FUNNEL_CLOUD_TIP_ALTITUDE_BLOCKS.get());
        clampYAboveTerrain(level, tip, tip + 48.0D);
    }

    /**
     * No surface force of any kind.
     *
     * <p>Impulses on entities are {@code wind * falloff * horizontalForceScale * entityForceMultiplier},
     * so zeroing the pair zeroes the whole term while leaving the base class's bookkeeping intact;
     * the suction box is collapsed for the same reason, so there is nothing to iterate over.
     */
    @Override
    protected double entityForceMultiplier() {
        return 0.0D;
    }

    @Override
    protected double horizontalForceScale() {
        return 0.0D;
    }

    @Override
    protected double verticalSuctionRange() {
        return 0.0D;
    }

    @Override
    protected double suctionRangeMultiplier() {
        return 0.0D;
    }

    /** Hangs almost still: a funnel cloud drifts with the shower it belongs to. */
    @Override
    protected double motionDrag() {
        return 0.9985D;
    }
}
