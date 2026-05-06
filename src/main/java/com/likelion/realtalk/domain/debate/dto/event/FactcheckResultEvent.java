package com.likelion.realtalk.domain.debate.dto.event;

public record FactcheckResultEvent(
        int turnIndex,
        String verdict,
        String explanation
) {
}
