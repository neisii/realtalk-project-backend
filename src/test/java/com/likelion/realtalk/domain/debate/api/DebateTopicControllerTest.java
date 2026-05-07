package com.likelion.realtalk.domain.debate.api;

import com.likelion.realtalk.domain.debate.dto.response.TopicResponse;
import com.likelion.realtalk.domain.debate.service.DebateTopicService;
import com.likelion.realtalk.domain.oauth.handler.OAuth2FailureHandler;
import com.likelion.realtalk.domain.oauth.handler.OAuth2SuccessHandler;
import com.likelion.realtalk.domain.oauth.service.CustomOAuth2UserService;
import com.likelion.realtalk.global.security.core.CustomUserDetails;
import com.likelion.realtalk.global.security.jwt.JwtProvider;
import com.likelion.realtalk.infra.redis.DebateRedisRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import com.likelion.realtalk.global.config.SecurityConfig;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(DebateTopicController.class)
@Import(SecurityConfig.class)
class DebateTopicControllerTest {

    @Autowired MockMvc mockMvc;

    @MockitoBean DebateTopicService debateTopicService;
    @MockitoBean JwtProvider jwtProvider;
    @MockitoBean DebateRedisRepository debateRedisRepository;
    @MockitoBean CustomOAuth2UserService customOAuth2UserService;
    @MockitoBean OAuth2SuccessHandler oAuth2SuccessHandler;
    @MockitoBean OAuth2FailureHandler oAuth2FailureHandler;

    private static final CustomUserDetails USER = new CustomUserDetails(1L, "alice", "USER");
    private static final CustomUserDetails ADMIN = new CustomUserDetails(2L, "admin", "ADMIN");

    @Test
    @DisplayName("인증 없이 주제 등록 시 401을 반환한다")
    void createTopic_notAuthenticated_returns401() throws Exception {
        mockMvc.perform(post("/api/debate-topics")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"test\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("USER 권한으로 주제 등록 시 403을 반환한다")
    void createTopic_userRole_returns403() throws Exception {
        mockMvc.perform(post("/api/debate-topics")
                        .with(user(USER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"test\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("ADMIN 권한으로 주제 등록 시 201과 생성된 주제를 반환한다")
    void createTopic_adminRole_returns201WithTopic() throws Exception {
        given(debateTopicService.create(any()))
                .willReturn(new TopicResponse(1L, "새로운 토론 주제"));

        mockMvc.perform(post("/api/debate-topics")
                        .with(user(ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"새로운 토론 주제\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value(1))
                .andExpect(jsonPath("$.data.title").value("새로운 토론 주제"));
    }

    @Test
    @DisplayName("빈 제목으로 주제 등록 시 400과 INVALID_INPUT 코드를 반환한다")
    void createTopic_blankTitle_returns400WithInvalidInput() throws Exception {
        mockMvc.perform(post("/api/debate-topics")
                        .with(user(ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }

    @Test
    @DisplayName("ADMIN 권한으로 주제 삭제 시 200을 반환한다")
    void deleteTopic_adminRole_returns200() throws Exception {
        mockMvc.perform(delete("/api/debate-topics/{id}", 1L)
                        .with(user(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("USER 권한으로 주제 삭제 시 403을 반환한다")
    void deleteTopic_userRole_returns403() throws Exception {
        mockMvc.perform(delete("/api/debate-topics/{id}", 1L)
                        .with(user(USER)))
                .andExpect(status().isForbidden());
    }
}
