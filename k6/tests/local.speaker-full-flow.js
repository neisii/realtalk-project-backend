// tests/local.speaker-full-flow.js
/**
 * Speaker 전체 흐름 테스트
 *
 * 목표: HTTP 방 생성 → 시작 → Speaker/Audience WebSocket 입장 →
 *       발언 종료(speech/end) → TURN_CHANGED + AI_SUMMARY 수신까지 E2E 검증.
 *
 * 시나리오:
 *   - controller (VU 1): HTTP로 방 생성 및 시작, roomUuid를 SharedArray에 기록
 *   - speaker (VU 2):    Speaker A로 입장, 10초 후 speech/end 전송
 *   - watcher (VU 3~N):  Audience로 입장, TURN_CHANGED + AI_SUMMARY 수신 측정
 *
 * 커스텀 메트릭:
 *   - turn_changed_after_speech: speech/end 후 TURN_CHANGED 수신 비율
 *   - ai_summary_received:       AI_SUMMARY 수신 비율 (10초 내)
 *   - ai_summary_latency_ms:     speech/end → AI_SUMMARY 수신 지연
 *
 * 환경 변수:
 *   BASE_URL    - HTTP API URL (기본: http://localhost:8080)
 *   WS_URL      - WebSocket URL (기본: ws://localhost:8080/ws-stomp/websocket)
 *   SPEAKER_JWT - Speaker 역할용 JWT Access Token
 *   CATEGORY_ID - 카테고리 ID (기본: 1)
 *   WATCHERS    - Watcher VU 수 (기본: 10)
 *   DUR         - 테스트 시간 (기본: 120s)
 */

import http from 'k6/http';
import ws from 'k6/ws';
import { check, sleep } from 'k6';
import { SharedArray } from 'k6/data';
import { Rate, Trend } from 'k6/metrics';
import {
  stompConnect, stompSubscribe, stompSend,
  parseStompBody, getStompDestination, randId,
} from '../data/stomp-helpers.js';

const BASE_URL    = __ENV.BASE_URL    || 'http://localhost:8080';
const WS_URL      = __ENV.WS_URL      || 'ws://localhost:8080/ws-stomp/websocket';
const SPEAKER_JWT = __ENV.SPEAKER_JWT || '';
const CATEGORY_ID = parseInt(__ENV.CATEGORY_ID || '1', 10);
const WATCHERS    = parseInt(__ENV.WATCHERS || '10', 10);
const DUR         = __ENV.DUR         || '120s';
const DEBUG       = (__ENV.DEBUG || '').toLowerCase() === 'true';

// setup()에서 생성된 roomUuid를 공유
const state = new SharedArray('roomState', function () {
  return [{ roomUuid: '' }];
});

const turnChangedAfterSpeech = new Rate('turn_changed_after_speech');
const aiSummaryReceived      = new Rate('ai_summary_received');
const aiSummaryLatency       = new Trend('ai_summary_latency_ms');

export const options = {
  scenarios: {
    speaker: {
      executor: 'constant-vus',
      vus: 1,
      duration: DUR,
      exec: 'speakerScenario',
      startTime: '5s', // controller가 방 생성 후 시작
    },
    watcher: {
      executor: 'constant-vus',
      vus: WATCHERS,
      duration: DUR,
      exec: 'watcherScenario',
      startTime: '5s',
    },
  },
  thresholds: {
    'turn_changed_after_speech': ['rate>0.999'],
    'ai_summary_received':       ['rate>0.95'],
    'ai_summary_latency_ms':     ['p(95)<10000'],
    'http_req_duration':         ['p(95)<500'],
    'http_req_failed':           ['rate<0.01'],
  },
};

// 방 생성 및 시작 (setup 역할, VU 독립적)
export function setup() {
  if (!SPEAKER_JWT) {
    console.error('[SETUP] SPEAKER_JWT 환경변수가 필요합니다.');
    return { roomUuid: null };
  }

  const headers = {
    'Authorization': `Bearer ${SPEAKER_JWT}`,
    'Content-Type': 'application/json',
  };

  const createRes = http.post(`${BASE_URL}/api/debate-rooms`, JSON.stringify({
    title: `[K6] Full Flow Test ${randId(4)}`,
    sideA: '찬성',
    sideB: '반대',
    turnDurationSecs: 30,
    totalDurationSecs: 180,
    maxSpeaker: 2,
    maxAudience: 50,
    categoryId: CATEGORY_ID,
    debateType: 'NORMAL',
  }), { headers });

  check(createRes, { '방 생성 201': (r) => r.status === 201 });
  if (createRes.status !== 201) return { roomUuid: null };

  const roomUuid = createRes.json('data.roomUuid');
  if (DEBUG) console.log(`[SETUP] 방 생성 완료: ${roomUuid}`);

  sleep(1);

  const startRes = http.post(
    `${BASE_URL}/api/debate-rooms/${roomUuid}/start`,
    null,
    { headers }
  );
  check(startRes, { '방 시작 200': (r) => r.status === 200 });
  if (DEBUG) console.log(`[SETUP] 방 시작: ${startRes.status}`);

  return { roomUuid };
}

// Speaker 시나리오: 입장 후 10초 뒤 speech/end 전송
export function speakerScenario(data) {
  const roomUuid = data?.roomUuid;
  if (!roomUuid || !SPEAKER_JWT) return;

  const SUB_ROOM    = `/sub/debate-room/${roomUuid}`;
  const SUB_SPEAKER = `/topic/speaker/${roomUuid}`;

  ws.connect(WS_URL, {}, function (socket) {
    socket.setTimeout(() => { socket.close(); }, 100000);

    socket.on('message', (raw) => {
      const s = String(raw);
      if (s.startsWith('CONNECTED')) {
        socket.send(stompSubscribe(SUB_ROOM, 'sub-room'));
        socket.send(stompSubscribe(SUB_SPEAKER, 'sub-speaker'));
        socket.send(stompSend('/pub/debate/join', {
          roomUuid,
          role: 'SPEAKER',
          side: 'A',
          nonce: `speaker-${randId(6)}`,
        }));

        // 10초 후 speech/end
        socket.setTimeout(() => {
          const sentAt = Date.now();
          socket.send(stompSend('/pub/speech/end', { roomUuid, sentAt }));
          if (DEBUG) console.log(`[SPEAKER] speech/end 전송 at ${sentAt}`);
        }, 10000);
      }
    });

    // JWT를 Authorization 헤더에 포함
    const frame = `CONNECT\naccept-version:1.2\nAuthorization:Bearer ${SPEAKER_JWT}\n\n `;
    socket.send(frame);
  });
}

// Watcher 시나리오: TURN_CHANGED + AI_SUMMARY 수신 측정
export function watcherScenario(data) {
  const roomUuid = data?.roomUuid;
  if (!roomUuid) return;

  const SUB_ROOM    = `/sub/debate-room/${roomUuid}`;
  const SUB_SPEAKER = `/topic/speaker/${roomUuid}`;
  const SUB_AI      = `/topic/ai/${roomUuid}`;
  const nonce       = `watcher-${__VU}-${randId(6)}`;

  let speechEndAt        = null;
  let gotTurnChanged     = false;
  let gotAiSummary       = false;
  const AI_TIMEOUT_MS    = 12000; // 12초 내 AI_SUMMARY 기대

  ws.connect(WS_URL, {}, function (socket) {
    socket.setTimeout(() => {
      turnChangedAfterSpeech.add(gotTurnChanged);
      aiSummaryReceived.add(gotAiSummary);
      socket.close();
    }, 100000);

    socket.on('message', (raw) => {
      const s = String(raw);
      if (s.startsWith('CONNECTED')) {
        socket.send(stompSubscribe(SUB_ROOM, 'sub-room'));
        socket.send(stompSubscribe(SUB_SPEAKER, 'sub-speaker'));
        socket.send(stompSubscribe(SUB_AI, 'sub-ai'));
        socket.send(stompSend('/pub/debate/join', {
          roomUuid,
          role: 'AUDIENCE',
          nonce,
        }));
      }

      if (s.startsWith('MESSAGE')) {
        const dest = getStompDestination(s);
        const msg  = parseStompBody(s);
        if (!msg) return;

        // speech/end 전송 시각 공유 (브로드캐스트로 전달된다고 가정)
        if (dest === SUB_ROOM && msg.type === 'SPEECH_END_ACK' && msg.sentAt) {
          speechEndAt = msg.sentAt;
        }

        if (dest === SUB_SPEAKER && msg.type === 'TURN_CHANGED') {
          gotTurnChanged = true;
          if (DEBUG) console.log(`[VU ${__VU}] TURN_CHANGED turnIndex=${msg.turnIndex}`);
        }

        if (dest === SUB_AI && msg.type === 'AI_SUMMARY') {
          gotAiSummary = true;
          if (speechEndAt) {
            aiSummaryLatency.add(Date.now() - speechEndAt);
          }
          if (DEBUG) console.log(`[VU ${__VU}] AI_SUMMARY 수신`);
        }
      }
    });

    socket.send(stompConnect());
  });
}
