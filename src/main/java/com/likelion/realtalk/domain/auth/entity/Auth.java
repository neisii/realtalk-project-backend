package com.likelion.realtalk.domain.auth.entity;

import com.likelion.realtalk.domain.user.entity.User;
import com.likelion.realtalk.global.common.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "auths",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_auths_provider",
                columnNames = {"provider", "provider_id"}
        ))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class Auth extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    @Column(nullable = false, length = 50)
    private String provider;

    @Column(name = "provider_id", nullable = false)
    private String providerId;

    @Column(name = "provider_email")
    private String providerEmail;
}
