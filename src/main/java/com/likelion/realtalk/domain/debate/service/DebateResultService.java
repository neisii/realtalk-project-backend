package com.likelion.realtalk.domain.debate.service;

import com.likelion.realtalk.domain.debate.dto.request.VoteRequest;
import com.likelion.realtalk.domain.debate.dto.response.DebateResultResponse;
import com.likelion.realtalk.domain.debate.dto.response.SpeechResponse;
import com.likelion.realtalk.domain.debate.entity.DebateResult;
import com.likelion.realtalk.domain.debate.entity.DebateRoom;
import com.likelion.realtalk.domain.debate.entity.DebateVote;
import com.likelion.realtalk.domain.debate.repository.DebateResultRepository;
import com.likelion.realtalk.domain.debate.repository.DebateRoomRepository;
import com.likelion.realtalk.domain.debate.repository.DebateSpeechRepository;
import com.likelion.realtalk.domain.debate.repository.DebateVoteRepository;
import com.likelion.realtalk.domain.debate.type.DebateStatus;
import com.likelion.realtalk.domain.user.entity.User;
import com.likelion.realtalk.domain.user.repository.UserRepository;
import com.likelion.realtalk.global.exception.CustomException;
import com.likelion.realtalk.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DebateResultService {

    private final DebateRoomRepository debateRoomRepository;
    private final DebateResultRepository debateResultRepository;
    private final DebateVoteRepository debateVoteRepository;
    private final DebateSpeechRepository debateSpeechRepository;
    private final UserRepository userRepository;

    public DebateResultResponse getResult(String roomUuid) {
        DebateRoom room = getEndedRoom(roomUuid);
        DebateResult result = debateResultRepository.findByDebateRoom(room)
                .orElseThrow(() -> new CustomException(ErrorCode.DEBATE_ROOM_NOT_FOUND));
        return DebateResultResponse.from(result);
    }

    @Transactional
    public DebateResultResponse vote(String roomUuid, VoteRequest request, Long voterUserId, String guestId) {
        DebateRoom room = getEndedRoom(roomUuid);
        DebateResult result = debateResultRepository.findByDebateRoom(room)
                .orElseThrow(() -> new CustomException(ErrorCode.DEBATE_ROOM_NOT_FOUND));

        if (voterUserId != null && debateVoteRepository.existsByDebateResultAndVoterUserId(result, voterUserId)) {
            throw new CustomException(ErrorCode.DUPLICATE_VOTE);
        }
        if (guestId != null && debateVoteRepository.existsByDebateResultAndVoterGuestId(result, guestId)) {
            throw new CustomException(ErrorCode.DUPLICATE_VOTE);
        }

        DebateVote vote = DebateVote.builder()
                .debateResult(result)
                .voterUserId(voterUserId)
                .voterGuestId(guestId)
                .side(request.side())
                .build();
        debateVoteRepository.save(vote);
        result.incrementVote(request.side());

        return DebateResultResponse.from(result);
    }

    public List<SpeechResponse> getSpeeches(String roomUuid) {
        DebateRoom room = debateRoomRepository.findByUuid(roomUuid)
                .orElseThrow(() -> new CustomException(ErrorCode.DEBATE_ROOM_NOT_FOUND));

        var speeches = debateSpeechRepository.findByDebateRoomOrderByTurnIndexAsc(room);

        Set<Long> userIds = speeches.stream()
                .filter(s -> s.getSpeakerUserId() != null)
                .map(s -> s.getSpeakerUserId())
                .collect(Collectors.toSet());

        Map<Long, String> usernames = userRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(User::getId, User::getUsername));

        return speeches.stream()
                .map(s -> SpeechResponse.from(s, usernames.getOrDefault(s.getSpeakerUserId(), "Unknown")))
                .toList();
    }

    private DebateRoom getEndedRoom(String roomUuid) {
        DebateRoom room = debateRoomRepository.findByUuid(roomUuid)
                .orElseThrow(() -> new CustomException(ErrorCode.DEBATE_ROOM_NOT_FOUND));
        if (room.getStatus() != DebateStatus.ENDED) {
            throw new CustomException(ErrorCode.ROOM_NOT_ENDED);
        }
        return room;
    }
}
