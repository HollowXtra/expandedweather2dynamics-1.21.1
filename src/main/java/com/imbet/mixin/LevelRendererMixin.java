package com.imbet.mixin;

import com.imbet.client.CelestialRenderer;
import com.imbet.client.RainWindBend;
import com.imbet.client.StormSkyClient;
import com.imbet.client.VolumetricPrecipitationRenderer;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {

    /**
     * Takes vanilla's clouds away under a storm. They sit below Weather2's deck and write depth, so from underneath a
     * storm they are bright white slabs cut into the dark of it, hiding everything behind them.
     */
    @Inject(method = "renderClouds(Lcom/mojang/blaze3d/vertex/PoseStack;Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;FDDD)V", at = @At("HEAD"), cancellable = true)
    private void expandedweather2dynamics$hideVanillaCloudsUnderStorm(com.mojang.blaze3d.vertex.PoseStack poseStack, org.joml.Matrix4f frustum, org.joml.Matrix4f projection,
                                                                       float partialTick, double camX, double camY, double camZ, CallbackInfo ci) {
        if (StormSkyClient.darkness() > 0.12D) {
            ci.cancel();
        }
    }

    /** The rain pass's camera, captured once at its head for {@link RainWindBend} to put its vertices back in the world. */
    @Inject(method = "renderSnowAndRain(Lnet/minecraft/client/renderer/LightTexture;FDDD)V", at = @At("HEAD"))
    private void expandedweather2dynamics$beginRainPass(LightTexture lightTexture, float partialTick, double camX, double camY, double camZ, CallbackInfo ci) {
        RainWindBend.beginFrame(camX, camY, camZ);
    }

    /**
     * Bends each vertex of vanilla's rain and snow sheets with the local wind.
     *
     * <p>Every precipitation vertex in the pass goes through this call - four per sheet, rain and snow
     * alike, which is the eight the annotation expects - and moving their positions is the whole
     * intervention: the sprite, the alpha, the fall offset, the biome and light tests and the depth
     * state are still the ones vanilla computed, so this leans vanilla's rain rather than replacing it.
     * One injection is required, so a version that stopped funnelling them through here would fail
     * loudly instead of quietly drawing straight rain; the eight are expected rather than required, so
     * a change in the count is reported without taking the game down over a cosmetic feature.
     */
    @Redirect(method = "renderSnowAndRain(Lnet/minecraft/client/renderer/LightTexture;FDDD)V",
            at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/BufferBuilder;addVertex(FFF)Lcom/mojang/blaze3d/vertex/VertexConsumer;"),
            require = 1, expect = 8)
    private VertexConsumer expandedweather2dynamics$bendRainVertex(BufferBuilder builder, float x, float y, float z) {
        Vec3 bent = RainWindBend.bend(x, y, z);
        return builder.addVertex((float) bent.x, (float) bent.y, (float) bent.z);
    }

    @Inject(method = "renderSnowAndRain(Lnet/minecraft/client/renderer/LightTexture;FDDD)V", at = @At("HEAD"), cancellable = true)
    private void expandedweather2dynamics$cancelVanillaPrecipitationNearWeather2Cells(LightTexture lightTexture, float partialTick, double camX, double camY, double camZ, CallbackInfo ci) {
        if (VolumetricPrecipitationRenderer.shouldBypassVanillaRain()) {
            ci.cancel();
        }
    }

    /**
     * The sun, the moon and the stars, hidden by the mod's own sky rather than by a number written into the
     * level.
     *
     * <p>All three are drawn with one alpha, {@code 1 - getRainLevel(partialTick)}, and that single number is
     * why this mod used to have to write a rain level into the level to get the sun out of the way - which left
     * the sun's hiding in the hands of the game's weather rather than the storm's, since the two are different
     * things. The number is the mod's own now, derived from the same darkness that darkens the sky, the fog, the
     * cloud deck and the world's ambient light, so the sun is gone exactly when the sky is dark and there is no
     * second system to disagree with the first. It is a floor on the game's own rain level rather than a
     * replacement, so an ordinary rainy day still hides the sun the way it always has.
     *
     * <p>Exactly one call is expected, and it is required: this is the sun's own alpha, so a version that
     * stopped funnelling it through here would leave the sun blazing through every storm in the mod rather than
     * failing visibly.
     */
    @Redirect(method = "renderSky(Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;FLnet/minecraft/client/Camera;ZLjava/lang/Runnable;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/ClientLevel;getRainLevel(F)F"),
            require = 1, expect = 1)
    private float expandedweather2dynamics$ownSunHiding(ClientLevel level, float partialTick) {
        return StormSkyClient.sunHidingLevel(level.getRainLevel(partialTick));
    }

    /**
     * Draws the game's own sun and moon with a transparent texture while this mod is drawing its round ones.
     *
     * <p>The two bodies are drawn as textured quads, so the transparent texture leaves the vanilla quads in
     * place - still positioned, still moving, still faded by the rain - and simply collects nothing. That is
     * what lets the replacement be a replacement: the game's pass is untouched and only its result is
     * blanked, and when {@link CelestialRenderer#shouldHideVanilla} says no, which it does if the round
     * bodies are off, the program failed to load or the dimension has no normal sky, the vanilla textures
     * are bound exactly as before and the sun and moon are the game's own. Exactly the two binds this method
     * makes are expected; a change in that count is reported rather than taking the game down.
     */
    @Redirect(method = "renderSky(Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;FLnet/minecraft/client/Camera;ZLjava/lang/Runnable;)V",
            at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;setShaderTexture(ILnet/minecraft/resources/ResourceLocation;)V"),
            require = 2, expect = 2)
    private static void expandedweather2dynamics$hideVanillaCelestial(int index, ResourceLocation location) {
        RenderSystem.setShaderTexture(index,
                CelestialRenderer.shouldHideVanilla(location) ? CelestialRenderer.blankTexture() : location);
    }
}
