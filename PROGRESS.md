# PlanB Market — 진행 상황

> 8단계까지 완료. 다음은 9단계(Actuator + AOP + 대시보드).
> 마지막 검증: `scripts/verify.sh` **97/97 통과**

---

## 0. 바로 시작하기

저장소 루트가 곧 프로젝트다. 하위 디렉터리로 들어갈 필요 없음.

```bash
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

**enum 10개** (`domain/enums/`)
`Category`(8종) · `ExpiryType` · `TicketStatus` · `ListingStatus` · `DepositStatus` ·
`EscrowStatus` · `EntryType` · `LedgerReason` · `NotificationType` · `SystemAccount`

> 7단계에서 `RemainingBucket`이 추가돼 지금은 11개.

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

### 7단계 — MyBatis 통계 3종 + 거래 요약

**MyBatis** (`mapper/`, `resources/mapper/AnalysisMapper.xml`)
- `AnalysisMapper` + XML — 쿼리 4개 (가격추천 2 = 본조회 + 폴백, 카테고리 현황, 실효 손실)
- 행 타입 3개 — `RatioSampleRow` · `CategoryStatRow` · `ExpiryLossRow`
  (응답 DTO와 분리. SQL은 "표본이 이렇더라"까지만 답하고 판단은 서비스가 함)
- `config/MyBatisConfig` — `@MapperScan`. 스캔 경계를 한 곳에 적어 JPA와 안 섞이게

**enum 1개** — `RemainingBucket` (D0/D1/D3/D7, 경계와 라벨을 필드로 보유)

**API 4종**

| Method | URI | 도구 |
|---|---|---|
| GET | `/api/analysis/price-suggestion?ticketId=` | MyBatis |
| GET | `/api/analysis/category-summary` | MyBatis |
| GET | `/api/analysis/expiry-loss?days=7` | MyBatis |
| GET | `/api/members/{id}/summary` | **JPA** — 경계 사례 |

넷 다 공개 조회. 가격 추천은 판매 등록 *전에* 봐야 쓸모가 있어서 로그인을 요구하면 자리를 잃음.
거래 요약이 공개인 건 회원 상세(`GET /api/members/{id}`)가 이미 잔액을 공개하고 있어서 —
여기만 막으면 정책이 어긋남. **잔액을 공개하는 것 자체는 실서비스라면 다시 볼 자리**라
보고서 7장(한계)에 적을 것.

눈여겨볼 곳:
- **구간 경계가 `RemainingBucket` 한 곳에만 있음.** SQL엔 `minHours`/`maxHours`를 파라미터로
  넘김. `CASE WHEN`을 XML에 박으면 자바에도 같은 경계가 또 있어야 하고, 어긋나도
  에러가 안 나고 조용히 틀린 추천가가 나감 (`NOTES.md` 12-2절)
- **폴백 3단계** — 카테고리+구간 → 카테고리 → 정가 70%. 응답의 `basis`로 어느 단계인지 노출
- `members/{id}/summary`를 JPA로 남긴 이유 → `NOTES.md` 12절. **보고서 5-7절 재료**

### 8단계 — 동시성 락

**비관적 락 2곳** (`SELECT ... FOR UPDATE`)
- `ListingRepository.findByIdForUpdate` — 경합이 실제로 일어나는 첫 관문
- `MemberRepository.findByIdForUpdate` — 잔액 lost update 방지
- **락 획득 순서 고정: Listing → 구매자 → 판매자.** `reserve`·`pay` 둘 다 이 순서

**동시성 시뮬레이터** — `POST /api/admin/simulate-concurrent`
- N개 스레드가 같은 판매 건에 동시에 예약. `CountDownLatch`로 출발을 맞춰 경합을 만듦
- `EscrowService.reserve(listingId, buyerId, useLock)` — 인자 둘이 는 건 시뮬레이터 때문.
  세션은 요청 스코프라 스레드에 없고, **락 유무만 갈리고 나머지 경로는 완전히 같아야**
  "락 말고 다른 게 달랐던 것 아니냐"에 답할 수 있음
- `LedgerService.reconcileBalances()` — 뒷정리. 잔액을 원장 합계에 맞춰 되돌림

**실측 결과** (판매 4번, 20스레드)

| | 성공 | 예약 생성 | dataIntegrity | balanceIntegrity | **ledgerBalanced** |
|---|---|---|---|---|---|
| 락 없음 | 7~9 | 7~9건 | ❌ | ❌ (4명 어긋남) | **✅** |
| 락 적용 | 1 | 1건 | ✅ | ✅ | ✅ |

⭐ **락 없음인데 원장 차대는 맞는다**는 게 이 단계 최대 수확.
홀드마다 원장 2줄이 제대로 남아서 돈은 한 푼도 안 샜는데, 티켓 하나가 9명에게 잠겼다.
**정합성 검증이 못 잡는 종류의 버그가 있다** — `NOTES.md` 17절, 보고서 5-11절 재료.

눈여겨볼 곳:
- **`reconcileBalances()`가 `LedgerService`에 있는 이유** — 처음엔 `AdminService`에 뒀다가
  같은 클래스 내부 호출이라 `@Transactional`이 안 걸려 "고쳤다는데 안 고쳐지는" 증상을 봄.
  SPEC 7장이 경고한 AOP 프록시 한계에 그대로 걸린 것 (`NOTES.md` 13-6절)
- **시뮬레이터가 뒷정리를 하는 이유** — 안 하면 lost update가 영구히 남아 정합성 검증이
  계속 실패함. 락 없음/락 적용을 나란히 보여주려면 반복 실행이 돼야 함.
  대신 무슨 일이 있었는지는 응답 `lostUpdates`에 남김

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
- **AOP·Actuator 커스텀 없음** — 9단계 예정
- **대시보드 없음** — 9단계 예정
- **`GET /api/admin/dashboard-summary` 없음** — SPEC 4-8에 있지만 대시보드 재료라 9단계에서
  같이 만드는 게 맞음. 지금 만들면 화면이 뭘 필요로 하는지 모른 채 모양을 정하게 됨

### 정리 안 한 것 (7단계 범위 밖)
- **`Ticket.sourceTicketId`가 죽은 필드** — 엔티티와 `TicketResponse`에만 있고 읽고 쓰는
  코드가 0건. 1매 분할 발행의 잔재라 5단계 재설계 때 같이 빠졌어야 함. 시드도 전부 NULL
- **`Escrow.quantity`** — 항상 `ticket.quantity` 복사본. 전량 구매만 남아서 독립적 의미가
  없어짐. 다만 통계에는 무해 — `amount`와 `originalPrice`가 둘 다 전체 기준이라 비율이 맞음
- **`TicketStatus` javadoc이 아직 1매 대기를 설명함** — "2매짜리를 두 명이 나눠 산 경우"
- 셋 다 동작에 영향 없음. 캡처를 다시 찍을 일이 없는 10단계에 몰아서 정리할 것

### 알아둘 점
- **`Paging`의 offset 해석** — `offset / count`로 페이지 번호를 만들기 때문에 offset이
  count의 배수가 아니면 그 값을 품는 페이지의 시작으로 내려감 (`Paging.java` 주석 참조)
- **시스템 계정의 `balanceAfter`는 동시성에 취약** — 8단계에서 실측으로 확인함.
  락 없이 20스레드를 쏘면 `DEPOSIT_POOL` 원장 26줄 중 **6줄의 `balanceAfter`가 어긋났음.**
  잠글 행이 없어서(회원과 달리 `Member` 행이 없음) 두 이체가 같은 합계를 읽고
  각자 자기 금액을 더한 값을 씀.

  **고치지 않기로 했음.** 고치려면 모든 이체를 직렬화해야 하는데 표시용 컬럼 하나
  때문에 치르기엔 너무 큰 값임. 대신 아무것도 여기에 안 기대게 해뒀음 —
  정합성 검증은 전부 `SUM`으로 구하고(`balanceOf`/`sumAmountByEntryType`),
  `balanceAfter`를 읽는 곳은 원장 조회 응답 하나뿐(화면 표시용).
  **누적 합계 자체는 언제나 맞고, 어긋나는 건 "그 줄 시점의 스냅샷"뿐임.**
  회원 계정은 해당 없음 — 잔액 경로에 `Member` 행 비관적 락이 걸려 있음
- **`EscrowStatus.VOIDED` 도달이 드묾** — 자동 확정이 만료보다 10분 먼저 일어나게
  잡아둬서, 앱이 꺼져 있던 동안 만료가 지난 경우 정도가 아니면 잘 안 걸림.
  방어적으로 구현해 둔 분기임
- **검증 스크립트가 H2 콘솔로 시각을 조작함** — 10분·제한시간을 실시간으로 기다릴 수
  없어서. 테스트 전용 API를 앱에 뚫지 않으려고 택한 우회로
- **가격 추천 2단계 폴백(`DEFAULT`)은 시드로 안 걸림** — 8개 카테고리 전부 표본이
  하나씩은 있어서. 검증 스크립트가 기프티콘 표본 한 건을 30일 창 밖으로 밀어내
  일부러 만들어 확인함 (위 H2 우회로를 같은 이유로 재사용)
- **Windows Git Bash에서 요청 본문에 한글을 쓰면 깨짐** — MSYS2가 네이티브 `curl.exe`로
  넘기는 인자를 UTF-8 → cp949로 바꿔버려서 서버가 400을 냄. **앱 문제가 아님**
  (파일 `@body.json`으로 넘기면 정상). 검증 스크립트의 해당 본문은 영문으로 바꿔뒀음
- **시각에 의존하는 단정을 숫자로 박지 말 것** — 7단계에서 세 군데가 이 이유로 깨졌음.
  전부 앱은 정상이고 검증만 틀린 경우라 스크립트 쪽을 고쳤다.

  | 자리 | 왜 깨졌나 | 어떻게 고쳤나 |
  |---|---|---|
  | 실효 알림 건수 `3` | 티켓 1이 앱 시작 2분 뒤 만료라, 앞 단계에서 스케줄러를 기다리는 사이 같이 실효되면 4건 | 실제 `EXPIRED` 티켓 수와 대조 |
  | 시한 초과 결제 `400` | 1분 주기 스케줄러가 먼저 돌면 예약이 이미 몰수돼 사라져서 404가 정답 | 400 또는 404(몰수 완료) 둘 다 통과, 어느 쪽인지 출력 |
  | 카테고리·손실 집계 | 스크립트가 앞 단계에서 새 거래를 만들어 시드 숫자와 달라짐 | 전부 SQL 집계와 교차 대조 |
  | (8단계) "OPEN 아닌 판매 건" 테스트 | 티켓 1이 아직 만료 전이면 판매 1번이 여전히 OPEN | 시드에서 항상 COMPLETED인 판매 21번으로 교체 |

  > 마지막 줄은 **이 교훈을 적어놓고 바로 다음 단계에서 또 밟은 것**이다.
  > "만료됐을 테니 OPEN이 아니겠지"라고 가정한 게 화근. 시각에 의존하지 않는
  > 대상(시드에서 상태가 고정된 행)을 고르는 게 답이었다.

  > **스케줄러가 도는 앱에서는 "지금 몇 건인가"가 고정값이 아니다.** 기댓값을 손으로
  > 적는 대신 다른 경로(SQL)로 같은 답을 구해 맞춰보는 게 맞다. 그러면 검증이
  > 깨지기 어려워질 뿐 아니라, **API와 SQL 두 경로가 같은 답을 내는지**까지 보게 된다.

### 캡처 진행 상황
`docs/captures/`에 일부 있으나 **5단계 재설계로 상당수가 무효화됨**
(`purchase` API 삭제, 1매 대기 삭제, Swagger 태그·엔드포인트 변경).
살아 있는 것: `01`, `02`, `04`, `04b`, `05`, `07`.

캡처는 **8단계 끝에 1차, 10단계에 최종** 두 번 몰아서 찍기로 함.
`scripts/verify.sh`를 돌리면 📸 표시가 뜨는 12개 지점이 캡처할 자리.

---

## 6. 7단계에서 확정한 것 (참고)

| 항목 | 결정 | 근거 |
|---|---|---|
| bucket 경계 | `min <= h < max` — 아래 포함, 위 배제 | SPEC의 `D1(1~3일)`/`D3(3~7일)`이 겹쳐 있었음 |
| bucket 측정 | 표본 = `expiresAt − paidAt`, 내 티켓 = `expiresAt − now` | `now` 기준으로 재면 과거 거래가 전부 D0으로 몰림 |
| 최근 30일 | `confirmed_at` 기준 | 조건이 `CONFIRMED`라 `IS NOT NULL`이 필터를 겸함 |
| 실효 손실액 | 정가·시장가 **둘 다** | 다른 질문에 답함. 차이가 "미등록 소멸"을 드러냄 |
| 실효 날짜 키 | `expiresAt`의 날짜 | 처리 시각을 쓰면 스케줄러 지연에 집계가 흔들림 |
| 실효율 분모 | 양도완료 + 실효 (판매 중 제외) | 전체로 나누면 새 티켓 등록만으로 지표가 좋아짐 |
| 추천가 반올림 | 원 단위 `Math.round` | 100원 단위 절삭 같은 규칙은 근거 없이 만들지 않음 |
| 인증 | 넷 다 공개 | 개인정보가 아님. 가격 추천은 등록 *전에* 봐야 쓸모가 있음 |

시드는 손대지 않았음. 과거 거래 15건이 (카테고리 × bucket) 조합마다 1건씩 깔려 있고
`D0 0.58~0.62 → D7 0.86~0.91` 분포라 **만료가 가까울수록 싸게 팔린다**는 패턴이 그대로 나옴.
조합마다 표본이 1건뿐이라 **1단계 폴백이 정상 경로로 실제로 탐** (기차 D3, 항공 D7 등).

`price-suggestion`은 `category` 파라미터를 안 받음 — 티켓에서 그대로 나오는 값이라
따로 받으면 "둘이 어긋나면 어느 쪽을 믿나"라는 분기가 공짜로 생김.

---

## 7. 8단계에서 확정한 것 (참고)

| 항목 | 결정 | 근거 |
|---|---|---|
| 락 방식 | **비관적 락** | 마지막 1건 경합이라 낙관적 락은 재시도해도 어차피 실패 — 비용만 늚 |
| 락 대상 | Listing, Member | SPEC 5-1의 세 경합 지점이 이 둘로 덮임 |
| 락 순서 | **Listing → 구매자 → 판매자** | 데드락을 감지해서 푸는 대신 안 생기게 만듦 |
| 시뮬레이터 대상 | `reserve` | 경합이 실제로 일어나는 첫 관문. 결과가 `ALREADY_RESERVED` 하나로 깔끔 |
| 락 on/off 구현 | 조회 메서드만 바꿔 끼움 | 메서드를 두 벌 두면 "락 말고 다른 게 달랐다"는 반론에 못 답함 |
| 뒷정리 | 예약 전액 환불 + 잔액 원장에 맞춤 | 안 하면 lost update가 영구히 남아 시연을 한 번밖에 못 함 |
| 뒷정리 위치 | `LedgerService` | 잔액을 바꾸는 통로는 하나라는 규칙. AOP 프록시 제약과 답이 같았음 |
| 시스템 계정 `balanceAfter` | **안 고침** | 표시용 컬럼 하나 때문에 전 이체를 직렬화할 수 없음. 검증은 전부 SUM 기반 |

시연 순서 (캡처용):

```bash
curl -X POST http://localhost:8080/api/admin/simulate-concurrent \
  -H 'Content-Type: application/json' \
  -d '{"listingId":4,"threadCount":20,"useLock":false}'
```

```bash
curl -X POST http://localhost:8080/api/admin/simulate-concurrent \
  -H 'Content-Type: application/json' \
  -d '{"listingId":4,"threadCount":20,"useLock":true}'
```

두 응답을 나란히 놓는 게 PDF 하이라이트다. **락 없음 응답의 `ledgerBalanced: true`를
같이 보일 것** — 정합성 검증이 못 잡는 버그라는 근거다.
뒷정리가 붙어 있어서 몇 번이든 반복해서 돌릴 수 있다.

---

## 8. 9단계에서 할 일 — Actuator + AOP + 대시보드

### 만들 것

**AOP 2종** (`aop/`)
- `ApiLogAspect` — `controller` 패키지 전체. 요청/응답/처리시간
- `TradeAuditAspect` — 거래 서비스 메서드. 금전 이동 감사 로그 + Micrometer 카운터

**Actuator**
- 커스텀 HealthIndicator 2종 — `ExpiryBacklogHealthIndicator`, `LedgerIntegrityHealthIndicator`
- 커스텀 메트릭 4종 — `planb.escrow.created` / `.confirmed`, `planb.deposit.forfeited`,
  `planb.ticket.expired`
- `application.yml`에 `management.endpoints.web.exposure.include` 추가 필요
  (지금은 health·info만 열려 있음)

**대시보드** — `static/index.html`, 다크 테마 + 네온, 카운트다운, 차트, 검증 버튼

**`GET /api/admin/dashboard-summary`** — SPEC 4-8. 대시보드가 뭘 필요로 하는지 정한 뒤에 만들 것

### 미리 정해둘 것
- **AOP 프록시 한계를 실증으로 남길 것** — 이미 8단계에서 걸렸음(`NOTES.md` 13-6절).
  SPEC 12장 6번 항목의 답이 이미 있으니, `TradeAuditAspect`를 붙일 때
  `EscrowService.settle()` 같은 **내부 호출 메서드에는 안 걸린다**는 걸 로그로 한 번 더 보일 것
- **HealthIndicator가 DOWN을 내는 조건** — 임계값을 정해야 함.
  만료 처리 지연 건수 몇 건부터? 원장 차대는 1원이라도 어긋나면 DOWN이 자연스러움
- **대시보드가 부를 API** — 이미 다 있음. `expiring-soon`, `category-summary`,
  `expiry-loss`, `integrity-check`, `simulate-concurrent`.
  `dashboard-summary`만 새로 만들면 됨
- **차트는 외부 라이브러리 없이** CSS/SVG로 (SPEC 10장)

### 마친 뒤

```bash
./scripts/verify.sh
```

97개가 그대로 통과해야 함.

> 9·10단계는 기존 API를 안 건드리므로 **8단계에서 찍은 캡처는 끝까지 살아남는다.**
> 아직 안 찍었으면 지금이 1차 캡처 시점 — 📸 표시가 뜨는 자리를 한 번에 찍을 것.

---

## 9. 참고 문서

| 파일 | 내용 |
|---|---|
| `SPEC.md` | 원본 설계 사양서 (5단계 재설계분은 미반영) |
| `PLAN.md` | 11단계 진행 계획 |
| `NOTES.md` | **설계 판단 근거** — 보고서 5장 재료 |
| `scripts/verify.sh` | 전체 시나리오 검증 (55개) + 캡처 가이드 |
