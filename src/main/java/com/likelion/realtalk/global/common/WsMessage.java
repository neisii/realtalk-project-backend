package com.likelion.realtalk.global.common;

import java.time.Instant;

public record WsMessage<T>(
        String type,
        T payload,
        String serverTimestamp
) {
    public static <T> WsMessage<T> of(String type, T payload) {
        return new WsMessage<>(type, payload, Instant.now().toString());
    }
}
