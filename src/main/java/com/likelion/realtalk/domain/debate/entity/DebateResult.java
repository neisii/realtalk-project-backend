package com.likelion.realtalk.domain.debate.entity;

import com.likelion.realtalk.domain.debate.type.Side;
import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

@Entity
@Table(name = "debate_results")
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class DebateResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "debate_room_id", nullable = false, unique = true)
    private DebateRoom debateRoom;

    @Column(name = "ai_analysis", columnDefinition = "TEXT")
    private String aiAnalysis;

    @Column(name = "side_a_votes", nullable = false)
    private int sideAVotes;

    @Column(name = "side_b_votes", nullable = false)
    private int sideBVotes;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public void incrementVote(Side side) {
        if (side == Side.A) {
            this.sideAVotes++;
        } else {
            this.sideBVotes++;
        }
    }

    public void updateAiAnalysis(String aiAnalysis) {
        this.aiAnalysis = aiAnalysis;
    }
}
