package com.likelion.realtalk.global.config;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.speech.v1.SpeechClient;
import com.google.cloud.speech.v1.SpeechSettings;
import com.google.api.gax.core.FixedCredentialsProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Slf4j
@Configuration
public class GcpConfig {

    @Value("${gcp.credentials-json:}")
    private String credentialsJson;

    @Bean
    @Lazy
    public SpeechClient speechClient() throws IOException {
        if (credentialsJson == null || credentialsJson.isBlank()) {
            log.info("GCP_CREDENTIALS_JSON not set — using Application Default Credentials");
            return SpeechClient.create();
        }

        GoogleCredentials credentials = GoogleCredentials
                .fromStream(new ByteArrayInputStream(
                        credentialsJson.getBytes(StandardCharsets.UTF_8)));

        SpeechSettings settings = SpeechSettings.newBuilder()
                .setCredentialsProvider(FixedCredentialsProvider.create(credentials))
                .build();

        return SpeechClient.create(settings);
    }
}
