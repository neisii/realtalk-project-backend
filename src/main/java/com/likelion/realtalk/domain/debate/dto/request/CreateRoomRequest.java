package com.likelion.realtalk.domain.debate.dto.request;

import com.likelion.realtalk.domain.debate.type.DebateType;
import jakarta.validation.constraints.*;

public record CreateRoomRequest(
        @NotBlank @Size(max = 255) String title,
        String description,
        Long categoryId,
        @NotBlank @Size(max = 255) String sideA,
        @NotBlank @Size(max = 255) String sideB,
        @Min(10) @Max(600) int turnDurationSecs,
        @Min(60) @Max(7200) int totalDurationSecs,
        @Min(2) @Max(10) int maxSpeaker,
        @Min(0) @Max(1000) int maxAudience,
        DebateType debateType
) {
}
