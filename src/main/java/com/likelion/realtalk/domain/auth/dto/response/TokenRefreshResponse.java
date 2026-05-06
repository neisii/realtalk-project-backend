package com.likelion.realtalk.domain.auth.dto.response;

public record TokenRefreshResponse(String accessToken, long expiresIn) {
}
