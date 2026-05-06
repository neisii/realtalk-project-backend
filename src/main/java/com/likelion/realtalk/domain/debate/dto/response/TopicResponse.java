package com.likelion.realtalk.domain.debate.dto.response;

import com.likelion.realtalk.domain.debate.entity.DebateTopic;

public record TopicResponse(Long id, String title) {

    public static TopicResponse from(DebateTopic topic) {
        return new TopicResponse(topic.getId(), topic.getTitle());
    }
}
