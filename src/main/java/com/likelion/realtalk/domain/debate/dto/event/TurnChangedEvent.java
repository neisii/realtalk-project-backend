package com.likelion.realtalk.domain.debate.dto.event;

public record TurnChangedEvent(
        int turnIndex,
        String currentSpeakerUserId,
        String currentSpeakerName,
        String side
) {
}
