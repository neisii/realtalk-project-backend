// tests/local.audience-multi-chat.js

import ws from 'k6/ws';
import { Rate } from 'k6/metrics';
import { randomIntBetween } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';
import { CFG, stomp, pickPhrase, jitter } from '../data/audience-chat-params.js';

export const options = {
  vus: (__ENV.VUS && parseInt(__ENV.VUS, 10)) || randomIntBetween(5, 20), // 1~2자리 랜덤
  duration: __ENV.DUR || '40s',                                           // 전체 실행시간
  thresholds: {
    'join_accepted': ['rate>0.95'],
    'chat_echoed':   ['rate>0.90'],
  },
};

const joinAccepted = new Rate('join_accepted');
const chatEchoed   = new Rate('chat_echoed');
const DEBUG = (__ENV.DEBUG || '').toLowerCase() === 'true';

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
    // 세션 유지
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

      // CONNECTED → SUBSCRIBE → JOIN
      if (s.startsWith('CONNECTED')) {
        // 최소 구독만 사용(방 브로드캐스트)
        socket.send(stomp(
`SUBSCRIBE
id:sub-room
destination:${CFG.SUB_CHAT(CFG.ROOM)}
\n`));

        const joinBody = JSON.stringify({
          roomId: CFG.ROOM, role: 'AUDIENCE', side, nonce
        });
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

          // JOIN 수락
          if (!gotJoin && msg?.type === 'JOIN_ACCEPTED' && msg?.nonce === nonce) {
            gotJoin = true;

            // JOIN 후 N회 메시지 예약 전송
            let offset = 0;
            const latestSendLimit = sessionHoldMs - 2000; // 종료 2초 전까지
            for (let i = 0; i < chatCount; i++) {
              const delay = jitter(CFG.MIN_DELAY_MS, CFG.MAX_DELAY_MS);
              offset += delay;
              if (offset > latestSendLimit) break; // 너무 늦는 전송은 스킵

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
        } catch (_) { /* ignore */ }
      }
    });

    // 종료 시 메트릭 집계
    socket.on('close', () => {
      joinAccepted.add(gotJoin, { room: CFG.ROOM, side });
      chatEchoed.add(echoed > 0, { room: CFG.ROOM, side, echoed: String(echoed) });
      if (DEBUG) console.log(`[VU ${__VU}] close: side=${side} join=${gotJoin} echoed=${echoed}`);
    });
  });
}

