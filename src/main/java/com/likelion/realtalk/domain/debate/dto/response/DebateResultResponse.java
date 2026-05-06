package com.likelion.realtalk.domain.debate.dto.response;

import com.likelion.realtalk.domain.debate.entity.DebateResult;

public record DebateResultResponse(
        String aiAnalysis,
        int sideAVotes,
        int sideBVotes,
        int totalVotes,
        double sideARate,
        double sideBRate
) {
    public static DebateResultResponse from(DebateResult result) {
        int total = result.getSideAVotes() + result.getSideBVotes();
        double aRate = total == 0 ? 0.0 : (double) result.getSideAVotes() / total * 100;
        double bRate = total == 0 ? 0.0 : (double) result.getSideBVotes() / total * 100;
        return new DebateResultResponse(
                result.getAiAnalysis(),
                result.getSideAVotes(),
                result.getSideBVotes(),
                total,
                Math.round(aRate * 10.0) / 10.0,
                Math.round(bRate * 10.0) / 10.0
        );
    }
}
