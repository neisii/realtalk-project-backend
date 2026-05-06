package com.likelion.realtalk.domain.debate.dto.response;

import java.time.LocalDateTime;

public record DebateRoomDetailResponse(
        String uuid,
        String title,
        String description,
        String sideA,
        String sideB,
        String status,
        String debateType,
        String categoryName,
        int turnDurationSecs,
        int totalDurationSecs,
        long speakersA,
        long speakersB,
        long audiences,
        int maxSpeaker,
        int maxAudience,
        LocalDateTime startedAt,
        LocalDateTime endedAt,
        LocalDateTime createdAt
) {
}
