package com.imbet.mixin;

import com.imbet.Config;
import com.imbet.weather.DebrisEntity;
import com.imbet.weather.LodStormEnvelope;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import weather2.config.ConfigMisc;
import weather2.weathersystem.WeatherManagerServer;
import weather2.weathersystem.storm.StormObject;
import weather2.weathersystem.storm.WeatherObject;

/**
 * Replaces Weather2's grabbed-block particle with this mod's own debris entity.
 *
 * <p>{@code syncBlockParticleNew} is the single place a block that has been torn off is turned into
 * debris - both Weather2's own tornado grab in {@code TornadoHelper.tryRip} and this mod's
 * {@code StructureLift} handoff go through it - and it is handed the exact {@link BlockState} and
 * {@link BlockPos} of the block. That makes it the one clean point to redirect: cancel it and spawn a
 * {@link DebrisEntity} instead, and Weather2's particle is never created while the material the physics
 * needs is known from the start.</p>
 *
 * <p>It is injected at HEAD and only cancels when the entity is actually spawned, so turning the feature
 * off, or a spawn refused by the active cap, falls straight back to Weather2's own debris. The mod's own
 * debris is therefore a replacement that can be reverted by a single config switch, not a one-way edit
 * to Weather2.</p>
 *
 * <p>The two redirects below are the LOD half of that compatibility, and they are here rather than in a class of
 * their own because this is already the one mixin onto Weather2's server manager. Weather2 reads its own box size
 * from {@link ConfigMisc} in exactly two places - the spawn point, and the scan that forgets a storm with no
 * player near it - and both are redirected so the number they get is the LOD distance when one is drawing. The
 * redirects are the whole intervention: nothing is cancelled, no storm is forced into existence, and with the
 * compat off every read returns the configured value untouched.</p>
 */
@Mixin(value = WeatherManagerServer.class, remap = false)
public abstract class WeatherManagerServerMixin {

    @Inject(method = "syncBlockParticleNew", at = @At("HEAD"), cancellable = true, remap = false)
    private void expandedweather2dynamics$spawnDebrisEntity(BlockPos pos, BlockState state, WeatherObject storm,
                                                            CallbackInfo ci) {
        if (!Config.DEBRIS_ENTITY_ENABLED.get()) {
            return;
        }
        WeatherManagerServer manager = (WeatherManagerServer) (Object) this;
        if (!(manager.getWorld() instanceof ServerLevel level)) {
            return;
        }
        StormObject owner = storm instanceof StormObject stormObject ? stormObject : null;
        if (DebrisEntity.spawn(level, owner, pos, state) != null) {
            ci.cancel();
        }
    }

    /**
     * Widens the box a storm has to be near, in the scan that forgets one with no player around.
     *
     * <p>Weather2 asks {@code getNearestPlayer} for the nearest player within this radius and removes a storm
     * after ten seconds outside it, so a storm out over terrain only an LOD renderer draws would be deleted as
     * soon as it got there. One read, one answer, and the caller's own arithmetic is untouched.</p>
     */
    @Redirect(method = "tick", remap = false, require = 1, expect = 1,
            at = @At(value = "FIELD", opcode = Opcodes.GETSTATIC,
                    target = "Lweather2/config/ConfigMisc;Misc_simBoxRadiusCutoff:I"))
    private int expandedweather2dynamics$lodStormKeepAlive() {
        return LodStormEnvelope.keepAliveRadius(ConfigMisc.Misc_simBoxRadiusCutoff);
    }

    /**
     * Widens the box a storm is born in.
     *
     * <p>The spawn point is drawn as player position plus and minus a random offset inside this radius, in x and
     * again in z - four reads of the one field - so the redirect answers all four with the same extended value
     * and the candidate is picked over the wider ground exactly as Weather2 picks it over the narrow one. The
     * offset stays triangular about the player, so most storms still form near somebody while some form over the
     * far country, which is the point of the whole extension.</p>
     */
    @Redirect(method = "trySpawnStormCloudNearPlayerForLayer", remap = false, require = 1, expect = 4,
            at = @At(value = "FIELD", opcode = Opcodes.GETSTATIC,
                    target = "Lweather2/config/ConfigMisc;Misc_simBoxRadiusSpawn:I"))
    private int expandedweather2dynamics$lodStormSpawnRadius() {
        return LodStormEnvelope.spawnRadius(ConfigMisc.Misc_simBoxRadiusSpawn);
    }
}
