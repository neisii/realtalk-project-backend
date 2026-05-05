# RealTalk — Product Requirements Document

**Version:** 2.0  
**Date:** 2026-05-05  
**Status:** Draft — 재건축 기준 문서

> 이 문서는 기존 구현의 결함 분석을 포함한 완전한 재건축 기준 PRD다.
> "현재 동작하는 것"이 아닌 "올바르게 동작해야 하는 것"을 정의한다.

---

## 1. Product Overview

### 1.1 서비스 정의

RealTalk(리얼톡)은 실시간 양자 토론 플랫폼이다.

- 참가자는 A/B 두 입장 중 하나를 택해 발언자(Speaker)로 참여하거나, 청중(Audience)으로 참관한다.
- 발언은 순서제(턴제)로 진행되며 마이크를 통한 음성 발언을 지원한다.
- AI(Claude)가 각 발언을 실시간 요약하고 사실 여부를 검증하며, 토론 종료 후 종합 분석을 제공한다.
- 청중은 토론 결과에 선호 입장을 투표한다.

### 1.2 핵심 강조 요소

이 프로젝트가 기술적으로 해결해야 할 핵심은 다음 세 가지다.
모든 기능 요구사항과 시스템 설계 결정은 이 세 가지를 기준으로 평가한다.

| # | 요소 | 의미 |
|---|------|------|
| 1 | **WebSocket 실시간성** | 발언·채팅·타이머 이벤트가 연결된 모든 클라이언트에 즉시, 신뢰성 있게 전달될 것 |
| 2 | **실시간 동기화** | 같은 방의 모든 참가자가 동일한 상태(타이머, 발언자, 참가자 목록)를 동시에 볼 것 |
| 3 | **병목 해소** | STT·AI·DB·Redis 처리가 실시간 이벤트 전달을 막지 않을 것 |

### 1.3 Target Users

| 유형 | 설명 | 인증 |
|------|------|------|
| **발언자 (Speaker)** | 특정 입장(A 또는 B)으로 발언에 참여 | 필수 (OAuth2) |
| **청중 (Audience)** | 토론 관람, 투표 참여 | 선택 (게스트 허용) |
| **방 개설자 (Host)** | 토론 방 생성 및 시작 권한, Speaker 겸임 가능 | 필수 |
| **어드민 (Admin)** | 주제 관리, 카테고리 관리 | 필수 (ADMIN role) |

---

## 2. Goals & Non-Goals

### 2.1 Goals (재건축 v1 범위)

- [ ] 토론 방 생명 주기 전체 관리 (생성 → 대기 → 진행 → 종료 → 결과)
- [ ] WebSocket(STOMP) 기반 실시간 채팅 및 이벤트 브로드캐스트
- [ ] 서버 주도(server-authoritative) 타이머 동기화
- [ ] 원자적(atomic) 발언 턴 전환 (race condition 없음)
- [ ] 비동기 STT(Google Cloud) 처리 — 핸들러 스레드 비블로킹
- [ ] 비동기 AI(Claude) 처리 — 발언 요약, 팩트체크, 종합 분석
- [ ] Google / Kakao OAuth2 로그인 + JWT 인증
- [ ] 청중 선호도 투표 및 결과 집계
- [ ] 카테고리 기반 방 탐색 및 매칭
- [ ] 수평 확장 가능한 메시지 브로커 구조 (Redis Pub/Sub)
- [ ] 서버 인스턴스 무관한 WebSocket 메시지 전달

### 2.2 Non-Goals (v1 제외)

- WebRTC 영상 스트리밍 (시그널링 인프라만 준비)
- 방 녹화 · VOD 다시보기
- 3인 이상 다자 토론
- 팔로우 / 피드 / 소셜 기능
- 유료 결제, 구독 플랜
- 모바일 네이티브 앱

---

## 3. User Stories

### 3.1 인증

| ID | As a… | I want to… | So that… | 우선순위 |
|----|--------|-----------|----------|----------|
| AUTH-1 | 신규 사용자 | Google 또는 Kakao로 소셜 로그인 | 별도 회원가입 없이 이용 | P0 |
| AUTH-2 | 로그인 사용자 | Access Token 자동 갱신 | 끊김 없이 사용 | P0 |
| AUTH-3 | 로그인 사용자 | 안전하게 로그아웃 | 세션을 종료 | P1 |
| AUTH-4 | 게스트 | 로그인 없이 청중으로 참여 | 진입 장벽 없이 토론 관람 | P1 |

### 3.2 토론 방 관리

| ID | As a… | I want to… | So that… | 우선순위 |
|----|--------|-----------|----------|----------|
| ROOM-1 | 로그인 사용자 | 주제·양 입장 이름·시간·카테고리·정원을 설정해 방 생성 | 원하는 조건의 토론 진행 | P0 |
| ROOM-2 | 사용자 | 전체 대기 중 방 목록 조회 | 참여할 방 탐색 | P0 |
| ROOM-3 | 사용자 | 카테고리로 빠르게 방 매칭 | 관심 주제 토론 입장 | P1 |
| ROOM-4 | 방 개설자 | 모든 발언자가 준비됐을 때 토론 시작 | 준비된 상태에서 진행 | P0 |
| ROOM-5 | 방 개설자 | 토론을 강제 종료 | 문제 상황에서 토론 중단 | P1 |

### 3.3 토론 참여

| ID | As a… | I want to… | So that… | 우선순위 |
|----|--------|-----------|----------|----------|
| PART-1 | 로그인 사용자 | A 또는 B 입장 발언자로 입장 | 내 입장을 대변 | P0 |
| PART-2 | 사용자 | 청중으로 입장 (게스트 허용) | 토론 관람 | P0 |
| PART-3 | 참가자 | 실시간 채팅 전송 | 다른 참가자와 소통 | P0 |
| PART-4 | 발언자 | 내 차례에 음성으로 발언 (STT 변환) | 음성으로 주장 전달 | P0 |
| PART-5 | 발언자 | **모든 참가자와 동일한** 남은 시간을 실시간 확인 | 시간 계획적 사용 | P0 |
| PART-6 | 참가자 | 방 입장 즉시 현재 상태(타이머, 발언자, 참가자 목록) 수신 | 중간 입장 후에도 맥락 파악 | P0 |
| PART-7 | 참가자 | 네트워크 재연결 후 현재 상태 복원 | 잠깐 끊겨도 토론 계속 | P1 |

### 3.4 AI 기능

| ID | As a… | I want to… | So that… | 우선순위 |
|----|--------|-----------|----------|----------|
| AI-1 | 참가자 | 각 발언이 끝나면 자동 AI 요약 수신 | 핵심 파악 | P0 |
| AI-2 | 참가자 | 요청 시 발언 주장의 팩트체크 결과 수신 | 근거 있는 토론 | P1 |
| AI-3 | 사용자 | 토론 종료 후 AI 종합 분석 리포트 조회 | 토론 복기 및 인사이트 | P0 |
| AI-4 | 참가자 | **AI 처리 중에도 다음 발언 순서가 즉시 전환**됨 | AI 지연이 토론 진행을 막지 않음 | P0 |

### 3.5 토론 결과

| ID | As a… | I want to… | So that… | 우선순위 |
|----|--------|-----------|----------|----------|
| RESULT-1 | 청중 | 토론 종료 후 A/B 선호 투표 | 내 의견 반영 | P0 |
| RESULT-2 | 사용자 | A/B 선호 비율 조회 | 청중 반응 확인 | P0 |
| RESULT-3 | 사용자 | 턴별 AI 요약 목록 조회 | 발언 내용 복기 | P1 |

---

## 4. Functional Requirements

### 4.1 토론 방 생명 주기

```
[WAITING]  방 생성 직후. 발언자/청중 입장 가능.
    │
    │  Host가 시작 요청 (발언자 최소 1명 이상 필요)
    ▼
[STARTED]  타이머 시작. 발언 턴 진행.
    │
    │  totalDuration 경과 또는 Host 강제 종료
    ▼
[ENDED]    더 이상 입장/발언 불가. AI 분석 비동기 실행.
    │
    ▼
[결과 조회] AI 분석 완료 후 결과 API 응답 가능.
```

**요구사항:**
- 한 번 STARTED된 방은 WAITING으로 되돌릴 수 없다.
- ENDED 상태에서만 결과 API가 정상 응답한다.
- 방 상태 변경은 원자적으로 처리되어야 한다 (중간 상태 없음).

### 4.2 발언 턴 (Turn) 시스템

**턴 진행 규칙:**
- A측 → B측 → A측 → B측 … 교대 발언
- 한 턴의 시간: 방 설정값으로 고정 (예: 90초)
- 타이머 만료 시 서버가 자동으로 다음 턴 전환, 클라이언트에 즉시 브로드캐스트
- FAST 모드: 일반 모드 대비 턴 시간 50% 단축

**원자성 요구사항:**
- 턴 전환은 반드시 단일 원자 연산으로 처리 (동시에 두 이벤트가 턴 전환을 시도할 경우 하나만 성공)
- Redis Lua script 또는 동등한 원자적 메커니즘 사용 필수
- 현재 발언자가 연결 끊김과 타이머 만료가 동시에 발생해도 턴이 두 번 전환되어선 안 된다

**발언자 부재 처리:**
- 해당 측 발언자가 모두 없으면 턴을 스킵하고 상대측으로 전환
- 양측 모두 발언자 없으면 토론 자동 종료

### 4.3 타이머 동기화

**요구사항:**
- 타이머는 **서버 주도(server-authoritative)** 방식으로 동작한다
- 서버가 1초 간격으로 남은 시간을 WebSocket으로 푸시한다
- 클라이언트는 서버가 보낸 값을 표시한다 (로컬 시계 계산 금지)
- 클라이언트가 재연결 시 서버에서 현재 남은 시간을 즉시 수신한다
- 타이머 만료 이벤트(턴 전환, 토론 종료)는 서버에서 발생시킨다 — Redis key expiry 이벤트에만 의존 금지

**배경 (기존 구현의 결함):**
기존 구현은 서버가 만료 절대 시각(`"2025-05-05 14:30:45.123"`)을 한 번 전송하고 클라이언트가 로컬 시계로 계산했다. 클라이언트마다 시계가 다르고 수신 시점이 달라 drift가 발생했다. 또한 Redis key expiry 이벤트는 즉시 보장되지 않아 실제 턴 전환이 수 초 지연될 수 있었다.

### 4.4 참가자 정원 및 입장 규칙

| 역할 | 인증 | 정원 |
|------|------|------|
| Speaker A측 | 필수 | `maxSpeaker / 2` |
| Speaker B측 | 필수 | `maxSpeaker / 2` |
| Audience | 불필요 (게스트) | `maxAudience` |

**입장 처리:**
- 정원 초과 시 JOIN_REJECTED 메시지 반환
- 이미 STARTED/ENDED 방에 Speaker로 입장 불가
- STARTED 방에 Audience 입장은 허용
- 중간 입장한 참가자는 즉시 현재 상태 스냅샷(타이머, 현재 발언자, 참가자 목록) 수신

### 4.5 채팅

- 방 상태(WAITING/STARTED/ENDED) 무관하게 입장한 참가자는 채팅 가능
- 메시지는 방의 모든 연결 클라이언트에 브로드캐스트
- 메시지 형식: 송신자 이름, 내용, 서버 타임스탬프
- 서버 타임스탬프 사용 (클라이언트 시각 신뢰 불가)

### 4.6 음성 발언 및 STT

- 발언자가 마이크를 켜면 바이너리 오디오를 WebSocket으로 스트리밍 전송
- 발언 종료 시 오디오 버퍼를 Google Cloud STT로 전송
- **STT 처리는 비동기로 수행** — WebSocket 핸들러 스레드를 블로킹하지 않는다
- STT 완료 후 트랜스크립트를 AI 요약 파이프라인으로 전달

### 4.7 AI 처리 파이프라인

**요구사항:**
- AI 처리(요약, 팩트체크, 종합 분석)는 **모두 비동기**로 실행된다
- AI 처리 완료를 기다리지 않고 다음 턴 전환이 즉시 이루어진다
- AI 결과가 완료되면 해당 방 구독자에게 WebSocket으로 푸시한다
- Claude API 실패 시 graceful degradation: 요약 없이 진행, 사용자에게 실패 알림

**파이프라인 흐름:**
```
발언 종료
  │
  ├─► [즉시] 다음 턴 전환 브로드캐스트
  │
  └─► [비동기] 오디오 → STT → 트랜스크립트
                │
                └─► [비동기] Claude 요약 생성
                │           │
                │           └─► 완료 시 WebSocket 푸시 → Redis 저장
                │
                └─► [비동기, 온디맨드] Claude 팩트체크
                            │
                            └─► 완료 시 WebSocket 푸시

토론 종료
  └─► [비동기] Claude 종합 분석 → DB 저장 → WebSocket 푸시 (or polling)
```

**배경 (기존 구현의 결함):**
기존 구현은 STT → factcheck → summary를 같은 스레드에서 순차 블로킹 호출했다. 발언 1회당 평균 7~30초 동안 WebSocket 핸들러 스레드가 점유되었으며, 동시 발언 처리 시 스레드 풀 고갈로 이후 메시지가 큐에 적체되었다.

### 4.8 투표 및 결과

- 청중은 토론 종료 후 A/B 중 하나에 투표 (1인 1표)
- 중복 투표 방지 (userId 또는 sessionId 기준)
- 결과: A 선호 비율, B 선호 비율, 총 투표 수
- AI 종합 분석 텍스트 함께 제공

---

## 5. Non-Functional Requirements

### 5.1 실시간 성능

| 지표 | 요구 수준 | 측정 방법 |
|------|----------|----------|
| WebSocket 메시지 전달 지연 (P95) | < 200ms | K6 custom metric |
| 타이머 클라이언트 간 오차 | < 50ms | 다중 클라이언트 동시 수신 시각 비교 |
| 턴 전환 브로드캐스트 지연 | < 100ms | 턴 만료 이벤트 → 전체 클라이언트 수신 |
| AI 요약 결과 전달 (P95) | < 10s | 발언 종료 → WebSocket 수신 |
| STT 결과 전달 (P95) | < 5s | 발언 종료 → 트랜스크립트 수신 |

### 5.2 동시성 및 처리량

| 지표 | 요구 수준 |
|------|----------|
| 동시 활성 방 수 | 100개 |
| 방당 최대 참가자 | Speaker 4명 + Audience 100명 |
| 총 동시 WebSocket 연결 | 10,400개 이상 |
| 채팅 메시지 처리량 | 1,000 msg/sec |
| 턴 전환 처리 안전성 | 동시 이벤트 발생 시 정확히 1회 전환 보장 |

### 5.3 가용성 및 확장성

| 요구사항 | 내용 |
|---------|------|
| 수평 확장 | 서버 인스턴스 N개 배포 시 동일 방의 모든 메시지가 모든 인스턴스 클라이언트에 전달됨 |
| 메시지 브로커 | Redis Pub/Sub 또는 외부 브로커 사용 (in-memory 브로커 금지) |
| 단일 장애점 | 서버 인스턴스 1개 중단 시 해당 연결만 끊기고 재연결 후 상태 복원 가능 |
| Redis 의존성 | Redis 장애 시 신규 방 생성 차단, 기존 방은 best-effort 유지 |

### 5.4 일관성 (Consistency)

| 요구사항 | 내용 |
|---------|------|
| 타이머 상태 | 모든 클라이언트가 ±50ms 이내 동일한 값을 표시 |
| 발언자 상태 | 동일 방의 모든 클라이언트가 동일한 현재 발언자를 표시 |
| 참가자 목록 | 입장/퇴장 이벤트 후 1초 이내 모든 클라이언트에 반영 |
| 턴 전환 | 중복 전환 없음, 누락 전환 없음 |

### 5.5 보안

| 요구사항 | 내용 |
|---------|------|
| 인증 | JWT HS256, Access Token 1시간, Refresh Token 14일 |
| WebSocket 인증 | STOMP CONNECT 헤더에 JWT 필수 (Speaker 역할), Audience는 게스트 세션 허용 |
| CORS | 명시적 허용 Origin만 (프로덕션에서 `*` 사용 금지) |
| 토큰 저장 | Access: Authorization 헤더, Refresh: HttpOnly Cookie |
| 입력 검증 | 모든 API 입력 서버 사이드 검증, WebSocket 메시지도 포함 |

### 5.6 관찰 가능성

| 요소 | 내용 |
|------|------|
| 메트릭 | Prometheus + Micrometer: 방별 연결 수, 메시지 처리량, AI 응답 시간, 턴 전환 수 |
| 로그 | JSON 구조화 로그, Loki 수집, roomUUID를 correlation ID로 사용 |
| 분산 추적 | 발언 종료 → STT → AI → 브로드캐스트 전체 span 추적 |
| 예외 추적 | Sentry: 전체 예외 + AI/STT 실패 알림 |
| 부하 테스트 | K6: join 성공률 >99%, 채팅 echo 성공률 >99%, Speaker 턴 시나리오 포함 필수 |

---

## 6. 기존 구현 결함 분석 (재건축 시 반드시 해결)

> 기존 프로젝트는 여러 인원이 개발하여 일관성이 없고, 아래 결함들이 확인되었다.
> 이 절은 동일한 실수를 반복하지 않기 위한 기준이다.

### 6.1 [CRITICAL] 발언 턴 전환 Race Condition

**문제:**  
`SpeakerService.startNextSpeaker()`가 Redis에서 spokenUsers를 Read → Modify → Write하는 과정에서 아무런 락이 없다. Redis key expiry 이벤트와 발언자 disconnect 이벤트가 동시에 발생하면 동일한 턴이 두 번 전환되거나, 발언자 순서가 꼬인다.

**요구사항:**  
턴 전환 로직은 단일 Redis Lua script(또는 동등한 원자적 수단)로 처리한다. Read-Modify-Write는 항상 원자 단위여야 한다.

### 6.2 [CRITICAL] STT + AI 동기 블로킹

**문제:**  
발언 종료 시 같은 스레드에서 Google STT (3~10초) → Claude factcheck (2~10초) → Claude summary (2~10초)를 순차 블로킹 호출했다. WebSocket 핸들러 스레드가 최대 30초 점유되며 이후 메시지 처리가 지연/중단된다.

**요구사항:**  
STT와 AI 호출은 핸들러 스레드와 분리된 비동기 파이프라인에서 처리한다. 핸들러 스레드는 요청 접수 즉시 반환해야 한다.

### 6.3 [CRITICAL] 수평 확장 불가 (Simple In-Memory Broker)

**문제:**  
Spring `enableSimpleBroker()`는 단일 프로세스 내 메모리에서만 메시지를 전달한다. 서버 인스턴스가 2대 이상일 경우, 다른 인스턴스에 연결된 클라이언트는 메시지를 수신하지 못해 같은 방의 참가자가 서로 다른 상태를 본다 (split brain).

**요구사항:**  
메시지 브로커를 Redis Pub/Sub (또는 RabbitMQ)로 교체하여 모든 서버 인스턴스가 동일한 브로드캐스트를 수신한다.

### 6.4 [HIGH] 클라이언트 계산 타이머 — Drift 발생

**문제:**  
서버가 만료 절대 시각을 한 번 전송하고 클라이언트가 로컬 시계로 계산했다. 클라이언트마다 시계 오차 + 수신 시점 차이로 최대 500ms drift가 발생했다. 또한 Redis key expiry 이벤트는 즉시 보장되지 않아 실제 턴 전환이 수 초 늦을 수 있었다.

**요구사항:**  
서버가 1초 단위로 남은 시간을 직접 푸시한다. 클라이언트는 로컬 카운트다운을 계산하지 않는다.

### 6.5 [HIGH] 중간 입장 시 상태 동기화 없음

**문제:**  
방에 입장한 참가자는 채팅 히스토리, 현재 발언자, 타이머 잔여 시간을 알 수 없었다. 새로 입장하면 빈 화면에서 시작했다.

**요구사항:**  
JOIN_ACCEPTED 응답에 현재 상태 스냅샷 포함: 현재 발언자, 남은 시간, 참가자 목록, 최근 채팅 N개.

### 6.6 [HIGH] 재연결 후 상태 복원 없음

**문제:**  
네트워크 끊김 후 재연결 시 기존 Redis 세션과의 매핑이 복원되지 않아 참가자로 재인식되지 않았다. WebSocket 재연결은 새 세션으로 처리되었다.

**요구사항:**  
재연결 시 JWT 또는 세션 ID로 기존 참가자 상태를 복원하고, 현재 방 상태 스냅샷을 전송한다.

### 6.7 [MEDIUM] 코드 일관성 부재

**문제:**  
여러 인원이 개발하여 동일 개념에 다른 네이밍, 다른 에러 처리 방식, 다른 레이어 접근 방식이 혼재한다. 예: 일부는 직접 Redis 접근, 일부는 Repository 추상화, 일부는 Service에서 직접 Redis 접근.

**요구사항:**  
레이어 규칙을 명확히 정의하고 전체 코드베이스에서 일관 적용한다 (아키텍처 가이드는 SPEC.md 참조).

### 6.8 [MEDIUM] 보안 미완성

**문제:**
- 프로덕션에서 CORS origin `*` 사용
- 다수 API endpoint가 `permitAll()` 처리 (권한 검토 미완료)
- WebSocket 메시지 입력 검증 없음

**요구사항:**  
- CORS는 환경별 명시적 Origin 목록 관리
- 각 endpoint 인증/권한 요구사항 명시 후 구현
- WebSocket 메시지도 서버 사이드 검증 필수

### 6.9 [LOW] 불필요한 ID 이중 관리

**문제:**  
외부 UUID ↔ 내부 DB PK를 Redis에서 양방향 매핑으로 관리. 복잡도가 높고 Redis 장애 시 방 조회 불가.

**요구사항:**  
UUID를 DB Primary Key로 직접 사용하거나, UUID를 DB에 컬럼으로 저장하여 Redis 의존 없이 조회 가능하게 한다.

---

## 7. System Behavior Specifications

### 7.1 타이머 동작 명세

```
서버 동작:
  - 방 시작 시: timerEndAt = now() + durationSeconds를 Redis에 저장
  - @Scheduled(1초 간격): 모든 활성 방에 대해 remainingSeconds = timerEndAt - now() 계산
  - remainingSeconds > 0: /topic/debate/{roomUUID}/expire 에 { remainingSeconds } 브로드캐스트
  - remainingSeconds <= 0: 토론 종료 처리 (원자적), 브로드캐스트 중지

발언자 타이머:
  - 턴 시작 시: speakerTimerEndAt = now() + turnDurationSeconds
  - @Scheduled(1초 간격): 현재 발언자 방의 remainingSeconds 계산 후 브로드캐스트
  - remainingSeconds <= 0: 턴 전환 처리 (원자적)

클라이언트 동작:
  - 서버에서 받은 remainingSeconds를 그대로 표시
  - 로컬 카운트다운 계산 금지
```

### 7.2 턴 전환 원자성 명세

```
turnAdvance(roomUUID):
  REDIS MULTI:
    1. GET currentSpeakerId
    2. SMEMBERS spokenUsers
    3. 현재 발언자가 spokenUsers에 없으면 SADD
    4. GET nextSpeakerId (스포크 안 한 참가자 중 다음 순서)
    5. nextSpeakerId 있으면 SET currentSpeakerId = nextSpeakerId
    6. 없으면 INCR turnCount, CLEAR spokenUsers, SET currentSpeakerId = firstSpeaker
  EXEC

  - EXEC 실패(충돌) 시 WATCH → 재시도 (최대 3회)
  - 성공 후 브로드캐스트: { type: TURN_CHANGED, currentSpeaker, remainingSeconds }
```

### 7.3 JOIN 처리 및 상태 스냅샷 명세

```
JOIN 요청 수신 시:
  1. 방 상태 확인 (ENDED면 JOIN_REJECTED)
  2. 정원 확인 (초과면 JOIN_REJECTED)
  3. Speaker면 JWT 검증 (없으면 JOIN_REJECTED)
  4. Redis에 참가자 추가
  5. JOIN_ACCEPTED 전송 with snapshot:
     {
       type: "JOIN_ACCEPTED",
       role: "SPEAKER" | "AUDIENCE",
       side: "A" | "B" | null,
       snapshot: {
         debateStatus: "STARTED",
         currentSpeaker: { userId, name, side },
         remainingSeconds: 45,
         participants: { A: [...], B: [...], audiences: [...] },
         recentChats: [...] (최근 20개)
       }
     }
  6. 전체 방 브로드캐스트: PARTICIPANT_LIST 업데이트
```

### 7.4 AI 파이프라인 비동기 명세

```
발언 종료 이벤트 수신 시 (핸들러 스레드):
  1. 턴 전환 즉시 실행 (원자적)
  2. 브로드캐스트: { type: TURN_CHANGED, ... }
  3. CompletableFuture.supplyAsync(STT처리, sttExecutor)
       .thenApplyAsync(transcript -> claude.summary(transcript), aiExecutor)
       .thenAcceptAsync(summary -> {
           redis.saveSummary(roomUUID, turnIndex, summary);
           ws.broadcast(roomUUID, { type: AI_SUMMARY, turnIndex, summary });
       }, broadcastExecutor)
       .exceptionally(e -> { ws.broadcast(roomUUID, { type: AI_FAILED, ... }); null; });
  4. 핸들러 스레드 즉시 반환

팩트체크 (온디맨드):
  CompletableFuture.supplyAsync(
      () -> claude.factcheck(claim), aiExecutor
  ).thenAccept(result -> ws.broadcast(roomUUID, { type: FACTCHECK_RESULT, ... }));
```

---

## 8. WebSocket 이벤트 명세

### 8.1 클라이언트 → 서버 (Publish)

| Destination | 페이로드 | 인증 |
|-------------|---------|------|
| `/pub/debate/join` | `{ roomUUID, role, side }` | Speaker: JWT 필수 |
| `/pub/debate/leave` | `{ roomUUID }` | - |
| `/pub/chat/message` | `{ roomUUID, message }` | - |
| `/pub/ai/factcheck` | `{ roomUUID, turnIndex, claim }` | - |

### 8.2 서버 → 클라이언트 (Subscribe)

| Topic | 이벤트 타입 | 트리거 |
|-------|-----------|--------|
| `/sub/debate-room/{roomUUID}` | JOIN_ACCEPTED, JOIN_REJECTED, PARTICIPANT_LIST, CHAT, DEBATE_STARTED, DEBATE_ENDED | 참가자 이벤트 |
| `/topic/debate/{roomUUID}/expire` | `{ remainingSeconds }` | 서버 @Scheduled 1초 |
| `/topic/speaker/{roomUUID}/expire` | `{ remainingSeconds, speakerId, speakerName, side }` | 서버 @Scheduled 1초 |
| `/topic/speaker/{roomUUID}` | TURN_CHANGED | 턴 전환 시 |
| `/topic/ai/{roomUUID}` | AI_SUMMARY, FACTCHECK_RESULT, AI_FAILED, DEBATE_ANALYSIS | AI 처리 완료 시 |

### 8.3 메시지 타입 정의

```json
// TURN_CHANGED
{
  "type": "TURN_CHANGED",
  "turnIndex": 3,
  "currentSpeaker": { "userId": "123", "name": "홍길동", "side": "A" },
  "serverTimestamp": "2026-05-05T10:00:00.000Z"
}

// AI_SUMMARY
{
  "type": "AI_SUMMARY",
  "turnIndex": 3,
  "side": "A",
  "summary": "발언자는 기본소득제가 빈곤 해소에 효과적이라고 주장했다.",
  "serverTimestamp": "2026-05-05T10:01:30.000Z"
}

// CHAT
{
  "type": "CHAT",
  "senderId": "123",
  "senderName": "홍길동",
  "message": "좋은 주장입니다",
  "serverTimestamp": "2026-05-05T10:00:05.000Z"
}
```

---

## 9. API 요구사항 (REST)

### 9.1 인증

| Method | Path | Auth | 설명 |
|--------|------|------|------|
| GET | `/oauth2/authorization/{provider}` | - | OAuth2 시작 |
| POST | `/api/auth/refresh` | Refresh Cookie | Access Token 갱신 |
| GET | `/api/auth/me` | Bearer | 내 프로필 조회 |
| POST | `/auth/logout` | Bearer | 로그아웃 |

### 9.2 토론 방

| Method | Path | Auth | 설명 |
|--------|------|------|------|
| POST | `/api/debate-rooms` | Bearer | 방 생성 |
| GET | `/api/debate-rooms` | - | 전체 방 목록 (status=WAITING) |
| GET | `/api/debate-rooms/{roomUUID}` | - | 방 상세 |
| POST | `/api/debate-rooms/{roomUUID}/start` | Bearer (Host만) | 토론 시작 |
| POST | `/api/debate-rooms/{roomUUID}/end` | Bearer (Host만) | 토론 강제 종료 |
| POST | `/api/debate-rooms/match` | - | 카테고리 기반 매칭 |

### 9.3 결과 및 AI

| Method | Path | Auth | 설명 |
|--------|------|------|------|
| GET | `/api/debate-results/{roomUUID}` | - | 토론 결과 조회 |
| GET | `/api/debate/{roomUUID}/ai/summaries` | - | 턴별 요약 목록 |
| POST | `/api/debate/{roomUUID}/vote` | - | 선호 입장 투표 |

### 9.4 카테고리 및 주제

| Method | Path | Auth | 설명 |
|--------|------|------|------|
| GET | `/api/categories` | - | 카테고리 목록 |
| GET | `/api/debate-topics` | - | 주제 목록 |
| POST | `/api/debate-topics` | Bearer (ADMIN) | 주제 추가 |
| DELETE | `/api/debate-topics/{id}` | Bearer (ADMIN) | 주제 삭제 |

---

## 10. 성공 지표 (Success Metrics)

| 지표 | 목표 | 측정 |
|------|------|------|
| 방 생성 → 시작 중도 이탈률 | < 20% | 이벤트 로그 |
| 발언 턴 전환 정확도 | 100% (중복/누락 0) | 테스트 자동화 |
| AI 요약 응답 시간 (P95) | < 10s | Prometheus |
| WebSocket 메시지 지연 (P95) | < 200ms | K6 |
| 타이머 클라이언트 간 오차 | < 50ms | K6 커스텀 |
| join 성공률 (100 VU 부하) | > 99% | K6 |
| 채팅 echo 성공률 (100 VU 부하) | > 99% | K6 |
| Speaker 턴 시나리오 무결성 | 100% | K6 커스텀 |

---

## 11. Open Questions

| # | 질문 | 결정 필요자 |
|---|------|------------|
| OQ-1 | 청중 투표는 토론 중 가능인가, 종료 후만 가능인가 | PO |
| OQ-2 | 팩트체크는 온디맨드(발언자 요청)인가, 자동 실행인가 | PO |
| OQ-3 | 연결 끊긴 Speaker 자리를 다른 참가자가 대체할 수 있는가 | PO |
| OQ-4 | FAST 모드의 턴당 시간은 정확히 몇 초인가 | PO |
| OQ-5 | 동일 사용자가 여러 방에 동시 참여 허용하는가 | 기술팀 |
| OQ-6 | 채팅 히스토리를 DB에 영구 저장할 것인가 (현재 미저장) | PO |
| OQ-7 | Audience의 발언자 전환 후 남은 AI 요약 시간 동안 어떤 UX를 보여줄 것인가 | 디자이너 |

---

## 12. Out of Scope & Roadmap

| 버전 | 기능 |
|------|------|
| v2 | WebRTC 영상 토론, 방 녹화 · VOD |
| v3 | 팔로우 · 피드 · 토론 실력 랭킹 |
| v4 | 유료 개최, 구독 플랜 |
