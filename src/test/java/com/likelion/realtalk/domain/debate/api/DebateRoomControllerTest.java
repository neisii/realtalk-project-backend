package com.likelion.realtalk.domain.debate.api;

import com.likelion.realtalk.domain.debate.dto.response.CreateRoomResponse;
import com.likelion.realtalk.domain.debate.dto.response.DebateRoomDetailResponse;
import com.likelion.realtalk.domain.debate.service.DebateRoomService;
import com.likelion.realtalk.domain.oauth.handler.OAuth2FailureHandler;
import com.likelion.realtalk.domain.oauth.handler.OAuth2SuccessHandler;
import com.likelion.realtalk.domain.oauth.service.CustomOAuth2UserService;
import com.likelion.realtalk.global.exception.CustomException;
import com.likelion.realtalk.global.exception.ErrorCode;
import com.likelion.realtalk.global.security.core.CustomUserDetails;
import com.likelion.realtalk.global.security.jwt.JwtProvider;
import com.likelion.realtalk.infra.redis.DebateRedisRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import com.likelion.realtalk.global.config.SecurityConfig;
import org.springframework.data.domain.Page;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;
import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(DebateRoomController.class)
@Import(SecurityConfig.class)
class DebateRoomControllerTest {

    @Autowired MockMvc mockMvc;

    @MockitoBean DebateRoomService debateRoomService;
    @MockitoBean JwtProvider jwtProvider;
    @MockitoBean DebateRedisRepository debateRedisRepository;
    @MockitoBean CustomOAuth2UserService customOAuth2UserService;
    @MockitoBean OAuth2SuccessHandler oAuth2SuccessHandler;
    @MockitoBean OAuth2FailureHandler oAuth2FailureHandler;

    private static final CustomUserDetails USER = new CustomUserDetails(1L, "alice", "USER");

    @Test
    @DisplayName("방 목록 조회는 인증 없이 200과 ApiResponse 래퍼를 반환한다")
    void listRooms_noAuth_returns200WithApiResponseWrapper() throws Exception {
        given(debateRoomService.listRooms(isNull(), any())).willReturn(Page.empty());

        mockMvc.perform(get("/api/debate-rooms"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.status").value(200))
                .andExpect(jsonPath("$.data").exists());
    }

    @Test
    @DisplayName("방 생성은 인증 없이 401을 반환한다")
    void createRoom_notAuthenticated_returns401() throws Exception {
        mockMvc.perform(post("/api/debate-rooms")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRoomJson()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("방 생성 시 turnDurationSecs가 최솟값(10) 미만이면 400과 INVALID_INPUT 코드를 반환한다")
    void createRoom_turnDurationTooShort_returns400WithInvalidInput() throws Exception {
        mockMvc.perform(post("/api/debate-rooms")
                        .with(user(USER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"T","sideA":"A","sideB":"B",
                                 "turnDurationSecs":5,"totalDurationSecs":600,
                                 "maxSpeaker":2,"maxAudience":100}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }

    @Test
    @DisplayName("유효한 요청으로 방 생성 시 201과 roomUuid를 반환한다")
    void createRoom_validRequest_returns201WithRoomUuid() throws Exception {
        given(debateRoomService.createRoom(any(), eq(1L)))
                .willReturn(new CreateRoomResponse("test-uuid", 1L));

        mockMvc.perform(post("/api/debate-rooms")
                        .with(user(USER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRoomJson()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.roomUuid").value("test-uuid"))
                .andExpect(jsonPath("$.data.roomId").value(1));
    }

    @Test
    @DisplayName("존재하지 않는 방 조회 시 404와 DEBATE_ROOM_NOT_FOUND 코드를 반환한다")
    void getRoom_notFound_returns404WithErrorCode() throws Exception {
        given(debateRoomService.getRoom("no-such-uuid"))
                .willThrow(new CustomException(ErrorCode.DEBATE_ROOM_NOT_FOUND));

        mockMvc.perform(get("/api/debate-rooms/{uuid}", "no-such-uuid"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("DEBATE_ROOM_NOT_FOUND"));
    }

    @Test
    @DisplayName("방 개설자가 아닌 사용자가 토론 시작 시 403과 FORBIDDEN 코드를 반환한다")
    void startDebate_notHost_returns403WithForbiddenCode() throws Exception {
        willThrow(new CustomException(ErrorCode.FORBIDDEN))
                .given(debateRoomService).startDebate(eq("room-uuid"), eq(1L));

        mockMvc.perform(post("/api/debate-rooms/{uuid}/start", "room-uuid")
                        .with(user(USER)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("방 상세 조회는 인증 없이 200을 반환한다")
    void getRoom_noAuth_returns200() throws Exception {
        given(debateRoomService.getRoom("test-uuid")).willReturn(
                new DebateRoomDetailResponse(
                        "test-uuid", "토론", null, "찬성", "반대",
                        "WAITING", "NORMAL", "경제",
                        90, 600, 0L, 0L, 0L, 2, 100,
                        null, null, LocalDateTime.now()));

        mockMvc.perform(get("/api/debate-rooms/{uuid}", "test-uuid"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.uuid").value("test-uuid"))
                .andExpect(jsonPath("$.data.status").value("WAITING"));
    }

    private String validRoomJson() {
        return """
                {"title":"Test Debate","sideA":"찬성","sideB":"반대",
                 "turnDurationSecs":90,"totalDurationSecs":600,
                 "maxSpeaker":2,"maxAudience":100}
                """;
    }
}
