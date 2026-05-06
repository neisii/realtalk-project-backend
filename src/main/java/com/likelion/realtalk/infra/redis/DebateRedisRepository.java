package com.likelion.realtalk.infra.redis;

import com.likelion.realtalk.domain.debate.type.DebateStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.util.*;
import java.util.concurrent.TimeUnit;

@Repository
@RequiredArgsConstructor
public class DebateRedisRepository {

    private final StringRedisTemplate redisTemplate;

    private static final long ROOM_TTL_HOURS = 25;
    private static final long CHAT_TTL_HOURS = 25;
    private static final long SPEAKER_TIMER_TTL_SECONDS = 120;
    private static final long JWT_BLACKLIST_DEFAULT_TTL_SECONDS = 3600;

    private static final String ACTIVE_ROOMS_KEY = "debate:active:rooms";

    // ── Key 생성 (private) ────────────────────────────────────

    private String statusKey(String roomUuid) {
        return "room:" + roomUuid + ":status";
    }

    private String timerKey(String roomUuid) {
        return "room:" + roomUuid + ":timer";
    }

    private String speakerTimerKey(String roomUuid) {
        return "room:" + roomUuid + ":speaker:timer";
    }

    private String currentSpeakerKey(String roomUuid) {
        return "room:" + roomUuid + ":speaker:current";
    }

    private String turnKey(String roomUuid) {
        return "room:" + roomUuid + ":speaker:turn";
    }

    private String spokenKey(String roomUuid) {
        return "room:" + roomUuid + ":speaker:spoken";
    }

    private String participantsKey(String roomUuid) {
        return "room:" + roomUuid + ":participants";
    }

    private String chatsKey(String roomUuid) {
        return "room:" + roomUuid + ":chats";
    }

    private String jtiKey(String jti) {
        return "session:" + jti;
    }

    // ── 방 상태 ──────────────────────────────────────────────

    public void setRoomStatus(String roomUuid, DebateStatus status) {
        redisTemplate.opsForValue().set(statusKey(roomUuid), status.name(),
                ROOM_TTL_HOURS, TimeUnit.HOURS);
    }

    public Optional<DebateStatus> getRoomStatus(String roomUuid) {
        String value = redisTemplate.opsForValue().get(statusKey(roomUuid));
        return Optional.ofNullable(value).map(DebateStatus::valueOf);
    }

    // ── 토론 타이머 ─────────────────────────────────────────

    public void setDebateTimerEndAt(String roomUuid, long epochMilli) {
        redisTemplate.opsForValue().set(timerKey(roomUuid), String.valueOf(epochMilli),
                ROOM_TTL_HOURS, TimeUnit.HOURS);
    }

    public Optional<Long> getDebateTimerEndAt(String roomUuid) {
        String value = redisTemplate.opsForValue().get(timerKey(roomUuid));
        return Optional.ofNullable(value).map(Long::parseLong);
    }

    // ── 발언자 타이머 ────────────────────────────────────────

    public void setSpeakerTimerEndAt(String roomUuid, long epochMilli) {
        redisTemplate.opsForValue().set(speakerTimerKey(roomUuid), String.valueOf(epochMilli),
                SPEAKER_TIMER_TTL_SECONDS, TimeUnit.SECONDS);
    }

    public Optional<Long> getSpeakerTimerEndAt(String roomUuid) {
        String value = redisTemplate.opsForValue().get(speakerTimerKey(roomUuid));
        return Optional.ofNullable(value).map(Long::parseLong);
    }

    // ── 현재 발언자 ─────────────────────────────────────────

    public void setCurrentSpeaker(String roomUuid, String userId) {
        redisTemplate.opsForValue().set(currentSpeakerKey(roomUuid), userId,
                ROOM_TTL_HOURS, TimeUnit.HOURS);
    }

    public Optional<String> getCurrentSpeaker(String roomUuid) {
        return Optional.ofNullable(redisTemplate.opsForValue().get(currentSpeakerKey(roomUuid)));
    }

    public void clearCurrentSpeaker(String roomUuid) {
        redisTemplate.delete(currentSpeakerKey(roomUuid));
    }

    // ── 턴 인덱스 ────────────────────────────────────────────

    public void setCurrentTurn(String roomUuid, int turnIndex) {
        redisTemplate.opsForValue().set(turnKey(roomUuid), String.valueOf(turnIndex),
                ROOM_TTL_HOURS, TimeUnit.HOURS);
    }

    public Optional<Integer> getCurrentTurn(String roomUuid) {
        String value = redisTemplate.opsForValue().get(turnKey(roomUuid));
        return Optional.ofNullable(value).map(Integer::parseInt);
    }

    // ── 이번 라운드 발언 완료 Set ─────────────────────────────

    public void addSpokenUser(String roomUuid, String userId) {
        redisTemplate.opsForSet().add(spokenKey(roomUuid), userId);
        redisTemplate.expire(spokenKey(roomUuid), ROOM_TTL_HOURS, TimeUnit.HOURS);
    }

    public Set<String> getSpokenUsers(String roomUuid) {
        Set<String> members = redisTemplate.opsForSet().members(spokenKey(roomUuid));
        return members != null ? members : Collections.emptySet();
    }

    public void clearSpokenUsers(String roomUuid) {
        redisTemplate.delete(spokenKey(roomUuid));
    }

    // ── 참가자 Hash (sessionId → JSON) ───────────────────────

    public void addParticipant(String roomUuid, String sessionId, String participantJson) {
        redisTemplate.opsForHash().put(participantsKey(roomUuid), sessionId, participantJson);
        redisTemplate.expire(participantsKey(roomUuid), ROOM_TTL_HOURS, TimeUnit.HOURS);
    }

    public void removeParticipant(String roomUuid, String sessionId) {
        redisTemplate.opsForHash().delete(participantsKey(roomUuid), sessionId);
    }

    public Optional<String> getParticipant(String roomUuid, String sessionId) {
        Object value = redisTemplate.opsForHash().get(participantsKey(roomUuid), sessionId);
        return Optional.ofNullable(value).map(Object::toString);
    }

    public Map<Object, Object> getAllParticipants(String roomUuid) {
        Map<Object, Object> entries = redisTemplate.opsForHash().entries(participantsKey(roomUuid));
        return entries != null ? entries : Collections.emptyMap();
    }

    // ── 채팅 List (최근 100개) ────────────────────────────────

    public void addChat(String roomUuid, String chatJson) {
        String key = chatsKey(roomUuid);
        redisTemplate.opsForList().rightPush(key, chatJson);
        redisTemplate.opsForList().trim(key, -100, -1);
        redisTemplate.expire(key, CHAT_TTL_HOURS, TimeUnit.HOURS);
    }

    public List<String> getRecentChats(String roomUuid, long count) {
        List<String> chats = redisTemplate.opsForList().range(chatsKey(roomUuid), -count, -1);
        return chats != null ? chats : Collections.emptyList();
    }

    // ── 활성 방 Set ─────────────────────────────────────────

    public void addActiveRoom(String roomUuid) {
        redisTemplate.opsForSet().add(ACTIVE_ROOMS_KEY, roomUuid);
    }

    public void removeActiveRoom(String roomUuid) {
        redisTemplate.opsForSet().remove(ACTIVE_ROOMS_KEY, roomUuid);
    }

    public Set<String> getActiveRooms() {
        Set<String> rooms = redisTemplate.opsForSet().members(ACTIVE_ROOMS_KEY);
        return rooms != null ? rooms : Collections.emptySet();
    }

    // ── 세션 → 방 매핑 (disconnect 시 역조회용) ───────────────────

    public void setSessionRoom(String sessionId, String roomUuid) {
        redisTemplate.opsForValue().set("session:" + sessionId + ":room", roomUuid,
                ROOM_TTL_HOURS, TimeUnit.HOURS);
    }

    public Optional<String> getSessionRoom(String sessionId) {
        return Optional.ofNullable(redisTemplate.opsForValue().get("session:" + sessionId + ":room"));
    }

    public void removeSessionRoom(String sessionId) {
        redisTemplate.delete("session:" + sessionId + ":room");
    }

    // ── JWT 블랙리스트 ────────────────────────────────────────

    public void blacklistJti(String jti, long ttlSeconds) {
        redisTemplate.opsForValue().set(jtiKey(jti), "blacklisted",
                ttlSeconds > 0 ? ttlSeconds : JWT_BLACKLIST_DEFAULT_TTL_SECONDS,
                TimeUnit.SECONDS);
    }

    public boolean isJtiBlacklisted(String jti) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(jtiKey(jti)));
    }

    // ── 방 전체 키 삭제 (토론 종료 후 정리) ─────────────────────

    public void deleteRoomKeys(String roomUuid) {
        List<String> keys = List.of(
                statusKey(roomUuid),
                timerKey(roomUuid),
                speakerTimerKey(roomUuid),
                currentSpeakerKey(roomUuid),
                turnKey(roomUuid),
                spokenKey(roomUuid),
                participantsKey(roomUuid)
        );
        redisTemplate.delete(keys);
        removeActiveRoom(roomUuid);
    }
}
