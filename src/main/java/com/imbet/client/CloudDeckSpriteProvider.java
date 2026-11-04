package com.imbet.client;

import com.imbet.Config;
import com.imbet.ExpandedWeather2Dynamics;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.TextureAtlasStitchedEvent;

/**
 * Resolves the two misty cloud sheets Weather2's own particles need instead of the dense
 * {@code cloud256} glow-puff sheet: one for the modern storm deck, one for the classic look.
 *
 * <p>{@code ParticleArt.cloud256()} is the sheet Weather2 gives every one of its fog puffs - the storm
 * cloud deck, the lower cloud layer, the funnel and the tornado's ground fog. The deck is thousands of
 * ~30-block billboards, so it gets a sheet of its own.
 *
 * <p>That sheet is the author's own cloud puff: a single 256x256 frame with a mean alpha of 0.683 of 255, a
 * brightest texel of 255, 59% of the frame above 200 and a neutral grey tone of 127. The cloud deck, the structure
 * particles and the loose dust draw that file; the funnel's two sheets draw the second puff the author
 * supplied, which is darker, softer and measured where it is used. Both are put in as they were
 * given rather than redrawn from, and the whole of the art decision is the two puffs themselves. What that
 * means where it is felt: the cloud's alpha is a feathered puff, near full across its middle and then away
 * over a soft rim at 28 levels a texel, filling 94% of its square, so on a thirty-block billboard a puff
 * reads as a soft mass that dissolves into the one behind it, and a stack of them reads as one body of cloud
 * rather than a pile of discs. That is a known trade - the
 * numbers are in {@code GenStormArt}'s own notes, and the disc this replaces is still measured and
 * argued over there - and it is the author's call rather than a measurement's, which is why nothing reshapes
 * either file.
 *
 * <p>The phase-aware accessors fall back to the first sheet whenever a sibling is missing from the atlas, and
 * no sibling is shipped: the animation is retired (see {@link StormBillboardPhase}), so every phase answers
 * this one sheet and a resource pack cannot break a storm by dropping a file that was never there.
 *
 * <p>The classic sprite is for the other direction. Under {@code storm_look.mode = CLASSIC} Weather2 draws
 * its own 1.12.2 deck and funnel again - hundreds of 30 to 37-block billboards stacked over each other. The
 * stock art those were built against is faint by construction (mean alpha 13/255, brightest texel 81), which
 * is what makes the stack sum into one soft overcast instead of solidifying into a faceted slab; drawing that
 * same geometry with the dense sheet is what made a classic storm read as a wall of polygons. So the classic
 * look samples this sheet rather than the funnel's.
 *
 * <p>None of the three roles means anything while {@link Config#originalParticleArt()} is on, which is the
 * nostalgic setting rather than the default: with Weather2's own 1.12.2 sheets selected there is exactly one
 * cloud sheet in play, it is already the art the deck and the classic funnel were drawn against, and all
 * three accessors answer with it - the art switch and this class both exist because the choice has to have
 * one owner, not because the deck wants a sheet of its own.
 *
 * <p>This class is also where both art sets are read out of the atlas ({@link ParticleArt#resolve}), because
 * a stitch is the only moment a particles-atlas sprite can be resolved and this mod should resolve both sets
 * in one pass over one atlas rather than from two handlers racing each other.
 *
 * The particles atlas is NOT part of {@code ModelManager}'s atlas set in
 * 1.21.1 (it lives in {@code ParticleEngine}), so
 * {@code Minecraft.getTextureAtlas(LOCATION_PARTICLES)} always fails there.
 * Like Weather2's own {@code ParticleRegistry}, we resolve the sprites from the
 * {@code TextureAtlasStitchedEvent} and refresh them on every stitch (resource
 * reload). If a sprite is ever unavailable, we fall back to the standard
 * cloud256 sheet rather than crash or render the missing texture.
 */
@EventBusSubscriber(modid = ExpandedWeather2Dynamics.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class CloudDeckSpriteProvider {
    private static final ResourceLocation CLASSIC_SPRITE =
            ResourceLocation.fromNamespaceAndPath("coroutil", "particles/cloud256classic");
    /**
     * The deck's and the funnel's sheets, one per animation phase.
     *
     * <p>Only index 0 exists in the tree. The other three are the paths an animated set would take - the same
     * boil cut with its frame order rotated, a quarter of a cycle apart, which is what {@link StormBillboardPhase}
     * is for and why the array is still indexed by phase at all. The art is a single still frame per role now,
     * so nothing resolves them and every phase answers its own role's sheet; the mechanism is kept because it
     * is the way back.
     */
    private static final ResourceLocation[] DECK_SPRITES = variants("particles/cloud256deck");
    private static final ResourceLocation[] COMBINED_SPRITES = variants("particles/cloud256combined");
    private static final TextureAtlasSprite[] deckSprites = new TextureAtlasSprite[StormBillboardPhase.PHASES];
    private static final TextureAtlasSprite[] combinedSprites = new TextureAtlasSprite[StormBillboardPhase.PHASES];
    private static TextureAtlasSprite classicSprite;

    private CloudDeckSpriteProvider() {
    }

    @SubscribeEvent
    static void onAtlasStitched(TextureAtlasStitchedEvent event) {
        if (!event.getAtlas().location().equals(TextureAtlas.LOCATION_PARTICLES)) {
            return;
        }
        resolveAll(event.getAtlas(), DECK_SPRITES, deckSprites);
        resolveAll(event.getAtlas(), COMBINED_SPRITES, combinedSprites);
        classicSprite = resolve(event.getAtlas(), CLASSIC_SPRITE);
        ParticleArt.resolve(event.getAtlas());
    }

    private static void resolveAll(TextureAtlas atlas, ResourceLocation[] wanted, TextureAtlasSprite[] into) {
        for (int index = 0; index < wanted.length && index < into.length; index++) {
            into[index] = resolve(atlas, wanted[index]);
        }
    }

    private static TextureAtlasSprite resolve(TextureAtlas atlas, ResourceLocation want) {
        TextureAtlasSprite sprite = atlas.getSprite(want);
        return sprite.contents().name().equals(want) ? sprite : null;
    }

    /** The deck's sheet at one animation phase, falling back to phase 0 and then to the puff sheet. */
    public static TextureAtlasSprite deckSprite(int phase) {
        if (Config.originalParticleArt()) {
            return ParticleArt.cloud256();
        }
        TextureAtlasSprite sprite = variant(deckSprites, phase);
        return sprite != null ? sprite : ParticleArt.cloud256();
    }

    /** The deck's sheet for a caller that has no billboard to stagger, which is phase 0. */
    public static TextureAtlasSprite deckSprite() {
        return deckSprite(0);
    }

    /**
     * The sheet for one animation phase, or the first one if this phase's file was not in the atlas - a storm
     * drawn at the wrong phase is a storm, and a storm drawn against a missing texture is a checkerboard.
     */
    private static TextureAtlasSprite variant(TextureAtlasSprite[] variants, int phase) {
        TextureAtlasSprite chosen = variants[Math.floorMod(phase, StormBillboardPhase.PHASES)];
        return chosen != null ? chosen : variants[0];
    }

    /** The sibling paths of one role's sheets: {@code cloud256deck}, {@code cloud256deck_p1}, and so on. */
    private static ResourceLocation[] variants(String path) {
        ResourceLocation[] locations = new ResourceLocation[StormBillboardPhase.PHASES];
        locations[0] = ResourceLocation.fromNamespaceAndPath("coroutil", path);
        for (int phase = 1; phase < locations.length; phase++) {
            locations[phase] = ResourceLocation.fromNamespaceAndPath("coroutil", path + "_p" + phase);
        }
        return locations;
    }

    public static TextureAtlasSprite classicSprite() {
        if (Config.originalParticleArt()) {
            return ParticleArt.cloud256();
        }
        TextureAtlasSprite sprite = classicSprite;
        return sprite != null ? sprite : ParticleArt.cloud256();
    }

    /**
     * The layered funnel's own sheet, and the single asset a storm's look turns on hardest: this is the
     * condensation column itself, and Weather2 draws a ring of these per block of height.
     *
     * <p>It is the second of the author's puffs, the one supplied for the tornado, and it is not the cloud the
     * deck and the dust draw: a single 256x256 frame, a mean alpha of 0.466, a peak of 255, 43% of it above
     * 200, 59% of its square covered and a mid grey of 103, with a harder edge than the cloud's and far less
     * alpha left at its outer ring. The rings therefore draw the puff's own mottled body across rather less of the
     * billboard, which on a ring that overlaps its neighbours the whole way up is what a column is built from -
     * so the edge it has is the edge of a ring rather than of the column.
     *
     * <p>Unlike the deck, the rain, the debris and the dust, this one ignores the art switch: a funnel has to
     * read solid, and Weather2's own sheet peaks at 81 of 255, so no alpha written against it can draw one. See
     * {@link ParticleArt#funnelSprite()} for the same reasoning on this mod's own vortices.
     */
    public static TextureAtlasSprite combinedSprite(int phase) {
        TextureAtlasSprite sprite = variant(combinedSprites, phase);
        if (sprite != null) {
            return sprite;
        }
        return ParticleArt.funnelSprite();
    }

    /** The layered funnel's sheet for a caller that has no billboard to stagger, which is phase 0. */
    public static TextureAtlasSprite combinedSprite() {
        return combinedSprite(0);
    }
}
