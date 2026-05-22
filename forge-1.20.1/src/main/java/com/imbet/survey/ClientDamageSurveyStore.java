package com.imbet.survey;

import com.imbet.network.DamageSurveyResultPayload;

public final class ClientDamageSurveyStore {
    private static DamageSurveyResult pendingResult;

    private ClientDamageSurveyStore() {
    }

    public static void accept(DamageSurveyResultPayload payload) {
        pendingResult = payload.result();
    }

    public static DamageSurveyResult consume() {
        DamageSurveyResult result = pendingResult;
        pendingResult = null;
        return result;
    }
}
