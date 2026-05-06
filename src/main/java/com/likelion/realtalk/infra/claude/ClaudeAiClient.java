package com.likelion.realtalk.infra.claude;

import com.likelion.realtalk.global.exception.CustomException;
import com.likelion.realtalk.global.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class ClaudeAiClient {

    private final ChatClient chatClient;

    public ClaudeAiClient(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    public String summarize(String transcript) {
        if (transcript == null || transcript.isBlank()) {
            return "발언 내용이 없습니다.";
        }
        try {
            return chatClient.prompt()
                    .user("""
                            다음 토론 발언을 2~3문장으로 요약해 주세요.
                            발언자의 핵심 주장을 중심으로 객관적으로 작성하세요.

                            발언 내용:
                            %s
                            """.formatted(transcript))
                    .call()
                    .content();
        } catch (Exception e) {
            log.error("Claude summarize failed", e);
            throw new CustomException(ErrorCode.AI_PROCESSING_FAILED);
        }
    }

    public String factcheck(String claim) {
        if (claim == null || claim.isBlank()) {
            return "{\"verdict\":\"unverifiable\",\"explanation\":\"검증할 내용이 없습니다.\",\"sources\":[]}";
        }
        try {
            return chatClient.prompt()
                    .user("""
                            다음 주장의 사실 여부를 검증해 주세요.
                            verdict는 'true'(사실), 'false'(허위), 'unverifiable'(확인불가) 중 하나로,
                            explanation은 200자 이내로 작성하세요.
                            JSON 형식으로만 반환: {"verdict":"...","explanation":"...","sources":[]}

                            주장: %s
                            """.formatted(claim))
                    .call()
                    .content();
        } catch (Exception e) {
            log.error("Claude factcheck failed", e);
            throw new CustomException(ErrorCode.AI_PROCESSING_FAILED);
        }
    }

    public String analyzeDebate(String speechesSummary) {
        if (speechesSummary == null || speechesSummary.isBlank()) {
            return "분석할 발언이 없습니다.";
        }
        try {
            return chatClient.prompt()
                    .user("""
                            다음 토론의 각 발언을 종합적으로 분석해 주세요.
                            양측의 주요 주장, 논리적 강점과 약점, 전반적인 토론 흐름을 3~5단락으로 작성하세요.

                            토론 발언 요약:
                            %s
                            """.formatted(speechesSummary))
                    .call()
                    .content();
        } catch (Exception e) {
            log.error("Claude analyzeDebate failed", e);
            throw new CustomException(ErrorCode.AI_PROCESSING_FAILED);
        }
    }
}
