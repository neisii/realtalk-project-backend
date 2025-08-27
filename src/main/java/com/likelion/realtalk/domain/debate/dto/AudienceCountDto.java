package com.likelion.realtalk.domain.debate.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
public class AudienceCountDto {

  private Long audienceCount;

  @Builder
  public AudienceCountDto(Long audienceCount) {
    this.audienceCount = audienceCount;
  }
}