#!/usr/bin/env bash
#
# PlanB Market 검증 스크립트
#
# 필수 시나리오를 순서대로 돌리면서 잔액과 정합성을 매 단계 확인한다.
# 두 가지 용도로 쓴다:
#   1) 회귀 테스트  — 새 기능을 붙인 뒤 앞 단계가 안 깨졌는지
#   2) 캡처 가이드  — 📸 표시가 뜨는 지점에서 화면을 찍으면 됨
#
# 데이터를 실제로 바꾸므로 갓 띄운 앱에 대고 한 번만 돌릴 것.
# 앱을 다시 띄우면 인메모리 H2가 초기화돼서 처음부터 다시 돌릴 수 있다.
#
#   사용법:  ./scripts/verify.sh [BASE_URL]
#   기본값:  http://localhost:8080
#
set -uo pipefail

BASE=${1:-http://localhost:8080}
JAR_DIR=$(mktemp -d)
PASS=0
FAIL=0

trap 'rm -rf "$JAR_DIR"' EXIT

# ══════════════════════════════════════════════════════════════
# 출력 도우미
# ══════════════════════════════════════════════════════════════
c_ok=$'\033[32m'; c_no=$'\033[31m'; c_hd=$'\033[36m'; c_cap=$'\033[35m'; c_off=$'\033[0m'

step()  { printf "\n${c_hd}━━━ %s ━━━${c_off}\n" "$*"; }
ok()    { PASS=$((PASS+1)); printf "  ${c_ok}✓${c_off} %s\n" "$*"; }
no()    { FAIL=$((FAIL+1)); printf "  ${c_no}✗ %s${c_off}\n" "$*"; }
info()  { printf "    %s\n" "$*"; }
shot()  { printf "  ${c_cap}📸 %s${c_off}\n" "$*"; }

assert_eq() { # 라벨 기대값 실제값
  if [ "$2" = "$3" ]; then ok "$1 = $3"; else no "$1: 기대 $2, 실제 $3"; fi
}

# ══════════════════════════════════════════════════════════════
# API 도우미
# ══════════════════════════════════════════════════════════════

# 회원마다 쿠키 항아리를 따로 둔다. 세션 기반이라 한 항아리를 돌려쓰면
# 마지막에 로그인한 사람으로 덮어써져서 "남의 것 접근" 검증이 무의미해진다
jar() { echo "$JAR_DIR/$1.cookie"; }

login() {
  curl -s -c "$(jar "$1")" -X POST "$BASE/api/members/login" \
    -H 'Content-Type: application/json' \
    -d "{\"id\":\"$1\",\"password\":\"pass1234\"}" -o /dev/null
}

# as <회원> <메서드> <경로> [본문]  -> 응답 본문
as() {
  local who=$1 method=$2 path=$3 body=${4:-}
  if [ -n "$body" ]; then
    curl -s -b "$(jar "$who")" -c "$(jar "$who")" -X "$method" "$BASE$path" \
      -H 'Content-Type: application/json' -d "$body"
  else
    curl -s -b "$(jar "$who")" -c "$(jar "$who")" -X "$method" "$BASE$path"
  fi
}

# status <회원> <메서드> <경로>  -> HTTP 상태 코드
status() {
  curl -s -o /dev/null -w '%{http_code}' -b "$(jar "$1")" -X "$2" "$BASE$3"
}

# jq 대신 python3. 맥 기본 환경에 jq가 없을 수 있어서
pick() { python3 -c "import sys,json; d=json.load(sys.stdin)['body']; print($1)"; }

balance()  { curl -s "$BASE/api/members/$1" | pick "d['balance']"; }
integrity() { curl -s "$BASE/api/admin/integrity-check" | pick "$1"; }

# 정합성 3종 + 고아 검사를 한 번에. 시나리오 사이마다 부른다
check_integrity() {
  local label=$1
  local passed; passed=$(integrity "str(d['passed']).lower()")
  if [ "$passed" = "true" ]; then
    ok "정합성 PASS — $label"
    info "차대 $(integrity "d['totalDebit']") / ESCROW_POOL $(integrity "d['escrowPoolBalance']") / DEPOSIT_POOL $(integrity "d['depositPoolBalance']") / PLATFORM $(integrity "d['platformBalance']")"
  else
    no "정합성 FAIL — $label"
    curl -s "$BASE/api/admin/integrity-check" | python3 -m json.tool
  fi
}

# 시간을 되돌린다. 청약철회 10분·결제 제한시간을 실시간으로 기다릴 수 없어서
# H2 콘솔로 직접 컬럼을 만진다. 검증 목적에만 쓰는 우회로이고,
# 이걸 위해 앱에 테스트 전용 API를 뚫지는 않았다
sql() {
  local sid
  sid=$(curl -s "$BASE/h2-console/" | grep -o 'jsessionid=[a-f0-9]*' | head -1 | cut -d= -f2)
  curl -s "$BASE/h2-console/login.do?jsessionid=$sid" \
    --data-urlencode "driver=org.h2.Driver" --data-urlencode "url=jdbc:h2:mem:planb" \
    --data-urlencode "user=sa" --data-urlencode "password=" -o /dev/null
  curl -s "$BASE/h2-console/query.do?jsessionid=$sid" --data-urlencode "sql=$1" \
    | tr -d '\n' | sed -e 's/<tr[^>]*>/\n/g' -e 's/<t[hd][^>]*>/|/g' -e 's/<[^>]*>//g' \
    | grep -v '^\s*$' | tail -n +2
}

# 스칼라 한 값만 뽑아낸다. H2 콘솔은 결과 끝에 "(1 row, 0 ms)"를 붙여 보내서
# 그대로 비교하면 값이 안 맞는 것처럼 보인다
sql_value() {
  sql "$1" | tail -1 | sed -e 's/([0-9].*$//' -e 's/|//g' -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//'
}

# ══════════════════════════════════════════════════════════════

printf "${c_hd}PlanB Market 검증  —  %s${c_off}\n" "$BASE"

if ! curl -s -o /dev/null --max-time 3 "$BASE/api/admin/integrity-check"; then
  printf "${c_no}앱에 연결할 수 없음. ./gradlew bootRun 먼저 실행할 것${c_off}\n"
  exit 1
fi

for u in user01 user02 user03 user04 user05; do login "$u"; done

# ══════════════════════════════════════════════════════════════
step "0. 시드 상태"
# ══════════════════════════════════════════════════════════════
assert_eq "user01 초기 잔액" 580110 "$(balance user01)"
assert_eq "user02 초기 잔액" 583815 "$(balance user02)"
assert_eq "PLATFORM 초기 잔액" 45425 "$(integrity "d['platformBalance']")"
check_integrity "시드"
shot "01_h2_테이블목록 / 02_시드데이터 — H2 콘솔에서"
shot "03_swagger_전체API — Swagger UI 태그 전부 펼쳐서"

# ══════════════════════════════════════════════════════════════
step "1. 정상 거래 — 예약 → 결제 → 확정"
# ══════════════════════════════════════════════════════════════
ASK=$(curl -s "$BASE/api/listings/5" | pick "d['askingPrice']")
DEP=$((ASK / 10))
REST=$((ASK - DEP))
info "판매건 5: 희망가 $ASK / 예약금 $DEP / 결제 시 추가 $REST"

B0=$(balance user01); S0=$(balance user02); P0=$(integrity "d['platformBalance']")

as user01 POST /api/listings/5/reserve > /dev/null
assert_eq "예약 후 구매자 잔액" "$((B0 - DEP))" "$(balance user01)"
assert_eq "DEPOSIT_POOL" "$DEP" "$(integrity "d['depositPoolBalance']")"
assert_eq "판매 건 상태" RESERVED "$(curl -s "$BASE/api/listings/5" | pick "d['status']")"
check_integrity "예약 직후"
shot "예약 응답 — 예약금·결제시한·추가결제액이 한 화면에"

assert_eq "다른 사람 예약 차단" 409 "$(status user03 POST /api/listings/5/reserve)"

ESC=$(as user01 POST /api/listings/5/pay | pick "d['id']")
COMM=$(curl -s -b "$(jar user01)" "$BASE/api/escrows/$ESC" | pick "d['commission']")
PAYOUT=$(curl -s -b "$(jar user01)" "$BASE/api/escrows/$ESC" | pick "d['sellerPayout']")
assert_eq "결제 후 구매자 잔액" "$((B0 - ASK))" "$(balance user01)"
assert_eq "결제 후 판매자 잔액(아직 안 받음)" "$S0" "$(balance user02)"
assert_eq "ESCROW_POOL에 묶인 금액" "$ASK" "$(integrity "d['escrowPoolBalance']")"
check_integrity "결제 직후 — 돈이 묶여 있는 상태"
shot "⭐ 판매자 잔액이 안 늘었다 + 정합성 PASS. 에스크로의 핵심"

as user01 POST "/api/escrows/$ESC/confirm" > /dev/null
assert_eq "확정 후 판매자 잔액" "$((S0 + PAYOUT))" "$(balance user02)"
assert_eq "확정 후 PLATFORM(수수료)" "$((P0 + COMM))" "$(integrity "d['platformBalance']")"
assert_eq "ESCROW_POOL 비워짐" 0 "$(integrity "d['escrowPoolBalance']")"
assert_eq "티켓 소유권 이전" user01 "$(curl -s "$BASE/api/tickets/5" | pick "d['ownerId']")"
check_integrity "확정 후"
shot "⭐ 08_정합성검증_PASS — 확정까지 끝난 뒤"
shot "07_원장조회 — GET /api/members/user01/ledger"

# ══════════════════════════════════════════════════════════════
step "2. 예약금 청약철회 — 10분 내 취소"
# ══════════════════════════════════════════════════════════════
B1=$(balance user01)
DEP6=$(as user01 POST /api/listings/6/reserve | pick "d['depositAmount']")
assert_eq "예약금 홀드" "$((B1 - DEP6))" "$(balance user01)"

RES=$(as user01 DELETE /api/listings/6/reserve)
assert_eq "예약금 상태" RELEASED "$(echo "$RES" | pick "d['depositStatus']")"
assert_eq "잔액 복구" "$B1" "$(balance user01)"
assert_eq "판매 건 복귀" OPEN "$(curl -s "$BASE/api/listings/6" | pick "d['status']")"
check_integrity "청약철회 후"
shot "청약철회 — RELEASED + 잔액 복구"

# ══════════════════════════════════════════════════════════════
step "3. 예약금 몰수 — 10분 지난 뒤 취소"
# ══════════════════════════════════════════════════════════════
B2=$(balance user01); P2=$(integrity "d['platformBalance']")
DEP6=$(as user01 POST /api/listings/6/reserve | pick "d['depositAmount']")
DID=$(as user01 GET "/api/members/user01/reservations?count=1" | pick "d['list'][0]['depositId']")
sql "UPDATE deposit SET held_at = DATEADD('MINUTE',-15,CURRENT_TIMESTAMP) WHERE id=$DID" > /dev/null
info "예약 $DID 의 신청 시각을 15분 전으로 되돌림"

RES=$(as user01 DELETE /api/listings/6/reserve)
assert_eq "예약금 상태" FORFEITED "$(echo "$RES" | pick "d['depositStatus']")"
assert_eq "잔액 안 돌아옴" "$((B2 - DEP6))" "$(balance user01)"
assert_eq "몰수분이 PLATFORM으로" "$((P2 + DEP6))" "$(integrity "d['platformBalance']")"
check_integrity "몰수 후"
shot "⭐ 몰수 — 돈이 사라진 게 아니라 PLATFORM으로 옮겨갔고 검증은 그대로 PASS"

# ══════════════════════════════════════════════════════════════
step "4. 결제 제한시간 초과 — 스케줄러가 정리"
# ══════════════════════════════════════════════════════════════
B3=$(balance user01); P3=$(integrity "d['platformBalance']")
DEP7=$(as user01 POST /api/listings/7/reserve | pick "d['depositAmount']")
DID=$(as user01 GET "/api/members/user01/reservations?count=1" | pick "d['list'][0]['depositId']")
sql "UPDATE deposit SET payment_deadline = DATEADD('MINUTE',-1,CURRENT_TIMESTAMP) WHERE id=$DID" > /dev/null
info "예약 $DID 의 제한시간을 1분 전으로 되돌림"

# 스케줄러가 아직 안 돌았어도 결제는 막혀야 한다.
# 1분 주기 사이로 결제가 새어나가지 않게 pay()가 시한을 한 번 더 본다.
#
# 다만 여기는 1분 주기 스케줄러와 경합한다. 시한을 되돌린 직후 스케줄러가 먼저 돌면
# 예약이 이미 몰수돼 사라져서 404가 정답이 된다. 400을 박아두면 앱은 정상인데
# 실행 시점에 따라 검증만 깨진다 — 둘 다 "결제가 통과되지 않는다"로 맞는 결과다
PAYCODE=$(status user01 POST /api/listings/7/pay)
if [ "$PAYCODE" = "400" ]; then
  ok "시한 지난 뒤 결제 차단 = 400 (pay()가 직접 막음)"
elif [ "$PAYCODE" = "404" ] && [ "$(sql_value "SELECT status FROM deposit WHERE id=$DID")" = "FORFEITED" ]; then
  ok "시한 지난 뒤 결제 차단 = 404 (스케줄러가 먼저 몰수해 예약이 사라짐)"
else
  no "시한 지난 뒤 결제 차단: 400 또는 404(몰수 완료)를 기대, 실제 $PAYCODE"
fi

info "스케줄러 대기 (최대 70초)"
for _ in $(seq 1 70); do
  [ "$(sql_value "SELECT status FROM deposit WHERE id=$DID")" = "FORFEITED" ] && break
  sleep 1
done
assert_eq "스케줄러가 몰수 처리" FORFEITED "$(sql_value "SELECT status FROM deposit WHERE id=$DID")"
assert_eq "판매 건 다시 열림" OPEN "$(curl -s "$BASE/api/listings/7" | pick "d['status']")"
assert_eq "몰수분이 PLATFORM으로" "$((P3 + DEP7))" "$(integrity "d['platformBalance']")"
assert_eq "잔액 안 돌아옴" "$((B3 - DEP7))" "$(balance user01)"
check_integrity "제한시간 초과 처리 후"
shot "스케줄러 로그 — 콘솔의 '결제 제한시간 초과 예약 N건 몰수 처리'"

# ══════════════════════════════════════════════════════════════
step "4-2. 알림 — 스케줄러가 만든 것들"
# ══════════════════════════════════════════════════════════════
UNREAD=$(as user01 GET /api/members/user01/notifications/unread-count | python3 -c "import sys,json; print(json.load(sys.stdin)['body'])")
if [ "$UNREAD" -gt 0 ]; then ok "안읽음 알림 $UNREAD건"; else no "알림이 하나도 안 쌓임"; fi
info "$(as user01 GET '/api/members/user01/notifications?count=5' | python3 -c "
import sys,json
for n in json.load(sys.stdin)['body']['list']: print('    %-22s %s' % (n['type'], n['title']))")"

NID=$(as user01 GET '/api/members/user01/notifications?count=1' | pick "d['list'][0]['id']")
assert_eq "읽음 처리" True "$(as user01 PATCH "/api/notifications/$NID/read" | pick "d['isRead']")"
assert_eq "안읽음 1건 줄어듦" "$((UNREAD - 1))" "$(as user01 GET /api/members/user01/notifications/unread-count | python3 -c "import sys,json; print(json.load(sys.stdin)['body'])")"
as user01 PATCH /api/members/user01/notifications/read-all > /dev/null
assert_eq "전체 읽음 후 0건" 0 "$(as user01 GET /api/members/user01/notifications/unread-count | python3 -c "import sys,json; print(json.load(sys.stdin)['body'])")"
assert_eq "남의 알림 조회" 403 "$(status user02 GET /api/members/user01/notifications)"
shot "17_알림목록 — 만료 임박·마감 임박·몰수 통보가 한 화면에"

# ══════════════════════════════════════════════════════════════
step "4-3. 만료 실효 — 스케줄러가 티켓을 소멸시킴"
# ══════════════════════════════════════════════════════════════
# 시드에 이미 만료된 티켓 3건(16·17·18)이 LISTED/OPEN으로 들어 있음.
# 스케줄러가 EXPIRED로 바꿔야 정상
info "스케줄러 대기 (최대 70초)"
for _ in $(seq 1 70); do
  [ "$(sql_value "SELECT status FROM ticket WHERE id=16")" = "EXPIRED" ] && break
  sleep 1
done
assert_eq "만료 티켓 16 실효" EXPIRED "$(sql_value "SELECT status FROM ticket WHERE id=16")"
assert_eq "만료 티켓 17 실효" EXPIRED "$(sql_value "SELECT status FROM ticket WHERE id=17")"
assert_eq "만료 티켓 18 실효" EXPIRED "$(sql_value "SELECT status FROM ticket WHERE id=18")"
assert_eq "판매 건도 실효" EXPIRED "$(sql_value "SELECT status FROM listing WHERE id=16")"
# 건수를 3으로 박아두면 안 된다. 티켓 1이 앱 시작 2분 뒤 만료라, 앞 단계에서 스케줄러를
# 기다리는 사이에 같이 실효되면 4건이 된다 — 앱은 정상인데 검증만 깨지는 자리였다.
# "실효된 티켓마다 알림이 나갔는가"가 확인하려던 것이므로 실제 실효 건수와 대조한다
assert_eq "실효 알림 = 실효 티켓 수" \
  "$(sql_value "SELECT COUNT(*) FROM ticket WHERE status='EXPIRED'")" \
  "$(sql_value "SELECT COUNT(*) FROM notification WHERE type='TICKET_EXPIRED'")"
check_integrity "만료 실효 후"
shot "18_만료실효 / 16_스케줄러로그 — 콘솔의 '만료 티켓 N건 실효 처리'"

# ══════════════════════════════════════════════════════════════
step "5. 판매자 철회 — 예약금 전액 환불"
# ══════════════════════════════════════════════════════════════
B4=$(balance user01)
as user01 POST /api/listings/8/reserve > /dev/null
as user05 DELETE /api/listings/8 > /dev/null
assert_eq "예약금 전액 환불" "$B4" "$(balance user01)"
assert_eq "판매 건 철회됨" WITHDRAWN "$(curl -s "$BASE/api/listings/8" | pick "d['status']")"
check_integrity "판매자 철회 후"

# ══════════════════════════════════════════════════════════════
step "6. 예외 처리"
# ══════════════════════════════════════════════════════════════
assert_eq "본인 티켓 예약" 400 "$(status user01 POST /api/listings/1/reserve)"
assert_eq "예약 없이 결제" 404 "$(status user01 POST /api/listings/9/pay)"
assert_eq "남의 원장 조회" 403 "$(status user02 GET /api/members/user01/ledger)"
# 본문을 제대로 채워 보낸다. 빈 본문이면 세션 검사보다 Bean Validation이 먼저 걸려서
# 401이 아니라 400이 나옴 — 인증이 안 걸린 게 아니라 검증 순서 때문
#
# 제목이 영문인 이유: Windows Git Bash에서는 MSYS2가 네이티브 curl.exe로 넘어가는 인자를
# UTF-8 → ANSI 코드페이지(한국어 환경이면 cp949)로 바꿔버린다. 그러면 서버에 깨진 바이트가
# 도착해서 Jackson이 400을 내고, 인증 검증인 이 항목이 엉뚱한 이유로 실패한다.
# 파일(@file)로 넘기면 변환을 피할 수 있지만, 이 검증은 인증을 보는 것이라
# 본문에 한글을 쓸 이유가 없어서 영문으로 바꿨다. (앱은 한글 본문을 정상 처리함)
assert_eq "비로그인 티켓 등록" 401 "$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/api/tickets" \
      -H 'Content-Type: application/json' \
      -d '{"category":"MOVIE","title":"anonymous test","originalPrice":10000,"quantity":1,"eventAt":"2027-01-01T19:00:00"}')"
assert_eq "없는 티켓 조회" 404 "$(status user01 GET /api/tickets/99999)"

VAL=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/api/members" \
      -H 'Content-Type: application/json' -d '{"id":"AB","password":"123"}')
assert_eq "입력 검증" 400 "$VAL"
shot "04_validation_400 / 05_404 — Swagger에서"

# 만료된 티켓은 스케줄러가 EXPIRED로 바꿔놨으므로 판매 건도 더 이상 살아 있지 않음.
# 판매건 18은 user03 소유라 "본인 티켓" 검사에 먼저 걸리지 않고 상태 검사까지 도달함
assert_eq "실효된 판매 건 예약" 409 "$(status user01 POST /api/listings/18/reserve)"

# ══════════════════════════════════════════════════════════════
step "7. 통계 (MyBatis) — 가격 추천"
# ══════════════════════════════════════════════════════════════
# 시드의 과거 거래 15건이 (카테고리 × 잔여시간 구간) 조합마다 1건씩 깔려 있다.
# 조합마다 표본이 1건뿐이라 폴백이 "혹시 몰라서" 넣은 코드가 아니라 정상 경로가 된다.
suggest() { curl -s "$BASE/api/analysis/price-suggestion?ticketId=$1" | pick "$2"; }

# 티켓 3 = 영화, 만료 6시간 뒤(D0). 같은 조합의 표본이 있어 1단계에서 잡힘
assert_eq "가격추천 구간 판정" D0 "$(suggest 3 "d['bucket']")"
assert_eq "가격추천 1단계 (카테고리+구간)" CATEGORY_BUCKET "$(suggest 3 "d['basis']")"
assert_eq "가격추천 표본 수 노출" 1 "$(suggest 3 "d['sampleCount']")"
# 0.58 × 28,000 — 시드의 D0 영화 거래가율이 그대로 반영돼야 한다
assert_eq "추천가 산출" 16240 "$(suggest 3 "d['suggestedPrice']")"

# 티켓 9 = 기차, 만료 4일 뒤(D3). 기차 표본은 D1에만 있어 1단계가 빈다
assert_eq "폴백 1단계 진입 (구간 표본 0건)" CATEGORY "$(suggest 9 "d['basis']")"
assert_eq "폴백 1단계도 표본은 있음" 1 "$(suggest 9 "d['sampleCount']")"

# 2단계 폴백은 시드로는 안 걸린다 — 8개 카테고리 전부 표본이 하나씩은 있어서.
# 기프티콘 표본 한 건을 30일 창 밖으로 밀어내 일부러 표본 0건을 만든다
sql "UPDATE escrow SET confirmed_at = DATEADD('DAY',-60,CURRENT_TIMESTAMP) WHERE id=15" > /dev/null
assert_eq "폴백 2단계 진입 (표본 전무)" DEFAULT "$(suggest 19 "d['basis']")"
assert_eq "표본 0건 표시" 0 "$(suggest 19 "d['sampleCount']")"
assert_eq "기본값 = 정가의 70%" 7000 "$(suggest 19 "d['suggestedPrice']")"

assert_eq "없는 티켓" 404 "$(status user01 GET /api/analysis/price-suggestion?ticketId=99999)"
shot "19_가격추천 — 티켓 3(1단계) / 티켓 9(폴백) / 티켓 19(기본값) 세 응답을 나란히"

# ══════════════════════════════════════════════════════════════
step "7-2. 통계 — 카테고리별 현황 · 일별 실효 손실"
# ══════════════════════════════════════════════════════════════
# API가 낸 집계를 SQL로 따로 세어 맞춰본다. 같은 답이 두 경로에서 나와야 한다
CAT=$(curl -s "$BASE/api/analysis/category-summary")
assert_eq "카테고리 8줄 (표본 없는 것도 0으로)" 8 "$(echo "$CAT" | pick "len(d['categories'])")"
assert_eq "실효 건수 = SQL 집계" \
  "$(sql_value "SELECT COUNT(*) FROM ticket WHERE status='EXPIRED'")" \
  "$(echo "$CAT" | pick "d['totals']['expiredCount']")"
assert_eq "양도 건수 = SQL 집계" \
  "$(sql_value "SELECT COUNT(*) FROM ticket WHERE status='TRANSFERRED'")" \
  "$(echo "$CAT" | pick "d['totals']['tradedCount']")"
assert_eq "실효 손실액 = SQL 집계" \
  "$(sql_value "SELECT SUM(original_price) FROM ticket WHERE status='EXPIRED'")" \
  "$(echo "$CAT" | pick "d['totals']['lostAmount']")"
shot "20_카테고리현황 — 실효율 칸을 같이 보이게"

LOSS=$(curl -s "$BASE/api/analysis/expiry-loss?days=7")
assert_eq "일별 손실 건수 = 카테고리 현황과 일치" \
  "$(echo "$CAT" | pick "d['totals']['expiredCount']")" \
  "$(echo "$LOSS" | pick "d['totals']['expiredCount']")"
assert_eq "일별 손실 정가 = 카테고리 현황과 일치" \
  "$(echo "$CAT" | pick "d['totals']['lostAmount']")" \
  "$(echo "$LOSS" | pick "d['totals']['originalLoss']")"
assert_eq "날짜별로 갈렸는지 (실효일이 서로 다름)" \
  "$(sql_value "SELECT COUNT(DISTINCT CAST(expires_at AS DATE)) FROM ticket WHERE status='EXPIRED'")" \
  "$(echo "$LOSS" | pick "len(d['daily'])")"
# 시장가는 판매 등록된 건만 잡히므로 정가 이하여야 한다(희망가가 정가보다 쌈)
assert_eq "시장가 손실 < 정가 손실" true \
  "$(echo "$LOSS" | pick "str(d['totals']['marketLoss'] < d['totals']['originalLoss']).lower()")"
assert_eq "days 하한" 400 "$(status user01 GET /api/analysis/expiry-loss?days=0)"
assert_eq "days 상한" 400 "$(status user01 GET /api/analysis/expiry-loss?days=91)"
shot "21_실효손실 — days=7 응답 전체"

# ══════════════════════════════════════════════════════════════
step "7-3. 거래 요약 (JPA) — MyBatis와의 경계 사례"
# ══════════════════════════════════════════════════════════════
# 같은 '통계'인데 이건 JPA로 만들었다. 단일 회원 기준 건수·합계라 여러 행을
# 구간으로 접을 일이 없어서. 그 경계 판단이 보고서 5-7절 재료다
SUM1=$(curl -s "$BASE/api/members/user01/summary")
assert_eq "판매 성사 = SQL 집계" \
  "$(sql_value "SELECT COUNT(*) FROM listing WHERE seller_id='user01' AND status='COMPLETED'")" \
  "$(echo "$SUM1" | pick "d['completedSales']")"
assert_eq "구매 확정 = SQL 집계" \
  "$(sql_value "SELECT COUNT(*) FROM escrow WHERE buyer_id='user01' AND status='CONFIRMED'")" \
  "$(echo "$SUM1" | pick "d['completedPurchases']")"
assert_eq "구매 총액 = SQL 집계" \
  "$(sql_value "SELECT SUM(amount) FROM escrow WHERE buyer_id='user01' AND status='CONFIRMED'")" \
  "$(echo "$SUM1" | pick "d['totalPurchased']")"
# 정산 수령액은 원장에서만 나온다. 에스크로 금액에서 세면 수수료 뗀 실수령이 아니라 거래액이 나옴
assert_eq "정산 수령 = 원장 집계" \
  "$(sql_value "SELECT SUM(amount) FROM ledger WHERE account_id='user01' AND reason='SELLER_SETTLE' AND entry_type='CREDIT'")" \
  "$(echo "$SUM1" | pick "d['totalEarned']")"
assert_eq "잔액 일치" "$(balance user01)" "$(echo "$SUM1" | pick "d['balance']")"
assert_eq "없는 회원" 404 "$(status user01 GET /api/members/nobody/summary)"

# ══════════════════════════════════════════════════════════════
step "8. 동시성 — 락 없음 vs 락 적용"
# ══════════════════════════════════════════════════════════════
# 이 프로젝트 최고의 증빙. 같은 판매 건에 20개 스레드가 동시에 예약을 건다.
# 바뀌는 건 조회에 락을 거느냐 뿐이고 나머지 코드 경로는 완전히 같다 —
# 그래야 "락 말고 다른 게 달랐던 것 아니냐"에 답할 수 있다.
#
# 판매 4번을 쓴다. 앞 시나리오가 손대지 않은 유일한 OPEN 건이라서
simulate() {
  curl -s -X POST "$BASE/api/admin/simulate-concurrent" -H 'Content-Type: application/json' \
    -d "{\"listingId\":4,\"threadCount\":20,\"useLock\":$1}"
}

NOLOCK=$(simulate false)
info "락 없음: 성공 $(echo "$NOLOCK" | pick "d['success']") / 예약 $(echo "$NOLOCK" | pick "d['reservationCount']")건 생성"
assert_eq "락 없음 — 중복 예약 발생" false "$(echo "$NOLOCK" | pick "str(d['dataIntegrity']).lower()")"
assert_eq "락 없음 — 예약이 2건 이상" true \
  "$(echo "$NOLOCK" | pick "str(d['reservationCount'] > 1).lower()")"
# 잔액 lost update — SPEC 5-1의 세 번째 경합 지점이 실제로 일어나는 자리
assert_eq "락 없음 — 잔액이 원장과 어긋남" false \
  "$(echo "$NOLOCK" | pick "str(d['balanceIntegrity']).lower()")"
# ⭐ 여기가 핵심. 중복이 생겨도 원장 차대는 맞는다 —
#    정합성 검증이 이 종류의 버그는 못 잡는다는 뜻
assert_eq "락 없음 — 그런데 원장 차대는 맞음" true \
  "$(echo "$NOLOCK" | pick "str(d['ledgerBalanced']).lower()")"

LOCKED=$(simulate true)
info "락 적용: 성공 $(echo "$LOCKED" | pick "d['success']") / 예약 $(echo "$LOCKED" | pick "d['reservationCount']")건 생성"
assert_eq "락 적용 — 1건만 성공" 1 "$(echo "$LOCKED" | pick "d['success']")"
assert_eq "락 적용 — 나머지 19건 거절" 19 "$(echo "$LOCKED" | pick "d['failed']")"
assert_eq "락 적용 — 거절 사유가 ALREADY_RESERVED" ALREADY_RESERVED \
  "$(echo "$LOCKED" | pick "d['failures'][0]['reason']")"
assert_eq "락 적용 — 예약 1건뿐" 1 "$(echo "$LOCKED" | pick "d['reservationCount']")"
assert_eq "락 적용 — 데이터 정합" true "$(echo "$LOCKED" | pick "str(d['dataIntegrity']).lower()")"
assert_eq "락 적용 — 잔액도 원장과 일치" true \
  "$(echo "$LOCKED" | pick "str(d['balanceIntegrity']).lower()")"

# 뒷정리가 됐는지. 안 되면 시연을 한 번밖에 못 한다
assert_eq "뒷정리 후 판매 건 재개방" OPEN "$(curl -s "$BASE/api/listings/4" | pick "d['status']")"
assert_eq "뒷정리 후 남은 예약 0건" 0 \
  "$(sql_value "SELECT COUNT(*) FROM deposit WHERE listing_id=4 AND status='HELD'")"
check_integrity "동시성 테스트 후 — 뒷정리까지 끝난 상태"

# 입력 검증
# 판매 21번은 시드에서 이미 COMPLETED이고 어떤 시나리오도 건드리지 않는다.
# 만료 예정인 건(1번 등)을 쓰면 "지금 만료됐나"에 따라 결과가 갈려서 또 시각에 의존하게 된다
assert_eq "OPEN 아닌 판매 건" 409 "$(curl -s -o /dev/null -w '%{http_code}' \
  -X POST "$BASE/api/admin/simulate-concurrent" -H 'Content-Type: application/json' \
  -d '{"listingId":21,"threadCount":5,"useLock":true}')"
assert_eq "스레드 수 하한" 400 "$(curl -s -o /dev/null -w '%{http_code}' \
  -X POST "$BASE/api/admin/simulate-concurrent" -H 'Content-Type: application/json' \
  -d '{"listingId":4,"threadCount":1,"useLock":true}')"
assert_eq "스레드 수 상한" 400 "$(curl -s -o /dev/null -w '%{http_code}' \
  -X POST "$BASE/api/admin/simulate-concurrent" -H 'Content-Type: application/json' \
  -d '{"listingId":4,"threadCount":51,"useLock":true}')"

shot "⭐⭐ 22_동시성_락없음 / 23_동시성_락적용 — 두 응답을 나란히. 이게 PDF 하이라이트"
info "락 없음 응답의 ledgerBalanced=true 를 같이 보일 것 — 정합성 검증이 못 잡는 버그라는 근거"

# ══════════════════════════════════════════════════════════════
step "9. 최종 정합성"
# ══════════════════════════════════════════════════════════════
check_integrity "전 시나리오 종료 후"
shot "⭐ 29_최종정합성검증 — 이게 보고서 6장의 결론"

# ══════════════════════════════════════════════════════════════
printf "\n${c_hd}━━━ 결과 ━━━${c_off}\n"
printf "  통과 ${c_ok}%d${c_off} / 실패 ${c_no}%d${c_off}\n\n" "$PASS" "$FAIL"
[ "$FAIL" -eq 0 ] || exit 1
