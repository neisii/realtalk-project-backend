package com.likelion.realtalk.domain.debate.dto.event;

public record TimerTickEvent(
        long debateRemainingSeconds,
        long speakerRemainingSeconds,
        String currentSpeakerUserId,
        String currentSpeakerName,
        String side
) {
}
