package com.likelion.realtalk.domain.debate.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.likelion.realtalk.domain.debate.dto.event.JoinAcceptedPayload;
import com.likelion.realtalk.domain.debate.dto.event.JoinRejectedPayload;
import com.likelion.realtalk.domain.debate.dto.request.JoinDebateRequest;
import com.likelion.realtalk.domain.debate.entity.DebateParticipant;
import com.likelion.realtalk.domain.debate.entity.DebateRoom;
import com.likelion.realtalk.domain.debate.repository.DebateParticipantRepository;
import com.likelion.realtalk.domain.debate.repository.DebateRoomRepository;
import com.likelion.realtalk.domain.debate.type.DebateStatus;
import com.likelion.realtalk.domain.debate.type.ParticipantRole;
import com.likelion.realtalk.domain.debate.type.Side;
import com.likelion.realtalk.domain.user.entity.UserProfile;
import com.likelion.realtalk.domain.user.repository.UserProfileRepository;
import com.likelion.realtalk.domain.user.repository.UserRepository;
import com.likelion.realtalk.global.common.WsMessage;
import com.likelion.realtalk.global.exception.CustomException;
import com.likelion.realtalk.global.exception.ErrorCode;
import com.likelion.realtalk.infra.redis.DebateRedisRepository;
import com.likelion.realtalk.infra.redis.RedisPublisher;
import com.likelion.realtalk.infra.redis.dto.ParticipantSessionInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DebateJoinService {

    private final DebateRoomRepository debateRoomRepository;
    private final DebateParticipantRepository debateParticipantRepository;
    private final UserRepository userRepository;
    private final UserProfileRepository userProfileRepository;
    private final DebateRedisRepository debateRedisRepository;
    private final RedisPublisher redisPublisher;
    private final ObjectMapper objectMapper;

    @Transactional
    public void join(JoinDebateRequest request, String sessionId, String userId) {
        DebateRoom room = debateRoomRepository.findByUuid(request.roomUuid())
                .orElse(null);
        if (room == null) {
            publishReject(request.roomUuid(), "ROOM_NOT_FOUND");
            return;
        }

        if (room.getStatus() == DebateStatus.ENDED) {
            publishReject(request.roomUuid(), "ROOM_ENDED");
            return;
        }

        boolean isSpeaker = "SPEAKER".equalsIgnoreCase(request.role());

        if (isSpeaker) {
            if (userId == null) {
                publishReject(request.roomUuid(), "AUTH_REQUIRED");
                return;
            }
            if (room.getStatus() == DebateStatus.STARTED) {
                publishReject(request.roomUuid(), "ROOM_STARTED");
                return;
            }
            Side side = parseSide(request.side());
            if (side == null) {
                publishReject(request.roomUuid(), "INVALID_SIDE");
                return;
            }
            long count = debateParticipantRepository
                    .countByDebateRoomAndParticipantRoleAndSideAndLeftAtIsNull(room, ParticipantRole.SPEAKER, side);
            if (count >= (long) room.getMaxSpeaker() / 2) {
                publishReject(request.roomUuid(), "ROOM_FULL");
                return;
            }

            // ADR-9: 재연결 복원
            if (restoreIfReconnecting(request.roomUuid(), sessionId, userId, room)) {
                return;
            }
        } else {
            long audienceCount = debateParticipantRepository
                    .countByDebateRoomAndParticipantRoleAndLeftAtIsNull(room, ParticipantRole.AUDIENCE);
            if (audienceCount >= room.getMaxAudience()) {
                publishReject(request.roomUuid(), "ROOM_FULL");
                return;
            }
        }

        // 참가자 이름 조회
        String participantName = resolveParticipantName(userId, sessionId);

        // DB 저장
        DebateParticipant participant = DebateParticipant.builder()
                .debateRoom(room)
                .userId(userId != null ? Long.parseLong(userId) : null)
                .participantRole(isSpeaker ? ParticipantRole.SPEAKER : ParticipantRole.AUDIENCE)
                .side(isSpeaker ? parseSide(request.side()) : null)
                .build();
        debateParticipantRepository.save(participant);

        // Redis 저장
        ParticipantSessionInfo sessionInfo = new ParticipantSessionInfo(
                sessionId, userId, participantName,
                isSpeaker ? "SPEAKER" : "AUDIENCE",
                isSpeaker ? request.side() : null);
        addParticipantToRedis(request.roomUuid(), sessionId, sessionInfo);
        debateRedisRepository.setSessionRoom(sessionId, request.roomUuid());

        // JOIN_ACCEPTED 발송
        Map<String, Object> snapshot = buildSnapshot(request.roomUuid(), room);
        JoinAcceptedPayload accepted = new JoinAcceptedPayload(
                isSpeaker ? "SPEAKER" : "AUDIENCE",
                isSpeaker ? request.side() : null,
                snapshot);
        redisPublisher.publish(request.roomUuid(), WsMessage.of("JOIN_ACCEPTED", accepted));

        broadcastParticipantList(request.roomUuid());
    }

    @Transactional
    public void leave(String roomUuid, String sessionId, String userId) {
        debateRedisRepository.removeParticipant(roomUuid, sessionId);
        debateRedisRepository.removeSessionRoom(sessionId);

        if (userId != null) {
            debateRoomRepository.findByUuid(roomUuid).ifPresent(room ->
                    debateParticipantRepository.findByDebateRoomAndLeftAtIsNull(room).stream()
                            .filter(p -> userId.equals(
                                    p.getUserId() != null ? String.valueOf(p.getUserId()) : null))
                            .findFirst()
                            .ifPresent(DebateParticipant::leave));
        }
        broadcastParticipantList(roomUuid);
    }

    // ── helpers ─────────────────────────────────────────────

    private boolean restoreIfReconnecting(String roomUuid, String sessionId, String userId, DebateRoom room) {
        for (Map.Entry<Object, Object> entry : debateRedisRepository.getAllParticipants(roomUuid).entrySet()) {
            String existingSessionId = entry.getKey().toString();
            if (sessionId.equals(existingSessionId)) continue;
            try {
                ParticipantSessionInfo info = objectMapper.readValue(entry.getValue().toString(), ParticipantSessionInfo.class);
                if (userId.equals(info.userId())) {
                    // 재연결: 기존 세션 제거 후 새 세션으로 복원
                    debateRedisRepository.removeParticipant(roomUuid, existingSessionId);
                    debateRedisRepository.removeSessionRoom(existingSessionId);
                    ParticipantSessionInfo updated = new ParticipantSessionInfo(
                            sessionId, info.userId(), info.name(), info.role(), info.side());
                    addParticipantToRedis(roomUuid, sessionId, updated);
                    debateRedisRepository.setSessionRoom(sessionId, roomUuid);

                    Map<String, Object> snapshot = buildSnapshot(roomUuid, room);
                    JoinAcceptedPayload accepted = new JoinAcceptedPayload(info.role(), info.side(), snapshot);
                    redisPublisher.publish(roomUuid, WsMessage.of("JOIN_ACCEPTED", accepted));
                    log.info("Reconnected user {} to room {}", userId, roomUuid);
                    return true;
                }
            } catch (Exception ignored) {}
        }
        return false;
    }

    private Map<String, Object> buildSnapshot(String roomUuid, DebateRoom room) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("debateStatus", room.getStatus().name());

        long now = System.currentTimeMillis();
        debateRedisRepository.getDebateTimerEndAt(roomUuid)
                .ifPresent(e -> snapshot.put("debateRemainingSeconds", Math.max(0, (e - now) / 1000)));
        debateRedisRepository.getSpeakerTimerEndAt(roomUuid)
                .ifPresent(e -> snapshot.put("speakerRemainingSeconds", Math.max(0, (e - now) / 1000)));

        String currentSpeakerId = debateRedisRepository.getCurrentSpeaker(roomUuid).orElse(null);
        if (currentSpeakerId != null) {
            for (Object v : debateRedisRepository.getAllParticipants(roomUuid).values()) {
                try {
                    ParticipantSessionInfo p = objectMapper.readValue(v.toString(), ParticipantSessionInfo.class);
                    if (currentSpeakerId.equals(p.userId())) {
                        snapshot.put("currentSpeaker", Map.of(
                                "userId", p.userId(), "name", p.name(), "side", Objects.toString(p.side(), "")));
                        break;
                    }
                } catch (Exception ignored) {}
            }
        }

        // 참가자 목록
        List<Map<String, String>> speakersA = new ArrayList<>();
        List<Map<String, String>> speakersB = new ArrayList<>();
        List<Map<String, String>> audiences = new ArrayList<>();
        for (Object v : debateRedisRepository.getAllParticipants(roomUuid).values()) {
            try {
                ParticipantSessionInfo p = objectMapper.readValue(v.toString(), ParticipantSessionInfo.class);
                Map<String, String> entry = new HashMap<>();
                entry.put("userId", Objects.toString(p.userId(), ""));
                entry.put("name", p.name());
                if ("SPEAKER".equals(p.role())) {
                    entry.put("side", Objects.toString(p.side(), ""));
                    if ("A".equals(p.side())) speakersA.add(entry);
                    else speakersB.add(entry);
                } else {
                    audiences.add(entry);
                }
            } catch (Exception ignored) {}
        }
        snapshot.put("participants", Map.of("speakersA", speakersA, "speakersB", speakersB, "audiences", audiences));

        // 최근 채팅 20개
        List<Object> chats = new ArrayList<>();
        for (String chatJson : debateRedisRepository.getRecentChats(roomUuid, 20)) {
            try {
                chats.add(objectMapper.readValue(chatJson, Map.class));
            } catch (Exception ignored) {}
        }
        snapshot.put("recentChats", chats);
        return snapshot;
    }

    void broadcastParticipantList(String roomUuid) {
        List<Map<String, String>> speakersA = new ArrayList<>();
        List<Map<String, String>> speakersB = new ArrayList<>();
        List<Map<String, String>> audiences = new ArrayList<>();
        for (Object v : debateRedisRepository.getAllParticipants(roomUuid).values()) {
            try {
                ParticipantSessionInfo p = objectMapper.readValue(v.toString(), ParticipantSessionInfo.class);
                Map<String, String> entry = Map.of(
                        "userId", Objects.toString(p.userId(), ""), "name", p.name());
                if ("SPEAKER".equals(p.role())) {
                    if ("A".equals(p.side())) speakersA.add(entry);
                    else speakersB.add(entry);
                } else {
                    audiences.add(entry);
                }
            } catch (Exception ignored) {}
        }
        redisPublisher.publish(roomUuid, WsMessage.of("PARTICIPANT_LIST",
                Map.of("speakersA", speakersA, "speakersB", speakersB, "audiences", audiences)));
    }

    private void publishReject(String roomUuid, String reason) {
        redisPublisher.publish(roomUuid, WsMessage.of("JOIN_REJECTED", new JoinRejectedPayload(reason)));
    }

    private String resolveParticipantName(String userId, String sessionId) {
        if (userId == null) {
            return "Guest_" + sessionId.substring(0, Math.min(6, sessionId.length()));
        }
        try {
            return userRepository.findById(Long.parseLong(userId))
                    .map(user -> userProfileRepository.findByUser(user)
                            .map(UserProfile::getNickname)
                            .filter(n -> n != null && !n.isBlank())
                            .orElse(user.getUsername()))
                    .orElse("Unknown");
        } catch (Exception e) {
            return "Unknown";
        }
    }

    private void addParticipantToRedis(String roomUuid, String sessionId, ParticipantSessionInfo info) {
        try {
            debateRedisRepository.addParticipant(roomUuid, sessionId, objectMapper.writeValueAsString(info));
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize participant for room {}", roomUuid, e);
        }
    }

    private Side parseSide(String side) {
        if (side == null) return null;
        try {
            return Side.valueOf(side.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
