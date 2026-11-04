package com.imbet.mixin;

import com.imbet.client.FoliageWindBlocks;
import com.imbet.client.FoliageWindController;
import com.imbet.client.FoliageWindVertexConsumer;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(BlockRenderDispatcher.class)
public abstract class BlockRenderDispatcherMixin {
    @Redirect(
            method = "renderBatched(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/BlockAndTintGetter;Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;ZLnet/minecraft/util/RandomSource;Lnet/neoforged/neoforge/client/model/data/ModelData;Lnet/minecraft/client/renderer/RenderType;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/block/ModelBlockRenderer;tesselateBlock(Lnet/minecraft/world/level/BlockAndTintGetter;Lnet/minecraft/client/resources/model/BakedModel;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;ZLnet/minecraft/util/RandomSource;JILnet/neoforged/neoforge/client/model/data/ModelData;Lnet/minecraft/client/renderer/RenderType;)V"
            )
    )
    private void expandedweather2dynamics$tagFoliageVertices(ModelBlockRenderer renderer, BlockAndTintGetter level, BakedModel model, BlockState state, BlockPos pos, PoseStack poseStack, VertexConsumer consumer, boolean checkSides, RandomSource random, long seed, int packedOverlay, ModelData modelData, RenderType renderType) {
        VertexConsumer target = consumer;
        int foliageClass = FoliageWindBlocks.classFor(state);
        if (foliageClass != FoliageWindBlocks.NONE && FoliageWindController.enabled() && state.getRenderShape() == RenderShape.MODEL && isSupportedCutoutLayer(renderType)) {
            // Section meshes bake their vertices relative to the section origin, so the row the block sits in
            // is what turns a vertex's Y into a height up its own plant. The mask is the section size, and is
            // exact for negative Y as well - it is the same arithmetic the section grid itself is built on.
            target = new FoliageWindVertexConsumer(consumer, foliageClass, pos.getY() & SECTION_MASK);
        }
        renderer.tesselateBlock(level, model, state, pos, poseStack, target, checkSides, random, seed, packedOverlay, modelData, renderType);
    }

    private static final int SECTION_MASK = 15;

    private static boolean isSupportedCutoutLayer(RenderType renderType) {
        return renderType == RenderType.cutout() || renderType == RenderType.cutoutMipped();
    }
}
