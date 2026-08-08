package com.skala.planbmarket.health;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import com.skala.planbmarket.domain.enums.DepositStatus;
import com.skala.planbmarket.domain.enums.EntryType;
import com.skala.planbmarket.domain.enums.EscrowStatus;
import com.skala.planbmarket.domain.enums.SystemAccount;
import com.skala.planbmarket.repository.DepositRepository;
import com.skala.planbmarket.repository.EscrowRepository;
import com.skala.planbmarket.repository.LedgerRepository;

import lombok.RequiredArgsConstructor;

/**
 * 원장 차대와 보관 계정 잔액이 맞는지 본다.
 *
 * <p><b>왜 여기서 전체 정합성 검증을 안 부르는가.</b> {@code GET /api/admin/integrity-check}는
 * 전 회원을 순회하고 전 에스크로·예약금을 하나씩 훑는다. 검증 API로는 맞지만
 * <b>헬스체크로는 과하다</b> — 헬스 엔드포인트는 모니터링이 수십 초마다 두드리는 자리라,
 * 데이터가 늘수록 헬스체크가 서비스를 갉아먹는 구조가 된다.
 *
 * <p>그래서 여기서는 <b>SUM 쿼리 몇 개로 끝나는 것만</b> 본다. 데이터가 몇 배로 늘어도
 * 비용이 그대로다:
 * <ol>
 *   <li>전체 차대 — {@code SUM(DEBIT) == SUM(CREDIT)}</li>
 *   <li>{@code ESCROW_POOL} 잔액 == 보관 중 거래액 합</li>
 *   <li>{@code DEPOSIT_POOL} 잔액 == 홀드 중 예약금 합</li>
 * </ol>
 *
 * <p><b>여기서 걸러지는 것과 아닌 것.</b> 회원별 잔액 대조(정합성 규칙 1)는 뺐다.
 * 전 회원 순회가 필요해서다. 대신 그 검사는 검증 API에 그대로 남아 있다 —
 * <b>헬스체크는 "지금 뭔가 크게 잘못됐나"를 싸게 보고, 검증 API는 "정확히 어디가
 * 어긋났나"를 비싸게 본다.</b> 둘은 대체재가 아니라 역할이 다르다.
 *
 * <p>임계값은 없다. <b>1원이라도 어긋나면 DOWN</b>이다. 만료 백로그와 달리 여기엔
 * "정상적으로 잠깐 어긋나는" 구간이 없다 — 이체는 원장 2줄을 한 트랜잭션에서 쓰므로,
 * 차대가 안 맞는다는 건 곧 코드에 버그가 있다는 뜻이다.
 */
@Component("ledgerIntegrity")
@RequiredArgsConstructor
public class LedgerIntegrityHealthIndicator implements HealthIndicator {

    private final LedgerRepository ledgerRepository;
    private final EscrowRepository escrowRepository;
    private final DepositRepository depositRepository;

    @Override
    public Health health() {
        long totalDebit = ledgerRepository.sumAmountByEntryType(EntryType.DEBIT);
        long totalCredit = ledgerRepository.sumAmountByEntryType(EntryType.CREDIT);

        long escrowPool = ledgerRepository.balanceOf(
                SystemAccount.ESCROW_POOL.name(), EntryType.CREDIT);
        long heldEscrow = escrowRepository.sumHeldAmount(EscrowStatus.HOLDING);

        long depositPool = ledgerRepository.balanceOf(
                SystemAccount.DEPOSIT_POOL.name(), EntryType.CREDIT);
        long heldDeposit = depositRepository.sumHeldAmount(DepositStatus.HELD);

        boolean balanced = totalDebit == totalCredit;
        boolean escrowMatch = escrowPool == heldEscrow;
        boolean depositMatch = depositPool == heldDeposit;
        boolean healthy = balanced && escrowMatch && depositMatch;

        Health.Builder builder = healthy ? Health.up() : Health.down();
        return builder
                .withDetail("ledgerBalanced", balanced)
                .withDetail("totalDebit", totalDebit)
                .withDetail("totalCredit", totalCredit)
                .withDetail("escrowPoolMatch", escrowMatch)
                .withDetail("escrowPoolBalance", escrowPool)
                .withDetail("heldEscrowTotal", heldEscrow)
                .withDetail("depositPoolMatch", depositMatch)
                .withDetail("depositPoolBalance", depositPool)
                .withDetail("heldDepositTotal", heldDeposit)
                .withDetail("scope", "SUM 쿼리로 끝나는 검사만. 회원별 대조는 "
                        + "GET /api/admin/integrity-check 에 있음")
                .build();
    }
}
