package com.imbet.mixin;

import com.imbet.Config;
import com.imbet.registry.ModVillagers;
import com.imbet.village.SuppliesCarry;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.layers.CrossedArmsItemLayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Makes a builder's carried supplies visibly deplete.
 *
 * <p>The crossed-arms layer draws whatever a villager holds in its main hand, and an item stack drawn in
 * the world shows no count - so eight blocks look exactly like one. Rather than replace the layer and
 * risk drifting from vanilla's pose, this is aimed at the one instruction that draws the item: by the
 * time control reaches it the pose has already been translated into the villager's crossed arms and
 * rotated, so scaling the pose here shrinks the block where it sits, in the arms, and nothing else about
 * the villager changes. The scale comes from {@link SuppliesCarry}, the same arithmetic the goal's tally
 * feeds, so what shrinks is the real count and not a decoration.
 *
 * <p>Scoped to the builder profession: every other villager is drawn exactly as vanilla draws it.
 */
@Mixin(CrossedArmsItemLayer.class)
public abstract class CrossedArmsItemLayerMixin {
    private static final String RENDER = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/world/entity/LivingEntity;FFFFFF)V";
    private static final String RENDER_ITEM = "Lnet/minecraft/client/renderer/ItemInHandRenderer;renderItem(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemDisplayContext;ZLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V";

    /**
     * The erasure of {@code T livingEntity} is named in the descriptor on purpose: the class carries a
     * bridge method with the same name, so a mixin that matched on the name alone would be ambiguous.
     */
    @Inject(method = RENDER, at = @At(value = "INVOKE", target = RENDER_ITEM))
    private void expandedweather2dynamics$shrinkCarriedSupplies(
            PoseStack poseStack,
            MultiBufferSource buffer,
            int packedLight,
            LivingEntity livingEntity,
            float limbSwing,
            float limbSwingAmount,
            float partialTicks,
            float ageInTicks,
            float netHeadYaw,
            float headPitch,
            CallbackInfo callback) {
        if (!(livingEntity instanceof Villager villager)) {
            return;
        }
        if (villager.getVillagerData().getProfession() != ModVillagers.BUILDER.get()) {
            return;
        }
        ItemStack carried = villager.getItemBySlot(EquipmentSlot.MAINHAND);
        float scale = SuppliesCarry.displayScale(carried.getCount(), Config.AI_VILLAGER_BUILDER_CARRY_LIMIT.get());
        if (scale < 1.0F) {
            poseStack.scale(scale, scale, scale);
        }
    }
}
