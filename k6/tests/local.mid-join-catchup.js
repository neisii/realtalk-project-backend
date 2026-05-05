// tests/local.mid-join-catchup.js
/**
 * 중간 입장 State Catch-up 테스트
 *
 * 목표: STARTED 상태의 방에 중간 입장 시, JOIN_ACCEPTED 응답의 snapshot 필드에
 *       현재 상태(타이머, 발언자, 참가자 목록)가 올바르게 포함되는지 검증한다.
 *
 * 커스텀 메트릭:
 *   - snapshot_received:       JOIN_ACCEPTED에 snapshot 포함 여부 (임계값: rate>0.999)
 *   - snapshot_timer_valid:    snapshot.debateRemainingSeconds > 0 여부 (임계값: rate>0.999)
 *   - snapshot_speaker_valid:  snapshot.currentSpeaker != null 여부 (임계값: rate>0.999)
 *   - join_to_snapshot_ms:     JOIN 전송 → snapshot 수신 지연 (임계값: p(95)<500)
 *
 * 환경 변수:
 *   ROOM    - 이미 STARTED 상태인 토론방 UUID
 *   WS_URL  - WebSocket URL (기본: ws://localhost:8080/ws-stomp/websocket)
 *   VUS     - 동시 입장 VU 수 (기본: 30)
 *   DUR     - 테스트 시간 (기본: 60s)
 *
 * 전제 조건: 테스트 실행 전 방이 STARTED 상태여야 하며, 발언자가 1명 이상 있어야 한다.
 */

import ws from 'k6/ws';
import { Rate, Trend } from 'k6/metrics';
import {
  stompConnect, stompSubscribe, stompSend,
  parseStompBody, getStompDestination, randId,
} from '../data/stomp-helpers.js';

const ROOM   = __ENV.ROOM   || 'REPLACE_WITH_STARTED_ROOM_UUID';
const WS_URL = __ENV.WS_URL || 'ws://localhost:8080/ws-stomp/websocket';
const VUS    = parseInt(__ENV.VUS || '30', 10);
const DUR    = __ENV.DUR    || '60s';
const DEBUG  = (__ENV.DEBUG || '').toLowerCase() === 'true';

const SUB_ROOM = `/sub/debate-room/${ROOM}`;

const snapshotReceived    = new Rate('snapshot_received');
const snapshotTimerValid  = new Rate('snapshot_timer_valid');
const snapshotSpeakerValid = new Rate('snapshot_speaker_valid');
const joinToSnapshotMs    = new Trend('join_to_snapshot_ms');

export const options = {
  vus: VUS,
  duration: DUR,
  thresholds: {
    'snapshot_received':       ['rate>0.999'],
    'snapshot_timer_valid':    ['rate>0.999'],
    'snapshot_speaker_valid':  ['rate>0.999'],
    'join_to_snapshot_ms':     ['p(95)<500'],
  },
};

export default function () {
  const nonce    = `catchup-${__VU}-${randId(6)}`;
  let joinSentAt = null;
  let done       = false;

  ws.connect(WS_URL, {}, function (socket) {
    socket.setTimeout(() => {
      if (!done) {
        // 타임아웃 내 JOIN_ACCEPTED 미수신
        snapshotReceived.add(false);
        snapshotTimerValid.add(false);
        snapshotSpeakerValid.add(false);
      }
      socket.close();
    }, 10000);

    socket.on('message', (raw) => {
      const s = String(raw);

      if (s.startsWith('CONNECTED')) {
        socket.send(stompSubscribe(SUB_ROOM, 'sub-room'));
        joinSentAt = Date.now();
        socket.send(stompSend('/pub/debate/join', {
          roomUuid: ROOM,
          role: 'AUDIENCE',
          nonce,
        }));
      }

      if (!done && s.startsWith('MESSAGE')) {
        const dest = getStompDestination(s);
        const msg  = parseStompBody(s);
        if (!msg) return;

        if (dest === SUB_ROOM && msg.type === 'JOIN_ACCEPTED' && msg.nonce === nonce) {
          done = true;
          const receivedAt = Date.now();
          if (joinSentAt) joinToSnapshotMs.add(receivedAt - joinSentAt);

          const snap = msg.snapshot;

          const hasSnapshot = snap != null;
          snapshotReceived.add(hasSnapshot);

          if (hasSnapshot) {
            const timerOk   = typeof snap.debateRemainingSeconds === 'number' && snap.debateRemainingSeconds > 0;
            const speakerOk = snap.currentSpeaker != null;

            snapshotTimerValid.add(timerOk);
            snapshotSpeakerValid.add(speakerOk);

            if (DEBUG) {
              console.log(
                `[VU ${__VU}] snapshot: status=${snap.debateStatus} ` +
                `remain=${snap.debateRemainingSeconds}s ` +
                `speaker=${JSON.stringify(snap.currentSpeaker)} ` +
                `timerOk=${timerOk} speakerOk=${speakerOk}`
              );
            }
          } else {
            snapshotTimerValid.add(false);
            snapshotSpeakerValid.add(false);
            console.warn(`[VU ${__VU}] JOIN_ACCEPTED에 snapshot 없음`);
          }

          socket.close();
        }
      }
    });

    socket.on('error', (e) => {
      if (DEBUG) console.error(`[VU ${__VU}] 오류: ${JSON.stringify(e)}`);
    });

    socket.send(stompConnect());
  });
}
