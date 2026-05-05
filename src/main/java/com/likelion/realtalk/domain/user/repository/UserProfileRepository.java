package com.likelion.realtalk.domain.user.repository;

import com.likelion.realtalk.domain.user.entity.User;
import com.likelion.realtalk.domain.user.entity.UserProfile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserProfileRepository extends JpaRepository<UserProfile, Long> {

    Optional<UserProfile> findByUser(User user);
}
