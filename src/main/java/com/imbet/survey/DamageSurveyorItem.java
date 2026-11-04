package com.imbet.survey;

import com.imbet.Config;
import com.imbet.network.DamageSurveyResultPayload;
import com.imbet.network.NetworkSafety;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.stream.Collectors;

public class DamageSurveyorItem extends Item {

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
        DamageScan scan = DamageScanner.scan(level, center, radius);
        TornadoSurveySavedData data = TornadoSurveySavedData.get(level);
        Vec3 surveyPos = Vec3.atCenterOf(center);
        return data.nearestTrack(surveyPos, radius, level.getGameTime(), Config.SURVEY_MAX_TRACK_AGE_TICKS.get(), Config.SURVEY_RECENT_TRACK_WINDOW_TICKS.get())
                .map(track -> resultForTrack(data, track, scan, surveyPos, level.getGameTime()))
                .orElseGet(() -> new DamageSurveyResult(
                        "NO TRACK",
                        scan.efLabel(),
                        scan.estimatedMph(),
                        scan.confidence(),
                        scan.scouredSurfaceBlocks(),
                        scan.debarkedLogs(),
                        scan.damagedColumns(),
                        scan.pathWidthBlocks(),
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

    private static DamageSurveyResult resultForTrack(TornadoSurveySavedData data, TornadoSurveySavedData.TornadoTrack track, DamageScan scan, Vec3 surveyPos, long gameTime) {
        int loggedMph = Math.round(track.maxWindMph());
        int estimatedMph = scan.rankedMph(loggedMph);
        String ef = DamageScan.efLabelFor(estimatedMph);
        String confidence = scan.confidenceWithTrack(track.points().size(), loggedMph);
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
                scan.scouredSurfaceBlocks(),
                scan.debarkedLogs(),
                scan.damagedColumns(),
                scan.pathWidthBlocks(),
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
}
