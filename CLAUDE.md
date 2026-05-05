# RealTalk Backend — Claude Session Guide

이 파일은 새 Claude 세션이 프로젝트를 이어받을 때 가장 먼저 읽는 문서다.
임의로 설계 결정을 내리기 전에 이 파일과 아래 문서 목록을 반드시 읽을 것.

---

## 프로젝트 개요

**RealTalk(리얼톡)** — 실시간 양자 토론 플랫폼. Spring Boot 3.x 백엔드.
참가자가 A/B 입장으로 나뉘어 턴제 토론을 하고, AI(Claude)가 발언을 요약·분석한다.

**재건축 이유:** 기존 코드는 다수 인원이 개발해 일관성이 없고, 실시간 동작에 구조적 결함이 있음.
기존 코드를 수정하지 않고 처음부터 새로 작성한다.

---

## 핵심 강조 요소 (모든 설계 결정의 최우선 기준)

1. **WebSocket 실시간성** — 이벤트가 연결된 모든 클라이언트에 즉시, 신뢰성 있게 전달될 것
2. **실시간 동기화** — 같은 방의 모든 참가자가 동일한 상태(타이머, 발언자)를 동시에 볼 것
3. **병목 해소** — STT·AI·DB·Redis 처리가 WebSocket 이벤트 전달을 블로킹하지 않을 것

---

## 문서 읽기 순서

| 순서 | 파일 | 목적 |
|------|------|------|
| 1 | `CLAUDE.md` (이 파일) | 프로젝트 맥락 + 확정 결정 |
| 2 | `PRD.md` | 요구사항, 기존 결함 분석, 동작 명세 |
| 3 | `SPEC.md` | 기술 아키텍처, 실시간 설계, API 명세 |
| 4 | `ARCHITECTURE.md` | 코드 규칙 (레이어, DTO, 예외, 네이밍) |
| 5 | `openapi.yaml` | REST API 계약 (엔드포인트, 스키마) |
| 6 | `db/migration/V1__init.sql` | DB 스키마 전체 |
| 7 | `k6/tests/` | 부하 테스트 전략 (완성 기준) |

---

## 확정된 아키텍처 결정

아래 결정들은 이미 내려진 것이다. 새 세션이 다시 선택하지 않는다.

### 1. @Scheduled 타이머 다중 인스턴스 중복 실행 방지

**결정: ShedLock 사용**

서버가 N대일 때 타이머 스케줄러가 N번 실행되면 클라이언트가 중복 메시지를 받는다.
ShedLock으로 분산 락을 걸어 한 인스턴스만 실행한다.

```gradle
implementation 'net.javacrumbs.shedlock:shedlock-spring:6.0.0'
implementation 'net.javacrumbs.shedlock:shedlock-provider-redis-spring:6.0.0'
```

```java
@Scheduled(fixedDelay = 1000)
@SchedulerLock(name = "timerBroadcast", lockAtMostFor = "PT3S", lockAtLeastFor = "PT1S")
public void broadcastTimers() { ... }
```

### 2. Refresh Token 저장 위치

**결정: DB 단일 저장 (`users.refresh_token` 컬럼)**

Redis에 중복 저장하지 않는다. `users.refresh_token`이 단일 진실 공급원.
로그아웃 시 해당 컬럼을 null로 설정한다.
JWT jti 블랙리스트는 Redis에 별도 저장한다 (`session:{jti}`, TTL = 남은 유효기간).

### 3. Speaker disconnect 시 턴 처리

**결정: 자동으로 다음 턴 전환**

현재 발언 중인 Speaker가 연결을 끊으면 즉시 다음 턴으로 전환한다.
대기하거나 해당 측 턴을 재시도하지 않는다.
구현: `SessionDisconnectEvent` 수신 → 해당 참가자가 현재 발언자이면 `advanceTurn()` 호출.

### 4. 클라이언트 재연결 시 참가자 상태 복원

**결정: JWT sub(userId)로 Redis 기존 참가자 정보 조회 후 복원**

재연결 시 새 WebSocket 세션으로 STOMP CONNECT → JWT에서 userId 추출 →
Redis `room:{uuid}:participants`에서 해당 userId 탐색 → 있으면 기존 참가자로 복원,
JOIN_ACCEPTED + 현재 상태 스냅샷 응답. 없으면 일반 신규 입장 처리.

### 5. 투표 허용 시점

**결정: ENDED 상태에서만 가능**

토론 진행 중 투표는 허용하지 않는다. `POST /api/debate-results/{roomUuid}/vote`는
방 상태가 ENDED일 때만 200을 반환하고, 아니면 409를 반환한다.

---

## 구현 시 높은 실수 위험 영역

아래 세 영역은 잘못 구현하면 프로젝트의 핵심 가치가 무너진다.
SPEC.md 섹션 5를 읽고 정확히 구현할 것.

| 영역 | 위험 | SPEC.md 참조 |
|------|------|------------|
| 원자적 턴 전환 (Redis Lua script) | 잘못 짜면 race condition 잔존 | 섹션 5.3 |
| Redis Pub/Sub STOMP 브로커 릴레이 | 잘못 설정하면 멀티 인스턴스에서 메시지 유실 | 섹션 5.1 |
| STOMP CONNECT JWT 인터셉터 | 미구현 시 인증 우회 가능 | 섹션 8 |

---

## 기존 구현에서 반복하면 안 되는 패턴

| 패턴 | 이유 |
|------|------|
| `enableSimpleBroker()` 사용 | 멀티 인스턴스 불가. `enableStompBrokerRelay()` 사용 |
| STT/AI를 WebSocket 핸들러 스레드에서 동기 호출 | 핸들러 스레드 최대 30초 점유. 반드시 비동기 |
| 서비스 클래스에 `Map<>` 인스턴스 필드로 상태 유지 | 서버 재시작·스케일아웃 시 소실 |
| `@Setter`를 JPA 엔티티에 추가 | 불변성 파괴. 도메인 메서드로 상태 변경 |
| `ddl-auto: update` 사용 | Flyway가 스키마 관리. `validate`만 허용 |
| Redis key 문자열을 Service에서 직접 조합 | Repository 레이어에서만 관리 |
| `RedisTemplate`을 Service에 직접 주입 | Repository를 통해서만 접근 |
| `@Scheduled` 중복 실행 방치 | ShedLock으로 단일 실행 보장 (결정 #1 참조) |

---

## 기술 스택 요약

| 항목 | 선택 |
|------|------|
| Language | Java 21 |
| Framework | Spring Boot 3.x |
| DB | MySQL 8.0 + Flyway |
| Cache/Broker | Redis 7 (Pub/Sub + 상태 저장) |
| 실시간 | WebSocket + STOMP (SockJS 지원) |
| AI | Claude 3.5 Haiku (Spring AI) |
| STT | Google Cloud Speech-to-Text |
| Auth | OAuth2 (Google, Kakao) + JWT HS256 |
| 빌드 | Gradle |

---

## 환경 변수 목록

```
ANTHROPIC_API_KEY
REDIS_HOST, REDIS_PORT
MYSQL_HOST, MYSQL_PORT, MYSQL_DATABASE, MYSQL_USERNAME, MYSQL_PASSWORD
GOOGLE_CLIENT_ID, GOOGLE_CLIENT_SECRET
KAKAO_CLIENT_ID, KAKAO_CLIENT_SECRET
GCP_CREDENTIALS_JSON
JWT_SECRET
FRONTEND_URL
SERVER_PORT
SENTRY_DSN, SENTRY_ENVIRONMENT
```
