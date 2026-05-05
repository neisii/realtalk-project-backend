package com.likelion.realtalk.global.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    // Auth
    UNAUTHORIZED(401, "인증이 필요합니다."),
    TOKEN_EXPIRED(401, "토큰이 만료되었습니다."),
    FORBIDDEN(403, "접근 권한이 없습니다."),

    // User
    USER_NOT_FOUND(404, "사용자를 찾을 수 없습니다."),

    // Debate Room
    DEBATE_ROOM_NOT_FOUND(404, "토론 방을 찾을 수 없습니다."),
    ROOM_ALREADY_STARTED(409, "이미 시작된 토론 방입니다."),
    ROOM_ALREADY_ENDED(409, "이미 종료된 토론 방입니다."),
    ROOM_FULL(409, "정원이 초과되었습니다."),
    INSUFFICIENT_SPEAKERS(422, "발언자가 없어 토론을 시작할 수 없습니다."),

    // Vote
    DUPLICATE_VOTE(400, "이미 투표했습니다."),

    // Category / Topic
    CATEGORY_NOT_FOUND(404, "카테고리를 찾을 수 없습니다."),
    TOPIC_NOT_FOUND(404, "토론 주제를 찾을 수 없습니다."),
    DUPLICATE_TOPIC(409, "이미 존재하는 토론 주제입니다."),

    // AI / STT
    AI_PROCESSING_FAILED(500, "AI 처리에 실패했습니다."),
    STT_PROCESSING_FAILED(500, "음성 변환에 실패했습니다."),

    // Common
    INVALID_INPUT(400, "입력값이 올바르지 않습니다."),
    INTERNAL_SERVER_ERROR(500, "서버 오류가 발생했습니다.");

    private final int status;
    private final String message;
}
