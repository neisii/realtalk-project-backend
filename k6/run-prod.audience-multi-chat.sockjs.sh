#!/bin/bash

# run-prod.audience-multi-chat.sockjs.sh
# ROOM: 토론방 공유용 UUID
# VUS: 초당 한번에 보낼 요청 수
# DUR: 몇 초(s)동안 시나리오를 수행할지 시간
# 설정 가능한 환경 변수는 시나리오 파일에 __ENV.* 참고

set -e

k6 run tests/prod.audience-multi-chat.sockjs.js \
  -e ROOM=9e3a81c2-e61e-4dbf-b269-0734785ec5d4 \
  -e ORIGIN=https://www.realtalks.co.kr
