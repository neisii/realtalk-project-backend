package com.likelion.realtalk.domain.debate.api;

import com.likelion.realtalk.domain.debate.dto.request.CreateTopicRequest;
import com.likelion.realtalk.domain.debate.dto.response.TopicResponse;
import com.likelion.realtalk.domain.debate.service.DebateTopicService;
import com.likelion.realtalk.global.common.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/debate-topics")
@RequiredArgsConstructor
public class DebateTopicController {

    private final DebateTopicService debateTopicService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<TopicResponse>>> getAll() {
        return ApiResponse.ok(debateTopicService.getAll());
    }

    @PostMapping
    public ResponseEntity<ApiResponse<TopicResponse>> create(@RequestBody @Valid CreateTopicRequest request) {
        return ApiResponse.created(debateTopicService.create(request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id) {
        debateTopicService.delete(id);
        return ApiResponse.ok();
    }
}
