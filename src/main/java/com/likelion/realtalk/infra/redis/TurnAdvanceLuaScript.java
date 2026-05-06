package com.likelion.realtalk.infra.redis;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class TurnAdvanceLuaScript {

    private final StringRedisTemplate redisTemplate;

    // NX + EX 원자적 잠금: 이미 존재하면 nil 반환 → 중복 턴 전환 방지
    private static final RedisScript<String> LOCK_SCRIPT = RedisScript.of(
            "return redis.call('SET', KEYS[1], ARGV[1], 'NX', 'EX', ARGV[2])",
            String.class
    );

    public boolean tryAcquireLock(String lockKey, int ttlSeconds) {
        String result = redisTemplate.execute(
                LOCK_SCRIPT,
                List.of(lockKey),
                "1",
                String.valueOf(ttlSeconds)
        );
        return "OK".equals(result);
    }

    public void releaseLock(String lockKey) {
        redisTemplate.delete(lockKey);
    }
}
