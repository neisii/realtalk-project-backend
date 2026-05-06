# RealTalk 백엔드

실시간 양자 토론 플랫폼. 참가자가 A/B 입장으로 나뉘어 턴제 토론을 하고, AI(Claude)가 발언을 요약·분석한다.

## 기술 스택

| 영역 | 선택 |
|---|---|
| Language | Java 21 (Virtual Thread) |
| Framework | Spring Boot 3.x |
| 실시간 | WebSocket + STOMP (SockJS) |
| DB | MySQL 8.0 + Flyway |
| Cache / Broker | Redis 7 (Pub/Sub + 상태 저장) |
| AI | Claude 3.5 Haiku (Spring AI) |
| STT | Google Cloud Speech-to-Text |
| Auth | OAuth2 (Google, Kakao) + JWT HS256 |

---

## 빠른 시작

### 사전 준비

- Java 21
- Docker Desktop

### 1단계 — 인프라 기동

```bash
docker-compose up -d
```

MySQL과 Redis가 뜨고 healthcheck를 통과할 때까지 기다린다 (최대 30초).

```bash
docker-compose ps   # mysql: healthy, redis: healthy 확인
```

### 2단계 — 환경변수 설정

```bash
cp .env.example .env
```

`.env`를 열어 아래 항목을 채운다. 나머지는 기본값으로 동작한다.

| 변수 | 필수 | 비고 |
|---|---|---|
| `JWT_SECRET` | ✅ | 32자 이상. `openssl rand -base64 32` |
| `ANTHROPIC_API_KEY` | ✅ | 없으면 기동 불가 |
| `GOOGLE_CLIENT_ID` / `GOOGLE_CLIENT_SECRET` | ✅ | OAuth2 로그인 |
| `KAKAO_CLIENT_ID` / `KAKAO_CLIENT_SECRET` | ✅ | OAuth2 로그인 |
| `GCP_CREDENTIALS_JSON` | — | STT 기능 사용 시. 없으면 기동은 가능 |

> OAuth2 설정 방법은 [외부 서비스 설정](#외부-서비스-설정) 참조.

### 3단계 — 서버 기동

```bash
./gradlew bootRun
```

Flyway가 자동으로 `V1__init.sql`을 실행해 스키마를 생성한다.

### 4단계 — 기동 확인

```bash
curl http://localhost:8080/actuator/health
# {"status":"UP",...}
```

---

## 환경변수 전체 참조

`.env.example`에 모든 항목과 주석이 정리되어 있다.

| 변수 | 기본값 | 설명 |
|---|---|---|
| `MYSQL_HOST` | localhost | |
| `MYSQL_PORT` | 3306 | |
| `MYSQL_DATABASE` | — | docker-compose: `realtalk` |
| `MYSQL_USERNAME` | — | docker-compose: `root` |
| `MYSQL_PASSWORD` | — | docker-compose: `realtalk` |
| `REDIS_HOST` | localhost | |
| `REDIS_PORT` | 6379 | |
| `JWT_SECRET` | — | **32자 이상 필수** |
| `BASE_URL` | — | 이 서버 주소. OAuth2 redirect URI에 사용 |
| `FRONTEND_URL` | — | CORS 허용 Origin |
| `ANTHROPIC_API_KEY` | — | Claude AI. **없으면 기동 불가** |
| `GCP_CREDENTIALS_JSON` | (빈값) | JSON 문자열. 없으면 STT 비활성 |
| `GOOGLE_CLIENT_ID/SECRET` | — | |
| `KAKAO_CLIENT_ID/SECRET` | — | |
| `SERVER_PORT` | 8080 | |
| `SENTRY_DSN` | (빈값) | 선택 |

---

## 외부 서비스 설정

### Google OAuth2

1. [Google Cloud Console](https://console.cloud.google.com) → API 및 서비스 → 사용자 인증 정보
2. OAuth 2.0 클라이언트 ID 생성 (웹 애플리케이션)
3. 승인된 리디렉션 URI 추가:
   ```
   http://localhost:8080/login/oauth2/code/google
   ```
4. 발급된 클라이언트 ID/Secret을 `.env`에 입력

### Kakao OAuth2

1. [Kakao Developers](https://developers.kakao.com) → 내 애플리케이션 → 앱 추가
2. 앱 설정 → 카카오 로그인 → Redirect URI 등록:
   ```
   http://localhost:8080/login/oauth2/code/kakao
   ```
3. 동의항목 → `profile_nickname` 활성화
4. 앱 키 → REST API 키를 `KAKAO_CLIENT_ID`에, Secret은 보안 → Client Secret에서 발급

### Google Cloud Speech-to-Text

1. GCP Console → IAM → 서비스 계정 → 키 생성 (JSON)
2. 다운받은 파일 내용을 한 줄 문자열로 변환:
   ```bash
   cat key.json | tr -d '\n'
   ```
3. 출력값 전체를 `.env`의 `GCP_CREDENTIALS_JSON=` 뒤에 붙여넣기

---

## API 문서

서버 기동 후 브라우저에서 접근:

```
http://localhost:8080/swagger-ui/index.html
```

---

## WebSocket 테스트

서버에 내장된 테스트 페이지를 브라우저에서 바로 사용할 수 있다.

| URL | 설명 |
|---|---|
| `http://localhost:8080/` | OAuth2 로그인 테스트 |
| `http://localhost:8080/stomp.html` | STOMP 연결 및 메시지 송수신 |
| `http://localhost:8080/debateroom.html` | 토론방 입장 / 채팅 / 타이머 통합 |
| `http://localhost:8080/participants.html` | 참가자 목록 확인 |
| `http://localhost:8080/stompspeakingtest.html` | 발언 턴 시나리오 |

> 외부 클라이언트나 Postman 없이도 핵심 실시간 기능을 바로 확인할 수 있다.

---

## 주요 STOMP 엔드포인트

**연결:** `ws://localhost:8080/ws-debate` (SockJS)

**발행 (클라이언트 → 서버)**

| 경로 | 설명 |
|---|---|
| `/pub/debate/join` | 방 입장 |
| `/pub/debate/leave` | 방 퇴장 |
| `/pub/chat/message` | 채팅 전송 |
| `/pub/speech/end` | 발언 종료 |
| `/pub/ai/factcheck` | 팩트체크 요청 |

**구독 (서버 → 클라이언트)**

| 경로 | 이벤트 |
|---|---|
| `/sub/debate-room/{roomUuid}` | JOIN_ACCEPTED, PARTICIPANT_LIST, CHAT, DEBATE_STARTED, DEBATE_ENDED |
| `/topic/debate/{roomUuid}/timer` | TIMER_TICK (1초 간격) |
| `/topic/speaker/{roomUuid}` | TURN_CHANGED |
| `/topic/ai/{roomUuid}` | AI_SUMMARY, FACTCHECK_RESULT, DEBATE_ANALYSIS |

---

## 자주 발생하는 오류

**`WeakKeyException: The signing key's size is 248 bits`**

`JWT_SECRET`이 32자 미만이다. `openssl rand -base64 32` 로 재생성한다.

**`Connection refused: localhost/127.0.0.1:6379`**

Redis 컨테이너가 뜨지 않았다. `docker-compose up -d` 후 `docker-compose ps`로 상태를 확인한다.

**`Flyway: Schema-validation: missing table`**

Hibernate가 DB 스키마와 엔티티 불일치를 감지했다. `MYSQL_DATABASE`가 docker-compose에서 생성한 DB 이름(`realtalk`)과 일치하는지 확인한다.

**`Could not resolve placeholder 'ANTHROPIC_API_KEY'`**

`.env` 파일이 없거나 `ANTHROPIC_API_KEY`가 비어있다. Spring AI는 기동 시 API 키를 주입하므로 더미 값이라도 필요하다.

**CORS 오류 (브라우저 콘솔)**

`FRONTEND_URL`이 실제 프론트엔드 주소와 다르다. 로컬 개발 시 `http://localhost:3000`으로 맞춘다.
