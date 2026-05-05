package com.likelion.realtalk.domain.debate.entity;

import com.likelion.realtalk.domain.debate.type.ParticipantRole;
import com.likelion.realtalk.domain.debate.type.Side;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "debate_participants")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class DebateParticipant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "debate_room_id", nullable = false)
    private DebateRoom debateRoom;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "guest_id")
    private String guestId;

    @Enumerated(EnumType.STRING)
    @Column(name = "participant_role", nullable = false)
    private ParticipantRole participantRole;

    @Enumerated(EnumType.STRING)
    @Column
    private Side side;

    @Column(name = "joined_at", nullable = false)
    private LocalDateTime joinedAt;

    @Column(name = "left_at")
    private LocalDateTime leftAt;

    @PrePersist
    private void prePersist() {
        if (this.joinedAt == null) {
            this.joinedAt = LocalDateTime.now();
        }
    }

    public void leave() {
        this.leftAt = LocalDateTime.now();
    }
}
