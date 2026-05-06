package com.likelion.realtalk.domain.debate.dto.request;

import jakarta.validation.constraints.NotNull;

public record MatchRoomRequest(@NotNull Long categoryId) {
}
