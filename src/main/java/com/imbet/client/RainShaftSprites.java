package com.imbet.client;

import com.imbet.Config;
import com.imbet.ExpandedWeather2Dynamics;
import extendedrenderer.particle.ParticleRegistry;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.TextureAtlasStitchedEvent;

/**
 * The falling-rain sheet sprites: actual rain, instead of Weather2's single soft smudge.
 *
 * <p>Weather2 draws every rain volume - the volumetric downfall around a storm and the rain
 * curtains of squall lines and hurricanes - with one sprite, {@code coroutil:particles/downfall3},
 * and that sprite is a bare soft blob: 256x256 of almost nothing (mean alpha 0.018, peak 0.43, no
 * structure at all). Stacked as large translucent quads that reads as grey fog, not as rain.
 *
 * <p>These replacements are real streaked curtains at three densities, drawn procedurally so the
 * threads stay fine (a 2px thread on an 8-block sheet is about a tenth of a block, which is what
 * rain looks like at a distance) and every one fades to zero alpha at all four borders, so a sheet
 * can never show a rectangle edge.
 *
 * <p>They cover the width of the sheet rather than sitting in a band down its middle, which is a
 * correction rather than a preference. An earlier version drew a soft band, and measured it came to
 * 118 of 256 columns with no resolved threads at all - so every sheet was a grey stripe with clear
 * air either side of it, and a storm's rain read as fog because a fog stripe is exactly what it was.
 * Rain is falling water across the whole width of what you are looking at; the sheets tile into a
 * wall of it, and the sheet's own edges are invisible because they are transparent.
 *
 * <p>Orientation is deliberate, and it is why the streaks run vertically in texture space. A sheet
 * spawned with {@code slantParticleToWind} has its pitch recomputed every tick by Weather2 as
 * {@code atan2(motionY, motionXZ)}, which is a hair off zero, so the quad stands upright and the
 * sprite's own up axis is world up - vertical threads in the texture are vertical rain in the
 * world. Weather2's original sheet is spawned with a pitch of 82 degrees instead and no wind
 * slant, which lays the quad almost flat: a nearly horizontal haze layer, which is exactly why its
 * rain reads as fog. {@link #isShaftSprite} is what tells the spawners a sprite is a rain sheet,
 * and {@link #isStreakedSprite} which of them should stand upright.
 */
@EventBusSubscriber(modid = ExpandedWeather2Dynamics.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class RainShaftSprites {

    private static final ResourceLocation LIGHT_SPRITE = sprite("rainshaft_light");
    private static final ResourceLocation STANDARD_SPRITE = sprite("rainshaft");
    private static final ResourceLocation HEAVY_SPRITE = sprite("rainshaft_heavy");
    private static final ResourceLocation VIRGA_SPRITE = sprite("rainshaft_virga");

    private static TextureAtlasSprite light;
    private static TextureAtlasSprite standard;
    private static TextureAtlasSprite heavy;
    private static TextureAtlasSprite virga;

    private RainShaftSprites() {
    }

    /**
     * The sheet sprite to use for a rain volume, chosen by how hard it is raining there: a thin
     * shower, rain, or a torrential rain-wrapped core. Mixes neighbouring densities with a small
     * probability so a stack of sheets does not sort into visible bands.
     */
    public static TextureAtlasSprite sheet(RandomSource random, float density) {
        TextureAtlasSprite legacy = legacySprite();
        if (!Config.VOLUMETRIC_DOWNFALL_SHAFT_TEXTURES.get() || random == null) {
            return legacy;
        }
        float roll = random.nextFloat();
        TextureAtlasSprite sprite;
        if (density < 0.42F) {
            sprite = roll < 0.78F ? light : standard;
        } else if (density < 0.72F) {
            sprite = roll < 0.12F ? light : roll < 0.92F ? standard : heavy;
        } else {
            sprite = roll < 0.72F ? heavy : standard;
        }
        if (sprite == null) {
            sprite = standard;
        }
        return sprite != null ? sprite : legacy;
    }

    /**
     * The sheet sprite for precipitation that is evaporating on the way down: a shaft that fades and
     * frays into wisps rather than ending. Mostly the dedicated virga texture, mixed with the
     * ordinary ones so a heavy virga storm still reads as dense. Falls back to the normal choice when
     * the virga texture is unavailable.
     */
    public static TextureAtlasSprite virgaSheet(RandomSource random, float density) {
        TextureAtlasSprite normal = sheet(random, density);
        if (!Config.VOLUMETRIC_DOWNFALL_SHAFT_TEXTURES.get() || random == null) {
            return normal;
        }
        if (virga != null && random.nextFloat() < 0.75F) {
            return virga;
        }
        return normal;
    }

    /**
     * Whether a sprite is one of the streaked shafts. The spawners use this to decide a sheet's
     * orientation and alpha, so it must cover the replacements and Weather2's original alike -
     * otherwise a textured sheet would silently switch to camera-facing billboard settings.
     */
    public static boolean isShaftSprite(TextureAtlasSprite sprite) {
        return sprite != null && (sprite == light || sprite == standard || sprite == heavy || sprite == virga || sprite == legacySprite());
    }

    /** Whether a sprite is one of the new streaked textures rather than Weather2's soft blob. */
    public static boolean isStreakedSprite(TextureAtlasSprite sprite) {
        return sprite != null && (sprite == light || sprite == standard || sprite == heavy || sprite == virga);
    }

    private static TextureAtlasSprite legacySprite() {
        return ParticleArt.downfall3() != null ? ParticleArt.downfall3() : ParticleRegistry.cloud;
    }

    private static ResourceLocation sprite(String name) {
        return ResourceLocation.fromNamespaceAndPath("coroutil", "particles/" + name);
    }

    @SubscribeEvent
    static void onAtlasStitched(TextureAtlasStitchedEvent event) {
        if (!event.getAtlas().location().equals(TextureAtlas.LOCATION_PARTICLES)) {
            return;
        }
        // getSprite hands back the missing-texture sprite when the entry is not in the atlas, so a
        // sprite is only accepted when it reports its own name.
        light = resolve(event.getAtlas(), LIGHT_SPRITE);
        standard = resolve(event.getAtlas(), STANDARD_SPRITE);
        heavy = resolve(event.getAtlas(), HEAVY_SPRITE);
        virga = resolve(event.getAtlas(), VIRGA_SPRITE);
    }

    private static TextureAtlasSprite resolve(TextureAtlas atlas, ResourceLocation location) {
        TextureAtlasSprite sprite = atlas.getSprite(location);
        return sprite.contents().name().equals(location) ? sprite : null;
    }
}
