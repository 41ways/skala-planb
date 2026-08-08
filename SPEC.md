# PlanB Market — 설계 사양서

> 소멸성 자산(만료 기한이 있는 티켓·예약권)의 P2P 양도 플랫폼

---

## 0. 프로젝트 개요

### 0-1. 주제 선정 배경

만료된 영화표, 사정이 생겨 못 탄 비행기표. 중고거래는 활발하지만 이런 것들은 그냥 버려진다.
이유는 하나다 — **정해진 시각이 지나면 가치가 0이 된다.**

기존 중고거래 플랫폼은 이 성질을 다루지 못한다. 일반 물건은 거래가 성사될 때까지 가만히 있지만,
예매권은 **거래하는 동안에도 계속 소멸을 향해 간다.**

이 프로젝트는 "시간이 재고를 소멸시키는" 도메인을 다룬다.

### 0-2. 교재 대비 구조적 차이

| 교재 (온라인 쇼핑몰) | PlanB Market |
|---|---|
| 상품은 가만히 있음 | 자산이 시간에 따라 스스로 소멸 |
| 재고 = 수량(int) | 재고 = 개별 자산(만료 시각 보유) |
| 단일 차감 | 에스크로 3자 보관 + 예약금 |
| 즉시 확정 | 상태기계 기반 다단계 전이 |
| 가격 고정 | 시장 데이터 기반 추천가 |

### 0-3. 기술 스택

```
Java 21 / Spring Boot 3.3.x / Gradle
Spring Data JPA (Hibernate)  — CRUD, 상태 변경, 트랜잭션
MyBatis                      — 집계·통계 조회
H2 (in-memory)
springdoc-openapi (Swagger UI)
Spring Boot Actuator + Micrometer
Spring AOP
Bean Validation
```

**제외**: JWT, Docker, Kubernetes (과제 요구사항상 불필요)
**인증**: HttpSession 기반 (교재의 SessionHandler 추상화 유지, 내부만 세션 구현)

---

## 1. 도메인 모델

### 1-1. 엔티티 목록 (8개)

| 엔티티 | 역할 | 교재 대응 |
|---|---|---|
| `Member` | 회원 (예치금 보유) | Customer |
| `Ticket` | 양도 대상 자산 (만료 기한 보유) | Product |
| `Listing` | 판매 등록 | — |
| `PairRequest` | 1매 대기 신청 | — |
| `Deposit` | 예약금 | — |
| `Escrow` | 거래 (구매자 1인당 1건) | OrderItem |
| `Ledger` | 정산 원장 (append-only) | — |
| `Notification` | 알림 | — |

---

### 1-2. Member

```
id            String   PK  (회원 ID)
password      String       (평문 저장, JWT 미사용이므로 단순 비교)
balance       Long         (예치금, 원 단위)
createdAt     LocalDateTime
```

- 예치금은 `POST /api/members/{id}/charge` 로 충전 (실제 결제 없음)
- 잔액 변경은 **반드시 Ledger 기록과 함께** 이루어져야 함

---

### 1-3. Ticket

```
id            Long     PK  (auto)
owner         Member   FK
category      Category enum
expiryType    ExpiryType enum
title         String       (예: "아이유 콘서트 - 올림픽홀 2/14 19:00")
originalPrice Long         (정가, 전체 수량 기준)
quantity      Integer      (1 또는 2)
eventAt       LocalDateTime  nullable  — POINT_IN_TIME용
validFrom     LocalDate      nullable  — DATE_RANGE용
validUntil    LocalDate      nullable  — DATE_RANGE / EXTENDABLE용
extendedUntil LocalDate      nullable  — EXTENDABLE 연장 시
expiresAt     LocalDateTime            — 계산·저장 필드 (아래 규칙)
status        TicketStatus enum
createdAt     LocalDateTime
```

**expiresAt 산출 규칙** (저장 시점에 계산해서 컬럼에 넣는다. 스케줄러가 이것만 보면 되도록)

| expiryType | expiresAt |
|---|---|
| POINT_IN_TIME | `eventAt` |
| DATE_RANGE | `validUntil` 23:59:59 |
| EXTENDABLE | `extendedUntil ?: validUntil` 23:59:59 |

**TicketStatus**: `OWNED` → `LISTED` → `TRANSFERRED` / `EXPIRED`

---

### 1-4. Category (8종)

| enum | 표시명 | expiryType | pairable |
|---|---|---|---|
| `MOVIE` | 영화 | POINT_IN_TIME | O |
| `CONCERT` | 콘서트 | POINT_IN_TIME | O |
| `SPORTS` | 스포츠 | POINT_IN_TIME | O |
| `EXHIBITION` | 전시·팝업 | DATE_RANGE | O |
| `TRAIN` | 기차 | POINT_IN_TIME | X |
| `FLIGHT` | 항공 | POINT_IN_TIME | X |
| `HOTEL` | 호텔 | DATE_RANGE | X |
| `GIFTICON` | 기프티콘 | EXTENDABLE | X |

**pairable 판정 근거** (보고서에 기재)
- 동반 관람이 성립하는 카테고리만 1매 대기를 허용한다.
- 기차·항공은 좌석이 인접하지 않을 수 있고, 호텔은 객실을 분할할 수 없으며, 기프티콘은 동반 개념 자체가 없다.
- 즉 **분류 축이 두 개**다: `ExpiryType`(만료 처리 방식)과 `pairable`(동반 가능 여부).
  Category enum 안에 두 속성을 필드로 갖게 한다.

```java
public enum Category {
    MOVIE("영화", ExpiryType.POINT_IN_TIME, true),
    CONCERT("콘서트", ExpiryType.POINT_IN_TIME, true),
    SPORTS("스포츠", ExpiryType.POINT_IN_TIME, true),
    EXHIBITION("전시·팝업", ExpiryType.DATE_RANGE, true),
    TRAIN("기차", ExpiryType.POINT_IN_TIME, false),
    FLIGHT("항공", ExpiryType.POINT_IN_TIME, false),
    HOTEL("호텔", ExpiryType.DATE_RANGE, false),
    GIFTICON("기프티콘", ExpiryType.EXTENDABLE, false);
    ...
}
```

---

### 1-5. Listing (판매 등록)

```
id            Long     PK
ticket        Ticket   FK (1:1)
seller        Member   FK
askingPrice   Long         (전체 희망가)
unitPrice     Long         (= askingPrice / ticket.quantity)
pairable      Boolean      (= category.pairable && quantity == 2)
status        ListingStatus enum
createdAt     LocalDateTime
```

**ListingStatus**
```
OPEN          등록됨, 구매 대기
PAIR_PENDING  1매 대기자 1명 존재 (아직 미충족)
MATCHED       대기자 2명 충족, 양쪽 결제 대기
IN_ESCROW     결제 완료, 에스크로 보관 중
COMPLETED     거래 확정
WITHDRAWN     판매자 철회
EXPIRED       만료 실효
```

---

### 1-6. PairRequest (1매 대기)

```
id              Long     PK
listing         Listing  FK
requester       Member   FK
deposit         Deposit  FK (1:1)
status          PairStatus enum
requestedAt     LocalDateTime
paymentDeadline LocalDateTime nullable  (성사 시 설정)
resolvedAt      LocalDateTime nullable
```

**PairStatus**
```
WAITING           대기 중 (다른 1명 기다림)
PAYMENT_PENDING   성사됨, 결제 대기 (제한시간 있음)
PAID              결제 완료
CANCELLED         청약철회 (예약 후 10분 내 취소) → 예약금 전액 환불
ABANDONED         이탈 (제한시간 초과 또는 10분 후 취소) → 예약금 몰수
REFUNDED          만료·판매자철회로 무산 → 예약금 전액 환불
```

---

### 1-7. Deposit (예약금)

```
id          Long   PK
member      Member FK
pairRequest PairRequest FK (1:1)
amount      Long
status      DepositStatus enum
heldAt      LocalDateTime
resolvedAt  LocalDateTime nullable
```

**DepositStatus**: `HELD` → `CAPTURED`(본결제 충당) / `FORFEITED`(몰수) / `RELEASED`(환불)

---

### 1-8. Escrow (거래)

```
id            Long   PK
listing       Listing FK
buyer         Member  FK
quantity      Integer      (1 = pair 거래, 2 = 단독 구매)
amount        Long         (실제 결제액)
discount      Long         (부분성사 크레딧, 기본 0)
status        EscrowStatus enum
paidAt        LocalDateTime
autoConfirmAt LocalDateTime
confirmedAt   LocalDateTime nullable
```

**EscrowStatus**: `HOLDING` → `CONFIRMED` / `REFUNDED` / `VOIDED`(만료 무산)

- **하나의 Listing에 Escrow가 2건 붙을 수 있다** (1매 대기 성사 시 각 구매자마다 1건)
- 자동 확정: `min(결제 후 24시간, ticket.expiresAt)`

---

### 1-9. Ledger (정산 원장)

**이 프로젝트의 핵심.** 모든 금전 이동을 append-only로 기록한다. UPDATE·DELETE 절대 금지.

```
id           Long   PK
accountId    String       (회원 ID 또는 "PLATFORM")
entryType    EntryType enum   (DEBIT 차감 / CREDIT 증가)
amount       Long             (항상 양수)
balanceAfter Long             (기록 직후 잔액 — 플랫폼은 누적)
reason       LedgerReason enum
refType      String           ("ESCROW" / "DEPOSIT" / "CHARGE")
refId        Long
memo         String
createdAt    LocalDateTime
```

**LedgerReason**
```
CHARGE              예치금 충전
DEPOSIT_HOLD        예약금 홀드
DEPOSIT_CAPTURE     예약금 → 본결제 충당
DEPOSIT_FORFEIT     예약금 몰수
DEPOSIT_RELEASE     예약금 환불
PURCHASE            구매 결제
ESCROW_REFUND       에스크로 환불
SELLER_SETTLE       판매자 정산
PLATFORM_INCOME     플랫폼 수익 (몰수분)
CREDIT_GRANT        부분성사 크레딧 지급
```

**정합성 규칙 (검증 API의 근거)**
1. 모든 회원에 대해 `SUM(CREDIT) - SUM(DEBIT) == member.balance`
2. 전체 원장에서 `SUM(CREDIT) == SUM(DEBIT)` (PLATFORM 계정 포함)
   → 돈이 시스템 밖으로 새거나 생겨나지 않았음을 증명

---

### 1-10. Notification (알림)

```
id        Long   PK
member    Member FK
type      NotificationType enum
title     String
message   String
refType   String
refId     Long
isRead    Boolean
createdAt LocalDateTime
```

**NotificationType**
```
PAIR_MATCHED        1매 대기 성사 — 결제하세요
PAYMENT_DEADLINE    결제 마감 임박
PAIR_ABANDONED      상대방 이탈
FULL_PURCHASE_OFFER 2매 전체 구매 제안
DEPOSIT_FORFEITED   예약금 몰수됨
EXPIRY_WARNING      보유 티켓 만료 임박
TICKET_EXPIRED      티켓 실효
ESCROW_CONFIRMED    거래 확정
LISTING_WITHDRAWN   판매자 철회
```

---

## 2. 비즈니스 정책 (확정)

### 2-1. 예약금

| 항목 | 값 |
|---|---|
| 금액 | 1매 가격(unitPrice)의 **10%** |
| 홀드 시점 | 1매 대기 등록 시 |
| 충당 | 결제 시 본결제 금액에서 차감 |

**환급 매트릭스**

| 상황 | 처리 |
|---|---|
| 정상 결제 | `CAPTURED` — 본결제 충당 |
| **예약 후 10분 내 취소** | `RELEASED` — 전액 환불 (청약철회) |
| 10분 후 취소 | `FORFEITED` — 몰수 |
| 결제 제한시간 초과 | `FORFEITED` — 몰수 |
| 티켓 만료로 무산 | `RELEASED` — 전액 환불 |
| 판매자 철회 | `RELEASED` — 전액 환불 |

### 2-2. 결제 제한시간

```
paymentDeadline = now + min(30분, (ticket.expiresAt - now) × 0.5)
```

만료가 임박할수록 결제 시간도 짧아진다. **시간이 모든 것을 지배한다**는 컨셉의 구현.

### 2-3. 부분 성사 (B 이탈)

A, B 두 명이 성사됐는데 A만 결제하고 B가 이탈한 경우:

```
1. B 예약금 몰수 (FORFEITED)
2. A에게 알림: "상대방이 이탈했습니다. 2매 전체를 구매하시겠어요?"
   └ 제안 응답 시한: min(30분, 남은시간 × 0.5)

3-A. A가 수락 → 2매 전체 구매
     ├ B 예약금의 50% → A에게 크레딧 (할인 적용)
     └ B 예약금의 50% → PLATFORM

3-B. A가 거절 / 무응답 → A 결제금 전액 환불
     └ B 예약금 100% → PLATFORM
```

**할인 부담 주체**: 플랫폼(B 예약금 재원). **판매자는 항상 정가 수령.**

```
예: 2매 130,000원 / unitPrice 65,000원 / 예약금 6,500원
A 수락 시 → A 실부담 130,000 - 3,250 = 126,750원
            판매자 수령 130,000원 (변동 없음)
            플랫폼 수익 3,250원
```

### 2-4. 판매자 철회

- 언제든 철회 가능 (`WITHDRAWN`)
- 모든 대기자 예약금 **전액 환불** (`RELEASED`)
- 결제 완료된 에스크로가 있으면 **철회 불가** (409 반환)
- ※ 경고/제재 시스템은 이번 범위 제외 → 보고서 "향후 과제"에 기재

### 2-5. 만료 처리

스케줄러가 1분마다 `expiresAt < now` 인 티켓을 찾아 처리한다.

| 대상 상태 | 처리 |
|---|---|
| `OPEN` / `PAIR_PENDING` | `EXPIRED`, 대기자 예약금 전액 환불 |
| `MATCHED` (결제 전) | `EXPIRED`, 예약금 전액 환불 (귀책 없음) |
| `IN_ESCROW` | `VOIDED`, 구매자 결제금 전액 환불 |
| `COMPLETED` | 처리 없음 (이미 확정) |

**만료 임박 경고**: `expiresAt - now < 24시간` 이고 아직 미거래인 건에 대해 소유자에게 `EXPIRY_WARNING` 알림 (1회만)

### 2-6. 가격 추천

```
잔여시간 구간(bucket): D0(24h 미만) / D1(1~3일) / D3(3~7일) / D7(7일 이상)

추천가 = 같은 카테고리 + 같은 bucket + 최근 30일 CONFIRMED 거래의
        AVG(amount / originalPrice) × 내 티켓 originalPrice

표본 0건 → 카테고리 전체 평균으로 폴백
그것도 0건 → 정가의 70% 기본값
```

응답에 `sampleCount`를 반드시 포함한다 (표본이 적으면 사용자가 판단할 수 있도록).
※ 신뢰도 등급(HIGH/MEDIUM/LOW)은 이번 범위 제외.

---

## 3. 상태 전이 다이어그램

### 3-1. Listing

```
                    ┌──────────────────────────────────┐
                    │                                  │
   OPEN ─(1매대기)─> PAIR_PENDING ─(2명충족)─> MATCHED ──┤
    │                    │                     │       │
    │                    │                     │  (둘다결제)
    │                    │                     │       ▼
    ├──(2매 단독구매)────────────────────────────> IN_ESCROW ─(확정)─> COMPLETED
    │                    │                     │       │
    ├──(판매자철회)──> WITHDRAWN <───────────────┘       │
    │                                                  │
    └──(만료)──────> EXPIRED <──────────────────────────┘
```

### 3-2. PairRequest

```
                    ┌─(10분내 취소)─> CANCELLED    [예약금 RELEASED]
                    │
   WAITING ─────────┼─(10분후 취소)─> ABANDONED    [예약금 FORFEITED]
      │             │
      │             └─(만료/철회)──> REFUNDED      [예약금 RELEASED]
      │
   (2명 충족)
      │
      ▼
   PAYMENT_PENDING ─(결제)────────> PAID          [예약금 CAPTURED]
      │
      ├─(제한시간 초과)───────────> ABANDONED     [예약금 FORFEITED]
      │
      └─(만료/철회)──────────────> REFUNDED       [예약금 RELEASED]
```

### 3-3. Escrow

```
   HOLDING ─(구매자 확정)──────> CONFIRMED   [판매자 정산]
      │
      ├─(자동확정 시각 도달)───> CONFIRMED
      │
      ├─(환불 요청 / 부분성사 거절)─> REFUNDED
      │
      └─(티켓 만료)───────────> VOIDED       [전액 환불]
```

---

## 4. API 명세

공통 응답 포맷은 교재의 `Response` 구조를 따른다.

```json
{ "result": "SUCCESS", "resultCode": 200, "body": { ... } }
{ "result": "FAILURE", "resultCode": 400, "message": "...", "errors": { ... } }
```

### 4-1. 회원 (JPA)

| Method | URI | 설명 |
|---|---|---|
| POST | `/api/members` | 회원 가입 |
| POST | `/api/members/login` | 로그인 (세션) |
| POST | `/api/members/logout` | 로그아웃 |
| GET | `/api/members/list?offset=&count=` | 전체 목록 (페이징) |
| GET | `/api/members/{id}` | 상세 (보유 티켓 포함) |
| PUT | `/api/members` | 정보 수정 |
| DELETE | `/api/members/{id}` | 삭제 |
| POST | `/api/members/{id}/charge` | 예치금 충전 |
| GET | `/api/members/{id}/summary` | **거래 요약 (JPA 쿼리메서드)** |
| GET | `/api/members/{id}/ledger?offset=&count=` | 내 원장 조회 |

### 4-2. 티켓 (JPA)

| Method | URI | 설명 |
|---|---|---|
| POST | `/api/tickets` | 티켓 등록 |
| GET | `/api/tickets/list?offset=&count=&category=` | 목록 |
| GET | `/api/tickets/{id}` | 상세 |
| PUT | `/api/tickets/{id}` | 수정 |
| DELETE | `/api/tickets/{id}` | 삭제 |
| POST | `/api/tickets/{id}/extend` | 기한 연장 (EXTENDABLE 전용) |

### 4-3. 판매 등록 (JPA)

| Method | URI | 설명 |
|---|---|---|
| POST | `/api/listings` | 판매 등록 |
| GET | `/api/listings/list?offset=&count=&category=&pairableOnly=&sort=` | 목록 |
| GET | `/api/listings/{id}` | 상세 (대기 현황 포함) |
| GET | `/api/listings/expiring-soon?hours=24` | 만료 임박 목록 |
| DELETE | `/api/listings/{id}` | 판매자 철회 |

### 4-4. 1매 대기 (JPA)

| Method | URI | 설명 |
|---|---|---|
| POST | `/api/listings/{id}/pair-requests` | 1매 대기 등록 (예약금 홀드) |
| GET | `/api/listings/{id}/pair-status` | **대기 현황 조회 (공개)** |
| DELETE | `/api/pair-requests/{id}` | 대기 취소 |
| POST | `/api/pair-requests/{id}/pay` | 성사 후 결제 |
| POST | `/api/pair-requests/{id}/accept-full` | 2매 전체 구매 수락 |
| POST | `/api/pair-requests/{id}/decline-full` | 2매 전체 구매 거절 |
| GET | `/api/members/{id}/pair-requests` | 내 대기 목록 |

**`GET /api/listings/{id}/pair-status` 응답** — A 구현, B 확장 대비

```json
{
  "listingId": 12,
  "pairable": true,
  "waitingCount": 1,
  "requiredCount": 1,
  "iAmWaiting": false,
  "status": "PAIR_PENDING",
  "participants": []
}
```

> `participants` 배열은 지금은 항상 빈 배열로 반환한다.
> 추후 대기자 프로필(마스킹 ID·거래 횟수)을 노출할 때 이 필드만 채우면 되도록 자리를 만들어 둔다.
> 응답 DTO를 요약(Summary)/상세(Participant) 두 계층으로 분리하는 이유.

### 4-5. 거래 (JPA)

| Method | URI | 설명 |
|---|---|---|
| POST | `/api/listings/{id}/purchase` | 2매 단독 구매 |
| GET | `/api/escrows/{id}` | 거래 상세 |
| POST | `/api/escrows/{id}/confirm` | 구매 확정 |
| POST | `/api/escrows/{id}/refund` | 환불 요청 |
| GET | `/api/members/{id}/escrows` | 내 거래 목록 |

### 4-6. 알림 (JPA)

| Method | URI | 설명 |
|---|---|---|
| GET | `/api/members/{id}/notifications?unreadOnly=` | 알림 목록 |
| GET | `/api/members/{id}/notifications/unread-count` | 안읽음 개수 |
| PATCH | `/api/notifications/{id}/read` | 읽음 처리 |
| PATCH | `/api/members/{id}/notifications/read-all` | 전체 읽음 |

### 4-7. 분석·통계 (MyBatis)

| Method | URI | 설명 |
|---|---|---|
| GET | `/api/analysis/price-suggestion?category=&ticketId=` | **가격 추천** |
| GET | `/api/analysis/category-summary` | **카테고리별 거래 현황** |
| GET | `/api/analysis/expiry-loss?days=7` | **일별 실효 손실** |

**MyBatis를 쓴 이유 (보고서용)**
- 세 쿼리 모두 여러 테이블을 조인해 `GROUP BY`로 행을 줄이고 `CASE WHEN`으로 구간을 나눈다.
- JPA로 하면 엔티티를 전부 메모리에 올려 집계해야 하지만, SQL은 DB가 집계한 결과만 받는다.
- 반면 `GET /api/members/{id}/summary`는 단일 회원 기준 필터링·건수 조회라 JPA 쿼리메서드로 충분했다.
  → **경계 사례를 놓고 판단 근거를 세운 것 자체가 설계 결과물이다.**

### 4-8. 관리·검증

| Method | URI | 설명 |
|---|---|---|
| GET | `/api/admin/integrity-check` | **정합성 자가검증** |
| GET | `/api/admin/dashboard-summary` | 대시보드 집계 |
| POST | `/api/admin/simulate-concurrent` | **동시성 테스트 트리거** |

**정합성 검증 응답**
```json
{
  "memberBalanceMatch": true,
  "mismatchedMembers": [],
  "ledgerBalanced": true,
  "totalDebit": 1250000,
  "totalCredit": 1250000,
  "orphanEscrows": 0,
  "orphanDeposits": 0,
  "checkedAt": "2026-08-08T14:23:11"
}
```

### 4-9. Actuator

```
/actuator/health      기본 + 커스텀 HealthIndicator
/actuator/info
/actuator/metrics
/actuator/prometheus
```

**커스텀 HealthIndicator 2종**
- `ExpiryBacklogHealthIndicator` — 만료 처리 지연 건수가 임계 초과면 DOWN
- `LedgerIntegrityHealthIndicator` — 원장 차대 불일치면 DOWN

**커스텀 메트릭**
- `planb.escrow.created` / `planb.escrow.confirmed` (Counter)
- `planb.deposit.forfeited` (Counter)
- `planb.ticket.expired` (Counter)
- `planb.pair.matched` (Counter)

---

## 5. 동시성 제어

### 5-1. 경합 지점 3곳

| 지점 | 문제 | 대응 |
|---|---|---|
| **1매 대기 슬롯** | 남은 1자리에 여러 명이 동시 신청 → 3명 이상 대기 등록됨 | Listing 행 비관적 락 |
| **2매 단독 구매** | 동시 구매 → 하나의 티켓이 2명에게 팔림 | Listing 행 비관적 락 |
| **잔액 차감** | 동시 결제 → 잔액이 음수가 되거나 덮어써짐 | Member 행 비관적 락 |

### 5-2. 구현

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT l FROM Listing l WHERE l.id = :id")
Optional<Listing> findByIdForUpdate(@Param("id") Long id);
```

**락 획득 순서 고정** — 데드락 방지
```
Listing → Member(구매자) → Member(판매자)
```
항상 이 순서로만 락을 잡는다. 순서가 뒤바뀌면 두 트랜잭션이 서로를 기다린다.

### 5-3. 검증 방법 (PDF의 핵심 자료)

`POST /api/admin/simulate-concurrent` 가 N개 스레드로 동시 요청을 발사한다.

```json
// 요청
{ "listingId": 12, "threadCount": 20, "useLock": false }

// 응답 (락 없음)
{ "success": 7, "failed": 13, "escrowCount": 7, "dataIntegrity": false,
  "message": "동일 티켓에 7건의 거래가 생성됨 — 중복 판매 발생" }

// 응답 (락 적용)
{ "success": 1, "failed": 19, "escrowCount": 1, "dataIntegrity": true,
  "message": "1건만 성공, 나머지는 ALREADY_SOLD 반환" }
```

**락 없음 / 락 적용 두 화면을 나란히 캡처하는 것이 이 프로젝트 최고의 증빙이다.**

---

## 6. 예외 처리

교재 구조를 따른다.

```java
public enum Error {
    DATA_NOT_FOUND(404, "데이터를 찾을 수 없습니다"),
    DATA_DUPLICATED(409, "이미 존재하는 데이터입니다"),
    NOT_AUTHENTICATED(401, "로그인이 필요합니다"),
    NO_PERMISSION(403, "권한이 없습니다"),

    INSUFFICIENT_BALANCE(400, "예치금이 부족합니다"),
    TICKET_EXPIRED(400, "이미 만료된 티켓입니다"),
    TICKET_NOT_PAIRABLE(400, "1매 대기가 불가능한 카테고리입니다"),
    LISTING_NOT_OPEN(409, "구매 가능한 상태가 아닙니다"),
    ALREADY_SOLD(409, "이미 판매된 티켓입니다"),
    ALREADY_WAITING(409, "이미 대기 중입니다"),
    PAIR_SLOT_FULL(409, "대기 인원이 이미 충족되었습니다"),
    PAYMENT_DEADLINE_PASSED(400, "결제 제한시간이 지났습니다"),
    SELF_TRADE_NOT_ALLOWED(400, "본인 티켓은 구매할 수 없습니다"),
    ESCROW_EXISTS(409, "진행 중인 거래가 있어 철회할 수 없습니다"),
    EXTEND_NOT_SUPPORTED(400, "연장할 수 없는 카테고리입니다");
}
```

- `ResponseException` — 업무 예외
- `ParameterException` — 입력 검증 실패
- `GlobalExceptionHandler` — `@RestControllerAdvice`, `MethodArgumentNotValidException` 필드별 수집

---

## 7. AOP

| Aspect | 대상 | 역할 |
|---|---|---|
| `ApiLogAspect` | `controller` 패키지 전체 | 요청/응답/처리시간 로깅 |
| `TradeAuditAspect` | 거래 관련 Service 메서드 | 금전 이동 감사 로그 + Micrometer 카운터 증가 |

**주의**: Spring AOP는 프록시 기반이라 **클래스 내부 호출(`this.method()`)은 가로채지 못한다.**
거래 로직을 private 메서드로 쪼개면 AOP·`@Transactional` 둘 다 안 걸린다. 이 점을 보고서에 기재할 것.

---

## 8. 패키지 구조

```
com.skala.planbmarket
├─ PlanbMarketApplication.java
├─ config/
│   ├─ SwaggerConfig.java
│   ├─ SchedulerConfig.java
│   └─ MyBatisConfig.java
├─ aop/
│   ├─ ApiLogAspect.java
│   └─ TradeAuditAspect.java
├─ controller/
│   ├─ MemberController.java
│   ├─ TicketController.java
│   ├─ ListingController.java
│   ├─ PairRequestController.java
│   ├─ EscrowController.java
│   ├─ NotificationController.java
│   ├─ AnalysisController.java
│   └─ AdminController.java
├─ service/
│   ├─ MemberService.java
│   ├─ TicketService.java
│   ├─ ListingService.java
│   ├─ PairRequestService.java
│   ├─ EscrowService.java
│   ├─ DepositService.java
│   ├─ LedgerService.java
│   ├─ NotificationService.java
│   ├─ AnalysisService.java
│   └─ AdminService.java
├─ repository/            ← JPA
├─ mapper/                ← MyBatis 인터페이스
├─ domain/
│   ├─ entity/
│   └─ enums/
├─ dto/
│   ├─ request/
│   └─ response/
├─ common/
│   ├─ Response.java
│   ├─ PagedList.java
│   └─ SessionHandler.java
├─ exception/
│   ├─ Error.java
│   ├─ ResponseException.java
│   ├─ ParameterException.java
│   └─ GlobalExceptionHandler.java
├─ scheduler/
│   ├─ ExpiryScheduler.java
│   ├─ PaymentDeadlineScheduler.java
│   └─ AutoConfirmScheduler.java
├─ health/
│   ├─ ExpiryBacklogHealthIndicator.java
│   └─ LedgerIntegrityHealthIndicator.java
└─ tools/
    └─ TimeUtil.java

src/main/resources/
├─ application.yml
├─ data.sql              ← 시드 데이터
├─ mapper/               ← MyBatis XML
│   ├─ AnalysisMapper.xml
│   └─ DashboardMapper.xml
└─ static/
    └─ index.html        ← 대시보드
```

**교재 구조와의 차이**: 교재는 `data/table`, `data/dto` 로 묶었으나, 엔티티가 8개로 늘어 `domain/entity`, `dto/request`, `dto/response` 로 세분화했다. (과제 안내상 폴더 구조는 자유)

---

## 9. 시드 데이터 (data.sql)

- 회원 5명 (각 예치금 500,000원)
- 티켓 20건 — 카테고리 8종 고루 분포
  - 만료 임박 3건 (2시간 / 6시간 / 20시간 후)
  - 정상 12건 (3~30일 후)
  - 이미 만료 3건 (스케줄러 동작 확인용)
  - 기프티콘 2건 (연장 테스트용)
- 완료된 과거 거래 15건 — **가격 추천 통계의 표본이 되어야 함**
  - 카테고리별·잔여시간 구간별로 분산 배치할 것

---

## 10. 대시보드 (static/index.html)

단일 HTML 파일. 외부 프레임워크 없이 vanilla JS + fetch.

### 구성

```
┌──────────────────────────────────────────────────────┐
│  PlanB Market — 소멸 모니터링                          │
├────────────┬────────────┬────────────┬───────────────┤
│ 만료임박  │ 오늘실효 │ 대기중   │ 거래액      │
│    3건     │  5건       │   2건      │  1,250,000원  │
│            │ -42,000원  │            │               │
├──────────────────────────────────────────────────────┤
│  거래 중 · 만료 임박                                │
│  ┌────────────────────────────────────────────────┐  │
│  │ 아바타 IMAX 2매        01:47:23 남음  ●점멸  │  │
│  │    에스크로 보관중 · 28,000원                    │  │
│  └────────────────────────────────────────────────┘  │
├──────────────────────────────────────────────────────┤
│  [전체][영화][콘서트][스포츠][전시][기차][항공][호텔][기프티콘] │
│  판매 중 목록 — 잔여시간 순 · 긴급도에 따라 카드 색상 변화     │
├──────────────────────────────────────────────────────┤
│  카테고리별 실효율 (막대)  │  일별 실효 손실 (선)    │
├──────────────────────────────────────────────────────┤
│  [ 정합성 검증 ]   [ 동시성 테스트 20건 발사 ]      │
│  결과가 아래에 즉시 표시됨                              │
└──────────────────────────────────────────────────────┘
```

### 요구사항

- **다크 테마 + 네온 액센트** (사이언 `#22d3ee`, 마젠타 `#f0369a`, 앰버 `#fbbf24`)
- **실시간 카운트다운** — `setInterval` 1초, 만료 임박(2시간 미만)은 붉게 점멸
- **긴급도 색상** — 잔여시간에 따라 카드 좌측 보더 색이 초록→앰버→적색으로 변화
- **폴링** — 5초마다 대시보드 요약 갱신, 내 알림 확인
- **정합성 검증 버튼** — 누르면 결과 배지 표시 (PASS 초록 / FAIL 적색)
- **동시성 테스트 버튼** — 락 없음 / 락 적용 두 버튼, 결과를 나란히 표시
- **차트** — 외부 라이브러리 없이 CSS/SVG로 직접 그릴 것 (CDN 의존 최소화)

---

## 11. 이번 범위 제외 (보고서 "향후 과제"에 기재)

| 항목 | 제외 사유 |
|---|---|
| 판매자 경고·거래제한 (1개월→3개월→6개월→영구) | 프로젝트 정체성(소멸성 자산)과 직접 관련이 낮은 부가 기능 |
| 대기자 프로필 공개 (B안) | 신뢰도·평판 체계가 함께 필요. DTO 자리는 확보해 둠 |
| 가격 추천 신뢰도 등급 | 표본 크기 기준 정립에 추가 검토 필요 |
| 잔여시간 구간별 성사율 분석 | **데이터 부족** — 시드 몇 건으로는 통계적 의미 없음 |
| JWT 인증 | 과제 요구사항상 불필요 |
| Docker / K8s | 과제 요구사항상 불필요 |

---

## 12. 보고서에 반드시 담을 "판단 지점"

구현보다 이 판단들이 평가 대상이다. 개발 중 결정할 때마다 기록할 것.

1. **왜 카테고리가 아니라 만료 유형으로 추상화했는가**
   카테고리가 달라도 만료 처리가 같으면 코드는 하나. 반대로 같은 티켓이라도 시점 만료와 기간 만료는 판정 로직이 다르다.

2. **예약금 몰수 정책을 왜 단순화했는가**
   상황별 차등 몰수를 검토했으나 "결제 전 만료"처럼 귀책이 없는 경우에 어느 쪽으로 정해도 불합리가 남았다.

3. **부분성사 할인을 왜 플랫폼이 부담하는가**
   판매자에게 전가하면 잘못이 없는 쪽이 손해를 본다. 몰수분을 판매자에게 주면 판매자가 이탈을 유도할 유인이 생긴다.

4. **MyBatis / JPA 경계를 어디에 그었는가**
   "조회가 복잡한가"가 아니라 "여러 행을 집계해 줄여야 하는가"가 기준.

5. **왜 비관적 락인가**
   낙관적 락은 충돌 시 재시도. 마지막 1건 경합에서는 재시도해도 어차피 실패하므로 비용만 늘어난다.

6. **AOP 프록시의 한계를 어떻게 확인했는가**
   내부 호출은 가로채지지 않는다. 실제 로그로 확인한 내용을 기재.

7. **원장을 왜 append-only로 설계했는가**
   잔액만 UPDATE하면 틀어졌을 때 원인을 찾을 수 없다. 기록이 남아야 검증이 가능하다.
