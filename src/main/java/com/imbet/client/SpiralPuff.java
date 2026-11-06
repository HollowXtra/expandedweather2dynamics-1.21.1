package com.imbet.client;

import com.imbet.weather.DynamicStormController;
import extendedrenderer.particle.entity.EntityRotFX;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import weather2.weathersystem.storm.StormObject;

/**
 * A puff of dust that goes round.
 *
 * <p>A particle is handed one velocity when it spawns and flies in a straight line for the rest of its life, so a dust
 * cloud made of them can only ever spray outward: it looks like an explosion that never ends, however the velocities
 * are tuned. What makes a tornado's foot read as a tornado is the opposite motion. Dust is drawn in along the ground,
 * speeds up as it closes on the funnel, and climbs the wall when it gets there.
 *
 * <p>So this keeps a place in the vortex rather than a velocity, and works out where that place has got to each tick:
 * round the axis, in toward the wall, and up. The axis is the storm's own, so a moving tornado carries its dust with it
 * and a leaning one takes it along the lean as it climbs.
 */
final class SpiralPuff extends EntityRotFX {
    /** Below this height up the funnel the axis is taken as the storm's position, because the lean has not begun. */
    private static final float AXIS_FOLLOW_FROM = 0.03F;
    private static final double MIN_WALL_RADIUS = 9.0D;
    /** How far out the dust settles at the least, as a share of the damage path's whole width. */
    private static final double WIND_FIELD_SHARE = 0.22D;
    /** How much of its ceiling a puff may climb to under a funnel that has lifted off the ground. */
    private static final double LIFTED_CEILING_SHARE = 0.32D;

    private final StormObject storm;
    /** Tangential speed at the wall, in blocks a tick. Round the axis it falls off as one over the distance. */
    private final double wallSpeed;
    private final double rotationSign;
    /** How much of the gap to the wall is closed each tick. */
    private final double inflow;
    /** Rise when it has reached the wall, in blocks a tick. Out on the ground it barely leaves the surface. */
    private final double climb;
    /** How far outside the wall it settles, as a share of the wall's radius. */
    private final double wallShare;
    private final double startGap;
    private final double funnelHeight;
    private final double groundY;
    /**
     * The highest it climbs, in blocks above the ground. Without it a puff that reached the wall went on climbing for the
     * whole of its life, and round a thin funnel, where the wall is close and every puff reaches it early, the dust
     * climbed in a column all the way up to the cloud.
     */
    private final double ceiling;
    /** How far the ceiling was already lowered for a lifted funnel when this puff was raised. */
    private final double loweringAtSpawn;

    private double angle;
    private double radius;
    private double height;

    SpiralPuff(ClientLevel level, StormObject storm, double angle, double radius, double groundY, double height,
               double funnelHeight, double wallSpeed, double rotationSign, double inflow, double climb, double wallShare,
               double ceiling) {
        super(level, storm.pos.x + Math.cos(angle) * radius, groundY + height, storm.pos.z + Math.sin(angle) * radius, 0.0D, 0.0D, 0.0D);
        this.storm = storm;
        this.angle = angle;
        this.radius = radius;
        this.groundY = groundY;
        this.height = height;
        this.funnelHeight = Math.max(8.0D, funnelHeight);
        this.wallSpeed = wallSpeed;
        this.rotationSign = rotationSign;
        this.inflow = inflow;
        this.climb = climb;
        this.wallShare = wallShare;
        this.ceiling = Math.max(height, ceiling);
        this.loweringAtSpawn = lowering(storm);
        this.startGap = Math.max(1.0D, radius - wallRadiusAt(fractionUp()));
        // Nothing here moves by velocity: the position is set outright, each tick, below.
        this.xd = 0.0D;
        this.yd = 0.0D;
        this.zd = 0.0D;
    }

    @Override
    public void tick() {
        this.xd = 0.0D;
        this.yd = 0.0D;
        this.zd = 0.0D;
        super.tick();
        if (!this.isAlive()) {
            return;
        }
        float up = fractionUp();
        double wall = wallRadiusAt(up);
        // Close the gap to the wall. How far along that is decides how far along the climb is, so dust stays low while
        // it is out on the field and goes up when it arrives. The grip is the same at every distance, because a puff that
        // started further out has further to come in the same lifetime.
        radius = Math.max(0.5D, radius + (wall - radius) * inflow);
        double arrived = Mth.clamp(1.0D - (radius - wall) / startGap, 0.0D, 1.0D);
        // Under a funnel lifting off the ground there is no wall to climb, so the dust stops low and goes on turning there.
        // A puff already above that is left where it is rather than pulled down. Only the lift since it was raised counts:
        // one raised under a lifted funnel was given a low ceiling to begin with.
        double cap = ceiling * Math.min(1.0D, lowering(storm) / loweringAtSpawn);
        if (height < cap) {
            height = Math.min(cap, height + climb * (0.06D + 0.94D * arrived * arrived));
        }
        // The speed round the axis gathers inward as one over the distance, up to the wall and no further: that is the
        // shape of the wind outside any vortex, and it is why the dust visibly accelerates as it closes in.
        double speed = wallSpeed * Math.min(1.0D, Math.max(1.0D, wall) / Math.max(1.0D, radius));
        angle += rotationSign * speed / Math.max(1.5D, radius);

        double axisX = storm.pos.x;
        double axisZ = storm.pos.z;
        if (up > AXIS_FOLLOW_FROM) {
            Vec3 axis = DynamicStormController.funnelLayerCenterAt(storm, up);
            if (axis != null) {
                axisX = axis.x;
                axisZ = axis.z;
            }
        }
        this.setPos(axisX + Math.cos(angle) * radius, groundY + height, axisZ + Math.sin(angle) * radius);
    }

    /** How much of its climb dust is allowed under this storm's funnel right now: all of it on the ground, less lifted. */
    private static double lowering(StormObject storm) {
        return Mth.lerp(DynamicStormController.stateFor(storm).groundDustOffTheGround(), 1.0D, LIFTED_CEILING_SHARE);
    }

    private float fractionUp() {
        return (float) Mth.clamp(height / funnelHeight, 0.0D, 1.0D);
    }

    private double wallRadiusAt(float up) {
        // Never right in on the axis. A forming funnel is only a few blocks across, and everything drawn to it piled up into
        // one solid dark ball hanging in the air.
        // A tornado's debris cloud is as wide as its wind field, not as its condensation funnel. Held to the funnel, the dust
        // of a thin one packed into a solid dark ball round its foot.
        double windField = DynamicStormController.stateFor(storm).damagePathWidth() * WIND_FIELD_SHARE;
        return Math.max(Math.max(MIN_WALL_RADIUS, windField), DynamicStormController.funnelWallRadiusAt(storm, up)) * wallShare;
    }
}
