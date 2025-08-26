// tests/audience-chat-realistic.js
import ws from 'k6/ws';
import { sleep } from 'k6';
import { CFG, stomp, pickPhrase, jitter } from '../data/audience-chat-params.js';

export let options = {
  vus: __ENV.VUS ? parseInt(__ENV.VUS, 10) : 10,
  duration: __ENV.DUR || '20s',
};

export default function () {
  ws.connect(CFG.WS_URL, {}, function (socket) {
    // CONNECT
    socket.send(stomp(
`CONNECT
accept-version:1.2
host:localhost
\n`));

    let connected = false;

    socket.on('message', (raw) => {
      const data = String(raw);

      if (!connected && data.includes('CONNECTED')) {
        connected = true;

        // SUBSCRIBE (브라우저 로그와 동일 채널)
        socket.send(stomp(
`SUBSCRIBE
id:sub-expire
destination:${CFG.SUB_DEBATE_EXPIRE(CFG.ROOM)}
\n`));
        socket.send(stomp(
`SUBSCRIBE
id:sub-speaker-expire
destination:${CFG.SUB_SPEAKER_EXPIRE(CFG.ROOM)}
\n`));
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
        socket.send(stomp(
`SUBSCRIBE
id:sub-speaker
destination:${CFG.SUB_SPEAKER(CFG.ROOM)}
\n`));
        socket.send(stomp(
`SUBSCRIBE
id:sub-ai
destination:${CFG.SUB_AI(CFG.ROOM)}
\n`));

        // JOIN (게스트 관중, 사이드 A 기본)
        const joinBody = JSON.stringify({
          roomId: CFG.ROOM,
          role: 'AUDIENCE',
          side: CFG.SIDE,
          nonce: `${Date.now()}-${__VU}`,
        });
        socket.send(stomp(
`SEND
destination:${CFG.APP}/debate/join
content-type:application/json
\n${joinBody}`));

        // 채팅 N회 전송 (무작위 지연 + 랜덤 문구 + 접두사)
        let offset = 0;
        for (let i = 0; i < CFG.MESSAGES_PER_VU; i++) {
          const delay = jitter(CFG.MIN_DELAY_MS, CFG.MAX_DELAY_MS);
          offset += delay;

          socket.setTimeout(() => {
            const phrase = pickPhrase();
            const chatBody = JSON.stringify({
              roomId: CFG.ROOM,
              message: `${CFG.TAG_PREFIX}[${__VU}] ${phrase} @${new Date().toLocaleTimeString()}`,
              type: 'CHAT', // 서버가 type 채워도 무방
            });

            socket.send(stomp(
`SEND
destination:${CFG.APP}/chat/message
content-type:application/json
\n${chatBody}`));
          }, offset);
        }
      }
    });

    // 충분히 대기 후 종료
    sleep(5);
    socket.close();
  });
}

