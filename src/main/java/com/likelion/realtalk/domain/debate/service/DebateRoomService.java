package com.likelion.realtalk.domain.debate.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.likelion.realtalk.domain.category.entity.Category;
import com.likelion.realtalk.domain.category.repository.CategoryRepository;
import com.likelion.realtalk.domain.debate.dto.event.DebateStartedPayload;
import com.likelion.realtalk.domain.debate.dto.event.DebateEndedEvent;
import com.likelion.realtalk.domain.debate.dto.request.CreateRoomRequest;
import com.likelion.realtalk.domain.debate.dto.request.MatchRoomRequest;
import com.likelion.realtalk.domain.debate.dto.response.CreateRoomResponse;
import com.likelion.realtalk.domain.debate.dto.response.DebateRoomDetailResponse;
import com.likelion.realtalk.domain.debate.dto.response.DebateRoomSummaryResponse;
import com.likelion.realtalk.domain.debate.entity.DebateParticipant;
import com.likelion.realtalk.domain.debate.entity.DebateResult;
import com.likelion.realtalk.domain.debate.entity.DebateRoom;
import com.likelion.realtalk.domain.debate.repository.DebateParticipantRepository;
import com.likelion.realtalk.domain.debate.repository.DebateResultRepository;
import com.likelion.realtalk.domain.debate.repository.DebateRoomRepository;
import com.likelion.realtalk.domain.debate.type.DebateStatus;
import com.likelion.realtalk.domain.debate.type.DebateType;
import com.likelion.realtalk.domain.debate.type.ParticipantRole;
import com.likelion.realtalk.domain.debate.type.Side;
import com.likelion.realtalk.domain.user.entity.User;
import com.likelion.realtalk.domain.user.repository.UserRepository;
import com.likelion.realtalk.global.common.WsMessage;
import com.likelion.realtalk.global.exception.CustomException;
import com.likelion.realtalk.global.exception.ErrorCode;
import com.likelion.realtalk.infra.pipeline.SpeechPipeline;
import com.likelion.realtalk.infra.redis.DebateRedisRepository;
import com.likelion.realtalk.infra.redis.RedisPublisher;
import com.likelion.realtalk.infra.redis.dto.ParticipantSessionInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DebateRoomService {

    private final DebateRoomRepository debateRoomRepository;
    private final CategoryRepository categoryRepository;
    private final UserRepository userRepository;
    private final DebateParticipantRepository debateParticipantRepository;
    private final DebateResultRepository debateResultRepository;
    private final DebateRedisRepository debateRedisRepository;
    private final RedisPublisher redisPublisher;
    private final SpeechPipeline speechPipeline;
    private final ObjectMapper objectMapper;

    @Transactional
    public CreateRoomResponse createRoom(CreateRoomRequest req, Long creatorId) {
        User creator = userRepository.findById(creatorId)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));

        Category category = req.categoryId() != null
                ? categoryRepository.findById(req.categoryId())
                        .orElseThrow(() -> new CustomException(ErrorCode.CATEGORY_NOT_FOUND))
                : null;

        DebateRoom room = DebateRoom.builder()
                .uuid(UUID.randomUUID().toString())
                .creator(creator)
                .category(category)
                .title(req.title())
                .description(req.description())
                .sideA(req.sideA())
                .sideB(req.sideB())
                .turnDurationSecs(req.turnDurationSecs())
                .totalDurationSecs(req.totalDurationSecs())
                .maxSpeaker(req.maxSpeaker() > 0 ? req.maxSpeaker() : 2)
                .maxAudience(req.maxAudience() > 0 ? req.maxAudience() : 100)
                .debateType(req.debateType() != null ? req.debateType() : DebateType.NORMAL)
                .status(DebateStatus.WAITING)
                .build();

        debateRoomRepository.save(room);
        return new CreateRoomResponse(room.getUuid(), room.getId());
    }

    public Page<DebateRoomSummaryResponse> listRooms(Long categoryId, Pageable pageable) {
        Page<DebateRoom> rooms = categoryId != null
                ? debateRoomRepository.findByCategoryIdAndStatus(categoryId, DebateStatus.WAITING, pageable)
                : debateRoomRepository.findByStatus(DebateStatus.WAITING, pageable);

        return rooms.map(room -> toSummaryResponse(room, countParticipants(room)));
    }

    public DebateRoomDetailResponse getRoom(String roomUuid) {
        DebateRoom room = debateRoomRepository.findByUuid(roomUuid)
                .orElseThrow(() -> new CustomException(ErrorCode.DEBATE_ROOM_NOT_FOUND));
        long[] counts = countParticipants(room);
        return toDetailResponse(room, counts);
    }

    @Transactional
    public void startDebate(String roomUuid, Long userId) {
        DebateRoom room = debateRoomRepository.findByUuid(roomUuid)
                .orElseThrow(() -> new CustomException(ErrorCode.DEBATE_ROOM_NOT_FOUND));

        if (!room.isHost(userId)) {
            throw new CustomException(ErrorCode.FORBIDDEN);
        }

        long speakerCount = debateParticipantRepository
                .countByDebateRoomAndParticipantRoleAndLeftAtIsNull(room, ParticipantRole.SPEAKER);
        if (speakerCount == 0) {
            throw new CustomException(ErrorCode.INSUFFICIENT_SPEAKERS);
        }

        room.start();

        long now = System.currentTimeMillis();
        debateRedisRepository.setRoomStatus(roomUuid, DebateStatus.STARTED);
        debateRedisRepository.setDebateTimerEndAt(roomUuid, now + (long) room.getTotalDurationSecs() * 1000);
        debateRedisRepository.setSpeakerTimerEndAt(roomUuid, now + (long) room.effectiveTurnDurationSecs() * 1000);
        debateRedisRepository.setCurrentTurn(roomUuid, 0);
        debateRedisRepository.addActiveRoom(roomUuid);

        // 첫 발언자 설정
        String firstSpeakerId = null;
        String firstSpeakerName = null;
        String firstSpeakerSide = null;

        String firstSpeakerIdFromRedis = findFirstSpeakerFromRedis(roomUuid);
        if (firstSpeakerIdFromRedis != null) {
            firstSpeakerId = firstSpeakerIdFromRedis;
            debateRedisRepository.setCurrentSpeaker(roomUuid, firstSpeakerId);
            ParticipantSessionInfo info = getParticipantInfoByUserId(roomUuid, firstSpeakerId);
            if (info != null) {
                firstSpeakerName = info.name();
                firstSpeakerSide = info.side();
            }
        } else {
            // Redis에 없으면 DB에서 조회
            List<DebateParticipant> speakers = debateParticipantRepository
                    .findByDebateRoomAndLeftAtIsNull(room).stream()
                    .filter(p -> p.getParticipantRole() == ParticipantRole.SPEAKER)
                    .toList();
            Optional<DebateParticipant> first = speakers.stream()
                    .filter(p -> p.getSide() == Side.A).findFirst()
                    .or(() -> speakers.stream().filter(p -> p.getSide() == Side.B).findFirst());
            if (first.isPresent() && first.get().getUserId() != null) {
                firstSpeakerId = String.valueOf(first.get().getUserId());
                firstSpeakerSide = first.get().getSide().name();
                debateRedisRepository.setCurrentSpeaker(roomUuid, firstSpeakerId);
            }
        }

        // DebateResult 생성 (투표/결과용)
        if (debateResultRepository.findByDebateRoom(room).isEmpty()) {
            debateResultRepository.save(DebateResult.builder()
                    .debateRoom(room)
                    .sideAVotes(0)
                    .sideBVotes(0)
                    .build());
        }

        redisPublisher.publish(roomUuid, WsMessage.of("DEBATE_STARTED",
                new DebateStartedPayload(
                        room.getTotalDurationSecs(),
                        room.effectiveTurnDurationSecs(),
                        firstSpeakerId,
                        firstSpeakerName,
                        firstSpeakerSide)));
    }

    @Transactional
    public void endDebate(String roomUuid, Long userId) {
        DebateRoom room = debateRoomRepository.findByUuid(roomUuid)
                .orElseThrow(() -> new CustomException(ErrorCode.DEBATE_ROOM_NOT_FOUND));

        if (!room.isHost(userId)) {
            throw new CustomException(ErrorCode.FORBIDDEN);
        }

        room.end();
        debateRedisRepository.removeActiveRoom(roomUuid);
        debateRedisRepository.clearCurrentSpeaker(roomUuid);
        debateRedisRepository.setRoomStatus(roomUuid, DebateStatus.ENDED);

        redisPublisher.publish(roomUuid, WsMessage.of("DEBATE_ENDED", new DebateEndedEvent()));
        speechPipeline.analyzeDebateAsync(roomUuid);
    }

    public DebateRoomSummaryResponse matchRoom(MatchRoomRequest request) {
        return debateRoomRepository
                .findFirstByCategoryIdAndStatusOrderByCreatedAtDesc(request.categoryId(), DebateStatus.WAITING)
                .map(room -> toSummaryResponse(room, countParticipants(room)))
                .orElse(null);
    }

    // ── helpers ─────────────────────────────────────────────

    private long[] countParticipants(DebateRoom room) {
        long a = debateParticipantRepository
                .countByDebateRoomAndParticipantRoleAndSideAndLeftAtIsNull(room, ParticipantRole.SPEAKER, Side.A);
        long b = debateParticipantRepository
                .countByDebateRoomAndParticipantRoleAndSideAndLeftAtIsNull(room, ParticipantRole.SPEAKER, Side.B);
        long aud = debateParticipantRepository
                .countByDebateRoomAndParticipantRoleAndLeftAtIsNull(room, ParticipantRole.AUDIENCE);
        return new long[]{a, b, aud};
    }

    private DebateRoomSummaryResponse toSummaryResponse(DebateRoom room, long[] counts) {
        return new DebateRoomSummaryResponse(
                room.getUuid(), room.getTitle(), room.getSideA(), room.getSideB(),
                room.getStatus().name(), room.getDebateType().name(),
                room.getCategory() != null ? room.getCategory().getCategoryName() : null,
                counts[0], counts[1], counts[2],
                room.getMaxSpeaker(), room.getMaxAudience(), room.getCreatedAt());
    }

    private DebateRoomDetailResponse toDetailResponse(DebateRoom room, long[] counts) {
        return new DebateRoomDetailResponse(
                room.getUuid(), room.getTitle(), room.getDescription(),
                room.getSideA(), room.getSideB(),
                room.getStatus().name(), room.getDebateType().name(),
                room.getCategory() != null ? room.getCategory().getCategoryName() : null,
                room.getTurnDurationSecs(), room.getTotalDurationSecs(),
                counts[0], counts[1], counts[2],
                room.getMaxSpeaker(), room.getMaxAudience(),
                room.getStartedAt(), room.getEndedAt(), room.getCreatedAt());
    }

    private String findFirstSpeakerFromRedis(String roomUuid) {
        return debateRedisRepository.getAllParticipants(roomUuid).values().stream()
                .map(v -> {
                    try {
                        return objectMapper.readValue(v.toString(), ParticipantSessionInfo.class);
                    } catch (Exception e) {
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .filter(p -> "SPEAKER".equals(p.role()) && "A".equals(p.side()))
                .map(ParticipantSessionInfo::userId)
                .filter(Objects::nonNull)
                .findFirst()
                .orElseGet(() ->
                        debateRedisRepository.getAllParticipants(roomUuid).values().stream()
                                .map(v -> {
                                    try {
                                        return objectMapper.readValue(v.toString(), ParticipantSessionInfo.class);
                                    } catch (Exception e) {
                                        return null;
                                    }
                                })
                                .filter(Objects::nonNull)
                                .filter(p -> "SPEAKER".equals(p.role()))
                                .map(ParticipantSessionInfo::userId)
                                .filter(Objects::nonNull)
                                .findFirst()
                                .orElse(null));
    }

    private ParticipantSessionInfo getParticipantInfoByUserId(String roomUuid, String userId) {
        return debateRedisRepository.getAllParticipants(roomUuid).values().stream()
                .map(v -> {
                    try {
                        return objectMapper.readValue(v.toString(), ParticipantSessionInfo.class);
                    } catch (Exception e) {
                        return null;
                    }
                })
                .filter(p -> p != null && userId.equals(p.userId()))
                .findFirst()
                .orElse(null);
    }
}
