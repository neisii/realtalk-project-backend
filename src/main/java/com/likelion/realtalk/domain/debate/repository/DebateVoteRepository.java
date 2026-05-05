package com.likelion.realtalk.domain.debate.repository;

import com.likelion.realtalk.domain.debate.entity.DebateResult;
import com.likelion.realtalk.domain.debate.entity.DebateVote;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DebateVoteRepository extends JpaRepository<DebateVote, Long> {

    boolean existsByDebateResultAndVoterUserId(DebateResult debateResult, Long voterUserId);

    boolean existsByDebateResultAndVoterGuestId(DebateResult debateResult, String voterGuestId);
}
