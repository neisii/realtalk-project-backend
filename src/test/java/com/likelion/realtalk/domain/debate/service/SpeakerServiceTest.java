package com.likelion.realtalk.domain.debate.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.likelion.realtalk.domain.debate.dto.event.TurnChangedEvent;
import com.likelion.realtalk.domain.debate.entity.DebateRoom;
import com.likelion.realtalk.domain.debate.repository.DebateRoomRepository;
import com.likelion.realtalk.domain.debate.type.DebateStatus;
import com.likelion.realtalk.domain.debate.type.DebateType;
import com.likelion.realtalk.domain.user.entity.User;
import com.likelion.realtalk.domain.user.type.UserRole;
import com.likelion.realtalk.global.common.WsMessage;
import com.likelion.realtalk.infra.redis.DebateRedisRepository;
import com.likelion.realtalk.infra.redis.RedisPublisher;
import com.likelion.realtalk.infra.redis.TurnAdvanceLuaScript;
import com.likelion.realtalk.infra.redis.dto.ParticipantSessionInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SpeakerServiceTest {

    private static final String ROOM_UUID = "test-room-uuid";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Mock private DebateRedisRepository debateRedisRepository;
    @Mock private TurnAdvanceLuaScript turnAdvanceLuaScript;
    @Mock private RedisPublisher redisPublisher;
    @Mock private DebateRoomRepository debateRoomRepository;

    private SpeakerService speakerService;
    private DebateRoom room;

    @BeforeEach
    void setUp() {
        speakerService = new SpeakerService(
                debateRedisRepository, turnAdvanceLuaScript,
                redisPublisher, debateRoomRepository, OBJECT_MAPPER);

        User creator = User.builder().id(1L).username("host").role(UserRole.USER).build();
        room = DebateRoom.builder()
                .uuid(ROOM_UUID).creator(creator).title("Test").sideA("A").sideB("B")
                .turnDurationSecs(90).totalDurationSecs(600)
                .maxSpeaker(2).maxAudience(100)
                .debateType(DebateType.NORMAL).status(DebateStatus.STARTED)
                .build();
    }

    @Test
    @DisplayName("분산 락 획득 실패 시 아무 상태도 변경하지 않는다")
    void advanceTurn_lockNotAcquired_doesNothing() {
        given(debateRedisRepository.getCurrentTurn(ROOM_UUID)).willReturn(Optional.of(0));
        given(turnAdvanceLuaScript.tryAcquireLock(anyString(), anyInt())).willReturn(false);

        speakerService.advanceTurn(ROOM_UUID);

        verify(debateRedisRepository, never()).setCurrentSpeaker(any(), any());
        verify(debateRedisRepository, never()).setCurrentTurn(any(), anyInt());
        verify(redisPublisher, never()).publish(any(), any());
    }

    @Test
    @DisplayName("A측 발언 후 다음 턴은 B측 발언자로 전환된다")
    void advanceTurn_currentSpeakerA_selectsNextFromBSide() throws Exception {
        // given
        Map<Object, Object> participants = buildParticipants(
                new ParticipantSessionInfo("s1", "1", "Alice", "SPEAKER", "A"),
                new ParticipantSessionInfo("s2", "2", "Bob", "SPEAKER", "B")
        );

        given(debateRedisRepository.getCurrentTurn(ROOM_UUID)).willReturn(Optional.of(0));
        given(turnAdvanceLuaScript.tryAcquireLock(anyString(), anyInt())).willReturn(true);
        given(debateRedisRepository.getCurrentSpeaker(ROOM_UUID)).willReturn(Optional.of("1"));
        given(debateRedisRepository.getAllParticipants(ROOM_UUID)).willReturn(participants);
        given(debateRedisRepository.getSpokenUsers(ROOM_UUID)).willReturn(Set.of("1"));
        given(debateRoomRepository.findByUuid(ROOM_UUID)).willReturn(Optional.of(room));

        // when
        speakerService.advanceTurn(ROOM_UUID);

        // then
        verify(debateRedisRepository).setCurrentSpeaker(ROOM_UUID, "2");
        verify(debateRedisRepository).setCurrentTurn(ROOM_UUID, 1);

        ArgumentCaptor<WsMessage<?>> captor = ArgumentCaptor.captor();
        verify(redisPublisher).publish(eq(ROOM_UUID), captor.capture());
        WsMessage<?> published = captor.getValue();
        assertThat(published.type()).isEqualTo("TURN_CHANGED");
        TurnChangedEvent event = (TurnChangedEvent) published.payload();
        assertThat(event.currentSpeakerUserId()).isEqualTo("2");
        assertThat(event.side()).isEqualTo("B");
    }

    @Test
    @DisplayName("B측 발언자가 없으면 같은 A측 미발언자로 전환된다")
    void advanceTurn_noBSideSpeaker_staysOnSameSide() throws Exception {
        // given — A측 발언자 2명, B측 없음
        Map<Object, Object> participants = buildParticipants(
                new ParticipantSessionInfo("s1", "1", "Alice", "SPEAKER", "A"),
                new ParticipantSessionInfo("s2", "3", "Charlie", "SPEAKER", "A")
        );

        given(debateRedisRepository.getCurrentTurn(ROOM_UUID)).willReturn(Optional.of(0));
        given(turnAdvanceLuaScript.tryAcquireLock(anyString(), anyInt())).willReturn(true);
        given(debateRedisRepository.getCurrentSpeaker(ROOM_UUID)).willReturn(Optional.of("1"));
        given(debateRedisRepository.getAllParticipants(ROOM_UUID)).willReturn(participants);
        given(debateRedisRepository.getSpokenUsers(ROOM_UUID)).willReturn(Set.of("1"));
        given(debateRoomRepository.findByUuid(ROOM_UUID)).willReturn(Optional.of(room));

        // when
        speakerService.advanceTurn(ROOM_UUID);

        // then: B측 없으므로 A측의 미발언자(userId="3")가 다음 발언자
        verify(debateRedisRepository).setCurrentSpeaker(ROOM_UUID, "3");
    }

    @Test
    @DisplayName("이번 라운드 모든 발언자가 발언하면 spoken set을 초기화하고 새 라운드를 시작한다")
    void advanceTurn_allSpoken_clearsSpokenSetAndStartsNewRound() throws Exception {
        // given — A(1), B(2) 모두 발언 완료
        Map<Object, Object> participants = buildParticipants(
                new ParticipantSessionInfo("s1", "1", "Alice", "SPEAKER", "A"),
                new ParticipantSessionInfo("s2", "2", "Bob", "SPEAKER", "B")
        );

        given(debateRedisRepository.getCurrentTurn(ROOM_UUID)).willReturn(Optional.of(1));
        given(turnAdvanceLuaScript.tryAcquireLock(anyString(), anyInt())).willReturn(true);
        given(debateRedisRepository.getCurrentSpeaker(ROOM_UUID)).willReturn(Optional.of("1"));
        given(debateRedisRepository.getAllParticipants(ROOM_UUID)).willReturn(participants);
        given(debateRedisRepository.getSpokenUsers(ROOM_UUID)).willReturn(Set.of("1", "2"));
        given(debateRoomRepository.findByUuid(ROOM_UUID)).willReturn(Optional.of(room));

        // when
        speakerService.advanceTurn(ROOM_UUID);

        // then
        verify(debateRedisRepository).clearSpokenUsers(ROOM_UUID);
        verify(debateRedisRepository).setCurrentTurn(ROOM_UUID, 2);
    }

    @Test
    @DisplayName("방에 SPEAKER가 없으면 토론을 종료하고 DEBATE_ENDED를 브로드캐스트한다")
    void advanceTurn_noSpeakers_endsDebateAndPublishesDebateEnded() {
        // given — 참가자 없음
        given(debateRedisRepository.getCurrentTurn(ROOM_UUID)).willReturn(Optional.of(0));
        given(turnAdvanceLuaScript.tryAcquireLock(anyString(), anyInt())).willReturn(true);
        given(debateRedisRepository.getCurrentSpeaker(ROOM_UUID)).willReturn(Optional.empty());
        given(debateRedisRepository.getAllParticipants(ROOM_UUID)).willReturn(Collections.emptyMap());
        given(debateRoomRepository.findByUuid(ROOM_UUID)).willReturn(Optional.of(room));

        // when
        speakerService.advanceTurn(ROOM_UUID);

        // then
        verify(debateRedisRepository).removeActiveRoom(ROOM_UUID);
        verify(debateRoomRepository).save(room);
        assertThat(room.getStatus()).isEqualTo(DebateStatus.ENDED);

        ArgumentCaptor<WsMessage<?>> captor = ArgumentCaptor.captor();
        verify(redisPublisher).publish(eq(ROOM_UUID), captor.capture());
        assertThat(captor.getValue().type()).isEqualTo("DEBATE_ENDED");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static Map<Object, Object> buildParticipants(ParticipantSessionInfo... infos) {
        Map<Object, Object> map = new LinkedHashMap<>();
        for (ParticipantSessionInfo info : infos) {
            try {
                map.put(info.sessionId(), OBJECT_MAPPER.writeValueAsString(info));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        return map;
    }
}
