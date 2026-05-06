package com.likelion.realtalk.global.security.stomp;

import com.likelion.realtalk.global.security.jwt.JwtProvider;
import com.likelion.realtalk.infra.redis.DebateRedisRepository;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Slf4j
@Component
@RequiredArgsConstructor
public class StompAuthChannelInterceptor implements ChannelInterceptor {

    private final JwtProvider jwtProvider;
    private final DebateRedisRepository debateRedisRepository;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor =
                MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);

        if (accessor == null || !StompCommand.CONNECT.equals(accessor.getCommand())) {
            return message;
        }

        String token = resolveToken(accessor);
        if (!StringUtils.hasText(token)) {
            // 게스트(Audience)는 토큰 없이 연결 허용
            return message;
        }

        try {
            Claims claims = jwtProvider.parseToken(token);

            if (debateRedisRepository.isJtiBlacklisted(claims.getId())) {
                log.warn("STOMP CONNECT: blacklisted token jti={}", claims.getId());
                return message; // 블랙리스트 토큰 - 게스트로 처리
            }

            // userId를 principal로 설정 → STOMP 핸들러에서 sessionId로 참조 가능
            String userId = claims.getSubject();
            accessor.setUser(() -> userId);
        } catch (Exception e) {
            log.debug("STOMP CONNECT JWT validation failed: {}", e.getMessage());
            // 유효하지 않은 토큰 - 게스트로 처리 (Speaker 시도 시 join 단계에서 거부)
        }

        return message;
    }

    private String resolveToken(StompHeaderAccessor accessor) {
        String authorization = accessor.getFirstNativeHeader("Authorization");
        if (StringUtils.hasText(authorization) && authorization.startsWith("Bearer ")) {
            return authorization.substring(7);
        }
        return null;
    }
}
