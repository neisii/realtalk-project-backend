package com.likelion.realtalk.infra.redis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.likelion.realtalk.global.common.WsMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class RedisPublisher {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    private static final String CHANNEL_PREFIX = "pubsub:debate:";

    public void publish(String roomUuid, WsMessage<?> message) {
        try {
            String json = objectMapper.writeValueAsString(message);
            redisTemplate.convertAndSend(CHANNEL_PREFIX + roomUuid, json);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize message for room {}: type={}", roomUuid, message.type(), e);
        }
    }
}
