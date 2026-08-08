# PlanB Market

> 소멸성 자산(만료 기한이 있는 티켓·예약권)의 P2P 양도 플랫폼

만료된 영화표, 사정이 생겨 못 탄 비행기표. 중고거래는 활발한데 이런 것들은 그냥 버려진다.
이유는 하나다 — **정해진 시각이 지나면 가치가 0이 된다.**

일반 중고거래는 상품이 가만히 있지만 예매권은 **거래하는 동안에도 계속 소멸을 향해 간다.**
이 프로젝트는 그 성질을 다룬다.

---

## 실행

**Java 21만 있으면 된다.** Gradle은 래퍼가 알아서 받는다.

```bash
java -version
```

없으면 (맥):

```bash
brew install --cask temurin@21
```

### 맥 · 리눅스

```bash
./gradlew bootRun
```

### 윈도우

```bash
run.bat
```

> `run.bat` / `verify.bat`은 윈도우 CMD 편의용이다.
> cmd에서는 `./gradlew`가 `.`을 명령어로 읽어서 안 돌아간다.
> 맥·리눅스에서는 무시하면 된다.

### 주소

| 주소 | 용도 |
|---|---|
| http://localhost:8080/ | **대시보드** — 소멸 모니터링 |
| http://localhost:8080/swagger-ui.html | API 문서 겸 테스트 (40개) |
| http://localhost:8080/actuator/health | 커스텀 HealthIndicator 2종 |
| http://localhost:8080/h2-console | DB 콘솔 |

H2 콘솔은 JDBC URL을 **`jdbc:h2:mem:planb`** 로 바꿔야 한다. 사용자 `sa`, 비밀번호 없음.

시연 계정 `user01` ~ `user05`, 비밀번호 전부 `pass1234`.

---

## 전체 검증

앱을 띄운 뒤 **다른 터미널에서**:

```bash
./scripts/verify.sh
```

필수 시나리오 6종을 순서대로 돌리며 **119개**를 확인한다. 2~3분 걸린다
(스케줄러가 도는 걸 실제로 기다리는 구간이 있다).

> **인메모리 H2라 앱을 끄면 데이터가 사라진다.** 스크립트가 데이터를 실제로 바꾸므로
> 갓 띄운 앱에 한 번만 돌릴 것. 다시 돌리려면 앱을 재시작하면 된다.

---

## 봐주셨으면 하는 것 5가지

### 1. 동시성 — 락이 없으면 실제로 깨진다

같은 판매 건에 20개 스레드가 동시에 예약을 건다. **`useLock`만 바꿔서 두 번** 돌리면 된다.

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

| | 성공 | 예약 생성 | dataIntegrity | **ledgerBalanced** |
|---|---|---|---|---|
| 락 없음 | 5~9 | 5~9건 | X | **O** |
| 락 적용 | 1 | 1건 | O | O |

**락 없이 중복 예약이 9건 생겨도 원장 차대는 맞는다.** 홀드마다 원장 2줄이 제대로 남기
때문이다. 돈은 한 푼도 안 샜는데 티켓 하나가 9명에게 잠겼다 —
**정합성 검증이 못 잡는 종류의 버그가 있다**는 뜻이다.

끝나면 만들어진 예약을 전액 환불로 되돌리므로 몇 번이든 다시 돌릴 수 있다.
대시보드 하단 버튼으로도 된다.

### 2. append-only 정산 원장과 자가검증

```bash
curl http://localhost:8080/api/admin/integrity-check
```

모든 금전 이동이 **DEBIT 1줄 + CREDIT 1줄 쌍**으로만 기록된다.
`Ledger`는 setter가 없고 생성자가 private이며 전 컬럼이 `updatable = false`다.

검증하는 것 5가지 — 회원별 잔액 == 원장 합 / 전체 차대 일치 /
`ESCROW_POOL` 잔액 == 보관 중 거래액 / `DEPOSIT_POOL` 잔액 == 홀드 중 예약금 / 고아 레코드 0건.

`EXTERNAL` · `ESCROW_POOL` · `DEPOSIT_POOL` · `PLATFORM` 네 시스템 계정을 둔 이유는
`NOTES.md` 1절에 있다.

### 3. MyBatis / JPA 경계

같은 "통계"인데 도구가 다르다. 기준은 **"여러 행을 집계해서 줄여야 하는가"**.

| API | 도구 | 이유 |
|---|---|---|
| `/api/analysis/category-summary` | MyBatis | 3테이블 조인, 35행 → 8행으로 접음 |
| `/api/members/{id}/summary` | **JPA** | 단일 회원 건수·합계. 접을 행이 없음 |

경계에 양쪽을 하나씩 남겨뒀다. `NOTES.md` 12절.

### 4. AOP 프록시 한계 — 지표로 증명

```bash
curl http://localhost:8080/actuator/metrics/planb.escrow.confirmed
curl http://localhost:8080/actuator/metrics/planb.audit.intercepted
```

`planb.escrow.confirmed`는 올라가 있는데 `planb.audit.intercepted`의 `method` 태그에는
**`settle`이 없다.** 프록시는 public 외부 호출만 잡는데 `settle()`은 package-private이고
내부 호출·스케줄러 경로로 들어오기 때문이다.

그래서 SPEC과 달리 카운터를 AOP가 아니라 서비스에서 직접 올린다.
AOP로 세면 자동확정이 통째로 빠진다. `NOTES.md` 18절.

### 5. 실시간 소멸 모니터링 대시보드

http://localhost:8080/ — 외부 라이브러리 0개. 차트도 CSS/SVG로 직접 그렸다.

카운트다운이 1초마다 줄고, 2시간 미만은 붉게 점멸한다.
행을 누르면 좌석·추천가(표본 수 포함)·예약 현황이 펼쳐진다.
우측 상단 버튼으로 다크 테마 전환.

---

## 기술 스택

```
Java 21 / Spring Boot 3.3.5 / Gradle 8.14.5 (wrapper)
Spring Data JPA (Hibernate)   CRUD·상태 전이·트랜잭션·비관적 락
MyBatis                       집계·통계 조회
H2 (in-memory)
springdoc-openapi (Swagger UI)
Spring Boot Actuator + Micrometer (+ Prometheus)
Spring AOP / Bean Validation
```

엔티티 7개 · API 40개 · 검증 119개.

API 40개 = 회원 12 · 거래 7 · 티켓 6 · 판매 등록 5 · 알림 4 · 관리·검증 3 · 분석·통계 3.

---

## 문서

| 파일 | 내용 |
|---|---|
| [SPEC.md](SPEC.md) | 원본 설계 사양서 |
| [PLAN.md](PLAN.md) | 11단계 진행 계획 |
| [PROGRESS.md](PROGRESS.md) | **단계별 산출물·확정 정책·알려진 이슈** |
| [NOTES.md](NOTES.md) | **설계 판단 근거** — 왜 그렇게 했는가 |

### SPEC과 달라진 곳

구현하면서 SPEC을 그대로 따르면 문제가 생기는 지점들이 나왔다.
바꾼 것과 근거는 전부 `NOTES.md`에 남겨뒀다.

| SPEC | 실제 | 근거 |
|---|---|---|
| 1매 대기 매칭 | 제거, 예약금을 모든 구매의 필수 경로로 | NOTES 4절 |
| 중개 수수료 없음 | 5% | NOTES 5절 |
| 자동확정 = 만료 시각 | 만료 −10분 | NOTES 7절 |
| `CASE WHEN`으로 구간 분류 | 경계를 자바 한 곳에 | NOTES 12-2절 |
| AOP가 메트릭 카운터 증가 | 서비스가 증가 | NOTES 18절 |
| 다크 테마 | 밝은 테마 기본 + 다크 토글 | 둘 다 확인 가능 |
