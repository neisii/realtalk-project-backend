package com.likelion.realtalk.domain.debate.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.likelion.realtalk.domain.debate.dto.event.DebateEndedEvent;
import com.likelion.realtalk.domain.debate.dto.event.TurnChangedEvent;
import com.likelion.realtalk.domain.debate.entity.DebateRoom;
import com.likelion.realtalk.domain.debate.repository.DebateRoomRepository;
import com.likelion.realtalk.domain.debate.type.DebateStatus;
import com.likelion.realtalk.global.common.WsMessage;
import com.likelion.realtalk.global.exception.CustomException;
import com.likelion.realtalk.global.exception.ErrorCode;
import com.likelion.realtalk.infra.redis.DebateRedisRepository;
import com.likelion.realtalk.infra.redis.RedisPublisher;
import com.likelion.realtalk.infra.redis.TurnAdvanceLuaScript;
import com.likelion.realtalk.infra.redis.dto.ParticipantSessionInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class SpeakerService {

    private final DebateRedisRepository debateRedisRepository;
    private final TurnAdvanceLuaScript turnAdvanceLuaScript;
    private final RedisPublisher redisPublisher;
    private final DebateRoomRepository debateRoomRepository;
    private final ObjectMapper objectMapper;

    @Transactional
    public void advanceTurn(String roomUuid) {
        int currentTurnIndex = debateRedisRepository.getCurrentTurn(roomUuid).orElse(0);

        // 동시 이벤트(타이머 만료 + disconnect) 중 하나만 처리
        String lockKey = "room:" + roomUuid + ":turn_lock";
        boolean acquired = turnAdvanceLuaScript.tryAcquireLock(lockKey, 5);
        if (!acquired) {
            log.debug("Turn advance lock not acquired for room {} turn {}", roomUuid, currentTurnIndex);
            return;
        }

        try {
            String currentSpeakerId = debateRedisRepository.getCurrentSpeaker(roomUuid).orElse(null);
            if (currentSpeakerId != null) {
                debateRedisRepository.addSpokenUser(roomUuid, currentSpeakerId);
            }

            List<ParticipantSessionInfo> speakers = loadSpeakers(roomUuid);
            if (speakers.isEmpty()) {
                handleNoSpeakers(roomUuid);
                return;
            }

            Set<String> spokenUsers = debateRedisRepository.getSpokenUsers(roomUuid);
            String nextSpeakerId = findNextSpeaker(speakers, spokenUsers, currentSpeakerId);

            if (nextSpeakerId == null) {
                // 이번 라운드 모두 발언 완료 → 새 라운드 시작
                debateRedisRepository.clearSpokenUsers(roomUuid);
                nextSpeakerId = findNextSpeaker(speakers, Collections.emptySet(), null);
            }

            if (nextSpeakerId == null) {
                handleNoSpeakers(roomUuid);
                return;
            }

            final String resolvedNextSpeakerId = nextSpeakerId;
            int newTurnIndex = currentTurnIndex + 1;
            debateRedisRepository.setCurrentTurn(roomUuid, newTurnIndex);
            debateRedisRepository.setCurrentSpeaker(roomUuid, resolvedNextSpeakerId);

            DebateRoom room = debateRoomRepository.findByUuid(roomUuid)
                    .orElseThrow(() -> new CustomException(ErrorCode.DEBATE_ROOM_NOT_FOUND));
            long newEndAt = System.currentTimeMillis() + (long) room.effectiveTurnDurationSecs() * 1000;
            debateRedisRepository.setSpeakerTimerEndAt(roomUuid, newEndAt);

            final int finalTurnIndex = newTurnIndex;
            speakers.stream()
                    .filter(p -> resolvedNextSpeakerId.equals(p.userId()))
                    .findFirst()
                    .ifPresent(nextSpeaker -> redisPublisher.publish(roomUuid, WsMessage.of(
                            "TURN_CHANGED",
                            new TurnChangedEvent(finalTurnIndex, nextSpeaker.userId(), nextSpeaker.name(), nextSpeaker.side())
                    )));

        } finally {
            turnAdvanceLuaScript.releaseLock(lockKey);
        }
    }

    private void handleNoSpeakers(String roomUuid) {
        log.info("No speakers in room {} — ending debate", roomUuid);
        debateRedisRepository.removeActiveRoom(roomUuid);
        debateRoomRepository.findByUuid(roomUuid).ifPresent(room -> {
            if (room.getStatus() == DebateStatus.STARTED) {
                room.end();
                debateRoomRepository.save(room);
            }
        });
        redisPublisher.publish(roomUuid, WsMessage.of("DEBATE_ENDED", new DebateEndedEvent()));
    }

    private List<ParticipantSessionInfo> loadSpeakers(String roomUuid) {
        return debateRedisRepository.getAllParticipants(roomUuid).values().stream()
                .map(v -> {
                    try {
                        return objectMapper.readValue(v.toString(), ParticipantSessionInfo.class);
                    } catch (Exception e) {
                        log.warn("Failed to parse participant in room {}: {}", roomUuid, v);
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .filter(p -> "SPEAKER".equals(p.role()))
                .toList();
    }

    // A/B 교대 발언: 현재 발언자 반대편 → 같은편 순으로 다음 미발언자 탐색
    private String findNextSpeaker(List<ParticipantSessionInfo> speakers,
                                    Set<String> spokenUsers,
                                    String currentSpeakerId) {
        String currentSide = speakers.stream()
                .filter(p -> p.userId() != null && p.userId().equals(currentSpeakerId))
                .map(ParticipantSessionInfo::side)
                .findFirst()
                .orElse("A");

        String oppositeSide = "A".equals(currentSide) ? "B" : "A";

        // 반대 측 미발언자 우선
        Optional<String> fromOpposite = speakers.stream()
                .filter(p -> oppositeSide.equals(p.side()))
                .filter(p -> p.userId() != null && !spokenUsers.contains(p.userId()))
                .map(ParticipantSessionInfo::userId)
                .findFirst();
        if (fromOpposite.isPresent()) return fromOpposite.get();

        // 같은 측 미발언자 차선
        return speakers.stream()
                .filter(p -> currentSide.equals(p.side()))
                .filter(p -> p.userId() != null && !spokenUsers.contains(p.userId()))
                .map(ParticipantSessionInfo::userId)
                .findFirst()
                .orElse(null);
    }
}
