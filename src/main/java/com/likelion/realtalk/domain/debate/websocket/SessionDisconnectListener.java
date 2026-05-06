package com.likelion.realtalk.domain.debate.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.likelion.realtalk.domain.debate.service.SpeakerService;
import com.likelion.realtalk.infra.redis.DebateRedisRepository;
import com.likelion.realtalk.infra.redis.RedisPublisher;
import com.likelion.realtalk.infra.redis.dto.ParticipantSessionInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class SessionDisconnectListener {

    private final DebateRedisRepository debateRedisRepository;
    private final SpeakerService speakerService;
    private final RedisPublisher redisPublisher;
    private final ObjectMapper objectMapper;

    @EventListener
    public void handleDisconnect(SessionDisconnectEvent event) {
        String sessionId = event.getSessionId();

        debateRedisRepository.getSessionRoom(sessionId).ifPresent(roomUuid -> {
            try {
                processDisconnect(sessionId, roomUuid);
            } catch (Exception e) {
                log.error("Error processing disconnect for session {} in room {}", sessionId, roomUuid, e);
            }
        });
    }

    private void processDisconnect(String sessionId, String roomUuid) {
        String currentSpeakerId = debateRedisRepository.getCurrentSpeaker(roomUuid).orElse(null);

        ParticipantSessionInfo participant = debateRedisRepository
                .getParticipant(roomUuid, sessionId)
                .map(json -> {
                    try {
                        return objectMapper.readValue(json, ParticipantSessionInfo.class);
                    } catch (Exception e) {
                        log.warn("Failed to parse participant json for session {}", sessionId);
                        return null;
                    }
                })
                .orElse(null);

        debateRedisRepository.removeParticipant(roomUuid, sessionId);
        debateRedisRepository.removeSessionRoom(sessionId);

        log.debug("Session {} disconnected from room {}", sessionId, roomUuid);

        // ADR-8: Speaker disconnect 시 자동 턴 전환
        if (participant != null
                && "SPEAKER".equals(participant.role())
                && participant.userId() != null
                && participant.userId().equals(currentSpeakerId)) {
            log.info("Current speaker {} disconnected from room {} — advancing turn", participant.userId(), roomUuid);
            speakerService.advanceTurn(roomUuid);
        }

        // 참가자 목록 변경 브로드캐스트 (실제 목록 빌드는 6단계 DebateRoomService에서 완성)
        broadcastParticipantList(roomUuid);
    }

    private void broadcastParticipantList(String roomUuid) {
        try {
            Map<Object, Object> raw = debateRedisRepository.getAllParticipants(roomUuid);
            redisPublisher.publish(roomUuid,
                    com.likelion.realtalk.global.common.WsMessage.of("PARTICIPANT_LIST", raw));
        } catch (Exception e) {
            log.warn("Failed to broadcast PARTICIPANT_LIST for room {}", roomUuid, e);
        }
    }
}
