package com.imbet.weather.vortex;

import net.minecraft.world.phys.Vec3;

public final class SatelliteVortexInstance extends StandaloneVortexInstance {
    public SatelliteVortexInstance(long id, long parentStormId, Vec3 position, Vec3 motion, float radius, float maxWindMph, long gameTime, long expireTick) {
        super(id, parentStormId, VortexMode.SATELLITE_TORNADO, position, motion, radius, maxWindMph, gameTime, expireTick);
    }
}
