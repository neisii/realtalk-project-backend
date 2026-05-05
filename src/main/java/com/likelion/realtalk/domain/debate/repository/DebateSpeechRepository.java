package com.likelion.realtalk.domain.debate.repository;

import com.likelion.realtalk.domain.debate.entity.DebateRoom;
import com.likelion.realtalk.domain.debate.entity.DebateSpeech;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DebateSpeechRepository extends JpaRepository<DebateSpeech, Long> {

    List<DebateSpeech> findByDebateRoomOrderByTurnIndexAsc(DebateRoom debateRoom);

    Optional<DebateSpeech> findByDebateRoomAndTurnIndex(DebateRoom debateRoom, int turnIndex);
}
