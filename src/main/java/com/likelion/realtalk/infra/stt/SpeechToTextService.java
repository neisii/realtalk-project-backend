package com.likelion.realtalk.infra.stt;

import com.google.cloud.speech.v1.*;
import com.google.protobuf.ByteString;
import com.likelion.realtalk.global.exception.CustomException;
import com.likelion.realtalk.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class SpeechToTextService {

    private final SpeechClient speechClient;

    // 동기 블로킹 호출 — SpeechPipeline에서 sttExecutor 스레드에 제출
    public String recognize(byte[] audioBytes) {
        if (audioBytes == null || audioBytes.length == 0) {
            return "";
        }

        RecognitionAudio audio = RecognitionAudio.newBuilder()
                .setContent(ByteString.copyFrom(audioBytes))
                .build();

        RecognitionConfig config = RecognitionConfig.newBuilder()
                .setEncoding(RecognitionConfig.AudioEncoding.WEBM_OPUS)
                .setSampleRateHertz(48000)
                .setLanguageCode("ko-KR")
                .setMaxAlternatives(1)
                .build();

        try {
            RecognizeResponse response = speechClient.recognize(config, audio);
            return response.getResultsList().stream()
                    .flatMap(r -> r.getAlternativesList().stream())
                    .findFirst()
                    .map(SpeechRecognitionAlternative::getTranscript)
                    .orElse("");
        } catch (Exception e) {
            log.error("Google STT failed, audioSize={}", audioBytes.length, e);
            throw new CustomException(ErrorCode.STT_PROCESSING_FAILED);
        }
    }
}
