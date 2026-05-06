package com.likelion.realtalk.domain.debate.api;

import com.likelion.realtalk.domain.debate.dto.request.CreateRoomRequest;
import com.likelion.realtalk.domain.debate.dto.request.MatchRoomRequest;
import com.likelion.realtalk.domain.debate.dto.response.CreateRoomResponse;
import com.likelion.realtalk.domain.debate.dto.response.DebateRoomDetailResponse;
import com.likelion.realtalk.domain.debate.dto.response.DebateRoomSummaryResponse;
import com.likelion.realtalk.domain.debate.service.DebateRoomService;
import com.likelion.realtalk.global.common.ApiResponse;
import com.likelion.realtalk.global.security.core.CustomUserDetails;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/debate-rooms")
@RequiredArgsConstructor
public class DebateRoomController {

    private final DebateRoomService debateRoomService;

    @PostMapping
    public ResponseEntity<ApiResponse<CreateRoomResponse>> createRoom(
            @RequestBody @Valid CreateRoomRequest request,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        return ApiResponse.created(debateRoomService.createRoom(request, userDetails.getUserId()));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<Page<DebateRoomSummaryResponse>>> listRooms(
            @RequestParam(required = false) Long categoryId,
            @PageableDefault(size = 20) Pageable pageable) {
        return ApiResponse.ok(debateRoomService.listRooms(categoryId, pageable));
    }

    @GetMapping("/{roomUuid}")
    public ResponseEntity<ApiResponse<DebateRoomDetailResponse>> getRoom(@PathVariable String roomUuid) {
        return ApiResponse.ok(debateRoomService.getRoom(roomUuid));
    }

    @PostMapping("/{roomUuid}/start")
    public ResponseEntity<ApiResponse<Void>> startDebate(
            @PathVariable String roomUuid,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        debateRoomService.startDebate(roomUuid, userDetails.getUserId());
        return ApiResponse.ok();
    }

    @PostMapping("/{roomUuid}/end")
    public ResponseEntity<ApiResponse<Void>> endDebate(
            @PathVariable String roomUuid,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        debateRoomService.endDebate(roomUuid, userDetails.getUserId());
        return ApiResponse.ok();
    }

    @PostMapping("/match")
    public ResponseEntity<ApiResponse<DebateRoomSummaryResponse>> matchRoom(
            @RequestBody @Valid MatchRoomRequest request) {
        return ApiResponse.ok(debateRoomService.matchRoom(request));
    }
}
