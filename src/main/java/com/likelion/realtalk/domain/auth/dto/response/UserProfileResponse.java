package com.likelion.realtalk.domain.auth.dto.response;

import com.likelion.realtalk.domain.user.entity.User;
import com.likelion.realtalk.domain.user.entity.UserProfile;

public record UserProfileResponse(Long userId, String username, String nickname, String role) {

    public static UserProfileResponse of(User user, UserProfile profile) {
        return new UserProfileResponse(
                user.getId(),
                user.getUsername(),
                profile != null ? profile.getNickname() : null,
                user.getRole().name()
        );
    }
}
