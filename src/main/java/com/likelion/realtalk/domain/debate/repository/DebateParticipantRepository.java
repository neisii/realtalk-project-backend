package com.likelion.realtalk.domain.debate.repository;

import com.likelion.realtalk.domain.debate.entity.DebateParticipant;
import com.likelion.realtalk.domain.debate.entity.DebateRoom;
import com.likelion.realtalk.domain.debate.type.ParticipantRole;
import com.likelion.realtalk.domain.debate.type.Side;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DebateParticipantRepository extends JpaRepository<DebateParticipant, Long> {

    List<DebateParticipant> findByDebateRoomAndLeftAtIsNull(DebateRoom debateRoom);

    long countByDebateRoomAndParticipantRoleAndSideAndLeftAtIsNull(
            DebateRoom debateRoom, ParticipantRole role, Side side);

    long countByDebateRoomAndParticipantRoleAndLeftAtIsNull(
            DebateRoom debateRoom, ParticipantRole role);
}
