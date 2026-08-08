# PlanB Market — 진행 상황

> **10단계까지 완료.** 남은 것은 11단계(PDF 보고서)와 캡처.
> 마지막 검증: `scripts/verify.sh` **119/119 통과**

---

## 0. 바로 시작하기

저장소 루트가 곧 프로젝트다. 하위 디렉터리로 들어갈 필요 없음.

### 맥 · 리눅스 (제출·캡처는 여기서)

```bash
./gradlew bootRun
```

```bash
./scripts/verify.sh
```

Gradle은 따로 설치할 필요 없다 — 래퍼가 알아서 받는다. **Java 21만 있으면 된다.**

```bash
java -version
```

없으면 (Homebrew):

```bash
brew install --cask temurin@21
```

`verify.sh`는 `curl`·`python3`를 쓴다. 둘 다 맥 기본이지만 `python3`가 처음이면
Xcode Command Line Tools 설치를 물어볼 수 있다 — 승인하면 된다.

> 저장소에 `gradlew`와 `scripts/verify.sh`가 **실행 권한(755)** 과 **LF 줄바꿈**으로
> 들어가 있어서 클론하면 바로 실행된다. `.gitattributes`가 이걸 못박아 둔다 —
> CRLF로 넘어가면 맥 bash가 ``을 명령어 일부로 읽어서 빌드가 시작조차 못 한다.

### 윈도우 CMD

cmd에서는 `./gradlew`가 안 된다. `.`을 명령어로 읽어서
`'.'은(는) 내부 또는 외부 명령... 이 아닙니다`가 뜬다. 그래서 배치 파일을 뒀다.

```bash
run.bat
```

```bash
verify.bat
```

- `run.bat` — 프로젝트 폴더로 이동 → 앱 실행 → 뜨면 브라우저 자동으로 열어줌.
  더블클릭해도 되고 어느 경로에서 실행해도 된다
- `verify.bat` — Git Bash를 찾아 `verify.sh`를 대신 돌린다. 앱이 안 떠 있으면
  먼저 알려준다 (안 그러면 전부 실패로 떠서 코드가 깨진 줄 알게 됨)

> **두 배치 파일은 CP949로 저장돼 있다.** UTF-8로 저장하면 cmd가 한글 주석의
> 바이트를 명령어로 잘못 읽어서 실행 자체가 깨진다. `.gitattributes`의
> `*.bat -text`가 git이 바이트를 건드리지 않게 막는다.

### 윈도우 Git Bash

맥과 같되 `./`가 꼭 필요하다. 없으면 `command not found`.

```bash
cd ~/skala-planb && ./gradlew bootRun
```

### 주소

| 주소 | 용도 |
|---|---|
| `http://localhost:8080/` | **대시보드** — 소멸 모니터링 |
| `http://localhost:8080/swagger-ui.html` | API 문서 겸 테스트 |
| `http://localhost:8080/actuator/health` | 커스텀 HealthIndicator 2종 |
| `http://localhost:8080/h2-console` | DB 콘솔 (JDBC URL을 **`jdbc:h2:mem:planb`** 로 바꿀 것, 사용자 `sa`, 비번 없음) |

시연 계정은 `user01` ~ `user05`, 비밀번호 전부 `pass1234`.

**인메모리 H2라 앱을 끄면 데이터가 사라짐.** `data.sql`이 매번 다시 깔리므로 검증
스크립트는 앱을 새로 띄운 뒤 한 번만 돌릴 것 (데이터를 실제로 바꿈).

### 개발 환경
- Java 21 (Temurin), Spring Boot 3.3.5, Gradle 8.14.5 (wrapper 포함)
- 윈도우에서 만들었고 맥에서 제출·캡처. 줄바꿈·실행권한은 `.gitattributes`가 맞춰 둠

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

### 9단계 — Actuator + AOP + 대시보드

**AOP 2종** (`aop/`)

| Aspect | 대상 | 하는 일 |
|---|---|---|
| `ApiLogAspect` | `controller` 패키지 전체 | 요청/응답/처리시간. 비밀번호는 정규식으로 마스킹 |
| `TradeAuditAspect` | Escrow·Deposit·Ledger의 public 메서드 | 금전 이동 감사 로그 + `planb.audit.intercepted` |

**Actuator**
- `ExpiryBacklogHealthIndicator` — 만료 처리가 밀린 티켓 5건 이상이면 DOWN
- `LedgerIntegrityHealthIndicator` — 차대·보관계정 불일치면 DOWN (1원이라도)
- 커스텀 메트릭 5종 (`common/PlanbMetrics`)
- `build.gradle`에 `micrometer-registry-prometheus` 추가 (없으면 `/actuator/prometheus`가 404)
- `application.yml`에 노출 범위 + `health.show-details: always`

**대시보드** — `src/main/resources/static/index.html` (외부 라이브러리 0개)
- 카드 4종 · 만료임박 목록(1초 카운트다운, 긴급도 색상) · 카테고리 필터
- 차트 2종 — 카테고리별 실효율(CSS 막대), 일별 실효 손실(SVG 꺾은선)
- 정합성 검증 버튼, 동시성 테스트 버튼 2종(락 없음/락 적용 나란히)
- 계정 전환 드롭다운(user01~05) — 알림 안읽음 폴링용
- 5초 폴링 + 1초 카운트다운

**`GET /api/admin/dashboard-summary`** — 상단 카드 재료. **JPA** (독립 건수·합계라 GROUP BY 없음)

**UI 보강 (캡처 직전)**
- `Ticket.seatInfo` (nullable, 범용) — 항공 `27A, 27B` / 기차 `4호차 12석` / 영화 `2관 H열 7·8번` /
  콘서트 `FLOOR B구역 18열` / 스포츠 `1루 내야 290블록`. 전시·호텔·기프티콘은 null
- `Ticket.mileage` (nullable) — **항공권 전용 표시값.** 양도 시 함께 넘어가는 적립 마일
- **드릴다운** — 목록 행을 누르면 좌석(알약으로 분리)·정가/희망가 비율·예약금·추천가
  (표본 수와 산출 단계 포함)·예약 현황·만료 시각이 펼쳐짐. 펼친 상태는 5초 폴링에도 유지
- **밝은 테마 기본 + 다크 토글** — SPEC 10장은 다크였는데 기본을 밝게 바꿈.
  색이 전부 CSS 변수라 `:root[data-theme]`만 갈아끼우면 되고, **토글을 남겨서
  SPEC이 요구한 네온 다크 화면도 그대로 시연 가능**
- 시드 항공권(티켓 10)을 1매 → 2매로 바꿈. 좌석이 여러 개로 나뉘는 걸 보여줄
  활성 항공권이 하나도 없었음

> **마일리지를 원장에 안 넣은 이유.** 마일로 결제까지 하게 하려면 원장에 통화 축이
> 필요하다. 지금 원장은 "이체 1건 = DEBIT 1줄 + CREDIT 1줄, 전체 차대 0"이라는 항등식
> 위에 서 있는데, 통화가 둘이 되면 그 항등식이 통화별로 갈라지고 정합성 검증·동시성
> 검증·검증 스크립트 119개를 전부 다시 짜야 한다. **표시용으로 두는 한 그 위험이 없다.**

> **좌석을 구조화(별도 테이블)하지 않은 이유.** 이 프로젝트는 티켓을 통째로 양도한다.
> 좌석 단위로 나눠 팔지 않으므로 좌석은 계산에 쓰이지 않고 표시만 된다.
> 계산에 안 쓰는 값을 구조화하는 건 비용만 늘린다.

#### ⭐ AOP 프록시 한계를 지표로 증명함

SPEC 7장은 `TradeAuditAspect`가 카운터를 올리라고 했지만 **그러면 숫자가 틀린다.**
프록시는 public 외부 호출만 잡는데, `settle()`·`voidEscrow()`는 package-private이고
내부 호출·스케줄러 경로로 들어온다.

그래서 **도메인 카운터는 서비스에서 직접**(`PlanbMetrics`), AOP는 **"무엇을 봤는지"만**
따로 센다. 검증 스크립트를 돌린 뒤 두 지표를 비교하면:

```
planb.escrow.confirmed             > 0     ← settle()이 실제로 돌았다
planb.audit.intercepted{method=}   reserve, pay, confirm, hold, capture,
                                   release, forfeit, transfer, …
                                   ↑ settle 없음, voidEscrow 없음
```

**돌긴 돌았는데 AOP에는 흔적이 없다.** `verify.sh` 9-2절이 이걸 단정으로 박아뒀다.
8단계의 `reconcileBalances()` 사건과 같은 뿌리 — 프록시 한계는 트랜잭션과 AOP에
똑같이 적용된다. `NOTES.md` 18절, 보고서 5-12절 재료.

#### 확정한 것

| 항목 | 결정 | 근거 |
|---|---|---|
| 만료 백로그 임계값 | **5건** | 스케줄러가 1분 주기라 정상 상태에서도 소수는 남음. 1건이면 깜빡여서 못 믿을 지표가 됨 |
| 원장 정합성 임계값 | **없음 (1원도 DOWN)** | 이체는 원장 2줄을 한 트랜잭션에 씀. 안 맞으면 곧 버그 |
| 헬스체크 범위 | SUM 쿼리만 (회원별 대조 제외) | 헬스는 수십 초마다 불림. 전 회원 순회는 검증 API의 몫 |
| 카운터 위치 | 서비스 직접 | AOP로 세면 자동확정이 통째로 빠짐 |
| `planb.pair.matched` | **`planb.reservation.created`로 대체** | 1매 대기가 5단계에서 사라져 셀 사건 자체가 없음 |
| 대시보드 차트 | CSS/SVG 직접 | CDN에 기대면 오프라인 시연에서 화면이 빔 |
| 카드 순서 | 만료임박 → 실효 → 예약 → 거래액 | "가만히 두면 손해 나는 것"이 위로 |

### 10단계 — 전체 검증

**`scripts/verify.sh` 119/119 통과** (55 → 81 → 97 → 119).

PLAN 10단계의 필수 시나리오 6종이 전부 스크립트 안에 있다:

| # | 시나리오 | 자리 | 결과 |
|---|---|---|---|
| 1 | 정상 거래 (등록→예약→결제→확정) | step 1 | ✅ 판매자는 확정 전까지 못 받음, 수수료 5% 차감 |
| 2 | 만료 실효 | step 4-3 | ✅ 스케줄러가 티켓 3건 EXPIRED, 알림 발송 |
| 3 | 예약금 몰수 | step 3·4 | ✅ 10분 후 취소 / 제한시간 초과 둘 다 PLATFORM으로 |
| 4 | ~~부분성사~~ → **청약철회·판매자 철회** | step 2·5 | ✅ 둘 다 전액 환불 (5단계 재설계로 대체) |
| 5 | 동시성 락 없음 vs 적용 | step 8 | ✅ 5~9건 중복 → 1건, 그런데 원장은 내내 맞음 |
| 6 | **최종 정합성** | step 10 | ✅ PASS |

> 4번은 SPEC의 부분 성사가 5단계에서 사라져서 대체했다. 검증하려던 것("한쪽이
> 이탈했을 때 돈이 어디로 가는가")은 청약철회(전액 환불)와 몰수(PLATFORM)에
> 그대로 살아 있다.

**최종 상태** — 차대 4,773,800 일치 / ESCROW_POOL 0 / DEPOSIT_POOL 0 / PLATFORM 66,945

전 시나리오를 돌린 뒤에도 **돈이 한 푼도 새지 않았다.** 이게 보고서 6장의 결론이다.

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

### 미구현 — 이번 범위에서 의도적으로 제외 (보고서 7장)
- **캡처** — 아래 "캡처 진행 상황" 참조. 코드는 다 됐고 화면만 남음
- **PDF 보고서** — 11단계
- SPEC 11장의 제외 항목들(판매자 경고·제재, 대기자 프로필, 신뢰도 등급, JWT, Docker/K8s)

### 10단계에서 정리한 것
- **`Ticket.sourceTicketId` 제거** — 엔티티·`TicketResponse`·시드 컬럼까지. 1매 분할 발행의
  잔재라 5단계 재설계 때 같이 빠졌어야 했음. 읽고 쓰는 코드가 0건이었음
- **`TicketStatus` javadoc** — 아직 1매 대기를 설명하고 있어서 새 흐름으로 고침
- 캡처를 아직 안 찍었을 때가 응답 모양을 바꿀 마지막 기회라 이 시점에 몰아서 함

> 시드를 고치다 한 번 깨뜨렸다. `NULL, NULL, DATEADD(` 패턴으로 지웠는데
> **의도한 자리(`expiry_warned_at`, `source_ticket_id`)가 아니라 앞쪽
> (`valid_until`, `extended_until`)이 먼저 걸렸다.** 앱이 "Column count does not match"로
> 아예 안 떴다. 상태 리터럴(`'LISTED'`)을 앵커로 삼아 다시 하고,
> 35행 전부 컬럼 수와 값 수가 같은지 세어서 확인했다.
> **일괄 치환은 "몇 건 바뀌었나"가 아니라 "맞는 자리가 바뀌었나"를 봐야 한다.**

### 남겨둔 것
- **`Escrow.quantity`** — 항상 `ticket.quantity` 복사본이라 독립적 의미는 없지만,
  거래 시점의 수량을 기록으로 남기는 값이라 그대로 둠. 통계에도 무해
  (`amount`와 `originalPrice`가 둘 다 전체 기준이라 비율이 맞음)

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
`docs/captures/`에 남아 있는 것 중 **아직 유효한 것**: `01`, `02`, `02b`, `04`, `04b`, `05`.
`03_swagger_전체API`는 7~9단계에서 엔드포인트가 늘어 다시 찍어야 한다.
`image-1786099*.png` 10장은 5단계 재설계로 무효화된 것들 — 지워도 된다.

**나머지는 아직 안 찍었다.** 코드가 다 끝났으므로 이제 한 번에 찍으면 된다.
자세한 건 아래 8절.

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

## 8. 남은 일 — 캡처 + 11단계(PDF 보고서)

### 8-1. 캡처 — 이제 한 번에 찍으면 된다

**코드는 다 됐다.** 9·10단계가 기존 API를 안 건드렸으므로 지금 찍는 건 끝까지 살아남는다.

인메모리 H2라 **한 세션 = 앱 새로 띄우고 `verify.sh` 완주**다. 스크립트가 📸 표시를
띄우는 지점이 곧 찍을 자리이고, 지금 20곳이다.

```bash
./gradlew bootRun
```

```bash
./scripts/verify.sh
```

브라우저 탭을 미리 네 개 열어두면 편하다:

| 탭 | 주소 |
|---|---|
| 대시보드 | `http://localhost:8080/` |
| Swagger | `http://localhost:8080/swagger-ui.html` |
| H2 콘솔 | `http://localhost:8080/h2-console` |
| Health | `http://localhost:8080/actuator/health` |

**우선순위** — 시간이 없으면 위에서부터:

1. `22_동시성_락없음` / `23_동시성_락적용` ⭐⭐ — PDF 하이라이트.
   **락 없음 응답의 `ledgerBalanced: true`가 같이 보이게** 찍을 것
2. `29_최종정합성검증` ⭐ — 보고서 6장의 결론
3. `27_대시보드_전체` — 차별화 포인트. 카운트다운이 붉게 점멸하는 순간을 노릴 것
4. `26_AOP로그` — 콘솔 `[AUDIT]` 줄 + `planb.audit.intercepted` 태그 목록(settle 없음)
5. `08_정합성검증_PASS`, `19_가격추천`, `24_actuator_health`
6. 나머지

`docs/captures/`에 `NN_설명.png`로 모을 것. `image-1786099*.png` 10장은 5단계 재설계로
무효화된 것들이라 지우면 된다.

### 8-2. 11단계 — PDF 보고서

목차는 `PLAN.md` 11단계에 있고, **5장(설계 판단 지점)이 승부처**다.
`NOTES.md` 부록의 구성표를 그대로 옮기면 5-1 ~ 5-14가 나온다.

SPEC과 달라진 것들은 보고서에서 **"바꾼 이유"로 쓸 자리**다. 숨길 게 아니라 재료다:

| SPEC | 실제 | 어디에 |
|---|---|---|
| 1매 대기 매칭 | 제거, 예약금을 본류로 | 5-3 (덜어내는 판단) |
| 수수료 없음 | 5% | 5-5 (유인 구조) |
| 자동확정 = 만료 시각 | 만료 −10분 | 5-4 (충돌을 없앤 판단) |
| `CASE WHEN`으로 구간 나눔 | 경계를 자바 한 곳에 | 5-8 (잠복 버그를 피한 판단) |
| AOP가 카운터를 올림 | 서비스가 올림 | 5-12 (프록시 한계) |
| `planb.pair.matched` | `planb.reservation.created` | 9단계 표 |
| 엔티티 8개 | 7개 | 5-3 |

7장(한계)에 옮길 것: `NOTES.md` 15절, 아래 5절 "알려진 이슈", SPEC 11장 제외 항목,
그리고 **잔액이 공개 조회에 노출된다는 점**.

---

## 9. 참고 문서

| 파일 | 내용 |
|---|---|
| `SPEC.md` | 원본 설계 사양서 (5단계 재설계분은 미반영) |
| `PLAN.md` | 11단계 진행 계획 |
| `NOTES.md` | **설계 판단 근거** — 보고서 5장 재료 |
| `scripts/verify.sh` | 전체 시나리오 검증 (55개) + 캡처 가이드 |
