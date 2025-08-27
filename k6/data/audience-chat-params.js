// data/audience-chat-params.js
// k6 공통 파라미터 & 유틸 (브라우저 콘솔 로그 기준으로 정리)

const DEFAULTS = {
  WS_URL: 'ws://localhost:8080/ws', // 브라우저 로그 기준
  APP: '/pub',                      // STOMP SEND prefix
  ROOM: 'b6610d9d-175c-477a-bdab-c86972ce4c03',       // 실행 시 -e ROOM=... 로 덮어쓰기
  MESSAGES_PER_VU: 5,
  MIN_DELAY_MS: 200,
  MAX_DELAY_MS: 1200,
  TAG_PREFIX: '[k6]',
  SIDE: 'A',                        // 기본 사이드 A (로그 기준)
};

// 100개 샘플 문구
const PHRASES = [
  "안녕하세요!", "오늘 날씨 좋네요", "점심 뭐 드셨어요?", "테스트 중입니다",
  "채팅 잘 보이나요?", "실제로는 어떤지 궁금합니다", "여기 분위기 최고네요", "반갑습니다",
  "프론트엔드 연결 확인!", "백엔드 응답 속도 체크", "WebSocket 연결 굿", "데이터 잘 오가네요",
  "k6 테스트 메시지", "실제 유저처럼 보이나요?", "랜덤 채팅입니다", "채팅 UI 확인해주세요",
  "성능테스트 중", "동시접속자 시뮬레이션", "DB 저장도 확인해보세요", "이 메시지 보이시나요?",
  "Redis 세션 동작 확인", "스트레스 테스트", "리얼 유저 느낌", "가짜 메시지입니다",
  "프론트 로그 확인하세요", "백엔드 로깅 확인", "UI 촬영용 메시지", "랜덤 멘트 테스트",
  "속도 괜찮나요?", "지연 없는지 체크", "연결 상태 양호", "오늘 기분 어때요?",
  "테스트 반복 메시지", "응답 잘 와요", "UI에 보이면 성공", "k6 부하테스트 메시지",
  "로컬 환경 점검", "JWT 헤더 문제 없나요?", "권한 체크 메시지", "관중 모드 테스트",
  "발언자 아닌 메시지", "UI 확인 부탁", "프론트 채팅창 캡처", "랜덤 유저 대화",
  "샘플 채팅 1", "샘플 채팅 2", "샘플 채팅 3", "샘플 채팅 4",
  "임의 문구 1", "임의 문구 2", "임의 문구 3", "임의 문구 4",
  "테스트 문장 A", "테스트 문장 B", "테스트 문장 C", "테스트 문장 D",
  "커넥션 유지 확인", "끊김 없는지 확인", "지연 발생 체크", "랜덤 문구",
  "부하테스트 메시지 A", "부하테스트 메시지 B", "부하테스트 메시지 C", "부하테스트 메시지 D",
  "실제 채팅 시뮬레이션", "랜덤 인삿말", "무작위 문장", "채팅 메시지 예시",
  "부하테스트용 대화", "UI 테스트 메시지", "랜덤 텍스트", "데이터 흐름 확인",
  "관중 참여 메시지", "테스트 유저 발언", "랜덤 대화 입력", "메시지 전송 체크",
  "서비스 점검 중", "시스템 응답 체크", "랜덤 발언 1", "랜덤 발언 2",
  "이벤트 잘 뜨나요?", "알림 확인 메시지", "세션 정상 확인", "UI 반영 확인",
  "콘솔 확인 메시지", "로깅 상태 확인", "임시 채팅 발언", "랜덤 대사",
  "테스트 루프 메시지", "반복 전송 확인", "랜덤 송출", "UI 연동 상태 체크",
  "백엔드 성능 확인", "프론트 연결 검증", "랜덤 메시지 전송", "테스트 종료 직전",
  "대화 시뮬레이션", "채팅창 표시 확인", "랜덤 문구 생성", "마지막 문구"
];

function cfgFromEnv() {
  const e = __ENV || {};
  const int = (v, d) => (v !== undefined ? parseInt(v, 10) : d);
  return {
    WS_URL: e.WS_URL || DEFAULTS.WS_URL,
    APP: e.APP || DEFAULTS.APP,
    ROOM: e.ROOM || DEFAULTS.ROOM,
    MESSAGES_PER_VU: int(e.MSGS, DEFAULTS.MESSAGES_PER_VU),
    MIN_DELAY_MS: int(e.MIN_DELAY, DEFAULTS.MIN_DELAY_MS),
    MAX_DELAY_MS: int(e.MAX_DELAY, DEFAULTS.MAX_DELAY_MS),
    TAG_PREFIX: e.TAG || DEFAULTS.TAG_PREFIX,
    SIDE: e.SIDE || DEFAULTS.SIDE,
  };
}

const BASE = cfgFromEnv();

// 브라우저 로그 기준 구독 채널
const SUB_CHAT            = (room) => `/sub/debate-room/${room}`;
const SUB_PARTICIPANTS    = (room) => `/sub/debate-room/${room}/participants`;
const SUB_SIDE_STATS      = (room) => `/sub/debate-room/${room}/side-stats`;
const SUB_DEBATE_EXPIRE   = (room) => `/topic/debate/${room}/expire`;
const SUB_SPEAKER_EXPIRE  = (room) => `/topic/speaker/${room}/expire`;
const SUB_SPEAKER         = (room) => `/topic/speaker/${room}`;
const SUB_AI              = (room) => `/topic/ai/${room}`;

function stomp(frame) { return frame.replace(/\n/g, '\r\n') + '\0'; }
function pickPhrase() { return PHRASES[(Math.random() * PHRASES.length) | 0]; }
function jitter(min, max) { return min + Math.floor(Math.random() * (max - min + 1)); }

export const CFG = {
  ...BASE,
  SUB_CHAT,
  SUB_PARTICIPANTS,
  SUB_SIDE_STATS,
  SUB_DEBATE_EXPIRE,
  SUB_SPEAKER_EXPIRE,
  SUB_SPEAKER,
  SUB_AI,
  PHRASES,
};

export { stomp, pickPhrase, jitter };

