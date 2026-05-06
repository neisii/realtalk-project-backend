package com.likelion.realtalk.domain.debate.service;

import com.likelion.realtalk.domain.debate.dto.request.CreateTopicRequest;
import com.likelion.realtalk.domain.debate.dto.response.TopicResponse;
import com.likelion.realtalk.domain.debate.entity.DebateTopic;
import com.likelion.realtalk.domain.debate.repository.DebateTopicRepository;
import com.likelion.realtalk.global.exception.CustomException;
import com.likelion.realtalk.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DebateTopicService {

    private final DebateTopicRepository debateTopicRepository;

    public List<TopicResponse> getAll() {
        return debateTopicRepository.findAll().stream()
                .map(TopicResponse::from)
                .toList();
    }

    @Transactional
    public TopicResponse create(CreateTopicRequest request) {
        if (debateTopicRepository.existsByTitle(request.title())) {
            throw new CustomException(ErrorCode.DUPLICATE_TOPIC);
        }
        DebateTopic topic = DebateTopic.builder().title(request.title()).build();
        return TopicResponse.from(debateTopicRepository.save(topic));
    }

    @Transactional
    public void delete(Long id) {
        DebateTopic topic = debateTopicRepository.findById(id)
                .orElseThrow(() -> new CustomException(ErrorCode.TOPIC_NOT_FOUND));
        debateTopicRepository.delete(topic);
    }
}
