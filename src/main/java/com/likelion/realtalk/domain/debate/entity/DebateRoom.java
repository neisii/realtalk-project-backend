package com.likelion.realtalk.domain.debate.entity;

import com.likelion.realtalk.domain.category.entity.Category;
import com.likelion.realtalk.domain.debate.type.DebateStatus;
import com.likelion.realtalk.domain.debate.type.DebateType;
import com.likelion.realtalk.domain.user.entity.User;
import com.likelion.realtalk.global.common.BaseTimeEntity;
import com.likelion.realtalk.global.exception.CustomException;
import com.likelion.realtalk.global.exception.ErrorCode;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "debate_rooms")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class DebateRoom extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 36)
    private String uuid;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "creator_id", nullable = false)
    private User creator;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id")
    private Category category;

    @Column(nullable = false)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "side_a", nullable = false)
    private String sideA;

    @Column(name = "side_b", nullable = false)
    private String sideB;

    @Column(name = "turn_duration_secs", nullable = false)
    private int turnDurationSecs;

    @Column(name = "total_duration_secs", nullable = false)
    private int totalDurationSecs;

    @Column(name = "max_speaker", nullable = false)
    private int maxSpeaker;

    @Column(name = "max_audience", nullable = false)
    private int maxAudience;

    @Enumerated(EnumType.STRING)
    @Column(name = "debate_type", nullable = false)
    private DebateType debateType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DebateStatus status;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "ended_at")
    private LocalDateTime endedAt;

    public void start() {
        if (this.status != DebateStatus.WAITING) {
            throw new CustomException(ErrorCode.ROOM_ALREADY_STARTED);
        }
        this.status = DebateStatus.STARTED;
        this.startedAt = LocalDateTime.now();
    }

    public void end() {
        if (this.status == DebateStatus.ENDED) {
            throw new CustomException(ErrorCode.ROOM_ALREADY_ENDED);
        }
        this.status = DebateStatus.ENDED;
        this.endedAt = LocalDateTime.now();
    }

    public boolean isHost(Long userId) {
        return this.creator.getId().equals(userId);
    }

    public int effectiveTurnDurationSecs() {
        return this.debateType == DebateType.FAST
                ? this.turnDurationSecs / 2
                : this.turnDurationSecs;
    }
}
