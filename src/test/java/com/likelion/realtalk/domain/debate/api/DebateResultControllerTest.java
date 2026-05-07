package com.likelion.realtalk.domain.debate.api;

import com.likelion.realtalk.domain.debate.dto.response.DebateResultResponse;
import com.likelion.realtalk.domain.debate.dto.response.SpeechResponse;
import com.likelion.realtalk.domain.debate.service.DebateResultService;
import com.likelion.realtalk.domain.oauth.handler.OAuth2FailureHandler;
import com.likelion.realtalk.domain.oauth.handler.OAuth2SuccessHandler;
import com.likelion.realtalk.domain.oauth.service.CustomOAuth2UserService;
import com.likelion.realtalk.domain.debate.type.Side;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(DebateResultController.class)
@Import(SecurityConfig.class)
class DebateResultControllerTest {

    @Autowired MockMvc mockMvc;

    @MockitoBean DebateResultService debateResultService;
    @MockitoBean JwtProvider jwtProvider;
    @MockitoBean DebateRedisRepository debateRedisRepository;
    @MockitoBean CustomOAuth2UserService customOAuth2UserService;
    @MockitoBean OAuth2SuccessHandler oAuth2SuccessHandler;
    @MockitoBean OAuth2FailureHandler oAuth2FailureHandler;

    private static final CustomUserDetails USER = new CustomUserDetails(1L, "alice", "USER");

    @Test
    @DisplayName("종료되지 않은 방의 결과 조회 시 409와 ROOM_NOT_ENDED 코드를 반환한다")
    void getResult_roomNotEnded_returns409WithRoomNotEnded() throws Exception {
        given(debateResultService.getResult("test-uuid"))
                .willThrow(new CustomException(ErrorCode.ROOM_NOT_ENDED));

        mockMvc.perform(get("/api/debate-results/{uuid}", "test-uuid"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("ROOM_NOT_ENDED"));
    }

    @Test
    @DisplayName("투표 요청에 side 필드가 없으면 400과 INVALID_INPUT 코드를 반환한다")
    void vote_missingSideField_returns400WithInvalidInput() throws Exception {
        mockMvc.perform(post("/api/debate-results/{uuid}/vote", "test-uuid")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }

    @Test
    @DisplayName("유효한 투표 요청 시 200과 갱신된 집계를 반환한다")
    void vote_validRequest_returns200WithUpdatedCounts() throws Exception {
        given(debateResultService.vote(eq("test-uuid"), any(), isNull(), isNull()))
                .willReturn(new DebateResultResponse(null, 1, 0, 1, 100.0, 0.0));

        mockMvc.perform(post("/api/debate-results/{uuid}/vote", "test-uuid")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"side\":\"A\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sideAVotes").value(1))
                .andExpect(jsonPath("$.data.sideARate").value(100.0));
    }

    @Test
    @DisplayName("로그인 사용자 투표 시 userId가 서비스에 전달된다")
    void vote_authenticatedUser_passesUserIdToService() throws Exception {
        given(debateResultService.vote(eq("test-uuid"), any(), eq(1L), isNull()))
                .willReturn(new DebateResultResponse(null, 1, 0, 1, 100.0, 0.0));

        mockMvc.perform(post("/api/debate-results/{uuid}/vote", "test-uuid")
                        .with(user(USER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"side\":\"A\"}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("발언 목록 조회는 인증 없이 200과 빈 배열을 반환한다")
    void getSpeeches_noAuth_returns200WithArray() throws Exception {
        given(debateResultService.getSpeeches("test-uuid")).willReturn(List.of());

        mockMvc.perform(get("/api/debate/{uuid}/speeches", "test-uuid"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray());
    }
}
