package com.imbet.registry;

import com.imbet.ExpandedWeather2Dynamics;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** The mod's own particle types. */
public final class ModParticles {
    private static final DeferredRegister<ParticleType<?>> PARTICLES =
            DeferredRegister.create(BuiltInRegistries.PARTICLE_TYPE, ExpandedWeather2Dynamics.MODID);

    /**
     * A wildfire's flame: a tongue of fire that flickers through its own frames, rises a little and dies, and is its own
     * light. Always shown, because a fire front is watched from much further than the game's own particles are drawn.
     */
    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> WILDFIRE_FLAME =
            PARTICLES.register("wildfire_flame", () -> new SimpleParticleType(true));

    private ModParticles() {
    }

    public static void register(IEventBus bus) {
        PARTICLES.register(bus);
    }
}
