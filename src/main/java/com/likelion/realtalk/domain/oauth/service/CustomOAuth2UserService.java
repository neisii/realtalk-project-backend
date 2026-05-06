package com.likelion.realtalk.domain.oauth.service;

import com.likelion.realtalk.domain.auth.entity.Auth;
import com.likelion.realtalk.domain.auth.repository.AuthRepository;
import com.likelion.realtalk.domain.oauth.userinfo.GoogleOAuth2UserInfo;
import com.likelion.realtalk.domain.oauth.userinfo.KakaoOAuth2UserInfo;
import com.likelion.realtalk.domain.oauth.userinfo.OAuth2UserInfo;
import com.likelion.realtalk.domain.user.entity.User;
import com.likelion.realtalk.domain.user.entity.UserProfile;
import com.likelion.realtalk.domain.user.repository.UserProfileRepository;
import com.likelion.realtalk.domain.user.repository.UserRepository;
import com.likelion.realtalk.domain.user.type.UserRole;
import com.likelion.realtalk.global.security.core.CustomUserDetails;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Service
@RequiredArgsConstructor
public class CustomOAuth2UserService extends DefaultOAuth2UserService {

    private final UserRepository userRepository;
    private final AuthRepository authRepository;
    private final UserProfileRepository userProfileRepository;

    @Override
    @Transactional
    public OAuth2User loadUser(OAuth2UserRequest userRequest) throws OAuth2AuthenticationException {
        OAuth2User oAuth2User = super.loadUser(userRequest);

        String registrationId = userRequest.getClientRegistration().getRegistrationId();
        Map<String, Object> attributes = oAuth2User.getAttributes();

        OAuth2UserInfo userInfo = resolveUserInfo(registrationId, attributes);
        User user = findOrCreateUser(userInfo);

        return new CustomUserDetails(user);
    }

    private OAuth2UserInfo resolveUserInfo(String registrationId, Map<String, Object> attributes) {
        return switch (registrationId.toLowerCase()) {
            case "google" -> new GoogleOAuth2UserInfo(attributes);
            case "kakao" -> new KakaoOAuth2UserInfo(attributes);
            default -> throw new OAuth2AuthenticationException("Unsupported provider: " + registrationId);
        };
    }

    private User findOrCreateUser(OAuth2UserInfo userInfo) {
        return authRepository
                .findByProviderAndProviderId(userInfo.getProvider(), userInfo.getProviderId())
                .map(Auth::getUser)
                .orElseGet(() -> createUser(userInfo));
    }

    private User createUser(OAuth2UserInfo userInfo) {
        String username = userInfo.getProvider() + "_" + userInfo.getProviderId();

        User user = User.builder()
                .username(username)
                .role(UserRole.USER)
                .build();
        userRepository.save(user);

        Auth auth = Auth.builder()
                .user(user)
                .provider(userInfo.getProvider())
                .providerId(userInfo.getProviderId())
                .providerEmail(userInfo.getEmail())
                .build();
        authRepository.save(auth);

        UserProfile profile = UserProfile.builder()
                .user(user)
                .nickname(userInfo.getNickname())
                .build();
        userProfileRepository.save(profile);

        return user;
    }
}
