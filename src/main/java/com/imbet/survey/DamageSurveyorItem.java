package com.imbet.survey;

import com.imbet.Config;
import com.imbet.network.DamageSurveyResultPayload;
import com.imbet.network.NetworkSafety;
import com.imbet.weather.BlockGrabSafety;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class DamageSurveyorItem extends Item {
    private static final Set<Block> STRIPPED_LOGS = Set.of(
            Blocks.STRIPPED_OAK_LOG,
            Blocks.STRIPPED_SPRUCE_LOG,
            Blocks.STRIPPED_BIRCH_LOG,
            Blocks.STRIPPED_JUNGLE_LOG,
            Blocks.STRIPPED_ACACIA_LOG,
            Blocks.STRIPPED_DARK_OAK_LOG,
            Blocks.STRIPPED_MANGROVE_LOG,
            Blocks.STRIPPED_CHERRY_LOG,
            Blocks.STRIPPED_OAK_WOOD,
            Blocks.STRIPPED_SPRUCE_WOOD,
            Blocks.STRIPPED_BIRCH_WOOD,
            Blocks.STRIPPED_JUNGLE_WOOD,
            Blocks.STRIPPED_ACACIA_WOOD,
            Blocks.STRIPPED_DARK_OAK_WOOD,
            Blocks.STRIPPED_MANGROVE_WOOD,
            Blocks.STRIPPED_CHERRY_WOOD,
            Blocks.STRIPPED_CRIMSON_STEM,
            Blocks.STRIPPED_WARPED_STEM,
            Blocks.STRIPPED_CRIMSON_HYPHAE,
            Blocks.STRIPPED_WARPED_HYPHAE
    );

    public DamageSurveyorItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Player player = context.getPlayer();
        Level level = context.getLevel();
        if (!(level instanceof ServerLevel serverLevel) || player == null) {
            return InteractionResult.SUCCESS;
        }
        DamageSurveyResult result = completeSurvey(serverLevel, context.getClickedPos());
        sendSurvey(player, result);
        return InteractionResult.CONSUME;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand usedHand) {
        ItemStack stack = player.getItemInHand(usedHand);
        if (level instanceof ServerLevel serverLevel) {
            DamageSurveyResult result = completeSurvey(serverLevel, player.blockPosition());
            sendSurvey(player, result);
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
    }

    private static DamageSurveyResult completeSurvey(ServerLevel level, BlockPos center) {
        if (!Config.SURVEYS_ENABLED.get()) {
            return new DamageSurveyResult("SURVEYS OFF", "No EF damage", 0, "disabled", 0, 0, 0, 0, 0.0F, 0.0F, level.getGameTime(), level.getGameTime(), center.getX() + 0.5D, center.getZ() + 0.5D, "", List.of());
        }
        int radius = Config.SURVEY_RADIUS_BLOCKS.get();
        SurveyScan scan = survey(level, center, radius);
        TornadoSurveySavedData data = TornadoSurveySavedData.get(level);
        Vec3 surveyPos = Vec3.atCenterOf(center);
        return data.nearestTrack(surveyPos, radius, level.getGameTime(), Config.SURVEY_MAX_TRACK_AGE_TICKS.get(), Config.SURVEY_RECENT_TRACK_WINDOW_TICKS.get())
                .map(track -> resultForTrack(data, track, scan, surveyPos, level.getGameTime()))
                .orElseGet(() -> new DamageSurveyResult(
                        "NO TRACK",
                        scan.efLabel(),
                        scan.estimatedMph,
                        scan.confidence,
                        scan.scouredSurfaceBlocks,
                        scan.debarkedLogs,
                        scan.damagedColumns,
                        scan.pathWidthBlocks,
                        0.0F,
                        0.0F,
                        level.getGameTime(),
                        level.getGameTime(),
                        surveyPos.x,
                        surveyPos.z,
                        "",
                        List.of()
                ));
    }

    private static SurveyScan survey(ServerLevel level, BlockPos center, int radius) {
        SurveyScan result = new SurveyScan(radius);
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int radiusSqr = radius * radius;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz > radiusSqr) {
                    continue;
                }
                BlockPos column = center.offset(dx, 0, dz);
                if (!level.isLoaded(column)) {
                    continue;
                }
                BlockPos surface = BlockGrabSafety.safeSurface(level, column, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES);
                if (surface == null) {
                    continue;
                }
                BlockPos ground = surface.below();
                if (level.isLoaded(ground)) {
                    result.recordGround(ground, level.getBlockState(ground));
                }
                for (int y = -4; y <= 14; y++) {
                    cursor.set(surface.getX(), surface.getY() + y, surface.getZ());
                    if (!level.isLoaded(cursor)) {
                        continue;
                    }
                    result.recordTree(cursor, level.getBlockState(cursor));
                }
            }
        }
        result.finish();
        return result;
    }

    private static DamageSurveyResult resultForTrack(TornadoSurveySavedData data, TornadoSurveySavedData.TornadoTrack track, SurveyScan scan, Vec3 surveyPos, long gameTime) {
        int loggedMph = Math.round(track.maxWindMph());
        int estimatedMph = Math.max(scan.estimatedMph, loggedMph);
        String ef = efLabelFor(estimatedMph);
        String confidence = confidence(scan, track, loggedMph);
        data.markSurveyed(track, ef, estimatedMph, confidence);
        List<DamageSurveyResult.MapPoint> points = track.compactRecentPoints(
                        192,
                        gameTime,
                        Config.SURVEY_RECENT_TRACK_WINDOW_TICKS.get(),
                        surveyPos,
                        Config.SURVEY_TRACK_CONTEXT_BLOCKS.get()
                ).stream()
                .map(DamageSurveyResult.MapPoint::from)
                .collect(Collectors.toList());
        String mergeSummary = String.join(", ", track.mergeHistory());
        return new DamageSurveyResult(
                track.displayId(),
                ef,
                estimatedMph,
                confidence,
                scan.scouredSurfaceBlocks,
                scan.debarkedLogs,
                scan.damagedColumns,
                scan.pathWidthBlocks,
                track.maxWidthBlocks(),
                track.maxWindMph(),
                track.startTick(),
                track.endTick(),
                surveyPos.x,
                surveyPos.z,
                mergeSummary,
                points
        );
    }

    private static void sendSurvey(Player player, DamageSurveyResult result) {
        player.sendSystemMessage(Component.literal("Damage Survey " + result.tornadoId() + ": " + result.efRating() + " estimate, " + result.estimatedMph() + "+ mph, confidence " + result.confidence())
                .withStyle(colorFor(result.efRating())));
        player.sendSystemMessage(Component.literal("Indicators: scoured=" + result.scouredSurfaceBlocks() + ", debarked logs=" + result.debarkedLogs() + ", damaged columns=" + result.damagedColumns() + ", path width ~" + result.pathWidthBlocks() + " blocks (" + result.widthMiles() + " mi).")
                .withStyle(ChatFormatting.GRAY));
        player.sendSystemMessage(Component.literal("In-game estimate only; official EF ratings require trained damage indicators and ground survey.")
                .withStyle(ChatFormatting.DARK_GRAY));
        if (player instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
            NetworkSafety.sendToPlayer(serverPlayer, new DamageSurveyResultPayload(result));
        }
    }

    private static boolean isScouredSurface(BlockState state) {
        return state.is(Blocks.DIRT_PATH)
                || state.is(Blocks.COARSE_DIRT)
                || state.is(Blocks.ROOTED_DIRT)
                || state.is(Blocks.SANDSTONE)
                || state.is(Blocks.RED_SANDSTONE)
                || state.is(Blocks.ICE);
    }

    private static boolean isDebarkedLog(BlockState state) {
        return STRIPPED_LOGS.contains(state.getBlock());
    }

    private static String confidence(SurveyScan scan, TornadoSurveySavedData.TornadoTrack track, int loggedMph) {
        if (scan.damagedColumns >= 80 || scan.debarkedLogs >= 8 || track.points().size() >= 18 && loggedMph >= 111) {
            return "high";
        }
        if (scan.damagedColumns >= 25 || scan.debarkedLogs > 0 || track.points().size() >= 6) {
            return "medium";
        }
        return "low";
    }

    private static String efLabelFor(int estimatedMph) {
        if (estimatedMph <= 0) {
            return "No EF damage";
        }
        if (estimatedMph > 200) {
            return "EF5";
        }
        if (estimatedMph >= 166) {
            return "EF4";
        }
        if (estimatedMph >= 136) {
            return "EF3";
        }
        if (estimatedMph >= 111) {
            return "EF2";
        }
        if (estimatedMph >= 86) {
            return "EF1";
        }
        return "EF0";
    }

    private static ChatFormatting colorFor(String efLabel) {
        return switch (efLabel) {
            case "EF5", "EF4" -> ChatFormatting.DARK_PURPLE;
            case "EF3" -> ChatFormatting.RED;
            case "EF2" -> ChatFormatting.GOLD;
            case "EF1" -> ChatFormatting.YELLOW;
            case "EF0" -> ChatFormatting.GREEN;
            default -> ChatFormatting.GRAY;
        };
    }

    private static final class SurveyScan {
        private final int radius;
        private int scouredSurfaceBlocks;
        private int debarkedLogs;
        private int damagedColumns;
        private int minX = Integer.MAX_VALUE;
        private int maxX = Integer.MIN_VALUE;
        private int minZ = Integer.MAX_VALUE;
        private int maxZ = Integer.MIN_VALUE;
        private int pathWidthBlocks;
        private int estimatedMph;
        private String confidence = "low";

        private SurveyScan(int radius) {
            this.radius = radius;
        }

        private void recordGround(BlockPos pos, BlockState state) {
            if (isScouredSurface(state)) {
                scouredSurfaceBlocks++;
                recordDamageColumn(pos);
            }
        }

        private void recordTree(BlockPos pos, BlockState state) {
            if (isDebarkedLog(state)) {
                debarkedLogs++;
                recordDamageColumn(pos);
            } else if (state.is(BlockTags.LEAVES) && scouredSurfaceBlocks > radius) {
                recordDamageColumn(pos);
            }
        }

        private void recordDamageColumn(BlockPos pos) {
            damagedColumns++;
            minX = Math.min(minX, pos.getX());
            maxX = Math.max(maxX, pos.getX());
            minZ = Math.min(minZ, pos.getZ());
            maxZ = Math.max(maxZ, pos.getZ());
        }

        private void finish() {
            if (damagedColumns <= 0) {
                pathWidthBlocks = 0;
                estimatedMph = 0;
                confidence = "none";
                return;
            }
            pathWidthBlocks = Math.max(maxX - minX + 1, maxZ - minZ + 1);
            int score = scouredSurfaceBlocks + debarkedLogs * 8 + Mth.clamp(pathWidthBlocks, 0, radius * 2);
            if (debarkedLogs >= 20 || scouredSurfaceBlocks >= 120 || pathWidthBlocks >= 80) {
                estimatedMph = 170;
            } else if (debarkedLogs >= 6 || scouredSurfaceBlocks >= 55 || pathWidthBlocks >= 48) {
                estimatedMph = 136;
            } else if (debarkedLogs > 0 || scouredSurfaceBlocks >= 24 || pathWidthBlocks >= 28) {
                estimatedMph = 111;
            } else if (score >= 18) {
                estimatedMph = 86;
            } else {
                estimatedMph = 65;
            }
            confidence = damagedColumns >= 80 || debarkedLogs >= 8 ? "high" : damagedColumns >= 25 || debarkedLogs > 0 ? "medium" : "low";
        }

        private String efLabel() {
            return efLabelFor(estimatedMph);
        }
    }
}
