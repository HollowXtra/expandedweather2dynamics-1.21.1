package com.imbet.client;

import com.imbet.Config;
import com.imbet.weather.DynamicStormController;
import com.imbet.weather.DynamicStormState;
import extendedrenderer.particle.entity.EntityRotFX;
import net.minecraft.util.Mth;
import weather2.weathersystem.storm.StormObject;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Carries this mod's dynamic size onto the revived 1.12.2 funnel.
 *
 * <p>Weather2's funnel particles know nothing about the dynamic width: each is spawned once at a
 * fixed scale and its spread comes only from the storm's own size field. Applying that width to the
 * funnel was the layer funnel's job, through its per-layer radius scale, and the layer funnel is
 * stood down in classic mode - so without this pass a classic tornado would keep drawing a column of
 * one width while its own state said it was a mile across. The classic look is meant to change how the
 * tornado is drawn, not to take the dynamic size away.
 *
 * <p>Scale, not position, is the lever: Weather2 drives these particles' positions itself every tick
 * and overwriting them would fight its swirl, while a bigger puff widens the column the way a wider
 * funnel really looks. The factor is derived from the state's own width multiplier, the same number
 * that scales the tornado's base size, so the drawn column and the damage swath stay in step - and it
 * is deliberately a mild, saturating one. See the band's own note below for why the raw multiplier is
 * the wrong thing to multiply a 37-block puff by.
 *
 * <p>This lives here rather than in {@code StormObjectMixin} because that mixin is applied on dedicated
 * servers too, and the particles are CoroUtil's client-only {@link EntityRotFX}. Mixin resolves every
 * class a mixin method calls into when it applies the mixin, so a mixin method that touched
 * {@code EntityRotFX} stopped every dedicated server from starting. Called from the mixin, this class is
 * only ever loaded by the client's tick.
 */
public final class ClassicFunnelScale {

    private ClassicFunnelScale() {
    }

    /**
     * Applies the dynamic width to one storm's classic funnel particles.
     *
     * @param storm the storm being ticked on the client
     * @param memory what the previous call returned for this storm, or {@code null} on the first call
     * @return the memory to hand back on the next call: the base scale and the factor last written for each
     *         funnel particle, so the width can be re-applied every tick without compounding
     */
    public static Object apply(StormObject storm, Object memory) {
        if (!Config.weather2OwnScatter() || !Config.VARIABLE_WIDTHS_ENABLED.get()) {
            return memory;
        }
        List<EntityRotFX> particles = storm.listParticlesFunnel;
        if (particles == null || particles.isEmpty()) {
            return null;
        }
        @SuppressWarnings("unchecked")
        Map<EntityRotFX, float[]> scales = memory instanceof Map<?, ?> map
                ? (Map<EntityRotFX, float[]>) map
                : new IdentityHashMap<>();
        DynamicStormState state = DynamicStormController.stateFor(storm);
        // Deliberately a narrow band, and deliberately sub-linear. Weather2's own funnel puffs are already
        // 22.5 to 37.5 blocks across and all spawn inside a radius that only grows with the storm's stage,
        // so puff size is a saturating lever: scaling it by the raw width multiplier (which runs into the
        // tens on a mile-wide tornado) drew 200-block quads and the funnel read as a wall of slabs filling
        // the sky. The square root keeps a wider storm visibly wider - a 4x wider swath draws puffs about
        // 1.6x across - while leaving Weather2's own footprint, which is what makes a classic funnel a
        // funnel, in charge of the shape. The rest of the read is appearance: opacity, tone and the
        // per-stage spread Weather2 already applies.
        float factor = Mth.clamp((float) Math.sqrt(Mth.clamp(state.widthMultiplier(), 0.25F, 64.0F)), 0.85F, 1.6F);
        scales.keySet().removeIf(particle -> particle == null || !particle.isAlive());
        for (EntityRotFX particle : particles) {
            if (particle == null || !particle.isAlive()) {
                continue;
            }
            float current = particle.getScale();
            float[] record = scales.get(particle);
            // If the particle's scale is not the one we last wrote, Weather2 has just created it (or set
            // it for its own reasons), so that value is its base. Re-reading on mismatch is what keeps the
            // width from compounding into a runaway with every tick.
            float base = record == null || Math.abs(current - record[0] * record[1]) > 0.02F ? current : record[0];
            float scaled = Mth.clamp(base * factor, 0.05F, 80.0F);
            if (Math.abs(scaled - current) > 0.001F) {
                particle.setScale(scaled);
            }
            record = record == null ? new float[2] : record;
            record[0] = base;
            record[1] = factor;
            scales.put(particle, record);
        }
        return scales;
    }
}
