# RealTalk — Architecture Guide

**Version:** 1.0  
**Date:** 2026-05-05

> 이 문서는 재건축 팀 전체가 따를 코드 규칙을 정의한다.
> "왜"를 모르면 규칙을 어기기 쉽다 — 모든 규칙에는 이유가 있다.

---

## 1. 패키지 구조

```
com.likelion.realtalk/
├── domain/
│   ├── debate/
│   │   ├── api/              # REST Controller, STOMP Controller (별도 클래스)
│   │   ├── service/
│   │   ├── repository/
│   │   ├── entity/
│   │   ├── dto/
│   │   │   ├── request/      # Request DTO (Record)
│   │   │   └── response/     # Response DTO (Record)
│   │   └── type/             # Enum
│   ├── user/
│   ├── auth/
│   ├── oauth/
│   └── category/
├── global/
│   ├── config/
│   ├── security/
│   ├── exception/            # GlobalExceptionHandler, ErrorCode, CustomException
│   ├── async/                # AsyncConfig (Thread Pool 정의)
│   └── common/               # ApiResponse, BaseTimeEntity
└── infra/
    ├── claude/               # ClaudeAiClient
    ├── stt/                  # SpeechToTextService
    ├── redis/                # RedisPublisher, RedisSubscriber
    └── timer/                # TimerScheduler
```

**규칙:**
- 모든 도메인 컨트롤러는 `api/` 패키지에 위치 (`controller/` 사용 금지)
- REST 컨트롤러와 STOMP 컨트롤러는 **반드시 별도 클래스**로 분리
- Enum은 `type/` 패키지에 모은다

---

## 2. 레이어 규칙

### 레이어별 책임

| 레이어 | 책임 | 허용 의존 |
|--------|------|----------|
| **Controller / Handler** | HTTP/WebSocket 요청 수신, DTO 변환, Service 호출, 응답 반환 | Service |
| **Service** | 비즈니스 로직, 트랜잭션 경계 결정, 도메인 조합 | Repository, Infra |
| **Repository** | DB / Redis 접근 추상화 | EntityManager, RedisTemplate |
| **Infra** | 외부 API 클라이언트 (Claude, STT, Redis Pub/Sub) | 외부 라이브러리 |

### 레이어별 금지 사항

**Controller:**
```java
// ❌ 금지 — 비즈니스 로직을 Controller에 작성
@PostMapping("/debate-rooms/{uuid}/start")
public ResponseEntity<ApiResponse<Void>> start(@PathVariable String uuid) {
    if (debateRoom.getStatus() != WAITING) throw new CustomException(ROOM_ALREADY_STARTED);
    debateRoom.setStatus(STARTED); // Controller가 직접 엔티티 조작
    ...
}

// ✅ 올바른 패턴
@PostMapping("/debate-rooms/{uuid}/start")
public ResponseEntity<ApiResponse<Void>> start(@PathVariable String uuid, @AuthenticationPrincipal CustomUserDetails user) {
    debateService.startDebate(uuid, user.getUserId());
    return ApiResponse.ok();
}
```

**Service:**
```java
// ❌ 금지 — Service에서 RedisTemplate 직접 호출
@Service
public class SpeakerService {
    private final RedisTemplate<String, String> redisTemplate; // 직접 주입 금지

    public void advanceTurn(String roomUUID) {
        redisTemplate.opsForValue().set("room:" + roomUUID + ":speaker", nextSpeakerId); // 금지
    }
}

// ✅ 올바른 패턴 — Repository를 통해 접근
@Service
public class SpeakerService {
    private final DebateRedisRepository debateRedisRepository;

    public void advanceTurn(String roomUUID) {
        debateRedisRepository.setCurrentSpeaker(roomUUID, nextSpeakerId);
    }
}
```

**Service:**
```java
// ❌ 금지 — 서비스에 인메모리 상태 유지
@Service
public class ParticipantService {
    // 서버 재시작 또는 수평 확장 시 상태 소실
    private final Map<Long, Map<String, RoomUserInfo>> roomParticipants = new HashMap<>();
}

// ✅ 올바른 패턴 — 모든 상태는 Redis 또는 DB에 저장
@Service
public class ParticipantService {
    private final DebateRedisRepository debateRedisRepository;

    public void addParticipant(String roomUUID, ParticipantInfo info) {
        debateRedisRepository.addParticipant(roomUUID, info);
    }
}
```

---

## 3. DTO 규칙

### 형태 기준

| 대상 | 형태 | 이유 |
|------|------|------|
| Request DTO | **Java Record** | DTO는 생성 후 변경이 없어야 함. 언어 수준에서 불변성 강제 |
| Response DTO | **Java Record** | 동일 이유 |
| JPA Entity | **Lombok** (`@Getter`, `@NoArgsConstructor`, `@Builder`) | JPA는 기본 생성자와 가변 필드가 필요 |
| 선택 필드 10개 이상의 복잡한 객체 | **Lombok @Builder** | Record는 복잡한 부분 생성에 불편 |

### Request DTO 작성 규칙

```java
// ✅ 올바른 패턴
// 위치: domain/debate/dto/request/CreateRoomRequest.java
public record CreateRoomRequest(
    @NotBlank String title,
    @NotBlank String sideA,
    @NotBlank String sideB,
    @Positive int turnDurationSecs,
    @Positive int totalDurationSecs,
    @Min(2) @Max(10) int maxSpeaker,
    Long categoryId
) {}

// ❌ 금지 — 동일 DTO를 다른 모듈에서 중복 정의
// domain/debate/dto/request/CategoryDto.java  ← debate 모듈에 category DTO 금지
// domain/category/dto/request/CategoryDto.java ← category 모듈에 정의하고 참조
```

### Response DTO 작성 규칙

```java
// ✅ 올바른 패턴
// 위치: domain/debate/dto/response/DebateRoomResponse.java
public record DebateRoomResponse(
    String uuid,
    String title,
    String sideA,
    String sideB,
    String status,
    int turnDurationSecs,
    int totalDurationSecs
) {
    // 엔티티 → Response 변환은 정적 팩토리 메서드로
    public static DebateRoomResponse from(DebateRoom room) {
        return new DebateRoomResponse(
            room.getUuid(),
            room.getTitle(),
            room.getSideA(),
            room.getSideB(),
            room.getStatus().name(),
            room.getTurnDurationSecs(),
            room.getTotalDurationSecs()
        );
    }
}
```

### Entity 작성 규칙

```java
// ✅ 올바른 패턴
@Entity
@Table(name = "debate_rooms")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class DebateRoom extends BaseTimeEntity {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 36)
    private String uuid;

    // ❌ @Setter 금지 — 상태 변경은 의미 있는 메서드로
    // @Setter private DebateRoomStatus status;

    // ✅ 상태 변경은 도메인 메서드로
    public void start() {
        if (this.status != DebateRoomStatus.WAITING) {
            throw new CustomException(ErrorCode.ROOM_ALREADY_STARTED);
        }
        this.status = DebateRoomStatus.STARTED;
        this.startedAt = LocalDateTime.now();
    }

    public void end() {
        this.status = DebateRoomStatus.ENDED;
        this.endedAt = LocalDateTime.now();
    }
}
```

---

## 4. 공통 응답 형식

모든 REST API는 `ApiResponse<T>` 래퍼로 응답한다.

```java
// global/common/ApiResponse.java
public record ApiResponse<T>(
    boolean success,
    int status,
    String message,
    T data
) {
    public static <T> ResponseEntity<ApiResponse<T>> ok(T data) {
        return ResponseEntity.ok(new ApiResponse<>(true, 200, "OK", data));
    }

    public static ResponseEntity<ApiResponse<Void>> ok() {
        return ResponseEntity.ok(new ApiResponse<>(true, 200, "OK", null));
    }

    public static <T> ResponseEntity<ApiResponse<T>> created(T data) {
        return ResponseEntity.status(201).body(new ApiResponse<>(true, 201, "Created", data));
    }
}
```

**Controller 사용 예시:**
```java
@GetMapping("/debate-rooms/{uuid}")
public ResponseEntity<ApiResponse<DebateRoomResponse>> getRoom(@PathVariable String uuid) {
    return ApiResponse.ok(debateService.getRoom(uuid));
}

@PostMapping("/debate-rooms")
public ResponseEntity<ApiResponse<CreateRoomResponse>> createRoom(
    @RequestBody @Valid CreateRoomRequest request,
    @AuthenticationPrincipal CustomUserDetails user
) {
    return ApiResponse.created(debateService.createRoom(request, user.getUserId()));
}
```

**오류 응답:**
```json
{
  "success": false,
  "status": 409,
  "message": "이미 시작된 토론 방입니다.",
  "data": null
}
```

---

## 5. 예외 처리 규칙

### 예외 계층 구조

```
RuntimeException
  └── CustomException (code: ErrorCode 포함)
```

```java
// global/exception/CustomException.java
@Getter
public class CustomException extends RuntimeException {
    private final ErrorCode errorCode;

    public CustomException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }
}
```

### ErrorCode 작성 규칙

```java
// global/exception/ErrorCode.java
@Getter
@RequiredArgsConstructor
public enum ErrorCode {
    // User
    USER_NOT_FOUND(404, "사용자를 찾을 수 없습니다."),

    // Debate Room
    DEBATE_ROOM_NOT_FOUND(404, "토론 방을 찾을 수 없습니다."),
    ROOM_ALREADY_STARTED(409, "이미 시작된 토론 방입니다."),
    ROOM_ALREADY_ENDED(409, "이미 종료된 토론 방입니다."),
    ROOM_FULL(409, "정원이 초과되었습니다."),
    INSUFFICIENT_SPEAKERS(422, "발언자가 없어 토론을 시작할 수 없습니다."),

    // Auth
    UNAUTHORIZED(401, "인증이 필요합니다."),
    TOKEN_EXPIRED(401, "토큰이 만료되었습니다."),
    FORBIDDEN(403, "접근 권한이 없습니다."),

    // AI / STT
    AI_PROCESSING_FAILED(500, "AI 처리에 실패했습니다."),
    STT_PROCESSING_FAILED(500, "음성 변환에 실패했습니다."),

    INTERNAL_SERVER_ERROR(500, "서버 오류가 발생했습니다.");

    private final int status;
    private final String message;
}
```

```java
// ❌ 금지 — IllegalArgumentException으로 직접 메시지 던지기
throw new IllegalArgumentException("이미 존재하는 토론 주제입니다: " + title);

// ❌ 금지 — 익명 클래스 사용
throw new CustomException(ErrorCode.NOT_FOUND) {};

// ✅ 올바른 패턴
throw new CustomException(ErrorCode.DEBATE_ROOM_NOT_FOUND);
```

### GlobalExceptionHandler

```java
// global/exception/GlobalExceptionHandler.java
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(CustomException.class)
    public ResponseEntity<ApiResponse<Void>> handleCustomException(CustomException e) {
        ErrorCode code = e.getErrorCode();
        return ResponseEntity.status(code.getStatus())
            .body(new ApiResponse<>(false, code.getStatus(), code.getMessage(), null));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
            .map(FieldError::getDefaultMessage)
            .findFirst().orElse("입력값이 올바르지 않습니다.");
        return ResponseEntity.badRequest()
            .body(new ApiResponse<>(false, 400, message, null));
    }

    // ❌ 금지 — 예외를 String으로 반환
    // @ExceptionHandler(Exception.class)
    // public String handleException(Exception e) { return e.getMessage(); }
}
```

---

## 6. Redis 접근 규칙

### 접근 경로

```
Service → DebateRedisRepository → RedisTemplate
```

- Service는 `RedisTemplate`을 직접 주입받지 않는다
- 모든 Redis 명령은 `Repository` 레이어에서만 실행

```java
// infra/redis/DebateRedisRepository.java
@Repository
@RequiredArgsConstructor
public class DebateRedisRepository {

    private final RedisTemplate<String, String> redisTemplate;

    // Key 생성은 Repository 내부에서만
    private String timerKey(String roomUUID) {
        return "room:" + roomUUID + ":timer";
    }

    public void setDebateTimer(String roomUUID, long epochMilli) {
        redisTemplate.opsForValue().set(timerKey(roomUUID), String.valueOf(epochMilli));
    }

    public Optional<Long> getDebateTimerEndAt(String roomUUID) {
        String value = redisTemplate.opsForValue().get(timerKey(roomUUID));
        return Optional.ofNullable(value).map(Long::parseLong);
    }
}
```

### Redis Key 네이밍

| 패턴 | 용도 |
|------|------|
| `room:{uuid}:timer` | 토론 타이머 만료 epoch ms |
| `room:{uuid}:speaker:current` | 현재 발언자 userId |
| `room:{uuid}:speaker:timer` | 발언자 타이머 만료 epoch ms |
| `room:{uuid}:speaker:turn` | 현재 턴 인덱스 |
| `room:{uuid}:speaker:spoken` | 이번 라운드 발언 완료 Set |
| `room:{uuid}:participants` | 참가자 Hash (sessionId → JSON) |
| `room:{uuid}:chats` | 최근 채팅 List (capped 100) |

모든 key 상수는 `DebateRedisRepository` 내 private 메서드로 관리한다. 서비스 레이어에서 key 문자열을 조합하는 것은 금지한다.

---

## 7. WebSocket / STOMP 규칙

### REST 컨트롤러와 STOMP 컨트롤러 분리

```java
// ❌ 금지 — 한 클래스에 REST + STOMP 혼재
@RestController  // REST
public class DebateController {
    @GetMapping("/api/debate-rooms")   // REST
    public ResponseEntity<?> list() { ... }

    @MessageMapping("/debate/join")    // STOMP — 금지
    public void join(JoinRequest req) { ... }
}

// ✅ 올바른 패턴 — 완전히 분리
// domain/debate/api/DebateRoomController.java
@RestController
@RequestMapping("/api/debate-rooms")
public class DebateRoomController { ... }

// domain/debate/api/DebateStompController.java
@Controller
public class DebateStompController {
    @MessageMapping("/debate/join")
    public void join(JoinRequest req, SimpMessageHeaderAccessor headerAccessor) { ... }
}
```

### 브로드캐스트 책임

- **Service**에서 `RedisPublisher`를 통해 메시지를 발행한다
- **Controller**에서 직접 `SimpMessagingTemplate`을 호출하지 않는다

```java
// ❌ 금지 — Controller에서 직접 broadcast
@Controller
public class DebateStompController {
    private final SimpMessageSendingOperations messagingTemplate;

    @MessageMapping("/debate/join")
    public void join(...) {
        messagingTemplate.convertAndSend("/sub/debate-room/" + roomUUID, response); // 금지
    }
}

// ✅ 올바른 패턴 — Service → RedisPublisher
@Service
public class DebateService {
    private final RedisPublisher redisPublisher;

    public void joinDebate(JoinRequest request, String sessionId) {
        // ... 비즈니스 로직 ...
        redisPublisher.publish(roomUUID, JoinAcceptedEvent.of(snapshot));
    }
}
```

### STOMP 메시지 응답 형식

WebSocket 메시지도 일관된 형식을 사용한다.

```java
// global/common/WsMessage.java
public record WsMessage<T>(
    String type,      // 이벤트 타입 (JOIN_ACCEPTED, CHAT, TURN_CHANGED 등)
    T payload,
    String serverTimestamp   // ISO-8601, 서버 시각
) {
    public static <T> WsMessage<T> of(String type, T payload) {
        return new WsMessage<>(type, payload, Instant.now().toString());
    }
}
```

```java
// ❌ 금지 — 타입 없는 raw Map 전송
messagingTemplate.convertAndSend("/sub/debate-room/" + uuid, new HashMap<>());

// ✅ 올바른 패턴
redisPublisher.publish(uuid, WsMessage.of("JOIN_ACCEPTED", snapshot));
```

---

## 8. 트랜잭션 규칙

| 규칙 | 이유 |
|------|------|
| `@Transactional`은 Service 레이어에만 | Controller, Repository에 트랜잭션 금지 |
| 조회 전용 Service는 클래스 레벨에 `@Transactional(readOnly = true)` | DB 읽기 성능 최적화 |
| 쓰기가 필요한 개별 메서드에만 `@Transactional` 추가 | readOnly 클래스 하에서 쓰기 재정의 |
| Redis 연산은 `@Transactional` 불필요 | Lua script로 원자성 보장 |

```java
// ✅ 올바른 패턴
@Service
@Transactional(readOnly = true)   // 클래스 전체 기본값
public class DebateRoomService {

    public DebateRoomResponse getRoom(String uuid) { ... }    // readOnly 적용

    @Transactional    // 쓰기 메서드만 재정의
    public CreateRoomResponse createRoom(CreateRoomRequest request, Long creatorId) { ... }

    @Transactional
    public void startDebate(String uuid, Long userId) { ... }
}
```

---

## 9. 네이밍 컨벤션

### 클래스 네이밍

| 종류 | 패턴 | 예시 |
|------|------|------|
| REST Controller | `{도메인}Controller` | `DebateRoomController` |
| STOMP Controller | `{도메인}StompController` | `DebateStompController` |
| Service | `{도메인}Service` | `DebateRoomService`, `SpeakerService` |
| JPA Repository | `{엔티티}Repository` | `DebateRoomRepository` |
| Redis Repository | `{도메인}RedisRepository` | `DebateRedisRepository` |
| Request DTO | `{동작}{도메인}Request` | `CreateRoomRequest`, `JoinDebateRequest` |
| Response DTO | `{도메인}Response` | `DebateRoomResponse`, `SpeechResponse` |
| Event/Message | `{이벤트}Event` | `TurnChangedEvent`, `JoinAcceptedEvent` |
| Enum | 단수형 명사 | `DebateStatus`, `ParticipantRole`, `Side` |

### 메서드 네이밍

| 동사 | 의미 | 예시 |
|------|------|------|
| `get*` | 조회 (없으면 예외) | `getRoom(uuid)` |
| `find*` | 조회 (없으면 Optional) | `findRoom(uuid)` |
| `create*` | 생성 | `createRoom(request)` |
| `start*` | 시작 | `startDebate(uuid)` |
| `end*` | 종료 | `endDebate(uuid)` |
| `join*` | 입장 | `joinDebate(request)` |
| `leave*` | 퇴장 | `leaveDebate(uuid, userId)` |
| `advance*` | 전진/전환 | `advanceTurn(uuid)` |
| `publish*` | 메시지 발행 | `publishTurnChanged(uuid, event)` |
| `save*` | 저장 | `saveSpeech(speech)` |

```java
// ❌ 금지 — 의미 불명확한 접두사
void pubSpeakerExpireTimer(...)   // pub* 접두사 금지
void clearSpeakerCaches(...)      // clear* / delete* 혼용 금지
String nz(...)                    // 축약어 금지

// ✅ 올바른 패턴
void publishSpeakerTimer(...)
void deleteSpeakerCache(...)
String nullToEmpty(...)
```

### 변수 네이밍

```java
// ❌ 금지 — 일관성 없는 ID 변수명
Long pk;           // 'pk' 축약어 금지
String roomUuid;   // 'uuid' 소문자 혼용 금지
String roomUUID;   // 위와 불일치

// ✅ 올바른 패턴 — 일관된 네이밍
Long roomId;       // DB Primary Key
String roomUuid;   // 외부 노출용 UUID (소문자 camelCase)
Long userId;
String sessionId;
```

---

## 10. 상태 관리 규칙

### 서비스 인스턴스 상태 금지

서비스 클래스의 인스턴스 필드에 상태를 저장하지 않는다. 서버가 여러 인스턴스로 확장되거나 재시작되면 소실된다.

```java
// ❌ 금지 — 서비스에 인메모리 상태
@Service
public class ParticipantService {
    private final Map<String, RoomUserInfo> participants = new HashMap<>(); // 금지
    private final List<String> activeRooms = new ArrayList<>();            // 금지
}

// ✅ 올바른 패턴 — 모든 상태는 Redis 또는 DB
@Service
public class ParticipantService {
    private final DebateRedisRepository debateRedisRepository;  // Redis에서 읽고 씀
    private final DebateParticipantRepository participantRepository; // DB 영구 저장
}
```

### 타이머 상태는 서버 주도

클라이언트가 타이머를 자체 계산하는 패턴을 서버 코드에서 유도하지 않는다.

```java
// ❌ 금지 — 만료 절대 시각을 한 번만 전송
messagingTemplate.convertAndSend("/topic/debate/" + uuid + "/expire",
    Map.of("expireAt", "2026-05-05 14:30:45.123"));  // 클라이언트가 계산 → drift 발생

// ✅ 올바른 패턴 — 서버가 1초 간격으로 남은 시간 push
// (TimerScheduler @Scheduled 에서 처리)
redisPublisher.publish(uuid, WsMessage.of("TIMER_TICK",
    new TimerTickPayload(debateRemainingSeconds, speakerRemainingSeconds)));
```

---

## 11. 비동기 처리 규칙

WebSocket 핸들러 스레드를 블로킹하는 외부 API 호출은 금지한다.

```java
// ❌ 금지 — 핸들러 스레드에서 동기 블로킹 호출
@MessageMapping("/speech/end")
public void handleSpeechEnd(SpeechEndRequest request) {
    String transcript = sttService.recognize(audio);    // 블로킹 (3~10초)
    String summary = claudeClient.summarize(transcript); // 블로킹 (2~10초)
    // 핸들러 스레드가 최대 30초 점유됨
}

// ✅ 올바른 패턴 — 즉시 반환 후 비동기 파이프라인
@MessageMapping("/speech/end")
public void handleSpeechEnd(SpeechEndRequest request) {
    speakerService.advanceTurn(request.roomUuid());   // 즉시, 원자적
    speechPipeline.processAsync(request);             // 비동기 제출 후 즉시 반환
}
```

비동기 처리 파이프라인은 `infra/` 레이어에 위치하며, `CompletableFuture`와 전용 스레드풀을 사용한다. 스레드풀 설정은 `global/async/AsyncConfig.java`에서 중앙 관리한다.
