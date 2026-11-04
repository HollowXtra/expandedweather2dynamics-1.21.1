package com.imbet.client;

import com.imbet.Config;
import com.imbet.weather.DynamicStormController;
import extendedrenderer.particle.entity.EntityRotFX;
import weather2.weathersystem.storm.StormObject;

import java.util.List;

/**
 * Turns Weather2's own cloud and dust swarms the opposite way for an anti-cyclonic storm.
 *
 * <p>The funnel was already ours: {@code TornadoFunnelSimpleMixin} neutralises Weather2's hard-coded
 * cyclonic layer spin and re-spins it from {@link DynamicStormController#rotationSign}. What was never
 * ours is everything the tornado is wrapped in. Weather2 keeps two swarms of {@code EntityRotFX} - the
 * cloud mass in {@code listParticlesCloud}, which for a tornado is the rotating deck and the lowered
 * wall cloud under it, and the dust in {@code listParticlesGround} - and it turns both from inside
 * {@code StormObject.tickClient()}:
 *
 * <pre>
 *     if (this.isSpinning()) {
 *         double speed = this.spinSpeed + rand.nextDouble() * 0.01;
 *         float angle = (float)(Math.atan2(vecZ, vecX) * 180.0 / Math.PI);
 *         angle = (float)((double)angle + speed * 50.0);      // ...plus per-particle jitter
 *         if (curSpeed &lt; speed * 20.0) {
 *             ent.setMotionX(ent.getMotionX() + -Math.sin(toRadians(angle)) * speed);
 *             ent.setMotionZ(ent.getMotionZ() +  Math.cos(toRadians(angle)) * speed);
 *         }
 *     }
 * </pre>
 *
 * <p>That direction is the sign of {@code spinSpeed}, and there is the problem: the same method rewrites
 * that field from the storm's stage at its own top - {@code spinSpeed = 0.02}, then {@code spinSpeedMax *
 * 0.2} for a tornado, plus {@code 0.1} for a hurricane, all positive - long before the loops read it,
 * while this mod's signed value is written at the method's RETURN. So no matter what direction the storm
 * is assigned, the swarms have always been swirled Weather2's way while the funnel turned ours.
 *
 * <p>Signing that read is not the fix, which is worth stating plainly because it is the obvious thing to
 * try. The value read there is also the loop's own guard - {@code curSpeed &lt; speed * 20.0} - so a
 * negative read makes that comparison false for every particle, and a signed read would not mirror the
 * swirl, it would stop it dead. The ground loop is harder still: its amplitude is
 * {@code Math.max(0.2, 5.0 * spinSpeed)}, a positive floor, so a signed read is replaced by a constant
 * before it can turn anything.
 *
 * <p>What works is a conjugation, and the reason it works is that these two loops only ever <em>add
 * impulses</em>. They set motion; they never move a particle. Nothing in either loop calls
 * {@code setPosition}, {@code onUpdate} or the like - the client's own particle engine integrates that
 * motion, between one storm tick and the next. So a reflection applied immediately before the loop and
 * again immediately after it sees the same positions on both sides (the storm's own drift is absorbed by
 * using one recorded centre for both halves), which makes the pairing exact: whatever motion the swarm
 * arrived with is reflected back out unchanged, and the only thing left mirrored is the impulse the loop
 * added. Each tick the swarm therefore gains the mirror of the kick it would have received, and since
 * every magnitude, guard, jitter and damping term is untouched, it builds the same speed in the other
 * direction. The guard's cap behaves identically because the reflection preserves the magnitude it
 * measures.
 *
 * <p>Only these two lists. Block debris is deliberately left alone: it is moved by Weather2's
 * {@code spinObject}, which this mod already drives from its own signed wind field, so mirroring it here
 * as well would flip it straight back to the cyclonic sense.
 *
 * <p>It runs only where there is a swirl in the first place. A storm's rotation is only ever assigned at
 * Weather2's HAIL stage or on tornado formation, both of which are past the stage its own
 * {@code isSpinning()} gate starts at, so an anti-cyclonic storm is always already spinning by the time
 * this can apply.
 */
public final class StormSwirlMirror {
    /**
     * The storm whose closing half is still owed, and the centre its reflection lines were built from.
     * A client tick is single-threaded and one storm's tick cannot nest inside another's, so a single
     * bracket at a time is all there can ever be.
     */
    private static StormObject bracketed;
    private static double centreX;
    private static double centreZ;

    private StormSwirlMirror() {
    }

    /**
     * Mirrors the swarms into the frame Weather2's own tick expects to find, and records the centre so
     * the closing half can use the very same lines. Called at the HEAD of {@code tickClient}.
     */
    public static void before(StormObject storm) {
        bracketed = null;
        if (!owns(storm)) {
            return;
        }
        centreX = storm.pos.x;
        centreZ = storm.pos.z;
        bracketed = storm;
        flipTangential(storm, storm.listParticlesCloud);
        flipTangential(storm, storm.listParticlesGround);
    }

    /**
     * Mirrors them back on the way out, leaving Weather2's own impulses as the only mirrored part.
     * Called at the RETURN of {@code tickClient}.
     */
    public static void after(StormObject storm) {
        if (bracketed == null || bracketed != storm) {
            return;
        }
        bracketed = null;
        flipTangential(storm, storm.listParticlesCloud);
        flipTangential(storm, storm.listParticlesGround);
    }

    /**
     * Whether the mirror applies: an anti-cyclonic roll that has actually been assigned to this storm,
     * and the knob that asks Weather2's own visuals to follow it. {@code visualRotationSign} is the one
     * place that question is answered, so a storm this mod considers cyclonic is bit-for-bit untouched.
     */
    private static boolean owns(StormObject storm) {
        return storm != null
                && storm.pos != null
                && Config.ANTI_CYCLONIC_TORNADOES_ENABLED.get()
                && Config.ANTI_CYCLONIC_INVERT_WEATHER2_SPIN.get()
                && DynamicStormController.visualRotationSign(storm) < 0.0D;
    }

    /**
     * Reflects each particle's velocity about the radius running from the storm's axis to it: the part
     * pointing away from the axis is kept exactly, the part pointing around the axis is reversed.
     *
     * <p>Decomposed and recomposed in the particle's own radial frame rather than by negating a sine, so
     * the speed is preserved to the last bit: the step is an orthogonal reflection, which is its own
     * inverse. That is what lets the same call run before and after Weather2's tick - the particle the
     * storm just nudged is nudged back to its mirror on the way out of it.
     */
    private static void flipTangential(StormObject storm, List<EntityRotFX> particles) {
        if (particles == null || particles.isEmpty()) {
            return;
        }
        for (EntityRotFX particle : particles) {
            if (particle == null || !particle.isAlive()) {
                continue;
            }
            double dx = particle.getPosX() - centreX;
            double dz = particle.getPosZ() - centreZ;
            double radiusSqr = dx * dx + dz * dz;
            if (radiusSqr < 1.0E-4D) {
                // On the axis there is no radius to be tangential to, so there is nothing to reflect.
                continue;
            }
            double radius = Math.sqrt(radiusSqr);
            double radialX = dx / radius;
            double radialZ = dz / radius;
            double motionX = particle.getMotionX();
            double motionZ = particle.getMotionZ();
            // The radial frame of this particle: out along the radius, around along its perpendicular.
            double radial = motionX * radialX + motionZ * radialZ;
            double tangential = -motionX * radialZ + motionZ * radialX;
            double mirrored = -tangential;
            particle.setMotionX(radial * radialX + mirrored * -radialZ);
            particle.setMotionZ(radial * radialZ + mirrored * radialX);
        }
    }
}
