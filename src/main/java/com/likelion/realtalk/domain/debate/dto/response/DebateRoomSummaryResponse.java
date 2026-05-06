package com.likelion.realtalk.domain.debate.dto.response;

import java.time.LocalDateTime;

public record DebateRoomSummaryResponse(
        String uuid,
        String title,
        String sideA,
        String sideB,
        String status,
        String debateType,
        String categoryName,
        long speakersA,
        long speakersB,
        long audiences,
        int maxSpeaker,
        int maxAudience,
        LocalDateTime createdAt
) {
}
