package com.likelion.realtalk.domain.debate.entity;

import com.likelion.realtalk.domain.debate.type.Side;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "debate_votes",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_vote_user", columnNames = {"debate_room_id", "voter_user_id"}),
                @UniqueConstraint(name = "uq_vote_guest", columnNames = {"debate_room_id", "voter_guest_id"})
        })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class DebateVote {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // FK → debate_results(debate_room_id). referencedColumnName으로 PK 대신 unique 컬럼 참조.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "debate_room_id",
            referencedColumnName = "debate_room_id",
            nullable = false)
    private DebateResult debateResult;

    @Column(name = "voter_user_id")
    private Long voterUserId;

    @Column(name = "voter_guest_id")
    private String voterGuestId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Side side;

    @Column(name = "voted_at", nullable = false)
    private LocalDateTime votedAt;

    @PrePersist
    private void prePersist() {
        if (this.votedAt == null) {
            this.votedAt = LocalDateTime.now();
        }
    }
}
