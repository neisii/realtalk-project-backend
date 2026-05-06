package com.likelion.realtalk.infra.redis;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class RedisSubscriber implements MessageListener {

    private final SimpMessagingTemplate messagingTemplate;
    private final RedisMessageListenerContainer listenerContainer;
    private final ObjectMapper objectMapper;

    private static final String CHANNEL_PREFIX = "pubsub:debate:";

    @PostConstruct
    public void subscribeAll() {
        listenerContainer.addMessageListener(this, new PatternTopic(CHANNEL_PREFIX + "*"));
        log.info("RedisSubscriber registered for pattern: {}*", CHANNEL_PREFIX);
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            String channel = new String(message.getChannel(), StandardCharsets.UTF_8);
            String body = new String(message.getBody(), StandardCharsets.UTF_8);
            String roomUuid = channel.substring(CHANNEL_PREFIX.length());

            Map<String, Object> wsMessage = objectMapper.readValue(body, new TypeReference<>() {});
            String type = String.valueOf(wsMessage.get("type"));
            String destination = resolveDestination(roomUuid, type);

            messagingTemplate.convertAndSend(destination, wsMessage);
        } catch (Exception e) {
            log.error("Failed to process Redis message: {}", new String(message.getBody(), StandardCharsets.UTF_8), e);
        }
    }

    private String resolveDestination(String roomUuid, String type) {
        return switch (type) {
            case "TIMER_TICK" -> "/topic/debate/" + roomUuid + "/timer";
            case "TURN_CHANGED" -> "/topic/speaker/" + roomUuid;
            case "AI_SUMMARY", "FACTCHECK_RESULT", "AI_FAILED", "DEBATE_ANALYSIS"
                    -> "/topic/ai/" + roomUuid;
            default -> "/sub/debate-room/" + roomUuid;
        };
    }
}
