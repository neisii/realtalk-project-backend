# RealTalk — Technical Specification

**Version:** 2.0  
**Date:** 2026-05-05  
**Status:** Draft — 재건축 기준 문서

> PRD.md의 요구사항을 기반으로 "어떻게 만들 것인가"를 정의한다.
> 기존 구현의 결함을 교정한 설계를 담는다.

---

## 1. Tech Stack

| Layer | 선택 | 이유 |
|-------|------|------|
| Language | Java 21 | Virtual Thread (Project Loom) 지원으로 블로킹 I/O 처리 효율화 |
| Framework | Spring Boot 3.x | Spring WebSocket, Security, Data JPA 통합 |
| Build | Gradle (Kotlin DSL) | 타입 안전 빌드 스크립트 |
| Primary DB | MySQL 8.0 | 토론 방·참가자·결과 영구 저장 |
| Cache / Pub/Sub | Redis 7 | 실시간 상태, 세션, 메시지 브로커 |
| WebSocket | Spring WebSocket + STOMP | 실시간 이벤트 전달 |
| Message Broker | Redis Pub/Sub (via Spring's Redis broker relay) | 수평 확장 지원, in-memory broker 대체 |
| AI | Anthropic Claude 3.5 Haiku | 발언 요약, 팩트체크, 종합 분석 |
| STT | Google Cloud Speech-to-Text | 음성 → 텍스트 변환 |
| Auth | OAuth2 (Google, Kakao) + JWT HS256 | 소셜 로그인, 무상태 인증 |
| Observability | Prometheus + Grafana + Loki + Sentry | 메트릭, 로그, 예외 추적 |
| Load Test | K6 | WebSocket 시나리오 부하 테스트 |
| 분산 스케줄 락 | ShedLock (Redis provider) | @Scheduled 다중 인스턴스 중복 실행 방지 |

---

## 2. System Architecture

### 2.1 전체 구조

```
Browser / Mobile Client
        │
        │  HTTPS REST + WSS (STOMP over WebSocket)
        ▼
┌─────────────────────────────────────────────────────┐
│               Spring Boot Application                │
│                                                     │
│  ┌──────────┐  ┌──────────────┐  ┌───────────────┐ │
│  │   Auth   │  │    Debate    │  │  User/Category│ │
│  │  Module  │  │    Module    │  │    Module     │ │
│  └────┬─────┘  └──────┬───────┘  └───────┬───────┘ │
│       │               │                   │         │
│  ┌────▼───────────────▼───────────────────▼──────┐  │
│  │            Service / Repository Layer          │  │
│  └────┬────────────────────┬─────────────────────┘  │
│       │                    │                         │
│  ┌────▼───────┐   ┌────────▼──────────────────────┐ │
│  │  Async     │   │   WebSocket Message Handler   │ │
│  │  Pipeline  │   │   (STOMP + Binary Audio)      │ │
│  │ (STT / AI) │   └───────────────────────────────┘ │
│  └────┬───────┘                                     │
└───────┼──────────────────────────────────────────── ┘
        │
        │  Redis Pub/Sub (모든 인스턴스에 브로드캐스트)
        ▼
┌──────────────────────────────────────────────────────┐
│  Redis 7                                             │
│  - 실시간 상태 (방, 참가자, 타이머)                      │
│  - 메시지 브로커 (Pub/Sub)                             │
│  - AI 요약 캐시                                        │
│  - 세션 / JWT Refresh Token                           │
└──────────────────────────────────────────────────────┘
        │
┌───────▼────────────┐   ┌──────────────────────────┐
│  MySQL 8.0         │   │  External APIs            │
│  - 영구 데이터       │   │  - Claude 3.5 Haiku       │
│  - 토론 결과         │   │  - Google Cloud STT       │
└────────────────────┘   │  - Google/Kakao OAuth2    │
                         └──────────────────────────┘
```

### 2.2 수평 확장 구조

```
Load Balancer (L7, sticky session 불필요)
    │           │           │
Server 1    Server 2    Server 3
    │           │           │
    └───────────┴───────────┘
                │
          Redis Pub/Sub
          (모든 서버가 동일 채널 구독)

→ 어느 서버에서 publish해도 모든 서버의 클라이언트가 수신
→ sticky session 불필요 (모든 서버가 동등)
```

---

## 3. Module Structure

```
com.likelion.realtalk/
├── domain/
│   ├── debate/
│   │   ├── api/             # REST Controller, STOMP Controller
│   │   ├── service/         # 비즈니스 로직 (DebateService, SpeakerService, TimerService)
│   │   ├── repository/      # JPA Repository
│   │   ├── entity/          # JPA Entity
│   │   ├── dto/             # Request/Response DTO
│   │   └── type/            # Enum (DebateStatus, Side, Role, DebateType)
│   ├── user/
│   │   ├── api/
│   │   ├── service/
│   │   ├── repository/
│   │   ├── entity/
│   │   └── dto/
│   ├── auth/
│   │   ├── api/
│   │   ├── service/
│   │   └── dto/
│   ├── oauth/
│   │   ├── service/         # OAuth2UserService
│   │   ├── handler/         # Success/Failure/Logout handler
│   │   ├── userinfo/        # Provider별 UserInfo 추출
│   │   └── type/            # OAuth2Provider enum
│   └── category/
│       ├── api/
│       ├── service/
│       ├── repository/
│       ├── entity/
│       └── dto/
├── global/
│   ├── config/              # Security, WebSocket, Redis, Async, Swagger
│   ├── security/            # JWT Filter, CustomUserDetails
│   ├── exception/           # GlobalExceptionHandler, ErrorCode
│   ├── async/               # AsyncConfig (Thread Pool 정의)
│   └── common/              # ApiResponse, BaseTimeEntity
└── infra/
    ├── claude/              # ClaudeAiClient
    ├── stt/                 # SpeechToTextService (비동기 래퍼)
    ├── redis/               # RedisPublisher, RedisSubscriber, RedisRepository
    └── timer/               # TimerScheduler (@Scheduled 타이머 브로드캐스트)
```

### 레이어 규칙 (전체 코드베이스 일관 적용)

| 레이어 | 역할 | 금지 사항 |
|--------|------|----------|
| Controller / Handler | 요청 수신, DTO 변환, Service 호출 | 비즈니스 로직, 직접 Redis 접근 |
| Service | 비즈니스 로직, 트랜잭션 경계 | 직접 Redis 명령어 호출 |
| Repository | DB / Redis 접근 추상화 | 비즈니스 로직 |
| Infra | 외부 API 클라이언트 (Claude, STT) | Spring Bean 의존 최소화 |

---

## 4. Data Models

### 4.1 ERD

```
users
  PK  id                  BIGINT AUTO_INCREMENT
      username            VARCHAR(255) NOT NULL UNIQUE  -- OAuth 닉네임
      role                ENUM('USER','ADMIN') DEFAULT 'USER'
      refresh_token       TEXT
      created_at, updated_at

user_profiles
  PK  user_profile_id     BIGINT AUTO_INCREMENT
  FK  user_id             → users.id UNIQUE
      nickname            VARCHAR(255)
      bio                 TEXT
      created_at, updated_at

auths
  PK  id                  BIGINT AUTO_INCREMENT
  FK  user_id             → users.id UNIQUE
      provider            VARCHAR(50)      -- 'google' | 'kakao'
      provider_id         VARCHAR(255)
      provider_email      VARCHAR(255)
      UNIQUE (provider, provider_id)
      created_at, updated_at

categories
  PK  id                  BIGINT AUTO_INCREMENT
      category_name       VARCHAR(255) NOT NULL UNIQUE

debate_rooms
  PK  id                  BIGINT AUTO_INCREMENT
      uuid                VARCHAR(36) NOT NULL UNIQUE   -- 외부 노출용 UUID (DB 저장)
  FK  creator_id          → users.id
  FK  category_id         → categories.id
      title               VARCHAR(255) NOT NULL
      description         TEXT
      side_a              VARCHAR(255) NOT NULL
      side_b              VARCHAR(255) NOT NULL
      turn_duration_secs  INT NOT NULL                  -- 턴당 시간(초)
      total_duration_secs INT NOT NULL                  -- 토론 전체 시간(초)
      max_speaker         INT NOT NULL DEFAULT 2
      max_audience        INT NOT NULL DEFAULT 100
      debate_type         ENUM('NORMAL','FAST') DEFAULT 'NORMAL'
      status              ENUM('WAITING','STARTED','ENDED') DEFAULT 'WAITING'
      started_at          DATETIME
      ended_at            DATETIME
      created_at, updated_at

debate_participants
  PK  id                  BIGINT AUTO_INCREMENT
  FK  debate_room_id      → debate_rooms.id
      user_id             BIGINT                        -- nullable (게스트)
      guest_id            VARCHAR(255)                  -- nullable (인증 사용자)
      participant_role    ENUM('SPEAKER','AUDIENCE') NOT NULL
      side                ENUM('A','B')                 -- AUDIENCE면 NULL
      joined_at           DATETIME NOT NULL
      left_at             DATETIME                      -- 퇴장 시각

debate_speeches
  PK  id                  BIGINT AUTO_INCREMENT
  FK  debate_room_id      → debate_rooms.id
      turn_index          INT NOT NULL
      speaker_user_id     BIGINT
      side                ENUM('A','B') NOT NULL
      transcript          TEXT                          -- STT 결과
      ai_summary          TEXT                          -- Claude 요약
      spoken_at           DATETIME NOT NULL

debate_results
  PK  id                  BIGINT AUTO_INCREMENT
  FK  debate_room_id      → debate_rooms.id UNIQUE
      ai_analysis         TEXT                          -- Claude 종합 분석
      side_a_votes        INT DEFAULT 0
      side_b_votes        INT DEFAULT 0
      created_at

debate_votes
  PK  id                  BIGINT AUTO_INCREMENT
  FK  debate_room_id      → debate_rooms.id
      voter_user_id       BIGINT                        -- nullable
      voter_guest_id      VARCHAR(255)                  -- nullable
      side                ENUM('A','B') NOT NULL
      voted_at            DATETIME NOT NULL
      UNIQUE (debate_room_id, voter_user_id)            -- 중복 투표 방지
      UNIQUE (debate_room_id, voter_guest_id)

debate_topics
  PK  id                  BIGINT AUTO_INCREMENT
      title               VARCHAR(200) NOT NULL UNIQUE
```

**변경 사항 (기존 대비):**
- `debate_rooms.uuid` 컬럼 추가 → Redis UUID↔PK 매핑 제거
- `debate_speeches` 신규 테이블 → 발언/요약을 DB에 영구 저장 (기존: Redis만 사용)
- `debate_votes` 신규 테이블 → 투표 중복 방지 DB 제약
- `debate_rooms.turn_duration_secs` 분리 → 턴 시간과 전체 시간 명확히 구분

### 4.2 Redis Key 구조

| Key Pattern | Type | TTL | 용도 |
|-------------|------|-----|------|
| `room:{uuid}:status` | String | debate 종료 + 1h | 방 현재 상태 (WAITING/STARTED/ENDED) |
| `room:{uuid}:timer` | String (epoch ms) | debate 종료 + 1h | 토론 타이머 만료 절대 시각 |
| `room:{uuid}:speaker:timer` | String (epoch ms) | 턴 종료 + 10s | 현재 발언자 타이머 만료 절대 시각 |
| `room:{uuid}:speaker:current` | String | 턴 종료 + 10s | 현재 발언자 userId |
| `room:{uuid}:speaker:turn` | String | debate 종료 + 1h | 현재 턴 인덱스 |
| `room:{uuid}:speaker:spoken` | Set | debate 종료 + 1h | 이번 라운드에 발언한 참가자 ID 목록 |
| `room:{uuid}:participants` | Hash | debate 종료 + 1h | sessionId → {userId, name, role, side} |
| `room:{uuid}:chats` | List (capped 100) | debate 종료 + 24h | 최근 채팅 100개 |
| `session:{jti}` | String | Refresh Token TTL | JWT jti → userId 매핑 (로그아웃 블랙리스트) |
| `pubsub:debate:{uuid}` | Channel | - | Redis Pub/Sub 채널 (방별 브로드캐스트) |

---

## 5. 실시간 아키텍처

### 5.1 WebSocket 브로커 설정

```java
// WebSocketConfig.java
@Override
public void configureMessageBroker(MessageBrokerRegistry registry) {
    // in-memory broker 제거, Redis relay 사용
    registry.enableStompBrokerRelay("/topic", "/sub")
        .setRelayHost(redisHost)
        .setRelayPort(61613)          // Redis STOMP relay 또는 RabbitMQ
        .setClientLogin("guest")
        .setClientPasscode("guest");

    registry.setApplicationDestinationPrefixes("/pub");
}
```

> **대안**: Spring의 Redis Pub/Sub을 직접 사용해 `SimpMessagingTemplate`으로 브로드캐스트하고,
> Redis Subscribe 이벤트를 받아 각 서버 인스턴스에서 STOMP로 전달하는 방식도 가능.
> RabbitMQ 외부 의존 없이 Redis만으로 구성하는 것이 이 프로젝트의 스택에 적합.

### 5.2 서버 주도 타이머

```
infra/timer/TimerScheduler.java

@Scheduled(fixedDelay = 1000)
@SchedulerLock(name = "timerBroadcast", lockAtMostFor = "PT3S", lockAtLeastFor = "PT1S")
public void broadcastTimers() {
    Set<String> activeRooms = redisRepository.getActiveRooms();

    for (String roomUUID : activeRooms) {
        long debateRemaining = calcRemaining(roomUUID, "timer");
        long speakerRemaining = calcRemaining(roomUUID, "speaker:timer");

        if (debateRemaining <= 0) {
            debateService.endDebate(roomUUID);   // 토론 종료 처리
            continue;
        }

        // 모든 서버 인스턴스에서 수신 가능하도록 Redis Pub/Sub으로 publish
        redisPublisher.publish("debate:" + roomUUID, {
            type: "TIMER_TICK",
            debateRemainingSeconds: debateRemaining,
            speakerRemainingSeconds: speakerRemaining
        });

        if (speakerRemaining <= 0) {
            speakerService.advanceTurn(roomUUID);  // 턴 전환 (원자적)
        }
    }
}
```

**설계 원칙:**
- 타이머 만료 기준: Redis key expiry 이벤트 대신 `@Scheduled`에서 직접 계산
- Redis key expiry 이벤트는 보조 수단으로만 활용 (장애 복구용)
- 클라이언트는 서버가 보낸 `remainingSeconds` 값만 표시

### 5.3 원자적 턴 전환

```lua
-- turn_advance.lua (Redis Lua script)
-- KEYS[1]: room:{uuid}:speaker:current
-- KEYS[2]: room:{uuid}:speaker:spoken
-- KEYS[3]: room:{uuid}:speaker:turn
-- KEYS[4]: room:{uuid}:participants (Hash)
-- ARGV[1]: 잠금 토큰 (멱등성 보장)

local lockKey = "room:" .. KEYS[1] .. ":turn_lock"
local acquired = redis.call("SET", lockKey, ARGV[1], "NX", "PX", 5000)
if not acquired then
    return {err = "LOCK_FAILED"}  -- 이미 다른 요청이 처리 중
end

local currentSpeaker = redis.call("GET", KEYS[1])
redis.call("SADD", KEYS[2], currentSpeaker)

-- 다음 발언자 결정 로직 (Lua 내에서 완결)
local allSpeakers = redis.call("HKEYS", KEYS[4])  -- 전체 참가자
-- ... 발언 안 한 참가자 중 다음 순서 계산 ...

redis.call("SET", KEYS[1], nextSpeaker)
redis.call("DEL", lockKey)
return {ok = nextSpeaker}
```

**설계 원칙:**
- 턴 전환 전체를 단일 Lua script로 처리 → 원자성 보장
- `NX` 락으로 동시 진입 방지 → 멱등성 보장
- 락 TTL 5초: 스크립트 실행 실패 시 자동 해제

### 5.4 비동기 AI 파이프라인

```
global/async/AsyncConfig.java

@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean("sttExecutor")
    public Executor sttExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(5);
        executor.setMaxPoolSize(20);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("stt-");
        return executor;
    }

    @Bean("aiExecutor")
    public Executor aiExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(5);
        executor.setMaxPoolSize(20);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("ai-");
        return executor;
    }
}
```

```
발언 종료 이벤트 수신 (WebSocket 핸들러 스레드):
  1. speakerService.advanceTurn(roomUUID)  -- 원자적, 즉시
  2. redisPublisher.publish(TURN_CHANGED)  -- 브로드캐스트, 즉시
  3. speechPipeline.processAsync(audio, roomUUID, turnIndex)  -- 비동기, 즉시 반환

speechPipeline.processAsync (sttExecutor 스레드):
  CompletableFuture
    .supplyAsync(() -> sttService.recognize(audio), sttExecutor)
    .thenApplyAsync(transcript -> {
        debateSpeechRepository.saveTranscript(roomUUID, turnIndex, transcript);
        return transcript;
    }, dbExecutor)
    .thenApplyAsync(transcript -> claudeClient.summarize(transcript), aiExecutor)
    .thenAcceptAsync(summary -> {
        debateSpeechRepository.saveSummary(roomUUID, turnIndex, summary);
        redisPublisher.publish(roomUUID, AI_SUMMARY { turnIndex, summary });
    }, dbExecutor)
    .exceptionally(e -> {
        log.error("Speech pipeline failed", e);
        redisPublisher.publish(roomUUID, AI_FAILED { turnIndex });
        return null;
    });
```

---

## 6. API 명세

### 6.1 공통

**Base URL:** `/api`

**응답 형식:**
```json
{
  "success": true,
  "status": 200,
  "message": "OK",
  "data": { }
}
```

**오류 응답:**
```json
{
  "success": false,
  "status": 404,
  "message": "토론 방을 찾을 수 없습니다.",
  "code": "DEBATE_ROOM_NOT_FOUND",
  "data": null
}
```

### 6.2 인증 API

#### `GET /oauth2/authorization/{provider}`
OAuth2 로그인 시작. provider: `google` | `kakao`

#### `POST /api/auth/refresh`
Access Token 갱신

**Request Header:** `Cookie: refreshToken=<token>`  
**Response:**
```json
{
  "data": {
    "accessToken": "eyJ...",
    "expiresIn": 3600
  }
}
```

#### `GET /api/auth/me`
**Auth:** Bearer 필수  
**Response:**
```json
{
  "data": {
    "userId": 1,
    "username": "user123",
    "nickname": "홍길동",
    "role": "USER"
  }
}
```

#### `POST /auth/logout`
**Auth:** Bearer 필수. Refresh Token 무효화.

---

### 6.3 토론 방 API

#### `POST /api/debate-rooms`
**Auth:** Bearer 필수

**Request:**
```json
{
  "title": "기본 소득제를 도입해야 한다",
  "description": "찬반 양측 자유 토론",
  "categoryId": 2,
  "sideA": "찬성",
  "sideB": "반대",
  "turnDurationSecs": 90,
  "totalDurationSecs": 600,
  "maxSpeaker": 2,
  "maxAudience": 100,
  "debateType": "NORMAL"
}
```
**Response:** `{ "data": { "roomUUID": "...", "roomId": 42 } }`

#### `GET /api/debate-rooms`
전체 WAITING 방 목록  
**Query:** `?categoryId=2&page=0&size=20`

#### `GET /api/debate-rooms/{roomUUID}`
방 상세 조회

**Response:**
```json
{
  "data": {
    "roomUUID": "...",
    "title": "...",
    "sideA": "찬성",
    "sideB": "반대",
    "status": "WAITING",
    "currentParticipants": {
      "speakersA": 1, "speakersB": 0, "audiences": 12
    },
    "maxSpeaker": 2,
    "maxAudience": 100,
    "totalDurationSecs": 600
  }
}
```

#### `POST /api/debate-rooms/{roomUUID}/start`
**Auth:** Bearer 필수 (방 개설자만)  
**Condition:** WAITING 상태이고 Speaker 최소 1명 이상

#### `POST /api/debate-rooms/{roomUUID}/end`
**Auth:** Bearer 필수 (방 개설자만)  
**Condition:** STARTED 상태

#### `POST /api/debate-rooms/match`
**Request:** `{ "categoryId": 2 }`  
**Response:** WAITING 상태인 동일 카테고리 방 1개 (없으면 null)

---

### 6.4 결과 및 투표 API

#### `GET /api/debate-results/{roomUUID}`
**Condition:** ENDED 상태  
**Response:**
```json
{
  "data": {
    "aiAnalysis": "이번 토론에서 A측은 ...",
    "sideAVotes": 25,
    "sideBVotes": 15,
    "totalVotes": 40,
    "sideARate": 62.5,
    "sideBRate": 37.5
  }
}
```

#### `POST /api/debate-results/{roomUUID}/vote`
**Request:** `{ "side": "A" }`  
**Auth:** 선택 (비로그인 시 게스트 세션 기반)  
**Response:** 갱신된 투표 집계

#### `GET /api/debate/{roomUUID}/speeches`
턴별 발언 + AI 요약 목록  
**Response:**
```json
{
  "data": [
    {
      "turnIndex": 1,
      "side": "A",
      "speakerName": "홍길동",
      "transcript": "기본 소득제는...",
      "aiSummary": "발언자는 기본소득이 빈곤 해소에 효과적이라고 주장했다.",
      "spokenAt": "2026-05-05T10:00:00Z"
    }
  ]
}
```

---

### 6.5 카테고리 및 주제 API

#### `GET /api/categories`
#### `GET /api/debate-topics`
#### `POST /api/debate-topics` — **Auth:** Bearer + ADMIN role
#### `DELETE /api/debate-topics/{id}` — **Auth:** Bearer + ADMIN role

---

## 7. WebSocket 명세

### 7.1 연결

**STOMP 엔드포인트:** `/ws-debate` (SockJS 지원)

**CONNECT 헤더:**
```
CONNECT
Authorization: Bearer {accessToken}    ← Speaker 역할 시 필수
accept-version: 1.1
```

게스트(Audience)는 Authorization 헤더 없이 연결 가능. STOMP CONNECT 수신 시 서버가 토큰 존재 여부에 따라 인증 수준 결정.

### 7.2 Publish (클라이언트 → 서버)

| Destination | Payload | 인증 |
|-------------|---------|------|
| `/pub/debate/join` | `{ roomUUID, role, side }` | Speaker: JWT 필수 |
| `/pub/debate/leave` | `{ roomUUID }` | — |
| `/pub/chat/message` | `{ roomUUID, message }` | — |
| `/pub/speech/end` | `{ roomUUID, turnIndex }` | JWT 필수 |
| `/pub/ai/factcheck` | `{ roomUUID, turnIndex, claim }` | — |

### 7.3 Subscribe (서버 → 클라이언트)

| Topic | 설명 |
|-------|------|
| `/sub/debate-room/{roomUUID}` | 방 이벤트 전체 (JOIN, CHAT, PARTICIPANT_LIST 등) |
| `/topic/debate/{roomUUID}/timer` | 토론 타이머 틱 (1초 간격) |
| `/topic/speaker/{roomUUID}/timer` | 발언자 타이머 틱 (1초 간격) |
| `/topic/speaker/{roomUUID}` | 턴 전환 이벤트 |
| `/topic/ai/{roomUUID}` | AI 결과 (요약, 팩트체크, 종합 분석) |

### 7.4 메시지 타입 정의

```json
// JOIN_ACCEPTED — 입장 성공, 현재 상태 스냅샷 포함
{
  "type": "JOIN_ACCEPTED",
  "role": "SPEAKER",
  "side": "A",
  "snapshot": {
    "debateStatus": "STARTED",
    "currentSpeaker": { "userId": "42", "name": "홍길동", "side": "A" },
    "debateRemainingSeconds": 450,
    "speakerRemainingSeconds": 67,
    "participants": {
      "speakersA": [{ "userId": "42", "name": "홍길동" }],
      "speakersB": [{ "userId": "99", "name": "김철수" }],
      "audiences": [...]
    },
    "recentChats": [...]
  }
}

// JOIN_REJECTED
{
  "type": "JOIN_REJECTED",
  "reason": "ROOM_FULL" | "ROOM_STARTED" | "AUTH_REQUIRED" | "ROOM_ENDED"
}

// PARTICIPANT_LIST — 참가자 변경 시 전체 목록 브로드캐스트
{
  "type": "PARTICIPANT_LIST",
  "speakersA": [{ "userId": "42", "name": "홍길동" }],
  "speakersB": [{ "userId": "99", "name": "김철수" }],
  "audiences": [{ "guestId": "g-xyz", "name": "Guest123" }]
}

// CHAT
{
  "type": "CHAT",
  "senderId": "42",
  "senderName": "홍길동",
  "message": "좋은 주장이네요",
  "serverTimestamp": "2026-05-05T10:00:05.123Z"
}

// TURN_CHANGED
{
  "type": "TURN_CHANGED",
  "turnIndex": 3,
  "currentSpeaker": { "userId": "99", "name": "김철수", "side": "B" },
  "serverTimestamp": "2026-05-05T10:05:00.000Z"
}

// TIMER_TICK (1초 간격)
{
  "type": "TIMER_TICK",
  "debateRemainingSeconds": 450,
  "speakerRemainingSeconds": 67,
  "currentSpeakerName": "홍길동",
  "side": "A"
}

// DEBATE_STARTED
{
  "type": "DEBATE_STARTED",
  "totalDurationSecs": 600,
  "turnDurationSecs": 90,
  "firstSpeaker": { "userId": "42", "name": "홍길동", "side": "A" },
  "serverTimestamp": "2026-05-05T10:00:00.000Z"
}

// DEBATE_ENDED
{
  "type": "DEBATE_ENDED",
  "serverTimestamp": "2026-05-05T10:10:00.000Z"
}

// AI_SUMMARY
{
  "type": "AI_SUMMARY",
  "turnIndex": 2,
  "side": "A",
  "summary": "발언자는 기본소득이 빈곤 해소에 효과적이라고 주장했다.",
  "serverTimestamp": "2026-05-05T10:02:45.000Z"
}

// FACTCHECK_RESULT
{
  "type": "FACTCHECK_RESULT",
  "turnIndex": 2,
  "verdict": "true" | "false" | "unverifiable",
  "explanation": "...",
  "sources": [{ "title": "...", "url": "https://..." }]
}

// AI_FAILED
{
  "type": "AI_FAILED",
  "turnIndex": 2,
  "reason": "AI 처리에 실패했습니다."
}

// DEBATE_ANALYSIS — 종합 분석 완료 시
{
  "type": "DEBATE_ANALYSIS",
  "analysis": "이번 토론에서 A측은 경제적 근거를 중심으로 주장했으며...",
  "serverTimestamp": "2026-05-05T10:12:30.000Z"
}
```

---

## 8. 보안

### 8.1 JWT

| 항목 | 값 |
|------|----|
| 알고리즘 | HS256 |
| Access Token 유효기간 | 1시간 |
| Refresh Token 유효기간 | 14일 |
| Access Token 저장 | `Authorization: Bearer` 헤더 |
| Refresh Token 저장 | HttpOnly, Secure Cookie |
| 로그아웃 처리 | Redis에 jti 블랙리스트 등록 (TTL = Refresh Token 남은 유효기간) |

**Claims:**
```json
{
  "sub": "42",
  "username": "홍길동",
  "roles": ["USER"],
  "jti": "uuid-for-blacklist",
  "iat": 1714900000,
  "exp": 1714903600
}
```

### 8.2 엔드포인트 접근 제어

| 패턴 | 접근 수준 |
|------|----------|
| `GET /api/debate-rooms/**` | Public |
| `GET /api/categories` | Public |
| `GET /api/debate-topics` | Public |
| `GET /api/debate-results/**` | Public |
| `POST /api/debate-rooms` | Authenticated |
| `POST /api/debate-rooms/*/start` | Authenticated (Host만) |
| `POST /api/debate-rooms/*/end` | Authenticated (Host만) |
| `POST /api/debate-results/*/vote` | Public (게스트 허용) |
| `POST /api/debate-topics` | ADMIN |
| `DELETE /api/debate-topics/**` | ADMIN |
| `GET /api/auth/me` | Authenticated |
| `POST /api/auth/refresh` | Public (Refresh Cookie 기반) |
| `/actuator/**` | Internal (IP 제한) |

### 8.3 CORS

```yaml
# 환경별 명시적 허용 origin
cors:
  allowed-origins:
    - ${FRONTEND_URL}   # 환경변수로 주입, '*' 사용 금지
  allowed-methods: GET, POST, PUT, DELETE, OPTIONS, PATCH
  allow-credentials: true
  max-age: 3600
```

---

## 9. 에러 코드

| HTTP | Code | 설명 |
|------|------|------|
| 400 | INVALID_INPUT | 요청 값 오류, 필수 필드 누락 |
| 400 | DUPLICATE_VOTE | 이미 투표함 |
| 401 | UNAUTHORIZED | 미인증 또는 토큰 만료 |
| 401 | TOKEN_EXPIRED | Access Token 만료 |
| 403 | FORBIDDEN | 권한 없음 (Host 아닌데 방 종료 시도 등) |
| 404 | USER_NOT_FOUND | 유저 없음 |
| 404 | DEBATE_ROOM_NOT_FOUND | 방 없음 |
| 409 | ROOM_ALREADY_STARTED | 방이 이미 시작됨 |
| 409 | ROOM_ALREADY_ENDED | 방이 이미 종료됨 |
| 409 | ROOM_FULL | 정원 초과 |
| 422 | INSUFFICIENT_SPEAKERS | 발언자 없이 시작 시도 |
| 500 | AI_PROCESSING_FAILED | Claude API 실패 |
| 500 | STT_PROCESSING_FAILED | Google STT 실패 |
| 500 | INTERNAL_SERVER_ERROR | 기타 서버 오류 |

---

## 10. 비동기 처리 전략

### 10.1 Thread Pool 구성

| Pool | 용도 | Core | Max | Queue |
|------|------|------|-----|-------|
| `sttExecutor` | Google Cloud STT 호출 | 5 | 20 | 100 |
| `aiExecutor` | Claude API 호출 | 5 | 20 | 100 |
| `dbExecutor` | 비동기 DB 저장 | 3 | 10 | 50 |
| `timerExecutor` | @Scheduled 타이머 | 2 | 5 | — |
| `broadcastExecutor` | Redis Pub/Sub publish | 3 | 10 | 200 |

### 10.2 Virtual Thread (Java 21)

Spring Boot 3.2+ + Java 21에서 `spring.threads.virtual.enabled=true` 설정 시, 블로킹 I/O를 Virtual Thread에서 처리하여 별도 Thread Pool 없이도 높은 동시성 확보 가능. 검토 후 적용 결정.

---

## 11. 관찰 가능성

### 11.1 커스텀 메트릭 (Micrometer)

| 메트릭 이름 | Type | 설명 |
|------------|------|------|
| `realtalk.debate.active_rooms` | Gauge | 현재 STARTED 방 수 |
| `realtalk.debate.connections` | Gauge | 현재 WebSocket 연결 수 |
| `realtalk.debate.turn_advance_total` | Counter | 총 턴 전환 횟수 |
| `realtalk.debate.turn_advance_conflict_total` | Counter | 원자적 턴 전환 충돌 횟수 |
| `realtalk.ai.summary_duration_seconds` | Histogram | AI 요약 응답 시간 |
| `realtalk.stt.duration_seconds` | Histogram | STT 처리 시간 |
| `realtalk.chat.messages_total` | Counter | 총 채팅 메시지 수 |

### 11.2 로깅

- 포맷: JSON (logstash-logback-encoder)
- 필수 필드: `roomUUID`, `userId`, `eventType`, `timestamp`
- `roomUUID`를 correlation ID로 사용하여 방별 이벤트 추적

### 11.3 분산 추적 span 구조

```
[발언 종료 이벤트]
  └── [turn_advance] (동기, ~5ms)
  └── [broadcast TURN_CHANGED] (동기, ~10ms)
  └── [stt_pipeline] (비동기)
        └── [stt_recognize] (3~10s)
        └── [db_save_transcript]
        └── [ai_summarize] (2~8s)
        └── [db_save_summary]
        └── [broadcast AI_SUMMARY]
```

---

## 12. 인프라 및 배포

### 12.1 환경 변수

| 변수 | 설명 |
|------|------|
| `ANTHROPIC_API_KEY` | Claude API 키 |
| `REDIS_HOST`, `REDIS_PORT` | Redis 연결 |
| `MYSQL_HOST`, `MYSQL_PORT`, `MYSQL_DATABASE` | DB 연결 |
| `MYSQL_USERNAME`, `MYSQL_PASSWORD` | DB 자격증명 |
| `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET` | Google OAuth2 |
| `KAKAO_CLIENT_ID`, `KAKAO_CLIENT_SECRET` | Kakao OAuth2 |
| `GCP_CREDENTIALS_JSON` | Google Cloud STT 서비스 계정 (파일 경로 대신 JSON 내용으로 주입) |
| `JWT_SECRET` | JWT 서명 키 (최소 256bit) |
| `FRONTEND_URL` | 프론트엔드 Origin (CORS) |
| `SERVER_PORT` | 서버 포트 (기본 8080) |
| `SENTRY_DSN`, `SENTRY_ENVIRONMENT` | Sentry |

### 12.2 Docker Compose

```yaml
services:
  app:
    build: .
    ports: ["8080:8080"]
    depends_on: [mysql, redis]
    environment:
      SPRING_PROFILES_ACTIVE: prod

  mysql:
    image: mysql:8.0
    volumes: [mysql-data:/var/lib/mysql]

  redis:
    image: redis:7-alpine
    command: redis-server --appendonly yes
    volumes: [redis-data:/data]

  # monitoring
  prometheus:
  grafana:
  loki:
```

### 12.3 CI/CD (GitHub Actions)

```
push to develop/main
  → Gradle build + test
  → Docker image build
  → push to registry
  → deploy to server
```

---

## 13. 주요 설계 결정 (ADR 요약)

### ADR-1: Redis Pub/Sub을 메시지 브로커로 사용

**결정:** Spring in-memory broker 제거, Redis Pub/Sub으로 교체  
**이유:** 수평 확장 시 split-brain 방지. 기존 구현은 단일 서버에서만 동작했음.  
**트레이드오프:** Redis 의존성 증가. Redis 장애 시 메시지 전달 불가. Redis Sentinel 또는 Cluster로 HA 구성 필요.

### ADR-2: 타이머를 @Scheduled 서버 푸시로 구현

**결정:** 클라이언트 로컬 계산 대신 서버가 1초마다 `remainingSeconds`를 직접 push  
**이유:** 클라이언트 시계 drift 및 네트워크 지연으로 인한 불일치 제거.  
**트레이드오프:** 서버 부하 증가 (방 수 × 1초 간격 publish). 100개 방 기준 초당 100회 Redis publish — 허용 범위.

### ADR-3: 턴 전환을 Redis Lua Script로 원자화

**결정:** `startNextSpeaker`를 Lua script로 단일 원자 연산 처리  
**이유:** 기존 Read-Modify-Write 패턴은 race condition으로 턴 중복/누락 발생.  
**트레이드오프:** Lua script 디버깅 어려움. 테스트 시 Redis 실 인스턴스 필요.

### ADR-4: STT + AI를 비동기 파이프라인으로 분리

**결정:** 발언 종료 이벤트 핸들러에서 즉시 반환, STT/AI는 별도 스레드풀에서 처리  
**이유:** 기존 동기 처리는 핸들러 스레드 최대 30초 점유로 이후 메시지 적체.  
**트레이드오프:** AI 요약과 다음 턴 사이에 요약이 늦게 도착할 수 있음. 클라이언트가 "요약 처리 중" 상태를 표시해야 함.

### ADR-5: UUID를 DB PK 대신 컬럼으로 저장

**결정:** `debate_rooms.uuid` 컬럼 추가, Redis UUID↔PK 매핑 제거  
**이유:** Redis 의존 없이 DB에서 직접 UUID로 방 조회 가능. Redis 장애 시 방 조회 불가 문제 해결.  
**트레이드오프:** UUID 컬럼 인덱스 추가 필요. UUID 문자열 비교는 정수 PK 비교보다 느림 (허용 범위).

### ADR-6: @Scheduled 분산 실행 — ShedLock (Redis provider)

**결정:** `shedlock-provider-redis-spring` 사용, `lockAtMostFor = PT3S`  
**이유:** 서버 N대 배포 시 타이머가 N번 브로드캐스트되어 클라이언트에 중복 메시지 전달됨.  
**트레이드오프:** Redis 장애 시 스케줄러 실행 불가. Redis Sentinel 구성으로 완화.

### ADR-7: Refresh Token 저장 — DB 단일 저장

**결정:** `users.refresh_token` 컬럼이 단일 진실 공급원. Redis에 중복 저장하지 않음.  
**이유:** 기존 구현이 DB 컬럼과 Redis를 혼용하여 불일치 가능성이 있었음.  
**트레이드오프:** 갱신 시 DB write 발생. 로그아웃은 컬럼 null 처리로 충분.

### ADR-8: Speaker disconnect 시 자동 턴 전환

**결정:** 현재 발언자가 disconnect하면 즉시 다음 턴으로 전환.  
**이유:** 실시간 토론에서 발언자 부재 시 토론이 멈추는 것이 최악의 UX.  
**트레이드오프:** 일시적 네트워크 끊김도 턴 소실로 처리됨. 재연결 복원으로 완화.

### ADR-9: 재연결 시 기존 참가자 복원 — JWT sub 기준

**결정:** 재연결 시 JWT userId로 Redis 기존 참가자 탐색, 있으면 복원 + 스냅샷 전송.  
**이유:** 재연결을 새 입장으로 처리하면 정원 오버플로우와 유령 참가자 문제 발생.  
**트레이드오프:** 게스트(JWT 없음)는 복원 불가, 항상 새 입장으로 처리.

### ADR-10: 투표 허용 시점 — ENDED 상태에서만 가능

**결정:** `POST /api/debate-results/{roomUuid}/vote`는 방 상태가 ENDED일 때만 허용.  
**이유:** 토론 중 투표는 발언에 영향을 줄 수 있음. 결과 확정 후 투표가 공정함.  
**트레이드오프:** 토론 중 참여감 감소. 추후 PO 결정으로 변경 가능.

### ADR-6: debate_speeches 테이블 신설

**결정:** 발언 내용과 AI 요약을 Redis가 아닌 DB에 영구 저장  
**이유:** Redis TTL 만료 시 발언 기록 유실. 토론 종료 후 복기 기능을 위해 영구 저장 필요.  
**트레이드오프:** DB write 빈도 증가. 비동기 파이프라인 내에서 저장하므로 실시간 성능에 영향 없음.
