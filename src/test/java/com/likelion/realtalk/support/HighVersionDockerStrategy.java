package com.likelion.realtalk.support;

import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.transport.DockerHttpClient;
import com.github.dockerjava.zerodep.ZerodepDockerHttpClient;
import org.testcontainers.dockerclient.DockerClientProviderStrategy;
import org.testcontainers.dockerclient.TransportConfig;

import java.net.URI;

/**
 * Docker Desktop 4.x (API 1.40+) 대응 커스텀 전략.
 * 기본 전략이 사용하는 docker-java API 기본값(1.32)을 1.44로 오버라이드한다.
 */
public class HighVersionDockerStrategy extends DockerClientProviderStrategy {

    private static final String SOCKET = "unix:///var/run/docker.sock";

    @Override
    public TransportConfig getTransportConfig() {
        return TransportConfig.builder()
                .dockerHost(URI.create(SOCKET))
                .build();
    }

    @Override
    public com.github.dockerjava.api.DockerClient getDockerClient() {
        DockerClientConfig config = DefaultDockerClientConfig.createDefaultConfigBuilder()
                .withDockerHost(SOCKET)
                .withApiVersion("1.44")
                .build();

        DockerHttpClient httpClient = new ZerodepDockerHttpClient.Builder()
                .dockerHost(URI.create(SOCKET))
                .build();

        return DockerClientImpl.getInstance(config, httpClient);
    }

    @Override
    public String getDescription() {
        return "Docker Desktop 4.x compatible (API 1.44) via " + SOCKET;
    }

    @Override
    protected boolean isApplicable() {
        return java.nio.file.Files.exists(java.nio.file.Paths.get("/var/run/docker.sock"));
    }

    @Override
    public int getPriority() {
        return 1000; // 기본 전략보다 높은 우선순위
    }
}
