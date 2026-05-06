package com.likelion.realtalk.infra.redis.dto;

public record ParticipantSessionInfo(
        String sessionId,
        String userId,
        String name,
        String role,
        String side
) {
}
