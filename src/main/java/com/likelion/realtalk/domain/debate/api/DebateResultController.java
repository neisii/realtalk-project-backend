package com.likelion.realtalk.domain.debate.api;

import com.likelion.realtalk.domain.debate.dto.request.VoteRequest;
import com.likelion.realtalk.domain.debate.dto.response.DebateResultResponse;
import com.likelion.realtalk.domain.debate.dto.response.SpeechResponse;
import com.likelion.realtalk.domain.debate.service.DebateResultService;
import com.likelion.realtalk.global.common.ApiResponse;
import com.likelion.realtalk.global.security.core.CustomUserDetails;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
public class DebateResultController {

    private final DebateResultService debateResultService;

    @GetMapping("/api/debate-results/{roomUuid}")
    public ResponseEntity<ApiResponse<DebateResultResponse>> getResult(@PathVariable String roomUuid) {
        return ApiResponse.ok(debateResultService.getResult(roomUuid));
    }

    @PostMapping("/api/debate-results/{roomUuid}/vote")
    public ResponseEntity<ApiResponse<DebateResultResponse>> vote(
            @PathVariable String roomUuid,
            @RequestBody @Valid VoteRequest request,
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @RequestHeader(value = "X-Guest-Id", required = false) String guestId) {
        Long userId = userDetails != null ? userDetails.getUserId() : null;
        return ApiResponse.ok(debateResultService.vote(roomUuid, request, userId, guestId));
    }

    @GetMapping("/api/debate/{roomUuid}/speeches")
    public ResponseEntity<ApiResponse<List<SpeechResponse>>> getSpeeches(@PathVariable String roomUuid) {
        return ApiResponse.ok(debateResultService.getSpeeches(roomUuid));
    }
}
