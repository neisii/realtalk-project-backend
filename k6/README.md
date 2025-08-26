# 파일 구분

> 1. local: 로컬 서버
2. prod: 상용 서버(nginx + backend + frontend)


# 디렉토리 구분
> 1. scenario: 시나리오 스크립트
2. data: 시나리오 수행하는데 필요한 설정

# 사용 방법
> 1. 루트의 셸 파일 실행

# k6 설치(WLS2 기준)
# 최신 k6 설치
sudo gpg -k || true
sudo apt-get update
sudo apt-get install -y ca-certificates gnupg
sudo mkdir -p /etc/apt/keyrings
curl -fsSL https://dl.k6.io/key.gpg | sudo gpg --dearmor -o /etc/apt/keyrings/k6-archive-keyring.gpg
echo "deb [signed-by=/etc/apt/keyrings/k6-archive-keyring.gpg] https://dl.k6.io/deb stable main" | sudo tee /etc/apt/sources.list.d/k6.list
sudo apt-get update && sudo apt-get install -y k6

k6 version
