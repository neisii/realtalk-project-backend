package com.likelion.realtalk.domain.debate.repository;

import com.likelion.realtalk.domain.debate.entity.DebateChat;
import com.likelion.realtalk.domain.debate.entity.DebateRoom;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DebateChatRepository extends JpaRepository<DebateChat, Long> {

    List<DebateChat> findTop20ByDebateRoomOrderBySentAtDesc(DebateRoom debateRoom);
}
