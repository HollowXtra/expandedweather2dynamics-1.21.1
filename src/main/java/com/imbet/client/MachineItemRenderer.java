package com.imbet.client;

import com.imbet.alert.SirenTone;
import com.imbet.alert.TornadoSirenBlock;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;

/**
 * A machine in the hand or the inventory, drawn from the same geometry as the machine in the world.
 *
 * <p>The items used to carry a JSON sketch of their machine, which was a second model to keep in step with the first
 * and looked like neither once the real one stopped being made of boxes. Now there is one model of each machine, so a
 * siren in the hotbar is the siren that gets placed. Its item model only says how large to draw it where.
 */
public final class MachineItemRenderer extends BlockEntityWithoutLevelRenderer {
    private static MachineItemRenderer instance;

    /** Hands a single shared renderer to every machine item. */
    public static final IClientItemExtensions EXTENSIONS = new IClientItemExtensions() {
        @Override
        public BlockEntityWithoutLevelRenderer getCustomRenderer() {
            if (instance == null) {
                Minecraft minecraft = Minecraft.getInstance();
                instance = new MachineItemRenderer(minecraft);
            }
            return instance;
        }
    };

    private MachineItemRenderer(Minecraft minecraft) {
        super(minecraft.getBlockEntityRenderDispatcher(), minecraft.getEntityModels());
    }

    @Override
    public void renderByItem(ItemStack stack, ItemDisplayContext context, PoseStack pose, MultiBufferSource buffer, int packedLight, int packedOverlay) {
        if (stack.getItem() instanceof BlockItem item && item.getBlock() instanceof TornadoSirenBlock siren) {
            SirenTone tone = siren.tone();
            TornadoSirenRenderer.draw(tone, 0.0F, 0.0F, false, 0xFFFFFFFF, pose, buffer, packedLight, packedOverlay);
        }
    }
}
