package com.likelion.realtalk.infra.redis;

import com.likelion.realtalk.domain.debate.type.DebateStatus;
import com.likelion.realtalk.support.BaseIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class DebateRedisRepositoryIT extends BaseIntegrationTest {

    @Autowired DebateRedisRepository debateRedisRepository;
    @Autowired StringRedisTemplate redisTemplate;

    private static final String ROOM_UUID = "integration-test-room";

    @BeforeEach
    void cleanUp() {
        // 테스트 간 Redis 상태 격리
        Set<String> keys = redisTemplate.keys("room:" + ROOM_UUID + "*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
        redisTemplate.delete("debate:active:rooms");
    }

    @Test
    @DisplayName("방 상태를 저장하고 조회하면 동일한 값이 반환된다")
    void setAndGetRoomStatus_returnsStoredValue() {
        debateRedisRepository.setRoomStatus(ROOM_UUID, DebateStatus.STARTED);

        assertThat(debateRedisRepository.getRoomStatus(ROOM_UUID))
                .isPresent()
                .contains(DebateStatus.STARTED);
    }

    @Test
    @DisplayName("토론 타이머 저장 후 조회하면 동일한 epoch millis가 반환된다")
    void setAndGetDebateTimer_returnsStoredEpochMilli() {
        long epochMilli = System.currentTimeMillis() + 300_000;
        debateRedisRepository.setDebateTimerEndAt(ROOM_UUID, epochMilli);

        assertThat(debateRedisRepository.getDebateTimerEndAt(ROOM_UUID))
                .isPresent()
                .contains(epochMilli);
    }

    @Test
    @DisplayName("현재 발언자 설정 후 조회하면 동일한 userId가 반환된다")
    void setAndGetCurrentSpeaker_returnsStoredUserId() {
        debateRedisRepository.setCurrentSpeaker(ROOM_UUID, "42");

        assertThat(debateRedisRepository.getCurrentSpeaker(ROOM_UUID))
                .isPresent()
                .contains("42");
    }

    @Test
    @DisplayName("참가자 추가 후 getAllParticipants 로 조회하면 추가한 참가자가 포함된다")
    void addAndGetParticipants_returnsStoredEntries() {
        String sessionId = "session-1";
        String participantJson = "{\"sessionId\":\"session-1\",\"userId\":\"1\",\"name\":\"Alice\",\"role\":\"SPEAKER\",\"side\":\"A\"}";

        debateRedisRepository.addParticipant(ROOM_UUID, sessionId, participantJson);

        Map<Object, Object> participants = debateRedisRepository.getAllParticipants(ROOM_UUID);
        assertThat(participants).containsKey(sessionId);
        assertThat(participants.get(sessionId).toString()).contains("Alice");
    }

    @Test
    @DisplayName("참가자 제거 후 getAllParticipants 에서 해당 항목이 사라진다")
    void removeParticipant_removesFromHash() {
        String sessionId = "session-2";
        debateRedisRepository.addParticipant(ROOM_UUID, sessionId, "{\"userId\":\"2\"}");
        debateRedisRepository.removeParticipant(ROOM_UUID, sessionId);

        assertThat(debateRedisRepository.getAllParticipants(ROOM_UUID))
                .doesNotContainKey(sessionId);
    }

    @Test
    @DisplayName("채팅 추가 후 getRecentChats 로 요청한 개수만큼 조회된다")
    void addChatAndGetRecentChats_returnsRequestedCount() {
        for (int i = 1; i <= 5; i++) {
            debateRedisRepository.addChat(ROOM_UUID, "{\"message\":\"msg" + i + "\"}");
        }

        List<String> recent3 = debateRedisRepository.getRecentChats(ROOM_UUID, 3);
        assertThat(recent3).hasSize(3);
        assertThat(recent3.get(2)).contains("msg5"); // 최신 메시지가 마지막에
    }

    @Test
    @DisplayName("100개 초과 채팅 추가 시 최근 100개만 유지된다")
    void addChat_cappedAt100_oldestDropped() {
        for (int i = 1; i <= 110; i++) {
            debateRedisRepository.addChat(ROOM_UUID, "{\"idx\":" + i + "}");
        }

        List<String> all = debateRedisRepository.getRecentChats(ROOM_UUID, 200);
        assertThat(all).hasSize(100);
        assertThat(all.get(0)).contains("\"idx\":11"); // 1~10은 잘려나감
    }

    @Test
    @DisplayName("활성 방 추가/조회/제거가 정상 동작한다")
    void activeRooms_addQueryRemove_workCorrectly() {
        debateRedisRepository.addActiveRoom("room-a");
        debateRedisRepository.addActiveRoom("room-b");

        assertThat(debateRedisRepository.getActiveRooms())
                .contains("room-a", "room-b");

        debateRedisRepository.removeActiveRoom("room-a");

        assertThat(debateRedisRepository.getActiveRooms())
                .doesNotContain("room-a")
                .contains("room-b");

        redisTemplate.delete("debate:active:rooms");
    }

    @Test
    @DisplayName("JWT jti 블랙리스트 등록 후 isJtiBlacklisted 가 true를 반환한다")
    void blacklistJti_thenIsBlacklisted_returnsTrue() {
        String jti = "test-jti-" + System.currentTimeMillis();
        debateRedisRepository.blacklistJti(jti, 60L);

        assertThat(debateRedisRepository.isJtiBlacklisted(jti)).isTrue();
        // 등록하지 않은 jti는 false
        assertThat(debateRedisRepository.isJtiBlacklisted("unknown-jti")).isFalse();
    }

    @Test
    @DisplayName("발언 완료 유저 추가 후 getSpokenUsers 에서 조회된다")
    void addSpokenUser_thenGetSpokenUsers_containsUser() {
        debateRedisRepository.addSpokenUser(ROOM_UUID, "user-1");
        debateRedisRepository.addSpokenUser(ROOM_UUID, "user-2");

        Set<String> spoken = debateRedisRepository.getSpokenUsers(ROOM_UUID);
        assertThat(spoken).contains("user-1", "user-2");

        debateRedisRepository.clearSpokenUsers(ROOM_UUID);
        assertThat(debateRedisRepository.getSpokenUsers(ROOM_UUID)).isEmpty();
    }

    @Test
    @DisplayName("세션-방 매핑 저장 후 조회하면 roomUuid가 반환된다")
    void setAndGetSessionRoom_returnsRoomUuid() {
        debateRedisRepository.setSessionRoom("session-xyz", ROOM_UUID);

        assertThat(debateRedisRepository.getSessionRoom("session-xyz"))
                .isPresent()
                .contains(ROOM_UUID);

        debateRedisRepository.removeSessionRoom("session-xyz");
        assertThat(debateRedisRepository.getSessionRoom("session-xyz")).isEmpty();
    }
}
