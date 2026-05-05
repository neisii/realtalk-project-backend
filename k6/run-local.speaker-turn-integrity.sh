#!/bin/bash
# run-local.speaker-turn-integrity.sh
#
# 턴 전환 무결성 테스트
# 전제: 방이 STARTED 상태이고 SPEAKER_JWT가 유효해야 합니다.
#
# 환경 변수:
#   ROOM        - STARTED 상태 토론방 UUID
#   SPEAKER_JWT - Speaker 역할 JWT Access Token
#   OBSERVERS   - Observer VU 수 (기본: 20)
#   DUR         - 테스트 시간 (기본: 60s)

set -e

: "${ROOM:?ROOM 환경변수를 설정하세요 (STARTED 상태 방 UUID)}"
: "${SPEAKER_JWT:?SPEAKER_JWT 환경변수를 설정하세요}"

k6 run tests/local.speaker-turn-integrity.js \
  -e ROOM="${ROOM}" \
  -e WS_URL=ws://localhost:8080/ws-stomp/websocket \
  -e SPEAKER_JWT="${SPEAKER_JWT}" \
  -e OBSERVERS="${OBSERVERS:-20}" \
  -e DUR="${DUR:-60s}" \
  --out json=results/turn-integrity-$(date +%Y%m%d-%H%M%S).json
