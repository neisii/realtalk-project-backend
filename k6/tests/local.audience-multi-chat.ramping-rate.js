// tests/local.audience-multi-chat.ramping-rate.js
/**
초당 입장 속도를 랜덤으로

로그 예시
[MON] vusActive=17, vusInitialized=25
[MON] vusActive=9,  vusInitialized=25
[MON] vusActive=22, vusInitialized=25

vusActive: 지금 돌고 있는 VU 수
vusInitialized: 이미 생성된 VU 총수(풀 크기)
*/


import ws from 'k6/ws';
import { Rate } from 'k6/metrics';
import exec from 'k6/execution';
import { sleep } from 'k6';
import { randomIntBetween } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';
import { CFG, stomp, pickPhrase, jitter } from '../data/audience-chat-params.js';

// ----- 랜덤 입장(도착) rate 스테이지 구성 -----
const STEPS      = parseInt(__ENV.STEPS || '12', 10);
const STEP_DUR   = __ENV.STEP_DUR || '5s';
const MIN_RATE   = parseInt(__ENV.MIN_RATE || '2', 10);   // 초당 최소 시작 수
const MAX_RATE   = parseInt(__ENV.MAX_RATE || '10', 10);  // 초당 최대 시작 수
const PRE_VU     = parseInt(__ENV.PRE_VU || '100', 10);   // 풀로 미리 잡아둘 VU
const MAX_VU     = parseInt(__ENV.MAX_VU || '500', 10);   // 상한

const RATE_STAGES = Array.from({ length: STEPS }, () => ({
  duration: STEP_DUR,
  target: randomIntBetween(MIN_RATE, MAX_RATE), // 초당 새로 시작할 iteration 수
}));

export const options = {
  scenarios: {
    ws: {
      executor: 'ramping-arrival-rate',
      startRate: 0,
      timeUnit: '1s',
      preAllocatedVUs: PRE_VU,
      maxVUs: MAX_VU,
      stages: RATE_STAGES,
      exec: 'default', // 아래 export default function 실행
    },
    mon: {
      executor: 'constant-vus',
      vus: 1,
      duration: (STEPS * (parseInt(STEP_DUR) ? parseInt(STEP_DUR) : 5)) + 's', // 간단 계산
      exec: 'monitor',
    },
  },
  thresholds: {
    'join_accepted': ['rate>0.99'],
    'chat_echoed':   ['rate>0.99'],
  },
};

const joinAccepted = new Rate('join_accepted');
const chatEchoed   = new Rate('chat_echoed');
const DEBUG = (__ENV.DEBUG || '').toLowerCase() === 'true';

// 모니터 함수
export function monitor() {
  for (let i = 0; i < 1e9; i++) {
    console.log(`[MON] vusActive=${exec.instance.vusActive}, iterationsCompleted=${exec.instance.iterationsCompleted}`);
    sleep(1);
  }
}

// SockJS 엔드포인트 자동 보정
const WS_URL = (CFG.WS_URL.includes('/ws-stomp') || CFG.WS_URL.endsWith('/websocket'))
  ? CFG.WS_URL
  : CFG.WS_URL.replace('/ws', '/ws-stomp/websocket');

export default function () {
  const nonce = `${Date.now()}-${__VU}-${Math.random().toString(36).slice(2,8)}`;
  const chatCount = randomIntBetween(3, 10);                 // 1인당 3~10회
  const baseHold  = Math.max(30000, chatCount * CFG.MAX_DELAY_MS + 5000);
  const sessionHoldMs = baseHold + 2000;                     // 종료 버퍼 +2s
  let gotJoin = false;
  let echoed = 0;

  // 🔀 A/B 랜덤 선택
  const side = Math.random() < 0.5 ? 'A' : 'B';

  ws.connect(WS_URL, {}, function (socket) {
    socket.setTimeout(() => socket.close(), sessionHoldMs);

    // STOMP CONNECT
    socket.send(stomp(
`CONNECT
accept-version:1.2
host:localhost
\n`));

    socket.on('message', (raw) => {
      const s = String(raw);
      if (DEBUG) console.log(`[VU ${__VU}] <<< ${s.slice(0,120).replace(/\n/g,'|')}...`);

      if (s.startsWith('CONNECTED')) {
        // 구독 → JOIN
        socket.send(stomp(
`SUBSCRIBE
id:sub-room
destination:${CFG.SUB_CHAT(CFG.ROOM)}
\n`));

        const joinBody = JSON.stringify({ roomId: CFG.ROOM, role: 'AUDIENCE', side, nonce });
        socket.send(stomp(
`SEND
destination:${CFG.APP}/debate/join
content-type:application/json
\n${joinBody}`));
      }

      // 방 브로드캐스트 수신
      if (s.startsWith('MESSAGE') && s.includes(`destination:${CFG.SUB_CHAT(CFG.ROOM)}`)) {
        const body = s.split('\n\n')[1]?.replace('\0','') || '';
        try {
          const msg = JSON.parse(body);

          if (!gotJoin && msg?.type === 'JOIN_ACCEPTED' && msg?.nonce === nonce) {
            gotJoin = true;

            // JOIN 후 N회 메시지 예약 전송
            let offset = 0;
            const latestSendLimit = sessionHoldMs - 2000; // 종료 2초 전까지
            for (let i = 0; i < chatCount; i++) {
              const delay = jitter(CFG.MIN_DELAY_MS, CFG.MAX_DELAY_MS);
              offset += delay;
              if (offset > latestSendLimit) break;

              const text = `${CFG.TAG_PREFIX}[${__VU}][side:${side}] ${pickPhrase()} #${i + 1}`;
              socket.setTimeout(() => {
                const chatBody = JSON.stringify({ roomId: CFG.ROOM, message: text, type: 'CHAT' });
                socket.send(stomp(
`SEND
destination:${CFG.APP}/chat/message
content-type:application/json
\n${chatBody}`));
              }, offset);
            }
          }

          // 내 메시지 에코 확인
          if (msg?.type === 'CHAT' && typeof msg.message === 'string'
              && msg.message.includes(`${CFG.TAG_PREFIX}[${__VU}]`)) {
            echoed++;
          }
        } catch (_) {}
      }
    });

    socket.on('close', () => {
      joinAccepted.add(gotJoin, { room: CFG.ROOM, side });
      chatEchoed.add(echoed > 0, { room: CFG.ROOM, side, echoed: String(echoed) });
      if (DEBUG) console.log(`[VU ${__VU}] close: side=${side} join=${gotJoin} echoed=${echoed}`);
    });
  });
}

