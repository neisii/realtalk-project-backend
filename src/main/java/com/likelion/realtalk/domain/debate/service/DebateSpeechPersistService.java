package com.likelion.realtalk.domain.debate.service;

import com.likelion.realtalk.domain.debate.entity.DebateResult;
import com.likelion.realtalk.domain.debate.entity.DebateRoom;
import com.likelion.realtalk.domain.debate.entity.DebateSpeech;
import com.likelion.realtalk.domain.debate.repository.DebateResultRepository;
import com.likelion.realtalk.domain.debate.repository.DebateRoomRepository;
import com.likelion.realtalk.domain.debate.repository.DebateSpeechRepository;
import com.likelion.realtalk.domain.debate.type.Side;
import com.likelion.realtalk.global.exception.CustomException;
import com.likelion.realtalk.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DebateSpeechPersistService {

    private final DebateSpeechRepository debateSpeechRepository;
    private final DebateRoomRepository debateRoomRepository;
    private final DebateResultRepository debateResultRepository;

    @Transactional
    public void saveTranscript(String roomUuid, int turnIndex, Long speakerUserId, Side side, String transcript) {
        DebateRoom room = debateRoomRepository.findByUuid(roomUuid)
                .orElseThrow(() -> new CustomException(ErrorCode.DEBATE_ROOM_NOT_FOUND));

        debateSpeechRepository.findByDebateRoomAndTurnIndex(room, turnIndex)
                .ifPresentOrElse(
                        speech -> speech.updateTranscript(transcript),
                        () -> debateSpeechRepository.save(DebateSpeech.builder()
                                .debateRoom(room)
                                .turnIndex(turnIndex)
                                .speakerUserId(speakerUserId)
                                .side(side)
                                .transcript(transcript)
                                .build())
                );
    }

    @Transactional
    public void updateSummary(String roomUuid, int turnIndex, String summary) {
        debateRoomRepository.findByUuid(roomUuid).ifPresent(room ->
                debateSpeechRepository.findByDebateRoomAndTurnIndex(room, turnIndex)
                        .ifPresent(speech -> speech.updateAiSummary(summary))
        );
    }

    @Transactional
    public void saveDebateAnalysis(String roomUuid, String analysis) {
        debateRoomRepository.findByUuid(roomUuid).ifPresent(room ->
                debateResultRepository.findByDebateRoom(room).ifPresent(result -> {
                    result.updateAiAnalysis(analysis);
                    log.info("Debate analysis saved for room {}: {} chars", roomUuid, analysis.length());
                })
        );
    }

    public String buildSpeechesSummary(String roomUuid) {
        return debateRoomRepository.findByUuid(roomUuid)
                .map(room -> debateSpeechRepository.findByDebateRoomOrderByTurnIndexAsc(room))
                .map(speeches -> speeches.stream()
                        .filter(s -> s.getAiSummary() != null)
                        .map(s -> "턴 " + s.getTurnIndex() + " [" + s.getSide() + "측]: " + s.getAiSummary())
                        .collect(Collectors.joining("\n")))
                .orElse("");
    }
}
