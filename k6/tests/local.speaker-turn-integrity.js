// tests/local.speaker-turn-integrity.js
/**
 * 턴 전환 무결성 테스트
 *
 * 목표: 한 번의 speech/end 이벤트에 TURN_CHANGED가 정확히 1회만
 *       모든 클라이언트에게 브로드캐스트되는지 검증한다.
 *
 * 시나리오:
 *   - speaker 시나리오(VU 1): Speaker A로 입장 → speech/end 전송
 *   - observer 시나리오(VU 2~N): Audience로 입장 → TURN_CHANGED 수신 횟수 카운트
 *
 * 커스텀 메트릭:
 *   - turn_changed_once: 정확히 1회 TURN_CHANGED 수신 비율 (임계값: rate>0.999)
 *   - turn_change_latency_ms: speech/end → TURN_CHANGED 수신 지연 (임계값: p(95)<100)
 *   - turn_changed_duplicate: TURN_CHANGED 2회+ 수신 횟수 (임계값: count==0)
 *
 * 환경 변수:
 *   ROOM        - 토론방 UUID (STARTED 상태여야 함)
 *   WS_URL      - WebSocket URL (기본: ws://localhost:8080/ws-stomp/websocket)
 *   SPEAKER_JWT - Speaker 역할용 JWT Access Token
 *   OBSERVERS   - Observer VU 수 (기본: 20)
 *   DUR         - 테스트 시간 (기본: 60s)
 */

import ws from 'k6/ws';
import { Rate, Trend, Counter } from 'k6/metrics';
import { sleep } from 'k6';
import {
  stompConnect, stompSubscribe, stompSend, stompDisconnect,
  parseStompBody, getStompDestination, randId,
} from '../data/stomp-helpers.js';

const ROOM        = __ENV.ROOM        || 'REPLACE_WITH_STARTED_ROOM_UUID';
const WS_URL      = __ENV.WS_URL      || 'ws://localhost:8080/ws-stomp/websocket';
const SPEAKER_JWT = __ENV.SPEAKER_JWT || '';
const OBSERVERS   = parseInt(__ENV.OBSERVERS || '20', 10);
const DUR         = __ENV.DUR         || '60s';
const DEBUG       = (__ENV.DEBUG || '').toLowerCase() === 'true';

const SUB_ROOM    = `/sub/debate-room/${ROOM}`;
const SUB_SPEAKER = `/topic/speaker/${ROOM}`;

const turnChangedOnce      = new Rate('turn_changed_once');
const turnChangeLat        = new Trend('turn_change_latency_ms');
const turnChangedDuplicate = new Counter('turn_changed_duplicate');

export const options = {
  scenarios: {
    speaker: {
      executor: 'constant-vus',
      vus: 1,
      duration: DUR,
      exec: 'speakerScenario',
    },
    observer: {
      executor: 'constant-vus',
      vus: OBSERVERS,
      duration: DUR,
      exec: 'observerScenario',
    },
  },
  thresholds: {
    'turn_changed_once':      ['rate>0.999'],
    'turn_change_latency_ms': ['p(95)<100'],
    'turn_changed_duplicate': ['count==0'],
  },
};

// Speaker 시나리오: 입장 후 speech/end를 30초 간격으로 반복 전송
export function speakerScenario() {
  if (!SPEAKER_JWT) {
    console.error('[SPEAKER] SPEAKER_JWT 환경변수가 필요합니다.');
    return;
  }

  ws.connect(WS_URL, {}, function (socket) {
    socket.on('message', (raw) => {
      const s = String(raw);

      if (s.startsWith('CONNECTED')) {
        socket.send(stompSubscribe(SUB_ROOM, 'sub-room'));

        const joinBody = {
          roomUuid: ROOM,
          role: 'SPEAKER',
          side: 'A',
          nonce: `speaker-${randId(6)}`,
        };
        socket.send(stompSend('/pub/debate/join', joinBody));

        // 5초 대기 후 speech/end 반복 전송 (30초 간격)
        socket.setTimeout(() => {
          function sendSpeechEnd() {
            const sentAt = Date.now();
            if (DEBUG) console.log(`[SPEAKER] speech/end 전송 at ${sentAt}`);
            socket.send(stompSend('/pub/speech/end', { roomUuid: ROOM, sentAt }));
            socket.setTimeout(sendSpeechEnd, 30000);
          }
          sendSpeechEnd();
        }, 5000);
      }
    });

    socket.on('error', (e) => {
      if (DEBUG) console.error(`[SPEAKER] 오류: ${JSON.stringify(e)}`);
    });

    const connectFrame = stompConnect();
    socket.send(`${connectFrame.slice(0, -1)}Authorization:Bearer ${SPEAKER_JWT}\n\n `);
  });
}

// Observer 시나리오: 입장 후 TURN_CHANGED 수신 횟수 검증
export function observerScenario() {
  const nonce = `obs-${__VU}-${randId(6)}`;
  let turnChangedCount = 0;
  let speechEndSentAt = null;
  let firstTurnChangedAt = null;

  ws.connect(WS_URL, {}, function (socket) {
    socket.setTimeout(() => {
      // 세션 종료 시 결과 기록
      if (turnChangedCount === 1) {
        turnChangedOnce.add(true);
        if (firstTurnChangedAt && speechEndSentAt) {
          turnChangeLat.add(firstTurnChangedAt - speechEndSentAt);
        }
      } else if (turnChangedCount === 0) {
        turnChangedOnce.add(false);
      } else {
        turnChangedOnce.add(false);
        turnChangedDuplicate.add(turnChangedCount - 1);
        console.error(`[VU ${__VU}] TURN_CHANGED ${turnChangedCount}회 수신 — 중복!`);
      }
      socket.close();
    }, 55000);

    socket.on('message', (raw) => {
      const s = String(raw);

      if (s.startsWith('CONNECTED')) {
        socket.send(stompSubscribe(SUB_ROOM, 'sub-room'));
        socket.send(stompSubscribe(SUB_SPEAKER, 'sub-speaker'));

        socket.send(stompSend('/pub/debate/join', {
          roomUuid: ROOM,
          role: 'AUDIENCE',
          nonce,
        }));
      }

      if (s.startsWith('MESSAGE')) {
        const dest = getStompDestination(s);
        const msg  = parseStompBody(s);
        if (!msg) return;

        // speaker가 speech/end 보낸 시각 수신 (브로드캐스트로 전달된다고 가정)
        if (dest === SUB_ROOM && msg.type === 'SPEECH_END_ACK') {
          speechEndSentAt = msg.sentAt;
        }

        // TURN_CHANGED 수신
        if (dest === SUB_SPEAKER && msg.type === 'TURN_CHANGED') {
          turnChangedCount++;
          if (turnChangedCount === 1) firstTurnChangedAt = Date.now();
          if (DEBUG) console.log(`[VU ${__VU}] TURN_CHANGED #${turnChangedCount} turnIndex=${msg.turnIndex}`);
        }
      }
    });

    socket.send(stompConnect());
  });
}
