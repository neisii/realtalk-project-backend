package com.likelion.realtalk.infra.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.likelion.realtalk.domain.debate.dto.event.AiFailedEvent;
import com.likelion.realtalk.domain.debate.dto.event.AiSummaryEvent;
import com.likelion.realtalk.domain.debate.dto.event.DebateAnalysisEvent;
import com.likelion.realtalk.domain.debate.dto.event.FactcheckResultEvent;
import com.likelion.realtalk.domain.debate.service.DebateSpeechPersistService;
import com.likelion.realtalk.domain.debate.type.Side;
import com.likelion.realtalk.global.common.WsMessage;
import com.likelion.realtalk.infra.claude.ClaudeAiClient;
import com.likelion.realtalk.infra.redis.RedisPublisher;
import com.likelion.realtalk.infra.stt.SpeechToTextService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

@Slf4j
@Component
public class SpeechPipeline {

    private final SpeechToTextService sttService;
    private final ClaudeAiClient claudeAiClient;
    private final DebateSpeechPersistService speechPersistService;
    private final RedisPublisher redisPublisher;
    private final ObjectMapper objectMapper;
    private final Executor sttExecutor;
    private final Executor aiExecutor;
    private final Executor dbExecutor;
    private final Executor broadcastExecutor;

    public SpeechPipeline(
            SpeechToTextService sttService,
            ClaudeAiClient claudeAiClient,
            DebateSpeechPersistService speechPersistService,
            RedisPublisher redisPublisher,
            ObjectMapper objectMapper,
            @Qualifier("sttExecutor") Executor sttExecutor,
            @Qualifier("aiExecutor") Executor aiExecutor,
            @Qualifier("dbExecutor") Executor dbExecutor,
            @Qualifier("broadcastExecutor") Executor broadcastExecutor) {
        this.sttService = sttService;
        this.claudeAiClient = claudeAiClient;
        this.speechPersistService = speechPersistService;
        this.redisPublisher = redisPublisher;
        this.objectMapper = objectMapper;
        this.sttExecutor = sttExecutor;
        this.aiExecutor = aiExecutor;
        this.dbExecutor = dbExecutor;
        this.broadcastExecutor = broadcastExecutor;
    }

    /**
     * 발언 종료 후 STT → DB 저장 → AI 요약 → DB 저장 → WebSocket 브로드캐스트
     * 핸들러 스레드 즉시 반환 — 파이프라인은 전용 스레드풀에서 비동기 실행
     */
    public void processAsync(byte[] audioBytes, String roomUuid,
                              int turnIndex, Long speakerUserId, Side side) {
        CompletableFuture
                .supplyAsync(() -> sttService.recognize(audioBytes), sttExecutor)
                .thenApplyAsync(transcript -> {
                    speechPersistService.saveTranscript(roomUuid, turnIndex, speakerUserId, side, transcript);
                    return transcript;
                }, dbExecutor)
                .thenApplyAsync(transcript -> claudeAiClient.summarize(transcript), aiExecutor)
                .thenAcceptAsync(summary -> {
                    speechPersistService.updateSummary(roomUuid, turnIndex, summary);
                    redisPublisher.publish(roomUuid, WsMessage.of(
                            "AI_SUMMARY",
                            new AiSummaryEvent(turnIndex, side != null ? side.name() : null, summary)
                    ));
                }, dbExecutor)
                .exceptionally(e -> {
                    log.error("Speech pipeline failed for room={} turn={}", roomUuid, turnIndex, e);
                    redisPublisher.publish(roomUuid, WsMessage.of(
                            "AI_FAILED",
                            new AiFailedEvent(turnIndex, "AI 처리에 실패했습니다.")
                    ));
                    return null;
                });
    }

    /**
     * 팩트체크 요청 처리 — 온디맨드, 완전 비동기
     */
    public void factcheckAsync(String roomUuid, int turnIndex, String claim) {
        CompletableFuture
                .supplyAsync(() -> claudeAiClient.factcheck(claim), aiExecutor)
                .thenAcceptAsync(jsonResult -> {
                    FactcheckResultEvent event = parseFactcheckResult(turnIndex, jsonResult);
                    redisPublisher.publish(roomUuid, WsMessage.of("FACTCHECK_RESULT", event));
                }, broadcastExecutor)
                .exceptionally(e -> {
                    log.error("Factcheck failed for room={} turn={}", roomUuid, turnIndex, e);
                    return null;
                });
    }

    /**
     * 토론 종료 후 종합 분석 — 비동기, 결과는 DB 저장 후 WebSocket 푸시
     */
    public void analyzeDebateAsync(String roomUuid) {
        CompletableFuture
                .supplyAsync(() -> speechPersistService.buildSpeechesSummary(roomUuid), dbExecutor)
                .thenApplyAsync(summary -> claudeAiClient.analyzeDebate(summary), aiExecutor)
                .thenAcceptAsync(analysis -> {
                    speechPersistService.saveDebateAnalysis(roomUuid, analysis);
                    redisPublisher.publish(roomUuid, WsMessage.of(
                            "DEBATE_ANALYSIS",
                            new DebateAnalysisEvent(analysis)
                    ));
                }, dbExecutor)
                .exceptionally(e -> {
                    log.error("Debate analysis failed for room={}", roomUuid, e);
                    return null;
                });
    }

    @SuppressWarnings("unchecked")
    private FactcheckResultEvent parseFactcheckResult(int turnIndex, String jsonResult) {
        try {
            Map<String, Object> map = objectMapper.readValue(jsonResult, Map.class);
            return new FactcheckResultEvent(
                    turnIndex,
                    String.valueOf(map.getOrDefault("verdict", "unverifiable")),
                    String.valueOf(map.getOrDefault("explanation", ""))
            );
        } catch (Exception e) {
            log.warn("Failed to parse factcheck JSON: {}", jsonResult);
            return new FactcheckResultEvent(turnIndex, "unverifiable", "팩트체크 결과 파싱 실패");
        }
    }
}
