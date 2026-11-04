package com.imbet.client;

import com.imbet.Config;
import extendedrenderer.particle.ParticleRegistry;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;

/**
 * Which art every storm particle draws: Weather2's own 1.12.2 particle sheets, or the ones this mod
 * regenerated for the same names.
 *
 * <p>This mod used to ship its regenerated sheets <em>at Weather2's own paths</em>, with the same file
 * names. A resource pack that ships a file at another mod's path wins, so Weather2's own art never reached
 * the atlas at all: not for this mod's particles, and not for Weather2's own either - the deck, its 1.12.2
 * scatter funnel and its debris all drew the replacement without anyone choosing it. The two sets are also
 * far apart rather than a refinement of each other. Measured over one frame each, Weather2's {@code cloud256}
 * is a single 256x256 sheet with a mean alpha of 13.2 of 255 and a brightest texel of 81; this mod's is a
 * single 256x256 frame too - the author's own cloud, put in as supplied - with a mean alpha of 176 and a
 * brightest texel of 255. That
 * is a thirteen-fold difference in the body of a puff, which is the difference between hundreds of stacked
 * billboards summing into cloud and the same billboards solidifying into a faceted slab.
 *
 * <p>So the two sets now live in different places and this class picks between them:
 *
 * <ul>
 *   <li>{@code coroutil:particles/<name>} - Weather2's own sheets, which its jar still carries and which are
 *       in the atlas again now that nothing this mod ships sits at those paths. These are the 1.12.2 art,
 *       unchanged: the sheet Weather2's own deck and funnel code was drawn against.</li>
 *   <li>{@code coroutil:particles/modern/<name>} - this mod's regenerated sheets, stitched through the atlas
 *       entries in {@code assets/minecraft/atlases/particles.json}.</li>
 * </ul>
 *
 * <p>{@link Config#originalParticleArt()} decides, and it is off by default: this mod's own sheets are the
 * modern art and are what a storm is drawn in unless a player asks for the old one. Our own spawn sites never
 * touch the choice: they call {@link #cloud256()} and friends, which answer with the selected sheet and fall
 * back to the other one if the atlas did not carry it, so a broken or missing sprite degrades to the other art
 * rather than to the missing-texture checkerboard.
 *
 * <p>One sheet is not part of that choice at all: the vortex funnels call {@link #funnelSprite()}, which is
 * this mod's own funnel art whatever the switch says. The reason is in that method's own note - a funnel has to
 * read as solid, and the 1.12.2 sheet's peak texel is a third of the way to opaque, so the art itself is the
 * ceiling on how solid a funnel can ever be drawn.
 *
 * <p><strong>Why the registry fields are written too.</strong> Six of the sheets this mod used to shadow are
 * read straight off {@link ParticleRegistry} by Weather2's own code - {@code cloud256} by StormObject's deck
 * and its scatter funnel, {@code downfall3}, {@code rain_white} and {@code debris_1/2/3} by its precipitation
 * and its debris - so the choice has to reach those fields or the storm would be drawn half in each art.
 * With the original sheets selected the field already holds exactly that sprite, because Weather2 re-resolves
 * it from its own path on every stitch and nothing shadows that path any more; with the regenerated sheets
 * selected this class has to point them at the modern sprites, and point them back if the option is turned
 * off again. That write happens on the next client tick rather than inside the stitch event, because the tick
 * is unambiguously after every mod's stitch handler has run - the fields are read when a particle is created,
 * not when it is drawn, so one tick of latency costs nothing.
 */
public final class ParticleArt {

    /** Weather2's own namespace: it carries the 1.12.2 sheets and every particle atlas entry for them. */
    private static final String NAMESPACE = "coroutil";
    /** Weather2's own sheets, at the paths its jar uses. */
    private static final String ORIGINAL = "particles/";
    /** This mod's regenerated sheets, which no longer sit on Weather2's paths. */
    private static final String MODERN = "particles/modern/";

    private static final ResourceLocation CLOUD256_ORIGINAL = location(ORIGINAL + "cloud256");
    private static final ResourceLocation CLOUD256_MODERN = location(MODERN + "cloud256");
    /** The vortex funnel's own sheet, which has no Weather2 original to swap with: it is this mod's art. */
    private static final ResourceLocation FUNNEL_MODERN = location(MODERN + "funnel");
    private static final ResourceLocation DOWNFALL3_ORIGINAL = location(ORIGINAL + "downfall3");
    private static final ResourceLocation DOWNFALL3_MODERN = location(MODERN + "downfall3");
    private static final ResourceLocation DEBRIS_1_ORIGINAL = location(ORIGINAL + "debris_1");
    private static final ResourceLocation DEBRIS_1_MODERN = location(MODERN + "debris_1");
    private static final ResourceLocation DEBRIS_2_ORIGINAL = location(ORIGINAL + "debris_2");
    private static final ResourceLocation DEBRIS_2_MODERN = location(MODERN + "debris_2");
    private static final ResourceLocation DEBRIS_3_ORIGINAL = location(ORIGINAL + "debris_3");
    private static final ResourceLocation DEBRIS_3_MODERN = location(MODERN + "debris_3");
    private static final ResourceLocation RAIN_WHITE_ORIGINAL = location(ORIGINAL + "rain_white");
    private static final ResourceLocation RAIN_WHITE_MODERN = location(MODERN + "rain_white");

    private static TextureAtlasSprite cloud256Original;
    private static TextureAtlasSprite cloud256Modern;
    private static TextureAtlasSprite funnelModern;
    private static TextureAtlasSprite downfall3Original;
    private static TextureAtlasSprite downfall3Modern;
    private static TextureAtlasSprite debris1Original;
    private static TextureAtlasSprite debris1Modern;
    private static TextureAtlasSprite debris2Original;
    private static TextureAtlasSprite debris2Modern;
    private static TextureAtlasSprite debris3Original;
    private static TextureAtlasSprite debris3Modern;
    private static TextureAtlasSprite rainWhiteOriginal;
    private static TextureAtlasSprite rainWhiteModern;
    /** Set by a stitch, cleared by the tick that writes the choice into Weather2's registry. */
    private static boolean pending;

    private ParticleArt() {
    }

    /**
     * Reads both art sets out of a freshly stitched particles atlas. Called from the one atlas handler this
     * mod already has ({@code CloudDeckSpriteProvider}) rather than from a second subscriber, so both see the
     * same atlas in the same order.
     */
    static void resolve(TextureAtlas atlas) {
        cloud256Original = resolve(atlas, CLOUD256_ORIGINAL);
        cloud256Modern = resolve(atlas, CLOUD256_MODERN);
        funnelModern = resolve(atlas, FUNNEL_MODERN);
        downfall3Original = resolve(atlas, DOWNFALL3_ORIGINAL);
        downfall3Modern = resolve(atlas, DOWNFALL3_MODERN);
        debris1Original = resolve(atlas, DEBRIS_1_ORIGINAL);
        debris1Modern = resolve(atlas, DEBRIS_1_MODERN);
        debris2Original = resolve(atlas, DEBRIS_2_ORIGINAL);
        debris2Modern = resolve(atlas, DEBRIS_2_MODERN);
        debris3Original = resolve(atlas, DEBRIS_3_ORIGINAL);
        debris3Modern = resolve(atlas, DEBRIS_3_MODERN);
        rainWhiteOriginal = resolve(atlas, RAIN_WHITE_ORIGINAL);
        rainWhiteModern = resolve(atlas, RAIN_WHITE_MODERN);
        pending = true;
    }

    /**
     * Hands the selected art to the six sheets Weather2's own code reads off its registry. Called once per
     * client tick; it does nothing until a stitch has happened and nothing after the write has been made.
     */
    public static void applyPending() {
        if (!pending) {
            return;
        }
        pending = false;
        ParticleRegistry.cloud256 = pick(cloud256Original, cloud256Modern, ParticleRegistry.cloud256);
        ParticleRegistry.downfall3 = pick(downfall3Original, downfall3Modern, ParticleRegistry.downfall3);
        ParticleRegistry.debris_1 = pick(debris1Original, debris1Modern, ParticleRegistry.debris_1);
        ParticleRegistry.debris_2 = pick(debris2Original, debris2Modern, ParticleRegistry.debris_2);
        ParticleRegistry.debris_3 = pick(debris3Original, debris3Modern, ParticleRegistry.debris_3);
        ParticleRegistry.rain_white = pick(rainWhiteOriginal, rainWhiteModern, ParticleRegistry.rain_white);
    }

    /** The puff sheet behind every dust, deck and ground-fog billboard, in the selected art. */
    public static TextureAtlasSprite cloud256() {
        return pick(cloud256Original, cloud256Modern, null);
    }

    /**
     * The sheet the vortex funnels draw: this mod's own, and not part of the art switch.
     *
     * <p>A funnel is the one thing in the storm that has to read as solid, and the switch cannot be trusted
     * with it. Weather2's own sheet has a peak texel of 81 of 255, so no alpha written against it can draw an
     * opaque funnel at all - the ceiling is the art - and its frame mean is 0.052 against this mod's 0.495,
     * which is the difference between a condensation column and a haze. So the funnels sample the sheet drawn
     * for them whatever the art switch says, while the deck, the rain, the debris and every loose dust puff
     * still follow it: that is the art the 1.12.2 geometry was drawn against, and it is still one switch away
     * for a player who wants the old storm back. Falling back to the selected puff sheet rather than throwing
     * keeps a broken or missing sprite from becoming the missing-texture checkerboard on the funnel itself.
     */
    public static TextureAtlasSprite funnelSprite() {
        return funnelModern != null ? funnelModern : cloud256();
    }

    /** The falling-precipitation streak sheet, in the selected art. */
    public static TextureAtlasSprite downfall3() {
        return pick(downfall3Original, downfall3Modern, null);
    }

    /** Tear-off debris sheet 1, in the selected art. */
    public static TextureAtlasSprite debris1() {
        return pick(debris1Original, debris1Modern, null);
    }

    /** Tear-off debris sheet 2, in the selected art. */
    public static TextureAtlasSprite debris2() {
        return pick(debris2Original, debris2Modern, null);
    }

    /** Tear-off debris sheet 3, in the selected art. */
    public static TextureAtlasSprite debris3() {
        return pick(debris3Original, debris3Modern, null);
    }

    /** The white rain streak sheet, in the selected art. */
    public static TextureAtlasSprite rainWhite() {
        return pick(rainWhiteOriginal, rainWhiteModern, null);
    }

    /**
     * The selected sprite, preferring the other set over returning nothing. {@code current} is what the caller
     * already holds, so a registry write with neither sheet in the atlas leaves Weather2's own value alone
     * instead of nulling the field out from under it.
     */
    private static TextureAtlasSprite pick(TextureAtlasSprite original, TextureAtlasSprite modern, TextureAtlasSprite current) {
        TextureAtlasSprite chosen = Config.originalParticleArt()
                ? (original != null ? original : modern)
                : (modern != null ? modern : original);
        return chosen != null ? chosen : current;
    }

    private static TextureAtlasSprite resolve(TextureAtlas atlas, ResourceLocation want) {
        TextureAtlasSprite sprite = atlas.getSprite(want);
        return sprite.contents().name().equals(want) ? sprite : null;
    }

    private static ResourceLocation location(String path) {
        return ResourceLocation.fromNamespaceAndPath(NAMESPACE, path);
    }
}
