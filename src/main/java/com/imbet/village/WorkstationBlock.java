package com.imbet.village;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.List;

/**
 * A villager workstation.
 *
 * <p>The two new jobs needed a home, and a home in Minecraft is a block: the village's own rule is that
 * a villager takes a profession by claiming a point of interest, so a surveyor needs a survey station
 * to claim and a builder needs a bench. These are deliberately plain blocks - the interest is the
 * profession, and a workstation that does something on top of that would be two features wearing one
 * name.
 *
 * <p>Using one tells you what it is for and who to put to work there, because a block that silently
 * creates a job is a block nobody finds - and, where the workstation has something to say about its own
 * village, what it is waiting for, because a job that does nothing looks exactly like a job that is
 * broken.
 */
public abstract class WorkstationBlock extends Block {
    private final String jobName;
    private final String[] description;
    private final VoxelShape silhouette;

    public WorkstationBlock(Properties properties, VoxelShape silhouette, String jobName, String... description) {
        super(properties);
        this.silhouette = silhouette;
        this.jobName = jobName;
        this.description = description;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return silhouette;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        if (player instanceof ServerPlayer serverPlayer) {
            serverPlayer.sendSystemMessage(Component.literal(jobName).withStyle(ChatFormatting.AQUA));
            for (String line : description) {
                serverPlayer.sendSystemMessage(Component.literal(line).withStyle(ChatFormatting.GRAY));
            }
            serverPlayer.sendSystemMessage(Component.literal(
                    "  Place it in a village and a villager without a job will take the post.")
                    .withStyle(ChatFormatting.DARK_GRAY));
            for (String line : statusLines(level, pos)) {
                serverPlayer.sendSystemMessage(Component.literal(line).withStyle(ChatFormatting.YELLOW));
            }
        }
        return InteractionResult.SUCCESS;
    }

    /**
     * What this workstation has to say about the village around it, or nothing at all.
     *
     * <p>Empty by default: most workstations are only their job. The bench overrides it because a builder
     * can be idle for four different reasons and a player has no way to tell them apart.
     */
    protected List<String> statusLines(Level level, BlockPos pos) {
        return List.of();
    }
}
