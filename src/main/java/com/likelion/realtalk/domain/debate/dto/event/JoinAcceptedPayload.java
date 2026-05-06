package com.likelion.realtalk.domain.debate.dto.event;

import java.util.Map;

public record JoinAcceptedPayload(
        String role,
        String side,
        Map<String, Object> snapshot
) {
}
