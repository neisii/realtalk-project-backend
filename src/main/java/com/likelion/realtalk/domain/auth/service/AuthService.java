package com.likelion.realtalk.domain.auth.service;

import com.likelion.realtalk.domain.auth.dto.response.TokenRefreshResponse;
import com.likelion.realtalk.domain.auth.dto.response.UserProfileResponse;
import com.likelion.realtalk.domain.user.entity.User;
import com.likelion.realtalk.domain.user.entity.UserProfile;
import com.likelion.realtalk.domain.user.repository.UserProfileRepository;
import com.likelion.realtalk.domain.user.repository.UserRepository;
import com.likelion.realtalk.global.exception.CustomException;
import com.likelion.realtalk.global.exception.ErrorCode;
import com.likelion.realtalk.global.security.jwt.JwtCookieUtil;
import com.likelion.realtalk.global.security.jwt.JwtProvider;
import com.likelion.realtalk.infra.redis.DebateRedisRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AuthService {

    private final JwtProvider jwtProvider;
    private final JwtCookieUtil jwtCookieUtil;
    private final UserRepository userRepository;
    private final UserProfileRepository userProfileRepository;
    private final DebateRedisRepository debateRedisRepository;

    @Transactional
    public TokenRefreshResponse refresh(HttpServletRequest request, HttpServletResponse response) {
        String refreshToken = jwtCookieUtil.resolveRefreshToken(request)
                .orElseThrow(() -> new CustomException(ErrorCode.UNAUTHORIZED));

        Long userId = jwtProvider.getUserId(refreshToken);

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));

        if (!refreshToken.equals(user.getRefreshToken())) {
            throw new CustomException(ErrorCode.UNAUTHORIZED);
        }

        String newAccessToken = jwtProvider.createAccessToken(
                userId, user.getUsername(), user.getRole().name());
        String newRefreshToken = jwtProvider.createRefreshToken(userId);

        user.updateRefreshToken(newRefreshToken);
        jwtCookieUtil.setRefreshTokenCookie(response, newRefreshToken);

        return new TokenRefreshResponse(newAccessToken, jwtProvider.getAccessTokenExpirySeconds());
    }

    @Transactional
    public void logout(HttpServletRequest request, HttpServletResponse response, Long userId) {
        String accessToken = resolveToken(request);

        if (StringUtils.hasText(accessToken)) {
            try {
                String jti = jwtProvider.getJti(accessToken);
                long remainingSecs = jwtProvider.getRemainingSeconds(accessToken);
                debateRedisRepository.blacklistJti(jti, remainingSecs);
            } catch (Exception e) {
                // Access Token이 만료됐어도 로그아웃은 진행
            }
        }

        userRepository.findById(userId).ifPresent(User::revokeRefreshToken);
        jwtCookieUtil.clearRefreshTokenCookie(response);
    }

    public UserProfileResponse getMe(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));
        UserProfile profile = userProfileRepository.findByUser(user).orElse(null);
        return UserProfileResponse.of(user, profile);
    }

    private String resolveToken(HttpServletRequest request) {
        String bearer = request.getHeader("Authorization");
        if (StringUtils.hasText(bearer) && bearer.startsWith("Bearer ")) {
            return bearer.substring(7);
        }
        return null;
    }
}
