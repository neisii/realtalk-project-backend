// tests/prod.aoudience-multi-chat.sockjs.js
//
// 실서버(Nginx + Spring SockJS) 대응 버전
// - SockJS /info 조회 → serverId/sessionId 생성 → SockJS-WebSocket 경로로 접속
// - JOIN_ACCEPTED(내 nonce) 확인 후 랜덤 횟수 채팅 전송, 에코 체크
//
// 실행 예)
// k6 run tests/audience-multi-chat.sockjs.js \
//   -e BASE_URL=https://api.realtalks.co.kr:8443 \
//   -e WS_PATH=/ws-debate \
//   -e ROOM=b6610d9d-175c-477a-bdab-c86972ce4c03 \
//   -e ORIGIN=https://www.realtalks.co.kr \
//   -e TAG='[k6-prod]' \
//   -e VUS=10 -e DUR=30s -e DEBUG=false
//
// 기본값: BASE_URL=https://api.realtalks.co.kr:8443, WS_PATH=/ws-debate, ORIGIN=BASE_URL

import http from 'k6/http';
import ws from 'k6/ws';
import { Rate } from 'k6/metrics';
import { sleep } from 'k6';
import { randomIntBetween } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';

// ====== ENV / 기본값 ======
const BASE_URL = __ENV.BASE_URL || 'https://api.realtalks.co.kr:8443';
const WS_PATH  = __ENV.WS_PATH  || '/ws-debate';
const ROOM     = __ENV.ROOM     || 'b6610d9d-175c-477a-bdab-c86972ce4c03';
const ORIGIN   = __ENV.ORIGIN   || BASE_URL; // 일부 프록시/보안설정에서 필요할 수 있음

const TAG_PREFIX   = __ENV.TAG || '[k6]';
const VUS          = (__ENV.VUS && parseInt(__ENV.VUS, 10)) || 10;
const DUR          = __ENV.DUR || '30s';
const MIN_DELAY_MS = (__ENV.MIN_DELAY && parseInt(__ENV.MIN_DELAY, 10)) || 250;
const MAX_DELAY_MS = (__ENV.MAX_DELAY && parseInt(__ENV.MAX_DELAY, 10)) || 1200;
const DEBUG        = (__ENV.DEBUG || '').toLowerCase() === 'true';

// ====== k6 옵션 / 임계치 ======
export const options = {
  vus: VUS,
  duration: DUR,
  thresholds: {
    'join_accepted': ['rate>0.99'],
    'chat_echoed':   ['rate>0.99'],
  },
};

// ====== 메트릭 ======
const joinAccepted = new Rate('join_accepted');
const chatEchoed   = new Rate('chat_echoed');

// ====== 유틸 ======
function pad3(n) {
  const s = String(n);
  return s.length === 1 ? `00${s}` : (s.length === 2 ? `0${s}` : s);
}
function randId(len = 8) {
  const chars = 'abcdefghijklmnopqrstuvwxyz0123456789';
  let r = '';
  for (let i = 0; i < len; i++) r += chars[(Math.random() * chars.length) | 0];
  return r;
}
function jitter(min, max) {
  return min + Math.floor(Math.random() * (max - min + 1));
}
const PHRASES = [
  '안녕하세요!', '실시간 채팅 테스트', '메시지 에코 확인', '부하 상황 시뮬', '랜덤 문장 전송',
  '연결 상태 체크', '지연 분산 확인', '브로드캐스트 수신 중', '테스트 계속', '마지막 한마디',
];

// 서버에는 순수 JSON 배열 문자열만 보냄
function sockSend(stompFrame) {
  return JSON.stringify([stompFrame]); // '["CONNECT\\n...\\u0000"]'
}

// STOMP 프레임 헬퍼 (끝에 \u0000 필수)
function stompConnect() {
  return 'CONNECT\naccept-version:1.2\nheart-beat:10000,10000\n\n\u0000';
}
function stompSub(destination, id = 'sub-room') {
  return `SUBSCRIBE\nid:${id}\ndestination:${destination}\n\n\u0000`;
}
function stompSend(destination, bodyJson) {
  return `SEND\ndestination:${destination}\ncontent-type:application/json\n\n${bodyJson}\u0000`;
}

// SockJS 수신 프레임 파서: 'o', 'h', 또는 'a["...","..."]'
function parseSockJsMessage(raw) {
  const s = String(raw);

  // open
  if (s === 'o') return { type: 'open' };
  // heartbeat
  if (s === 'h') return { type: 'heartbeat' };

  // data array
  if (s.startsWith('a[')) {
    try {
      const arr = JSON.parse(s.slice(1)); // parse ["...","..."]
      return { type: 'data', frames: arr }; // frames: array of STOMP strings
    } catch (_) {
      return { type: 'bad' };
    }
  }

  return { type: 'unknown' };
}

// STOMP MESSAGE 프레임의 body(JSON) 추출
function parseStompMessageBody(stompStr) {
  // STOMP 프레임은 \n\n 이후가 body, 끝은 \u0000
  const parts = stompStr.split('\n\n');
  if (parts.length < 2) return null;
  const body = parts.slice(1).join('\n\n').replace(/\u0000$/, '');
  try {
    return JSON.parse(body);
  } catch {
    return null;
  }
}

// ====== 시나리오 본체 ======
export default function () {
  // 1) SockJS /info 조회 (브라우저도 이걸 먼저 침)
  const t = Date.now();
  const infoUrl = `${BASE_URL}${WS_PATH}/info?t=${t}`;
  const infoRes = http.get(infoUrl, { headers: { 'Origin': ORIGIN } });
  if (DEBUG) console.log(`[INFO] GET ${infoUrl} -> ${infoRes.status} len=${(infoRes.body || '').length}`);
  if (infoRes.status !== 200) {
    if (DEBUG) console.log('[INFO] /info 실패 → 종료');
    sleep(1);
    return;
  }

  // 2) SockJS 경로 구성: /{server-id}/{session-id}/websocket
  const serverId  = pad3(randomIntBetween(0, 999));
  const sessionId = randId(8);
  const wsUrl = `${BASE_URL.replace('http', 'ws')}${WS_PATH}/${serverId}/${sessionId}/websocket`;

  // per-VU 파라미터
  const nonce = `${Date.now()}-${__VU}-${randId(6)}`;
  const side  = Math.random() < 0.5 ? 'A' : 'B';
  const chatCount = randomIntBetween(3, 10);
  const sessionHoldMs = Math.max(30000, chatCount * MAX_DELAY_MS + 5000) + 2000; // 최소 30초 + 버퍼

  let gotJoin = false;
  let echoed = 0;

  const headers = { 'Origin': ORIGIN };

  ws.connect(wsUrl, { headers }, function (socket) {
    if (DEBUG) console.log(`[WS OPEN] url=${wsUrl} side=${side} chats=${chatCount} hold=${sessionHoldMs}ms`);
    socket.setTimeout(() => socket.close(), sessionHoldMs);

    // 서버가 먼저 'o'(open)를 보내고, 그 다음부터 우리가 데이터를 보낼 수 있음
    socket.on('message', (raw) => {
      const parsed = parseSockJsMessage(raw);

      if (parsed.type === 'open') {
        // SockJS open 수신 → 이제 STOMP CONNECT 전송
        const connectFrame = stompConnect();
        socket.send(sockSend(connectFrame));
        if (DEBUG) console.log('[STOMP] CONNECT sent');
        return;
      }

      if (parsed.type === 'heartbeat') {
        // h (heartbeat) 무시
        return;
      }

      if (parsed.type === 'data' && parsed.frames?.length) {
        // frames: STOMP 문자열 배열
        for (const f of parsed.frames) {
          // CONNECTED 수신 → SUBSCRIBE → JOIN
          if (f.startsWith('CONNECTED')) {
            const subFrame = stompSub(`/sub/debate-room/${ROOM}`, `sub-${__VU}-${randId(4)}`);
            socket.send(sockSend(subFrame));

            const joinBody = JSON.stringify({ roomId: ROOM, role: 'AUDIENCE', side, nonce });
            const joinFrame = stompSend('/pub/debate/join', joinBody);
            socket.send(sockSend(joinFrame));

            if (DEBUG) console.log('[STOMP] SUBSCRIBE + JOIN sent');
            continue;
          }

          // MESSAGE 프레임 처리
          if (f.startsWith('MESSAGE') && f.includes(`/sub/debate-room/${ROOM}`)) {
            const msg = parseStompMessageBody(f);
            if (!msg) continue;

            // JOIN_ACCEPTED(내 nonce) → 이후 채팅 예약 발사
            if (!gotJoin && msg.type === 'JOIN_ACCEPTED' && msg.nonce === nonce) {
              gotJoin = true;

              let offset = 0;
              const cutoff = sessionHoldMs - 2000; // 종료 2초 전까지만 전송 예약
              for (let i = 0; i < chatCount; i++) {
                const delay = jitter(MIN_DELAY_MS, MAX_DELAY_MS);
                offset += delay;
                if (offset > cutoff) break;

                const text = `${TAG_PREFIX}[${__VU}][side:${side}] ${PHRASES[(Math.random() * PHRASES.length) | 0]} #${i + 1}`;
                socket.setTimeout(() => {
                  const chatBody = JSON.stringify({ roomId: ROOM, message: text, type: 'CHAT' });
                  const chatFrame = stompSend('/pub/chat/message', chatBody);
                  socket.send(sockSend(chatFrame));
                }, offset);
              }
              continue;
            }

            // 내 메시지 에코 체크
            if (msg.type === 'CHAT' && typeof msg.message === 'string'
              && msg.message.includes(`${TAG_PREFIX}[${__VU}]`)) {
              echoed++;
            }
          }
        }
        return;
      }

      // 그 외 (unknown/bad) → 무시
      if (DEBUG) console.log(`[WS RAW] ${String(raw).slice(0,120)}...`);
    });

    socket.on('error', (e) => {
      if (DEBUG) console.log(`[WS ERROR] ${JSON.stringify(e)}`);
    });

    socket.on('close', () => {
      joinAccepted.add(gotJoin, { room: ROOM, side });
      chatEchoed.add(echoed > 0, { room: ROOM, side, echoed: String(echoed) });
      if (DEBUG) console.log(`[WS CLOSE] join=${gotJoin} echoed=${echoed} side=${side}`);
    });
  });
}

