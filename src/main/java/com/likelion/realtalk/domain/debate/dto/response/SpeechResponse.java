package com.likelion.realtalk.domain.debate.dto.response;

import com.likelion.realtalk.domain.debate.entity.DebateSpeech;

import java.time.LocalDateTime;

public record SpeechResponse(
        int turnIndex,
        String side,
        String speakerName,
        String transcript,
        String aiSummary,
        LocalDateTime spokenAt
) {
    public static SpeechResponse from(DebateSpeech speech, String speakerName) {
        return new SpeechResponse(
                speech.getTurnIndex(),
                speech.getSide().name(),
                speakerName,
                speech.getTranscript(),
                speech.getAiSummary(),
                speech.getSpokenAt()
        );
    }
}
