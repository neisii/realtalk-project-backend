package com.likelion.realtalk.domain.debate.entity;

import com.likelion.realtalk.domain.debate.type.Side;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "debate_speeches")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class DebateSpeech {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "debate_room_id", nullable = false)
    private DebateRoom debateRoom;

    @Column(name = "turn_index", nullable = false)
    private int turnIndex;

    @Column(name = "speaker_user_id")
    private Long speakerUserId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Side side;

    @Column(columnDefinition = "TEXT")
    private String transcript;

    @Column(name = "ai_summary", columnDefinition = "TEXT")
    private String aiSummary;

    @Column(name = "spoken_at", nullable = false)
    private LocalDateTime spokenAt;

    @PrePersist
    private void prePersist() {
        if (this.spokenAt == null) {
            this.spokenAt = LocalDateTime.now();
        }
    }

    public void updateTranscript(String transcript) {
        this.transcript = transcript;
    }

    public void updateAiSummary(String aiSummary) {
        this.aiSummary = aiSummary;
    }
}
