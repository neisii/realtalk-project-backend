현재 디렉토리는 실시간 양자 토론 플랫폼 RealTalk의 Spring Boot 백엔드 레포다.
기존 src/ 코드가 있지만 여러 인원이 개발해 일관성이 없고 실시간 처리에 구조적 결함이 있다.
기존 코드를 수정하는 것이 아니라, src/main/java/ 를 완전히 교체하는 방식으로 재건축한다.

---

## 선행 작업 (코드 작성 전 반드시 먼저 실행)

기존 소스를 제거하고 새 코드를 위한 공간을 만든다.

1. 현재 src/main/java/ 하위 전체를 삭제한다
2. src/test/ 하위 전체를 삭제한다
3. 기존 코드가 필요하면 `git show HEAD:경로` 또는 `git log`로 조회한다
   — 파일 시스템에서 직접 읽으려 하지 않는다. 이미 삭제했기 때문이다

---

## 시작 전 필수 독해 순서

선행 작업 완료 후, 아래 파일을 순서대로 전부 읽어라. 읽지 않고 코드를 작성하지 않는다.

1. CLAUDE.md              — 핵심 결정 사항, 금지 패턴, 문서 지도
2. PRD.md                 — 요구사항, 기존 결함 분석, 동작 명세
3. SPEC.md                — 기술 아키텍처, 실시간 설계, ADR
4. ARCHITECTURE.md        — 코드 규칙 (레이어, DTO, 예외, 네이밍)
5. openapi.yaml           — REST API 계약 전체
6. db/migration/V1__init.sql — DB 스키마 전체

기존 코드가 궁금한 지점이 생기면 그때 git으로 조회한다.
문서와 git history가 유일한 참조 소스다.

---

## 절대 원칙 (위반 시 재건축 의미 없음)

- `enableSimpleBroker()` 사용 금지 → `enableStompBrokerRelay()` 사용
- STT·AI 호출을 WebSocket 핸들러 스레드에서 동기 호출 금지 → 비동기 파이프라인
- Service 클래스 인스턴스 필드에 상태(Map, List) 유지 금지 → Redis 또는 DB
- `@Setter`를 JPA 엔티티에 추가 금지 → 도메인 메서드로 상태 변경
- `ddl-auto: update` 금지 → Flyway + `validate`
- `RedisTemplate`을 Service에 직접 주입 금지 → Repository를 통해서만
- `@Scheduled`에 ShedLock 없이 사용 금지 → 다중 인스턴스 중복 실행 발생

---

## 강조 요소 (설계 결정 시 최우선 기준)

1. WebSocket 실시간성 — 이벤트가 모든 클라이언트에 즉시, 신뢰성 있게 전달
2. 실시간 동기화 — 같은 방의 모든 참가자가 동일한 상태를 동시에 확인
3. 병목 해소 — STT·AI·Redis·DB 처리가 실시간 이벤트 전달을 블로킹하지 않음

---

## 구현 순서

문서를 읽은 뒤 아래 순서로 구현한다. 이전 단계가 완성되지 않으면 다음 단계로 넘어가지 않는다.

1. 프로젝트 기반
   - build.gradle (의존성 전체)
   - application.yml (Flyway, Redis, OAuth2, JWT 설정)
   - global/config/ 전체 (Security, WebSocket, Redis, Async, Swagger)
   - global/exception/ (ErrorCode, CustomException, GlobalExceptionHandler)
   - global/common/ (ApiResponse, BaseTimeEntity)

2. 도메인 레이어 — 엔티티 + 리포지토리
   - V1__init.sql 기준으로 JPA 엔티티 작성 (ARCHITECTURE.md 엔티티 규칙 준수)
   - JPA Repository + DebateRedisRepository

3. 인증
   - OAuth2 (Google, Kakao) Success/Failure Handler + JWT 발급
   - JWT Filter (블랙리스트 조회 포함)
   - STOMP Channel Interceptor (JWT 검증)

4. 핵심 실시간 인프라
   - RedisPublisher / RedisSubscriber (Pub/Sub)
   - TimerScheduler (@Scheduled + ShedLock)
   - 원자적 턴 전환 Lua script + SpeakerService.advanceTurn()

5. 비동기 파이프라인
   - AsyncConfig (스레드풀 정의)
   - SpeechPipeline (STT → AI summary CompletableFuture 체인)

6. 도메인 서비스 + REST API
   - openapi.yaml 기준으로 Controller → Service 구현
   - STOMP Controller (join, leave, chat, speech/end)

7. 검증
   - k6/tests/ 시나리오 실행 기준으로 동작 확인
   - 특히 local.speaker-turn-integrity.js, local.timer-sync.js

---

## 작업 방식

- 각 단계를 시작하기 전에 구현 계획을 먼저 말하고 진행한다
- 설계 결정이 필요한 지점에서는 작성을 멈추고 질문한다
- CLAUDE.md의 확정된 결정(ADR-1~ADR-10)은 다시 논의하지 않는다
- 파일 하나를 완성한 뒤 다음 파일로 넘어간다. 여러 파일을 동시에 작성하다 멈추지 않는다
- **각 단계 시작 직후** PROGRESS.md를 생성/업데이트한다 (완료 후가 아니라 시작 직후)
- 각 단계 완료 시 git commit한다

### PROGRESS.md 형식

```
## 현재 상태
작업 중: 3단계 - 인증 / JwtAuthenticationFilter.java 작성 중

## 완료된 단계
- [x] 1단계: 프로젝트 기반 (build.gradle, application.yml, global/)
- [x] 2단계: 도메인 레이어 (엔티티, JPA Repository, DebateRedisRepository)
- [ ] 3단계: 인증

## 미완성 파일 (중단 시)
- src/main/java/.../JwtAuthenticationFilter.java — 블랙리스트 조회 로직까지 작성, 필터 등록 미완
```

---

## 세션 재개

Claude Code CLI는 컨텍스트 한도 도달 시 자동으로 대화를 압축하고 세션을 유지한다.
세션이 완전히 종료된 경우에만 아래 재개 방법을 사용한다.

**재개 프롬프트 (새 세션에 붙여넣기):**

```
RealTalk 백엔드 재건축 작업을 이어서 진행한다.
선행 작업(src/ 삭제)은 이미 완료됐다.

먼저 아래 순서로 현재 상태를 파악한다:
1. PROGRESS.md 읽기 — 어디서 중단됐는지 확인
2. git log --oneline -10 실행 — 완료된 커밋 확인
3. git status 실행 — 디스크에 미완성 파일 있는지 확인
4. CLAUDE.md 읽기 — 확정 결정 및 금지 패턴 재확인

파악 완료 후, 중단된 지점부터 이어서 구현한다.
새로 시작하거나 완료된 단계를 다시 작성하지 않는다.
```

---

## 지금 바로 시작

선행 작업(src/ 삭제)을 먼저 실행하고, 위 파일 6개를 읽어라.
읽은 내용을 바탕으로 1단계(프로젝트 기반) 구현 계획을 제시한 뒤
내 승인을 받고 코드 작성을 시작한다.
