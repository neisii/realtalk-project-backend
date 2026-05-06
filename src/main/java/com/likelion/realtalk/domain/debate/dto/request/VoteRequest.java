package com.likelion.realtalk.domain.debate.dto.request;

import com.likelion.realtalk.domain.debate.type.Side;
import jakarta.validation.constraints.NotNull;

public record VoteRequest(@NotNull Side side) {
}
