package com.imbet.network;

import com.imbet.survey.DamageSurveyResult;
import net.minecraft.network.FriendlyByteBuf;

public record DamageSurveyResultPayload(DamageSurveyResult result) {
    public void write(FriendlyByteBuf buf) {
        (result == null ? DamageSurveyResult.NONE : result).write(buf);
    }

    public static DamageSurveyResultPayload read(FriendlyByteBuf buf) {
        return new DamageSurveyResultPayload(DamageSurveyResult.read(buf));
    }
}
