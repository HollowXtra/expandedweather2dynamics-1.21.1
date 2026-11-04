package com.imbet.mixin;

import com.imbet.Config;
import com.imbet.client.CloudDeckSpriteProvider;
import com.imbet.client.LineStormDeck;
import com.imbet.client.StormBillboardPhase;
import extendedrenderer.particle.entity.EntityRotFX;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import weather2.weathersystem.storm.StormObject;

/**
 * Two things this mod does to a Weather2 storm's own cloud puffs, both at the moment they are made.
 *
 * <p><b>The art.</b> Weather2 spawns the storm cloud deck (and its lower cloud layer), its own 1.12.2 scatter
 * funnel and the tornado's ground fog through {@code StormObject.spawnFogParticle(x, y, z, type)}, which always
 * binds the dense {@code cloud256} funnel sprite. On ~30-block deck billboards that sheet renders every quad as a
 * solid puff - the overlapping polygons. This mixin reroutes those puffs to a misty sheet instead, and which sheet
 * depends on what is being drawn:
 *
 * <ul>
 *   <li>the deck and ground fog, in {@link Config.StormLook#MODERN} and {@link Config.StormLook#COMBINED}: the
 *       deck's own low-density accumulate sprite, so the deck sums into one continuous cloud shelf;</li>
 *   <li>Weather2's own 1.12.2 scatter funnel - the only 4-argument caller that passes render order 1 - and the
 *       deck and ground fog under {@link Config.StormLook#CLASSIC}: the stock-faint {@code cloud256classic}
 *       sheet. That is the art the 1.12.2 geometry was built against: hundreds of 22.5 to 37.5-block puffs
 *       stacked over each other, which sum into soft cloud at this density and solidify into a faceted slab at
 *       the dense one.</li>
 * </ul>
 *
 * <p>The same moment is where each deck puff would be given one of the deck sheet's animation phases, and it is
 * still the right moment for it - the only one at which a puff exists without having been made. Nothing is
 * phased at present: the deck's art is a single still frame of the author's own cloud, so the siblings are not
 * shipped and every phase answers the sheet itself. See {@link StormBillboardPhase}.
 *
 * <p>Both branches route rather than stand down: the geometry still has to be Weather2's own, but the art has to
 * be the art that geometry was drawn against. In COMBINED the scatter only spawns when
 * {@link Config#STORM_LOOK_CLASSIC_COLLAR} is on, so the first branch is what its collar draws on. Explicit-
 * sprite callers (the fire funnel's sheet, the April Fools variant) are untouched and keep what they asked for.
 *
 * <p><b>The shape of a squall line.</b> The same spawn is where a line's silhouette is decided. Weather2's deck
 * loop samples a square of half-width {@code size} around the storm's own {@code pos} at {@code pos.y + 30} and
 * hands the draw to {@code spawnFogParticle}; that square is the entire shape of Weather2's storm cloud, and it
 * is why a line built as a row of thunderstorms comes out as a row of round masses. The second inject below
 * takes the draw that arrives at the five-argument spawn - the one that actually makes the particle, whichever
 * of the two callers is asking - and, for a cell the server seeded on to a line, moves its x and z on to the
 * line's own band ({@link LineStormDeck}). Height, sprite, render order, scale, lifetime, population and every
 * later movement stay Weather2's own: only the footprint changes, which is the only thing that was ever making
 * the shape.
 *
 * <p>It hangs off the spawn method rather than off a call site inside {@code tickRender} deliberately. A
 * call-site target has to be found by position in a method this mod does not own, and a build whose bytecode
 * puts its calls anywhere but where the last one did fails the injection and takes the game down with it. The
 * method itself is the stable part of Weather2's API, and every caller of it passes through here.
 */
@Mixin(value = StormObject.class, remap = false)
public abstract class StormObjectCloudDeckMixin {

    /** Weather2's scatter funnel is the one 4-argument caller that asks for render order 1. */
    private static final int SCATTER_FUNNEL_RENDER_ORDER = 1;

    /** True only inside this mixin's own delegate call, so placing a puff cannot recurse into placing it. */
    @Unique
    private boolean expandedweather2dynamics$placingLineDeck;

    @Inject(method = "spawnFogParticle(DDDI)Lextendedrenderer/particle/entity/EntityRotFX;", at = @At("HEAD"), cancellable = true)
    private void expandedweather2dynamics$swapDeckSprite(double x, double y, double z, int type, CallbackInfoReturnable<EntityRotFX> cir) {
        StormObject storm = (StormObject) (Object) this;
        if (Config.weather2OwnScatter() && (Config.classicLook() || type == SCATTER_FUNNEL_RENDER_ORDER)) {
            cir.setReturnValue(storm.spawnFogParticle(x, y, z, type, CloudDeckSpriteProvider.classicSprite()));
            return;
        }
        // The phase is chosen here because here is the only moment the puff exists without having been made: a
        // spawn call has the puff's own position and nothing else to go on, and the sprite it is given now is
        // the sprite it keeps for its whole life. That is what made a deck read as cloud turning over rather
        // than as one sheet flipping - neighbours in the deck square land on different phases, because the
        // phase is a hash of where they spawn - and it costs one hash per particle and nothing per frame. It
        // is off by default now and there is nothing to phase: the art is one still frame.
        int phase = Config.billboardPhases() ? StormBillboardPhase.forPoint(x, y, z) : 0;
        cir.setReturnValue(storm.spawnFogParticle(x, y, z, type, CloudDeckSpriteProvider.deckSprite(phase)));
    }

    /**
     * The one place a deck puff's footprint is decided: the five-argument spawn, which both the deck's own
     * four-argument call and Weather2's explicit-sprite callers funnel through.
     *
     * <p>Renewed only where a line exists - {@link LineStormDeck#deckPoint} answers null for every other reason
     * there is: the switch off, a storm that is not one of the line's own cells, no synced line in reach, or a
     * point that fails Weather2's own player bubble or terrain-ahead check. Null leaves the spawn exactly where
     * Weather2 asked for it, with the sprite the caller named.
     */
    @Inject(method = "spawnFogParticle(DDDILnet/minecraft/client/renderer/texture/TextureAtlasSprite;)Lextendedrenderer/particle/entity/EntityRotFX;",
            at = @At("HEAD"), cancellable = true)
    private void expandedweather2dynamics$placeLineDeck(double x, double y, double z, int type, TextureAtlasSprite sprite, CallbackInfoReturnable<EntityRotFX> cir) {
        if (expandedweather2dynamics$placingLineDeck) {
            return;
        }
        StormObject storm = (StormObject) (Object) this;
        Vec3 point = LineStormDeck.deckPoint(storm, y);
        if (point == null) {
            return;
        }
        expandedweather2dynamics$placingLineDeck = true;
        try {
            cir.setReturnValue(storm.spawnFogParticle(point.x, point.y, point.z, type, sprite));
        } finally {
            expandedweather2dynamics$placingLineDeck = false;
        }
    }
}
