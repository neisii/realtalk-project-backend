package com.likelion.realtalk.domain.debate.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "debate_topics")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class DebateTopic {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 200)
    private String title;
}
