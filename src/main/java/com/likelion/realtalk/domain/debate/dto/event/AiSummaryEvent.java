package com.likelion.realtalk.domain.debate.dto.event;

public record AiSummaryEvent(
        int turnIndex,
        String side,
        String summary
) {
}
