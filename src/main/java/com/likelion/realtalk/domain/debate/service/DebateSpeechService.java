package com.likelion.realtalk.domain.debate.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.likelion.realtalk.domain.debate.type.Side;
import com.likelion.realtalk.infra.pipeline.SpeechPipeline;
import com.likelion.realtalk.infra.redis.DebateRedisRepository;
import com.likelion.realtalk.infra.redis.dto.ParticipantSessionInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class DebateSpeechService {

    private final SpeakerService speakerService;
    private final SpeechPipeline speechPipeline;
    private final DebateRedisRepository debateRedisRepository;
    private final ObjectMapper objectMapper;

    public void handleSpeechEnd(String roomUuid, int turnIndex, String sessionId, Long speakerUserId) {
        // 1. 즉시 턴 전환 (원자적)
        speakerService.advanceTurn(roomUuid);

        // 2. 발언자 side 조회
        Side side = resolveSide(roomUuid, sessionId);

        // 3. 비동기 STT → AI 파이프라인 (핸들러 스레드 즉시 반환)
        speechPipeline.processAsync(new byte[0], roomUuid, turnIndex, speakerUserId, side);
    }

    private Side resolveSide(String roomUuid, String sessionId) {
        return debateRedisRepository.getParticipant(roomUuid, sessionId)
                .map(json -> {
                    try {
                        ParticipantSessionInfo info = objectMapper.readValue(json, ParticipantSessionInfo.class);
                        return info.side() != null ? Side.valueOf(info.side()) : null;
                    } catch (Exception e) {
                        return null;
                    }
                })
                .orElse(null);
    }
}
