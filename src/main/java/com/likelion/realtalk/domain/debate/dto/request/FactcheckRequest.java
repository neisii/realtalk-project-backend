package com.likelion.realtalk.domain.debate.dto.request;

public record FactcheckRequest(String roomUuid, int turnIndex, String claim) {
}
