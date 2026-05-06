package com.likelion.realtalk.domain.debate.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.likelion.realtalk.domain.debate.dto.request.ChatMessageRequest;
import com.likelion.realtalk.domain.debate.dto.request.FactcheckRequest;
import com.likelion.realtalk.domain.debate.dto.request.JoinDebateRequest;
import com.likelion.realtalk.domain.debate.dto.request.SpeechEndRequest;
import com.likelion.realtalk.domain.debate.service.DebateJoinService;
import com.likelion.realtalk.domain.debate.service.DebateSpeechService;
import com.likelion.realtalk.global.common.WsMessage;
import com.likelion.realtalk.infra.pipeline.SpeechPipeline;
import com.likelion.realtalk.infra.redis.DebateRedisRepository;
import com.likelion.realtalk.infra.redis.RedisPublisher;
import com.likelion.realtalk.infra.redis.dto.ParticipantSessionInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.stereotype.Controller;

import java.security.Principal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Controller
@RequiredArgsConstructor
public class DebateStompController {

    private final DebateJoinService debateJoinService;
    private final DebateSpeechService debateSpeechService;
    private final SpeechPipeline speechPipeline;
    private final DebateRedisRepository debateRedisRepository;
    private final RedisPublisher redisPublisher;
    private final ObjectMapper objectMapper;

    @MessageMapping("/debate/join")
    public void join(JoinDebateRequest request, Principal principal, SimpMessageHeaderAccessor accessor) {
        String sessionId = accessor.getSessionId();
        String userId = principal != null ? principal.getName() : null;
        debateJoinService.join(request, sessionId, userId);
    }

    @MessageMapping("/debate/leave")
    public void leave(JoinDebateRequest request, Principal principal, SimpMessageHeaderAccessor accessor) {
        String sessionId = accessor.getSessionId();
        String userId = principal != null ? principal.getName() : null;
        debateJoinService.leave(request.roomUuid(), sessionId, userId);
    }

    @MessageMapping("/chat/message")
    public void chat(ChatMessageRequest request, Principal principal, SimpMessageHeaderAccessor accessor) {
        String sessionId = accessor.getSessionId();
        String userId = principal != null ? principal.getName() : null;

        String senderName = resolveSenderName(request.roomUuid(), sessionId, userId);

        Map<String, Object> chatEvent = new LinkedHashMap<>();
        chatEvent.put("senderId", userId != null ? userId : sessionId);
        chatEvent.put("senderName", senderName);
        chatEvent.put("message", request.message());

        try {
            String chatJson = objectMapper.writeValueAsString(chatEvent);
            debateRedisRepository.addChat(request.roomUuid(), chatJson);
        } catch (Exception e) {
            log.warn("Failed to persist chat for room {}", request.roomUuid());
        }

        redisPublisher.publish(request.roomUuid(), WsMessage.of("CHAT", chatEvent));
    }

    @MessageMapping("/speech/end")
    public void speechEnd(SpeechEndRequest request, Principal principal, SimpMessageHeaderAccessor accessor) {
        if (principal == null) return;

        String sessionId = accessor.getSessionId();
        Long speakerUserId = Long.parseLong(principal.getName());

        debateSpeechService.handleSpeechEnd(
                request.roomUuid(), request.turnIndex(), sessionId, speakerUserId);
    }

    @MessageMapping("/ai/factcheck")
    public void factcheck(FactcheckRequest request, Principal principal) {
        if (principal == null) return;
        speechPipeline.factcheckAsync(request.roomUuid(), request.turnIndex(), request.claim());
    }

    private String resolveSenderName(String roomUuid, String sessionId, String userId) {
        return debateRedisRepository.getParticipant(roomUuid, sessionId)
                .map(json -> {
                    try {
                        return objectMapper.readValue(json, ParticipantSessionInfo.class).name();
                    } catch (Exception e) {
                        return null;
                    }
                })
                .orElseGet(() -> userId != null ? "User_" + userId : "Guest_" + sessionId.substring(0, 6));
    }
}
