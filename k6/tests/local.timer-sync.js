// tests/local.timer-sync.js
/**
 * 타이머 동기화(drift) 측정 테스트
 *
 * 목표: 같은 방의 여러 클라이언트가 받은 TIMER_TICK의 배달 지연을 측정한다.
 *       서버가 1초마다 serverTimestamp를 포함한 TIMER_TICK을 push하므로,
 *       `Date.now() - serverTimestamp` = 배달 지연을 측정할 수 있다.
 *       P95 지연 < 500ms면 모든 클라이언트가 동일한 remainingSeconds를 봄이 보장된다.
 *
 * 커스텀 메트릭:
 *   - timer_delivery_ms: TIMER_TICK 배달 지연 (임계값: p(95)<500)
 *   - timer_in_sync:     지연 < 1000ms인 tick 비율 (임계값: rate>0.99)
 *   - timer_tick_total:  수신한 총 tick 수 (정보용)
 *
 * 환경 변수:
 *   ROOM    - 토론방 UUID (STARTED 상태여야 함)
 *   WS_URL  - WebSocket URL (기본: ws://localhost:8080/ws-stomp/websocket)
 *   VUS     - 동시 접속 VU 수 (기본: 20)
 *   DUR     - 테스트 시간 (기본: 60s)
 *
 * 주의: TIMER_TICK 메시지에 serverTimestamp(ISO-8601) 필드가 있어야 측정 가능.
 *       서버 SPEC: WsMessage<TimerTickPayload> 참조.
 */

import ws from 'k6/ws';
import { Trend, Rate, Counter } from 'k6/metrics';
import {
  stompConnect, stompSubscribe, stompSend,
  parseStompBody, getStompDestination, randId,
} from '../data/stomp-helpers.js';

const ROOM   = __ENV.ROOM   || 'REPLACE_WITH_STARTED_ROOM_UUID';
const WS_URL = __ENV.WS_URL || 'ws://localhost:8080/ws-stomp/websocket';
const VUS    = parseInt(__ENV.VUS || '20', 10);
const DUR    = __ENV.DUR    || '60s';
const DEBUG  = (__ENV.DEBUG || '').toLowerCase() === 'true';

const SUB_ROOM  = `/sub/debate-room/${ROOM}`;
const SUB_TIMER = `/topic/debate/${ROOM}/timer`;

const timerDelivery = new Trend('timer_delivery_ms');
const timerInSync   = new Rate('timer_in_sync');
const timerTotal    = new Counter('timer_tick_total');

export const options = {
  vus: VUS,
  duration: DUR,
  thresholds: {
    'timer_delivery_ms': ['p(95)<500'],
    'timer_in_sync':     ['rate>0.99'],
  },
};

export default function () {
  const nonce = `timer-${__VU}-${randId(6)}`;

  ws.connect(WS_URL, {}, function (socket) {
    socket.setTimeout(() => socket.close(), 55000);

    socket.on('message', (raw) => {
      const s = String(raw);

      if (s.startsWith('CONNECTED')) {
        socket.send(stompSubscribe(SUB_ROOM, 'sub-room'));
        socket.send(stompSubscribe(SUB_TIMER, 'sub-timer'));

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

        if (dest === SUB_TIMER && msg.type === 'TIMER_TICK') {
          const receivedAt = Date.now();
          timerTotal.add(1);

          if (msg.serverTimestamp) {
            const serverTs = new Date(msg.serverTimestamp).getTime();
            const deliveryMs = receivedAt - serverTs;

            timerDelivery.add(deliveryMs);
            timerInSync.add(deliveryMs < 1000);

            if (DEBUG) {
              console.log(
                `[VU ${__VU}] TIMER_TICK remain=${msg.debateRemainingSeconds}s ` +
                `delivery=${deliveryMs}ms`
              );
            }
          } else {
            // serverTimestamp 없으면 측정 불가 — 서버 구현 오류로 기록
            timerInSync.add(false);
            if (DEBUG) console.warn(`[VU ${__VU}] TIMER_TICK에 serverTimestamp 없음`);
          }
        }
      }
    });

    socket.on('error', (e) => {
      if (DEBUG) console.error(`[VU ${__VU}] 오류: ${JSON.stringify(e)}`);
    });

    socket.send(stompConnect());
  });
}
