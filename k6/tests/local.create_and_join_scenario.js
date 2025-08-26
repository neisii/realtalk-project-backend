
import http from 'k6/http';
import ws from 'k6/ws';
import { check, sleep } from 'k6';
import { Counter } from 'k6/metrics';

// ----------------- 설정 (Configuration) -----------------

// 테스트 실행 전, 유효한 JWT Access Token으로 변경해야 합니다.
const ACCESS_TOKEN = 'eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IkpvaG4gRG9lIiwiaWF0IjoxNTE2MjM5MDIyfQ.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c';

// API 엔드포인트
const BASE_URL = 'http://localhost:8080'; // 테스트 대상 서버 주소

// 커스텀 지표
const wsErrorCounter = new Counter('ws_error_count');

// ----------------- 테스트 옵션 (Test Options) -----------------

export const options = {
  scenarios: {
    create_and_join: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '30s', target: 10 },
        { duration: '1m', target: 10 },
        { duration: '10s', target: 0 },
      ],
      gracefulRampDown: '30s',
    },
  },
  thresholds: {
    'http_req_failed': ['rate<0.01'],
    'http_req_duration': ['p(95)<500'],
    'ws_error_count': ['count<10'],
  },
};

// ----------------- 테스트 시나리오 (Test Scenario) -----------------

export default function () {
  const headers = {
    'Authorization': `Bearer ${ACCESS_TOKEN}`,
    'Content-Type': 'application/json',
  };

  // 1. 토론방 직접 생성
  const createRoomPayload = {
    title: `K6 Test Room - VU ${__VU}`,
    debateDescription: 'This room was created by a k6 test.',
    category: {
        id: 1, // 카테고리 ID (예시)
        name: 'Technology'
    },
    sideA: 'Pro-AI',
    sideB: 'Anti-AI',
    debateType: 'FORMAL', // FORMAL, SHORT, SIMPLE
    durationSeconds: 600, // 10분
    maxSpeaker: 6,
    maxAudience: 20
  };

  const createRes = http.post(
    `${BASE_URL}/api/debate-rooms`,
    JSON.stringify(createRoomPayload),
    { headers }
  );

  check(createRes, { 'create room request is 201': (r) => r.status === 201 });

  if (createRes.status !== 201) {
    console.error('Failed to create room. Exiting VU.');
    return;
  }

  const roomUUID = createRes.json('uuid');
  console.log(`VU ${__VU}: Created room with UUID: ${roomUUID}`);

  sleep(1); // 1초 대기

  // 2. WebSocket (STOMP) 연결 및 참여
  const url = `ws://localhost:8080/ws-stomp/websocket`;
  const res = ws.connect(url, {}, function (socket) {
    socket.on('open', () => {
      socket.send('CONNECT\naccept-version:1.2\nheart-beat:10000,10000\n\n\x00');
    });

    socket.on('message', (data) => {
      if (data.startsWith('CONNECTED')) {
        console.log(`VU ${__VU}: WebSocket STOMP connection established for room ${roomUUID}`);

        // 구독
        socket.send(`SUBSCRIBE\nid:sub-0\ndestination:/sub/debate-room/${roomUUID}\n\n\x00`);
        
        // 토론방 참가 (Join)
        const joinPayload = JSON.stringify({
          roomId: roomUUID,
          role: 'SPEAKER',
          side: 'PROS',
        });
        socket.send(`SEND\ndestination:/pub/debate/join\ncontent-type:application/json\n\n${joinPayload}\x00`);
      }
    });

    socket.on('close', () => {
      console.log(`VU ${__VU}: WebSocket connection closed.`);
    });

    socket.on('error', function (e) {
      console.error(`VU ${__VU}: An unexpected error occurred in WebSocket: `, e.error());
      wsErrorCounter.add(1);
    });

    // 15초 후 연결 종료
    socket.setTimeout(function () {
      console.log(`VU ${__VU}: Disconnecting WebSocket...`);
      socket.send('DISCONNECT\n\n\x00');
      socket.close();
    }, 15000);
  });

  check(res, { 'WebSocket handshake is successful': (r) => r && r.status === 101 });
}
