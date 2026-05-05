package com.likelion.realtalk.domain.debate.repository;

import com.likelion.realtalk.domain.debate.entity.DebateResult;
import com.likelion.realtalk.domain.debate.entity.DebateRoom;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface DebateResultRepository extends JpaRepository<DebateResult, Long> {

    Optional<DebateResult> findByDebateRoom(DebateRoom debateRoom);
}
