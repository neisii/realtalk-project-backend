#!/bin/bash
# run-local.timer-sync.sh
#
# 타이머 동기화(drift) 측정 테스트
# 전제: 방이 STARTED 상태여야 합니다.
#
# 환경 변수:
#   ROOM - STARTED 상태 토론방 UUID
#   VUS  - 동시 접속 VU 수 (기본: 20)
#   DUR  - 테스트 시간 (기본: 60s)

set -e

: "${ROOM:?ROOM 환경변수를 설정하세요 (STARTED 상태 방 UUID)}"

k6 run tests/local.timer-sync.js \
  -e ROOM="${ROOM}" \
  -e WS_URL=ws://localhost:8080/ws-stomp/websocket \
  -e VUS="${VUS:-20}" \
  -e DUR="${DUR:-60s}" \
  --out json=results/timer-sync-$(date +%Y%m%d-%H%M%S).json
