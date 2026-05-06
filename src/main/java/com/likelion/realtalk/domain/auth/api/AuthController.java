package com.likelion.realtalk.domain.auth.api;

import com.likelion.realtalk.domain.auth.dto.response.TokenRefreshResponse;
import com.likelion.realtalk.domain.auth.dto.response.UserProfileResponse;
import com.likelion.realtalk.domain.auth.service.AuthService;
import com.likelion.realtalk.global.common.ApiResponse;
import com.likelion.realtalk.global.security.core.CustomUserDetails;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/api/auth/refresh")
    public ResponseEntity<ApiResponse<TokenRefreshResponse>> refresh(
            HttpServletRequest request,
            HttpServletResponse response) {
        return ApiResponse.ok(authService.refresh(request, response));
    }

    @GetMapping("/api/auth/me")
    public ResponseEntity<ApiResponse<UserProfileResponse>> getMe(
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        return ApiResponse.ok(authService.getMe(userDetails.getUserId()));
    }

    @PostMapping("/auth/logout")
    public ResponseEntity<ApiResponse<Void>> logout(
            HttpServletRequest request,
            HttpServletResponse response,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        authService.logout(request, response, userDetails.getUserId());
        return ApiResponse.ok();
    }
}
