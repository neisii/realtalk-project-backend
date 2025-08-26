// tests/local.audience-join-only.js

import ws from 'k6/ws';
import { Rate } from 'k6/metrics';
import { CFG, stomp, jitter } from '../data/audience-chat-params.js';

const joinAccepted = new Rate('join_accepted');
const DEBUG = (__ENV.DEBUG || '').toLowerCase() === 'true';

export let options = {
  vus: __ENV.VUS ? parseInt(__ENV.VUS, 10) : 20,
  duration: __ENV.DUR || '30s',
  thresholds: { 'join_accepted': ['rate>0.95'] },
};

export default function () {
  const nonce = `${Date.now()}-${__VU}-${Math.random().toString(36).slice(2,8)}`;
  let gotAccepted = false;

  ws.connect(CFG.WS_URL, {}, function (socket) {
    // 세션을 최소 2~3초 유지 (여유 주고 닫기)
    const holdMs = jitter(2000, 3000);
    socket.setTimeout(() => { socket.close(); }, holdMs);

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
        socket.send(stomp(
`SUBSCRIBE
id:sub-chat
destination:${CFG.SUB_CHAT(CFG.ROOM)}
\n`));
        socket.send(stomp(
`SUBSCRIBE
id:sub-participants
destination:${CFG.SUB_PARTICIPANTS(CFG.ROOM)}
\n`));
        socket.send(stomp(
`SUBSCRIBE
id:sub-side-stats
destination:${CFG.SUB_SIDE_STATS(CFG.ROOM)}
\n`));

        const joinBody = JSON.stringify({
          roomId: CFG.ROOM,
          role: 'AUDIENCE',
          side: CFG.SIDE,
          nonce: nonce,
        });
        socket.send(stomp(
`SEND
destination:${CFG.APP}/debate/join
content-type:application/json
\n${joinBody}`));
      }

      // JOIN_ACCEPTED 매칭
      if (s.startsWith('MESSAGE') && s.includes(`destination:${CFG.SUB_CHAT(CFG.ROOM)}`)) {
        const body = s.split('\n\n')[1]?.replace('\0','') || '';
        try {
          const msg = JSON.parse(body);
          if (msg?.type === 'JOIN_ACCEPTED' && msg?.nonce === nonce) {
            gotAccepted = true;
            if (DEBUG) console.log(`[VU ${__VU}] JOIN_ACCEPTED ok (nonce=${nonce})`);
          }
        } catch (_) {}
      }
    });

    socket.on('close', () => {
      joinAccepted.add(gotAccepted, { room: CFG.ROOM });
      if (DEBUG && !gotAccepted) console.log(`[VU ${__VU}] JOIN_ACCEPTED not received`);
    });
  });
}

