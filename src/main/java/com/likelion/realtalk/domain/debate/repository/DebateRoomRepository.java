package com.likelion.realtalk.domain.debate.repository;

import com.likelion.realtalk.domain.debate.entity.DebateRoom;
import com.likelion.realtalk.domain.debate.type.DebateStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface DebateRoomRepository extends JpaRepository<DebateRoom, Long> {

    Optional<DebateRoom> findByUuid(String uuid);

    Page<DebateRoom> findByStatus(DebateStatus status, Pageable pageable);

    Page<DebateRoom> findByCategoryIdAndStatus(Long categoryId, DebateStatus status, Pageable pageable);

    Optional<DebateRoom> findFirstByCategoryIdAndStatusOrderByCreatedAtDesc(Long categoryId, DebateStatus status);
}
