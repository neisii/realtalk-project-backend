package com.likelion.realtalk.domain.debate.dto.event;

public record DebateStartedPayload(
        int totalDurationSecs,
        int turnDurationSecs,
        String firstSpeakerUserId,
        String firstSpeakerName,
        String firstSpeakerSide
) {
}
