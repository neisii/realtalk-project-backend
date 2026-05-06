package com.likelion.realtalk.domain.debate.service;

import com.likelion.realtalk.domain.debate.dto.request.VoteRequest;
import com.likelion.realtalk.domain.debate.dto.response.DebateResultResponse;
import com.likelion.realtalk.domain.debate.entity.DebateResult;
import com.likelion.realtalk.domain.debate.entity.DebateRoom;
import com.likelion.realtalk.domain.debate.repository.*;
import com.likelion.realtalk.domain.debate.type.DebateStatus;
import com.likelion.realtalk.domain.debate.type.DebateType;
import com.likelion.realtalk.domain.debate.type.Side;
import com.likelion.realtalk.domain.user.entity.User;
import com.likelion.realtalk.domain.user.repository.UserRepository;
import com.likelion.realtalk.domain.user.type.UserRole;
import com.likelion.realtalk.global.exception.CustomException;
import com.likelion.realtalk.global.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
class DebateResultServiceTest {

    @Mock private DebateRoomRepository debateRoomRepository;
    @Mock private DebateResultRepository debateResultRepository;
    @Mock private DebateVoteRepository debateVoteRepository;
    @Mock private DebateSpeechRepository debateSpeechRepository;
    @Mock private UserRepository userRepository;

    @InjectMocks
    private DebateResultService debateResultService;

    private static final String ROOM_UUID = "test-room-uuid";

    private DebateRoom waitingRoom;
    private DebateRoom endedRoom;
    private DebateResult result;

    @BeforeEach
    void setUp() {
        User creator = User.builder().id(1L).username("host").role(UserRole.USER).build();

        waitingRoom = DebateRoom.builder()
                .uuid(ROOM_UUID).creator(creator).title("Test").sideA("A").sideB("B")
                .turnDurationSecs(90).totalDurationSecs(600)
                .maxSpeaker(2).maxAudience(100)
                .debateType(DebateType.NORMAL).status(DebateStatus.WAITING)
                .build();

        endedRoom = DebateRoom.builder()
                .uuid(ROOM_UUID).creator(creator).title("Test").sideA("A").sideB("B")
                .turnDurationSecs(90).totalDurationSecs(600)
                .maxSpeaker(2).maxAudience(100)
                .debateType(DebateType.NORMAL).status(DebateStatus.ENDED)
                .build();

        result = DebateResult.builder()
                .debateRoom(endedRoom)
                .sideAVotes(0)
                .sideBVotes(0)
                .build();
    }

    @Test
    @DisplayName("ENDED 상태가 아닌 방에 결과 조회 시 ROOM_NOT_ENDED 예외를 던진다")
    void getResult_roomNotEnded_throwsRoomNotEnded() {
        given(debateRoomRepository.findByUuid(ROOM_UUID)).willReturn(Optional.of(waitingRoom));

        assertThatThrownBy(() -> debateResultService.getResult(ROOM_UUID))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.ROOM_NOT_ENDED);
    }

    @Test
    @DisplayName("ENDED 상태가 아닌 방에 투표 시 ROOM_NOT_ENDED 예외를 던진다")
    void vote_roomNotEnded_throwsRoomNotEnded() {
        given(debateRoomRepository.findByUuid(ROOM_UUID)).willReturn(Optional.of(waitingRoom));

        assertThatThrownBy(() -> debateResultService.vote(ROOM_UUID, new VoteRequest(Side.A), 1L, null))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.ROOM_NOT_ENDED);
    }

    @Test
    @DisplayName("동일 userId로 중복 투표 시 DUPLICATE_VOTE 예외를 던진다")
    void vote_duplicateByUserId_throwsDuplicateVote() {
        given(debateRoomRepository.findByUuid(ROOM_UUID)).willReturn(Optional.of(endedRoom));
        given(debateResultRepository.findByDebateRoom(endedRoom)).willReturn(Optional.of(result));
        given(debateVoteRepository.existsByDebateResultAndVoterUserId(result, 1L)).willReturn(true);

        assertThatThrownBy(() -> debateResultService.vote(ROOM_UUID, new VoteRequest(Side.A), 1L, null))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.DUPLICATE_VOTE);
    }

    @Test
    @DisplayName("동일 guestId로 중복 투표 시 DUPLICATE_VOTE 예외를 던진다")
    void vote_duplicateByGuestId_throwsDuplicateVote() {
        given(debateRoomRepository.findByUuid(ROOM_UUID)).willReturn(Optional.of(endedRoom));
        given(debateResultRepository.findByDebateRoom(endedRoom)).willReturn(Optional.of(result));
        given(debateVoteRepository.existsByDebateResultAndVoterGuestId(result, "guest-123")).willReturn(true);

        assertThatThrownBy(() -> debateResultService.vote(ROOM_UUID, new VoteRequest(Side.A), null, "guest-123"))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.DUPLICATE_VOTE);
    }

    @Test
    @DisplayName("유효한 A측 투표 시 sideAVotes가 1 증가한 집계를 반환한다")
    void vote_validSideA_incrementsSideAVotes() {
        given(debateRoomRepository.findByUuid(ROOM_UUID)).willReturn(Optional.of(endedRoom));
        given(debateResultRepository.findByDebateRoom(endedRoom)).willReturn(Optional.of(result));
        given(debateVoteRepository.existsByDebateResultAndVoterUserId(result, 1L)).willReturn(false);

        DebateResultResponse response = debateResultService.vote(ROOM_UUID, new VoteRequest(Side.A), 1L, null);

        assertThat(response.sideAVotes()).isEqualTo(1);
        assertThat(response.sideBVotes()).isEqualTo(0);
        assertThat(response.totalVotes()).isEqualTo(1);
        assertThat(response.sideARate()).isEqualTo(100.0);
    }

    @Test
    @DisplayName("유효한 B측 투표 시 sideBVotes가 1 증가한 집계를 반환한다")
    void vote_validSideB_incrementsSideBVotes() {
        given(debateRoomRepository.findByUuid(ROOM_UUID)).willReturn(Optional.of(endedRoom));
        given(debateResultRepository.findByDebateRoom(endedRoom)).willReturn(Optional.of(result));
        given(debateVoteRepository.existsByDebateResultAndVoterUserId(result, 2L)).willReturn(false);

        DebateResultResponse response = debateResultService.vote(ROOM_UUID, new VoteRequest(Side.B), 2L, null);

        assertThat(response.sideAVotes()).isEqualTo(0);
        assertThat(response.sideBVotes()).isEqualTo(1);
        assertThat(response.sideBRate()).isEqualTo(100.0);
    }
}
