package com.likelion.realtalk.support;

import com.google.cloud.speech.v1.SpeechClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 3층 통합 테스트 공통 베이스.
 * MySQL + Redis Testcontainer를 띄우고 Spring 컨텍스트에 연결 정보를 주입한다.
 * 한 번 기동된 컨테이너는 모든 하위 테스트가 공유한다 (static).
 *
 * GcpConfig.speechClient() 는 @Lazy지만 SpeechToTextService가 eager하게 요청하므로
 * 통합 테스트에서는 @MockitoBean으로 대체한다 (GCP 자격증명 불필요).
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                // test/resources/application.yml의 Redis 자동구성 제외를 해제
                "spring.autoconfigure.exclude=",
                // Flyway 마이그레이션 활성화 (test YAML에서 비활성화되어 있음)
                "spring.flyway.enabled=true"
        }
)
@Testcontainers
public abstract class BaseIntegrationTest {

    // GCP SpeechClient — 통합 테스트에서는 Mock으로 대체 (STT 기능 불필요)
    @MockitoBean
    SpeechClient speechClient;

    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("realtalk")
            .withUsername("realtalk")
            .withPassword("realtalk")
            .withCommand(
                    "--character-set-server=utf8mb4",
                    "--collation-server=utf8mb4_unicode_ci"
            );

    @SuppressWarnings("resource")
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    static {
        // Docker Desktop 4.x에서 API 1.40+ 요구 — docker-java 기본값 1.32 오버라이드
        System.setProperty("DOCKER_API_VERSION", "1.44");
        MYSQL.start();
        REDIS.start();
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        // application.yml 플레이스홀더 해소용 — URL은 아래에서 직접 오버라이드
        registry.add("MYSQL_HOST", MYSQL::getHost);
        registry.add("MYSQL_PORT", () -> String.valueOf(MYSQL.getMappedPort(3306)));
        registry.add("MYSQL_DATABASE", () -> "realtalk");
        registry.add("MYSQL_USERNAME", MYSQL::getUsername);
        registry.add("MYSQL_PASSWORD", MYSQL::getPassword);
        registry.add("REDIS_HOST", REDIS::getHost);
        registry.add("REDIS_PORT", () -> String.valueOf(REDIS.getMappedPort(6379)));

        // 직접 오버라이드 (이중 보장)
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> String.valueOf(REDIS.getMappedPort(6379)));
    }
}
