package com.likelion.realtalk.domain.debate.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateTopicRequest(
        @NotBlank @Size(max = 200) String title
) {
}
