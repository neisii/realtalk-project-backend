package com.likelion.realtalk.infra.timer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.likelion.realtalk.domain.debate.dto.event.DebateEndedEvent;
import com.likelion.realtalk.domain.debate.dto.event.TimerTickEvent;
import com.likelion.realtalk.domain.debate.repository.DebateRoomRepository;
import com.likelion.realtalk.domain.debate.service.SpeakerService;
import com.likelion.realtalk.domain.debate.type.DebateStatus;
import com.likelion.realtalk.global.common.WsMessage;
import com.likelion.realtalk.infra.redis.DebateRedisRepository;
import com.likelion.realtalk.infra.redis.RedisPublisher;
import com.likelion.realtalk.infra.redis.dto.ParticipantSessionInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class TimerScheduler {

    private final DebateRedisRepository debateRedisRepository;
    private final SpeakerService speakerService;
    private final RedisPublisher redisPublisher;
    private final DebateRoomRepository debateRoomRepository;
    private final ObjectMapper objectMapper;

    @Scheduled(fixedDelay = 1000)
    @SchedulerLock(name = "timerBroadcast", lockAtMostFor = "PT3S", lockAtLeastFor = "PT1S")
    public void broadcastTimers() {
        Set<String> activeRooms = debateRedisRepository.getActiveRooms();
        for (String roomUuid : activeRooms) {
            try {
                processRoom(roomUuid);
            } catch (Exception e) {
                log.error("Timer processing error for room {}", roomUuid, e);
            }
        }
    }

    private void processRoom(String roomUuid) {
        long debateRemaining = calcRemainingSeconds(debateRedisRepository.getDebateTimerEndAt(roomUuid));

        if (debateRemaining <= 0) {
            endDebate(roomUuid);
            return;
        }

        long speakerRemaining = calcRemainingSeconds(debateRedisRepository.getSpeakerTimerEndAt(roomUuid));

        String currentSpeakerId = debateRedisRepository.getCurrentSpeaker(roomUuid).orElse(null);
        String speakerName = null;
        String side = null;

        if (currentSpeakerId != null) {
            Optional<ParticipantSessionInfo> speakerInfo = findParticipantByUserId(roomUuid, currentSpeakerId);
            if (speakerInfo.isPresent()) {
                speakerName = speakerInfo.get().name();
                side = speakerInfo.get().side();
            }
        }

        TimerTickEvent tick = new TimerTickEvent(
                debateRemaining, speakerRemaining, currentSpeakerId, speakerName, side);
        redisPublisher.publish(roomUuid, WsMessage.of("TIMER_TICK", tick));

        if (speakerRemaining <= 0) {
            speakerService.advanceTurn(roomUuid);
        }
    }

    private void endDebate(String roomUuid) {
        log.info("Debate timer expired for room {}", roomUuid);
        debateRedisRepository.removeActiveRoom(roomUuid);

        try {
            debateRoomRepository.findByUuid(roomUuid).ifPresent(room -> {
                if (room.getStatus() == DebateStatus.STARTED) {
                    room.end();
                    debateRoomRepository.save(room);
                }
            });
        } catch (Exception e) {
            log.error("Failed to update debate status in DB for room {}", roomUuid, e);
        }

        redisPublisher.publish(roomUuid, WsMessage.of("DEBATE_ENDED", new DebateEndedEvent()));
    }

    private Optional<ParticipantSessionInfo> findParticipantByUserId(String roomUuid, String userId) {
        return debateRedisRepository.getAllParticipants(roomUuid).values().stream()
                .map(v -> {
                    try {
                        return objectMapper.readValue(v.toString(), ParticipantSessionInfo.class);
                    } catch (Exception e) {
                        return null;
                    }
                })
                .filter(p -> p != null && userId.equals(p.userId()))
                .findFirst();
    }

    private long calcRemainingSeconds(Optional<Long> timerEndAt) {
        return timerEndAt
                .map(endAt -> (endAt - System.currentTimeMillis()) / 1000)
                .map(secs -> Math.max(secs, 0L))
                .orElse(0L);
    }
}
