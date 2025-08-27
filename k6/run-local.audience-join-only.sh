#!/bin/bash

# run-local.audience-join-only.sh
# ROOM: 토론방 공유용 UUID 
# VUS: 초당 한번에 보낼 요청 수
# DUR: 몇 초(s)동안 시나리오를 수행할지 시간
# 설정 가능한 환경 변수는 시나리오 파일에 __ENV.* 참고

set -e

k6 run tests/local.audience-join-only.js \
  -e ROOM=b6610d9d-175c-477a-bdab-c86972ce4c03 \
  -e WS_URL=ws://localhost:8080/ws-stomp/websocket \
  -e APP=/pub -e SIDE=A -e VUS=100 -e DUR=20s

