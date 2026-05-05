package com.likelion.realtalk.domain.auth.repository;

import com.likelion.realtalk.domain.auth.entity.Auth;
import com.likelion.realtalk.domain.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AuthRepository extends JpaRepository<Auth, Long> {

    Optional<Auth> findByProviderAndProviderId(String provider, String providerId);

    Optional<Auth> findByUser(User user);
}
