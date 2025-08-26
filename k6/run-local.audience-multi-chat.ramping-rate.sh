#!/bin/bash

set-e

# run-local.audience-multi-chat.ramping-rate.sh
# ROOM: 토론방 공유용 UUID
# VUS: 초당 한번에 보낼 요청 수
# DUR: 몇 초(s)동안 시나리오를 수행할지 시간


k6 run tests/local.audience-multi-chat.ramping-rate.js \
  -e ROOM=b6610d9d-175c-477a-bdab-c86972ce4c03 \
#  -e WS_URL=ws://localhost:8080/ws-stomp/websocket \
#  -e APP=/pub -e TAG='[k6-rate]' -e DEBUG=true \
#  -e STEPS=10 -e STEP_DUR=5s -e MIN_RATE=2 -e MAX_RATE=8 \
#  -e PRE_VU=50 -e MAX_VU=200

