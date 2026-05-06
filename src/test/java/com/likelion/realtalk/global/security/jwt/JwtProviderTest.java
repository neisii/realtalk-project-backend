package com.likelion.realtalk.global.security.jwt;

import com.likelion.realtalk.global.exception.CustomException;
import com.likelion.realtalk.global.exception.ErrorCode;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class JwtProviderTest {

    private static final String SECRET = "test-secret-key-at-least-32-characters-long!!";
    private static final long ACCESS_EXPIRY = 3_600_000L;   // 1시간
    private static final long REFRESH_EXPIRY = 1_209_600_000L; // 14일

    private JwtProvider jwtProvider;

    @BeforeEach
    void setUp() {
        jwtProvider = new JwtProvider(SECRET, ACCESS_EXPIRY, REFRESH_EXPIRY);
    }

    @Test
    @DisplayName("Access Token 생성 후 파싱하면 주입한 클레임이 그대로 나온다")
    void createAndParseAccessToken_claimsMatchInput() {
        String token = jwtProvider.createAccessToken(42L, "alice", "USER");

        assertThat(jwtProvider.getUserId(token)).isEqualTo(42L);
        assertThat(jwtProvider.getJti(token)).isNotBlank();
        assertThat(jwtProvider.getRemainingSeconds(token)).isPositive();
    }

    @Test
    @DisplayName("Refresh Token 생성 후 파싱하면 userId sub 클레임이 일치한다")
    void createAndParseRefreshToken_subjectMatchesUserId() {
        String token = jwtProvider.createRefreshToken(99L);

        assertThat(jwtProvider.getUserId(token)).isEqualTo(99L);
    }

    @Test
    @DisplayName("만료된 토큰 파싱 시 TOKEN_EXPIRED 예외를 던진다")
    void parseToken_expired_throwsTokenExpired() {
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        String expiredToken = Jwts.builder()
                .subject("1")
                .id(UUID.randomUUID().toString())
                .expiration(new Date(System.currentTimeMillis() - 1_000)) // 1초 전 만료
                .signWith(key)
                .compact();

        assertThatThrownBy(() -> jwtProvider.parseToken(expiredToken))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.TOKEN_EXPIRED);
    }

    @Test
    @DisplayName("잘못된 형식의 토큰 파싱 시 UNAUTHORIZED 예외를 던진다")
    void parseToken_malformed_throwsUnauthorized() {
        assertThatThrownBy(() -> jwtProvider.parseToken("not.a.valid.jwt"))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.UNAUTHORIZED);
    }

    @Test
    @DisplayName("다른 키로 서명된 토큰 파싱 시 UNAUTHORIZED 예외를 던진다")
    void parseToken_wrongSignature_throwsUnauthorized() {
        JwtProvider otherProvider = new JwtProvider(
                "completely-different-secret-key-at-least-32-chars!!", ACCESS_EXPIRY, REFRESH_EXPIRY);
        String tokenFromOther = otherProvider.createAccessToken(1L, "user", "USER");

        assertThatThrownBy(() -> jwtProvider.parseToken(tokenFromOther))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.UNAUTHORIZED);
    }

    @Test
    @DisplayName("getAccessTokenExpirySeconds는 밀리초 단위 만료 시간을 초로 변환해 반환한다")
    void getAccessTokenExpirySeconds_returnsSecondsUnit() {
        assertThat(jwtProvider.getAccessTokenExpirySeconds()).isEqualTo(ACCESS_EXPIRY / 1000);
    }
}
