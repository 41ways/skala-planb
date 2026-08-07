# PlanB Market — 진행 상황

> 6단계까지 완료. 다음은 7단계(MyBatis 통계 3종).
> 마지막 검증: `scripts/verify.sh` **55/55 통과**

---

## 0. 바로 시작하기

```bash
cd planb
./gradlew bootRun
```

| 주소 | 용도 |
|---|---|
| `http://localhost:8080/swagger-ui.html` | API 문서 겸 테스트 |
| `http://localhost:8080/h2-console` | DB 콘솔 (JDBC URL을 **`jdbc:h2:mem:planb`** 로 바꿀 것, 사용자 `sa`, 비번 없음) |

전체 시나리오 검증:

```bash
./scripts/verify.sh
```

시연 계정은 `user01` ~ `user05`, 비밀번호 전부 `pass1234`.

**인메모리 H2라 앱을 끄면 데이터가 사라짐.** `data.sql`이 매번 다시 깔리므로 검증 스크립트는 앱을 새로 띄운 뒤 한 번만 돌릴 것 (데이터를 실제로 바꿈).

### 개발 환경
- Java 21 (Temurin), Spring Boot 3.3.5, Gradle 8.14.5 (wrapper 포함)
- 다른 컴퓨터에서도 `./gradlew` 로 바로 됨. Gradle 별도 설치 불필요

---

## 1. 단계별 산출물

### 1단계 — 프로젝트 셋업 + 엔티티

**설정**
- `build.gradle` — web, data-jpa, mybatis, validation, aop, actuator, lombok, h2, springdoc
- `application.yml` — H2 인메모리, `ddl-auto: create`, `defer-datasource-initialization: true`
  (이게 없으면 `data.sql`이 테이블 생성 전에 실행돼 실패)

**enum 11개** (`domain/enums/`)
`Category`(8종) · `ExpiryType` · `TicketStatus` · `ListingStatus` · `DepositStatus` ·
`EscrowStatus` · `EntryType` · `LedgerReason` · `NotificationType` · `SystemAccount`

**엔티티 7개** (`domain/entity/`)
`Member` · `Ticket` · `Listing` · `Deposit` · `Escrow` · `Ledger` · `Notification`

> SPEC은 8개였으나 5단계에서 `PairRequest`를 제거해 7개가 됨.

눈여겨볼 곳:
- `Ledger` — setter 없음, 생성자 private, 전 컬럼 `updatable = false`. append-only를 두 겹으로 강제
- `Ticket.refreshDerived()` — `expiresAt`·`expiryType`을 카테고리에서 유도해 저장 시점에 계산
- `Deposit.resolve()`, `Escrow.confirm()/close()` — 종결 상태에서 재전이하면 `IllegalStateException`

**Repository 7개** (`repository/`) — `JpaRepository` 상속

**시드** `src/main/resources/data.sql` (213줄)
- 회원 5명, 활성 티켓 20건(카테고리 8종 전부), 과거 완료 거래 15건, 원장 100줄
- 시각이 전부 `CURRENT_TIMESTAMP` 상대값이라 언제 띄워도 시나리오가 성립
- 티켓 1번은 **앱 시작 2분 뒤 만료** — 스케줄러 실시간 확인용
- 티켓 16·17·18은 **이미 만료 상태로 시작** — 스케줄러가 `EXPIRED`로 바꿔야 정상

> 시드는 생성기로 뽑았음. 원장 `balanceAfter`가 그 시점까지의 누적 잔액이라 손으로 쓰면
> 거의 확실히 틀림. 생성기는 이 저장소에 없음(임시 폴더에서 작업) — 시드를 바꿔야 하면
> `data.sql`을 직접 수정하되 원장 차대가 맞는지 `integrity-check`로 확인할 것.

### 2단계 — 공통 + CRUD

**공통** (`common/`)
- `Response` — `{result, resultCode, body, message, errors}`, `NON_NULL`
- `PagedList` — 목록 + `totalCount`
- `Paging` — offset/count → Spring Data `Pageable` 변환
- `SessionHandler` — `HttpServletRequest` 프록시 주입, `requireLoginMemberId()` / `requireSelf()`

**예외** (`exception/`)
- `Error` enum — 상태 코드와 메시지를 한 곳에
- `ResponseException`(업무) · `ParameterException`(검증) · `GlobalExceptionHandler`(핸들러 7종)
- Jackson `InvalidFormatException`을 풀어서 **enum 오타 시 가능한 값 목록을 안내**

**API** — 회원 / 티켓 / 판매 등록 CRUD + 로그인, Swagger 설정

### 3단계 — 에스크로 + 원장

- `LedgerService` — **돈이 움직이는 유일한 통로.** 이체 1건 = 원장 2줄(DEBIT+CREDIT)
- `EscrowService` — 구매·확정·환불 (5단계에서 예약 흐름으로 재편)
- `AdminService` + `GET /api/admin/integrity-check` — 정합성 자가검증
- `MemberService.charge()` — 예치금 충전 (`EXTERNAL → 회원`)
- `GET /api/members/{id}/ledger` — 내 원장 조회

### 4단계 — 예약금

- `DepositService` — 홀드 / 충당 / 환불 / 몰수 4갈래, 전이마다 원장 2줄
- `TradePolicy` — 정책 숫자를 근거와 함께 한 곳에 모음

### 5단계 — **재설계** (아래 2절 참조)

- `EscrowService` 전면 개편 — 예약 → 결제 → 확정
- `ReservationResponse` 추가
- `PairRequest` 계열 전부 삭제

### 6단계 — 스케줄러 + 알림

**스케줄러 3종** (`scheduler/`)

| 클래스 | 주기 | 하는 일 |
|---|---|---|
| `ExpiryScheduler` | 1분 | 만료 임박 경고 → 티켓 실효 |
| `PaymentDeadlineScheduler` | 1분 | 마감 임박 경고 → 초과 예약 몰수 |
| `AutoConfirmScheduler` | 5분 | 자동 확정 + 정산 |

둘 다 **경고를 먼저, 처리를 나중에** 부름. 반대면 방금 실효된 티켓에
"곧 만료됩니다" 알림이 나감.

- `ExpiryService` — 만료 시점의 진행 단계별 처리, 만료 임박 경고, 자동 확정
- `NotificationService` + 알림 API 4종
- `config/SchedulerConfig` — `@EnableScheduling`

---

## 2. 5단계 재설계 — 1매 대기 제거

SPEC에 있던 **1매 대기 매칭(2매 티켓을 두 명이 나눠 사는 기능)을 전부 걷어내고**,
예약금을 일반 구매 흐름에 붙였음.

### 삭제한 것

| 분류 | 대상 |
|---|---|
| 파일 6개 | `PairRequest` 엔티티 / `PairStatus` / Repository / Service / Controller / `PairRequestResponse` |
| 엔티티 필드 | `Listing.pairable`, `Listing.unitPrice`, `Escrow.discount` |
| enum 값 | `ListingStatus.PAIR_PENDING`·`MATCHED`, `LedgerReason.CREDIT_GRANT`, `Category.pairable` |
| 알림 3종 | `PAIR_MATCHED`, `PAIR_ABANDONED`, `FULL_PURCHASE_OFFER` |
| 에러 3종 | `TICKET_NOT_PAIRABLE`, `ALREADY_WAITING`, `PAIR_SLOT_FULL` |
| API | `pair-requests`, `pair-status`, 내 대기 목록 |
| **기존 `purchase` API** | 새 상태 전이에 직접 구매 자리가 없어져서 `reserve` + `pay`로 대체 |

### 새 구매 흐름

```
OPEN ──(예약)──> RESERVED ──(결제)──> IN_ESCROW ──(확정)──> COMPLETED
  │                 │                    │
  │                 ├─(취소 10분 내)─> OPEN   [예약금 RELEASED]
  │                 ├─(취소 10분 후)─> OPEN   [예약금 FORFEITED]
  │                 └─(제한시간 초과)─> OPEN   [예약금 FORFEITED, 스케줄러]
  │
  ├──(판매자 철회)──> WITHDRAWN        [예약금 RELEASED]
  └──(만료)────────> EXPIRED          [예약금·결제금 전액 환불]
```

| 엔드포인트 | 설명 |
|---|---|
| `POST /api/listings/{id}/reserve` | 희망가의 10%를 예약금으로 홀드, `RESERVED`로 잠금 |
| `GET /api/listings/{id}/reserve` | 예약 현황 (당사자·판매자만) |
| `DELETE /api/listings/{id}/reserve` | 예약 취소 |
| `POST /api/listings/{id}/pay` | 본결제. 예약금이 결제액에 충당됨 |
| `POST /api/escrows/{id}/confirm` | 확정 → 판매자 정산 |
| `POST /api/escrows/{id}/refund` | 환불 (결제 후 10분 이내) |

`Deposit`이 `PairRequest` 대신 `Listing`을 물게 바뀌었고 `paymentDeadline`·`warnedAt`이 추가됨.
**`HELD` 상태인 예약금이 곧 "진행 중인 예약"** — 별도 예약 테이블 없음.

---

## 3. 원장 계정 체계 (핵심)

`accountId`에 회원 ID 외에 시스템 계정 4개가 들어감. 모든 금전 이동은 **반드시 2줄 쌍**.

| 이벤트 | DEBIT | CREDIT | reason |
|---|---|---|---|
| 예치금 충전 | `EXTERNAL` | 회원 | `CHARGE` |
| 예약금 홀드 | 회원 | `DEPOSIT_POOL` | `DEPOSIT_HOLD` |
| 예약금 환불 | `DEPOSIT_POOL` | 회원 | `DEPOSIT_RELEASE` |
| 예약금 몰수 | `DEPOSIT_POOL` | `PLATFORM` | `DEPOSIT_FORFEIT` |
| 예약금 충당 | `DEPOSIT_POOL` | `ESCROW_POOL` | `DEPOSIT_CAPTURE` |
| 잔액 결제분 | 회원 | `ESCROW_POOL` | `PURCHASE` |
| 에스크로 환불 | `ESCROW_POOL` | 회원 | `ESCROW_REFUND` |
| 중개 수수료 | `ESCROW_POOL` | `PLATFORM` | `COMMISSION` |
| 판매자 정산 | `ESCROW_POOL` | 판매자 | `SELLER_SETTLE` |

정합성 검증(`GET /api/admin/integrity-check`)이 확인하는 것:
1. 회원별 잔액 == 원장 합
2. 전체 `SUM(DEBIT)` == `SUM(CREDIT)`
3. `ESCROW_POOL` 잔액 == 보관 중 거래액 합
4. `DEPOSIT_POOL` 잔액 == 홀드 중 예약금 합
5. 고아 에스크로 / 고아 예약금 0건

---

## 4. 확정된 정책

| 항목 | 값 | 위치 |
|---|---|---|
| 중개 수수료 | **5%** (정산 시 판매자 몫에서) | `TradePolicy.COMMISSION_PERCENT` |
| 예약금 | 희망가의 **10%** | `TradePolicy.DEPOSIT_PERCENT` |
| 청약철회 시간 | **10분** (예약 취소·에스크로 환불 공통) | `TradePolicy.COOLING_OFF_MINUTES` |
| 결제 제한시간 | `min(30분, 남은시간 × 0.5)`, 최소 1분 | `TradePolicy.paymentDeadlineOf()` |
| 자동 확정 시각 | `min(결제+24h, 만료−10분)`, 최소 결제+1분 | `TradePolicy.autoConfirmAt()` |
| 만료 경고 | 24시간 전, 1회, **보유자 + 예약자** | `ExpiryScheduler.WARN_BEFORE_HOURS` |
| 마감 임박 경고 | 주어진 시간의 **절반 지점**, 1회 | `Deposit.needsDeadlineWarning()` |

그 외 결정:
- **인증 범위** — 조회는 공개, 변경은 로그인, 개인정보(원장·알림·내 거래·예약)는 본인만
- **회원 삭제** — 원장 기록이 있으면 409 거부 (soft delete 안 씀)
- **티켓 수정** — `OWNED` 상태에서만 가능, `LISTED` 이상은 409
- **잔액 확인 범위** — 예약 시 예약금만 확인, 본결제 금액은 안 봄

결정 근거는 `NOTES.md` 참조.

---

## 5. 알려진 이슈 · 미완성

### 미구현 (예정)
- **MyBatis 매퍼 없음** — 의존성만 붙어 있고 `mapper/*.xml`이 비어 있어서
  앱 시작 시 `No MyBatis mapper was found` 경고가 뜸. **정상임.** 7단계에서 채움
- **비관적 락 없음** — 8단계 예정. 지금은 동시에 같은 판매 건을 예약하면 중복이 생길 수 있음
- **AOP·Actuator 커스텀 없음** — 9단계 예정
- **대시보드 없음** — 9단계 예정

### 알아둘 점
- **`Paging`의 offset 해석** — `offset / count`로 페이지 번호를 만들기 때문에 offset이
  count의 배수가 아니면 그 값을 품는 페이지의 시작으로 내려감 (`Paging.java` 주석 참조)
- **시스템 계정의 `balanceAfter`는 동시성에 취약** — 매번 원장을 합산해서 구하는데
  락을 안 잡음. 정합성 검증은 `SUM`을 쓰므로 영향 없지만, 동시 이체 시 특정 줄의
  `balanceAfter`가 어긋날 수 있음. 8단계에서 짚을 것
- **`EscrowStatus.VOIDED` 도달이 드묾** — 자동 확정이 만료보다 10분 먼저 일어나게
  잡아둬서, 앱이 꺼져 있던 동안 만료가 지난 경우 정도가 아니면 잘 안 걸림.
  방어적으로 구현해 둔 분기임
- **검증 스크립트가 H2 콘솔로 시각을 조작함** — 10분·제한시간을 실시간으로 기다릴 수
  없어서. 테스트 전용 API를 앱에 뚫지 않으려고 택한 우회로

### 캡처 진행 상황
`docs/captures/`에 일부 있으나 **5단계 재설계로 상당수가 무효화됨**
(`purchase` API 삭제, 1매 대기 삭제, Swagger 태그·엔드포인트 변경).
살아 있는 것: `01`, `02`, `04`, `04b`, `05`, `07`.

캡처는 **8단계 끝에 1차, 10단계에 최종** 두 번 몰아서 찍기로 함.
`scripts/verify.sh`를 돌리면 📸 표시가 뜨는 12개 지점이 캡처할 자리.

---

## 6. 7단계에서 할 일 — MyBatis 통계 3종

### 만들 것
- `mapper/AnalysisMapper.java` (인터페이스) + `src/main/resources/mapper/AnalysisMapper.xml`
- `service/AnalysisService.java`, `controller/AnalysisController.java`
- `config/MyBatisConfig.java` (필요 시. `application.yml`에 `mapper-locations`는 이미 설정돼 있음)

### API 3종

| Method | URI | 설명 |
|---|---|---|
| GET | `/api/analysis/price-suggestion?category=&ticketId=` | 가격 추천 |
| GET | `/api/analysis/category-summary` | 카테고리별 거래 현황 |
| GET | `/api/analysis/expiry-loss?days=7` | 일별 실효 손실 |

### 가격 추천 규칙 (SPEC 2-6)

```
잔여시간 구간(bucket): D0(24h 미만) / D1(1~3일) / D3(3~7일) / D7(7일 이상)

추천가 = 같은 카테고리 + 같은 bucket + 최근 30일 CONFIRMED 거래의
        AVG(amount / originalPrice) × 내 티켓 originalPrice

표본 0건 → 카테고리 전체 평균으로 폴백
그것도 0건 → 정가의 70% 기본값
```

응답에 `sampleCount`를 반드시 포함할 것.

### 시드가 이미 준비돼 있음
과거 거래 15건이 (카테고리 × bucket) 조합마다 1건씩 깔려 있고, 비율이 이렇게 분포함:

```
D0  0.58 ~ 0.62   급처분
D1  0.70 ~ 0.74
D3  0.80 ~ 0.84
D7  0.86 ~ 0.91   여유 있을 때
```

**만료가 가까울수록 싸게 팔린다**는 패턴이라 추천가가 의미를 가짐.
그리고 조합마다 표본이 1건뿐이라 **폴백 로직이 반드시 타게 돼 있음** — 표본 0건 처리를
실제로 검증할 수 있는 구조.

### 먼저 정해야 할 것
- **bucket 경계 표기** — SPEC의 `D1(1~3일)` / `D3(3~7일)`이 경계에서 겹침. 어느 쪽을
  포함할지 정할 것 (`24 <= h < 72` 같은 식으로)
- **`Escrow.amount` 단위** — 지금은 전량 구매만 있어서 `amount`가 곧 전체 금액이고
  `originalPrice`도 전체 기준이라 비율이 그대로 맞음. 1매 대기를 뺐기 때문에
  SPEC에서 걱정하던 단위 불일치 문제는 **사라졌음**

### 마친 뒤
```bash
./scripts/verify.sh
```
55개가 그대로 통과해야 함. 통계 API 검증도 스크립트에 추가할 것.

---

## 7. 참고 문서

| 파일 | 내용 |
|---|---|
| `SPEC.md` | 원본 설계 사양서 (5단계 재설계분은 미반영) |
| `PLAN.md` | 11단계 진행 계획 |
| `NOTES.md` | **설계 판단 근거** — 보고서 5장 재료 |
| `scripts/verify.sh` | 전체 시나리오 검증 (55개) + 캡처 가이드 |
