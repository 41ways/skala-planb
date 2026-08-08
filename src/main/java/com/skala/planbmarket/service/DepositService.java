package com.skala.planbmarket.service;

import java.time.LocalDateTime;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.skala.planbmarket.common.PlanbMetrics;
import com.skala.planbmarket.domain.entity.Deposit;
import com.skala.planbmarket.domain.entity.Listing;
import com.skala.planbmarket.domain.entity.Member;
import com.skala.planbmarket.domain.enums.DepositStatus;
import com.skala.planbmarket.domain.enums.LedgerReason;
import com.skala.planbmarket.domain.enums.SystemAccount;
import com.skala.planbmarket.exception.Error;
import com.skala.planbmarket.exception.ResponseException;
import com.skala.planbmarket.repository.DepositRepository;

import lombok.RequiredArgsConstructor;

/**
 * 예약금 서비스.
 *
 * 예약금은 홀드된 순간 회원 잔액에서 빠져나가 DEPOSIT_POOL에 잡힘. 거기서 어디로 가느냐가
 * 네 갈래임 — 본결제에 넘기거나(CAPTURED), 돌려주거나(RELEASED), 몰수하거나(FORFEITED).
 * 어느 쪽이든 원장 2줄이 같이 남고, 상태는 HELD에서 딱 한 번만 바뀜.
 *
 * 어느 갈래로 갈지 판단하는 건 여기가 아니라 부르는 쪽(EscrowService, 스케줄러)임.
 * "귀책이 누구에게 있는가"는 거래의 맥락을 알아야 판단할 수 있는 거라,
 * 예약금 서비스는 시키는 대로 돈만 옮기고 판단은 안 함.
 */
@Service
@RequiredArgsConstructor
public class DepositService {

    private static final String REF_TYPE = "DEPOSIT";

    private final DepositRepository depositRepository;
    private final LedgerService ledgerService;
    private final PlanbMetrics metrics;

    /**
     * 예약금 홀드. 회원 잔액 → DEPOSIT_POOL.
     *
     * 잔액이 모자라면 여기서 막힘. 부르는 쪽에서 미리 확인하는 게 원칙이지만
     * LedgerService가 마지막 방어선을 한 겹 더 대고 있음.
     */
    @Transactional
    public Deposit hold(Member member, Listing listing, long amount, LocalDateTime paymentDeadline) {
        if (!member.canAfford(amount)) {
            throw new ResponseException(Error.INSUFFICIENT_BALANCE,
                    "잔액 " + member.getBalance() + "원, 예약금 " + amount + "원");
        }

        Deposit deposit = depositRepository.save(Deposit.builder()
                .member(member)
                .listing(listing)
                .amount(amount)
                .status(DepositStatus.HELD)
                .paymentDeadline(paymentDeadline)
                .build());

        ledgerService.transfer(member.getId(), SystemAccount.DEPOSIT_POOL.name(), amount,
                LedgerReason.DEPOSIT_HOLD, REF_TYPE, deposit.getId(), "구매 예약금 홀드");

        return deposit;
    }

    /**
     * 본결제에 충당. DEPOSIT_POOL → ESCROW_POOL.
     *
     * 구매자 잔액은 안 건드림. 예약금은 홀드 시점에 이미 빠져나갔기 때문에,
     * 여기서 또 빼면 같은 돈을 두 번 받는 셈이 됨. 보관 계정끼리만 옮기면 됨.
     */
    @Transactional
    public void capture(Deposit deposit, LocalDateTime now, Long escrowId) {
        deposit.resolve(DepositStatus.CAPTURED, now);
        ledgerService.transfer(SystemAccount.DEPOSIT_POOL.name(), SystemAccount.ESCROW_POOL.name(),
                deposit.getAmount(), LedgerReason.DEPOSIT_CAPTURE, "ESCROW", escrowId,
                "예약금을 본결제에 충당");
    }

    /** 전액 환불. DEPOSIT_POOL → 회원. 귀책이 없을 때 */
    @Transactional
    public void release(Deposit deposit, LocalDateTime now, String memo) {
        deposit.resolve(DepositStatus.RELEASED, now);
        ledgerService.transfer(SystemAccount.DEPOSIT_POOL.name(), deposit.getMember().getId(),
                deposit.getAmount(), LedgerReason.DEPOSIT_RELEASE, REF_TYPE, deposit.getId(), memo);
    }

    /**
     * 몰수. DEPOSIT_POOL → PLATFORM. 이탈했을 때.
     *
     * 판매자가 아니라 플랫폼으로 감. 판매자에게 주면 이탈을 유도할 유인이 생기고,
     * 실제로 판매자가 손해 본 건 시간이지 돈이 아님.
     */
    @Transactional
    public void forfeit(Deposit deposit, LocalDateTime now, String memo) {
        deposit.resolve(DepositStatus.FORFEITED, now);
        // 몰수는 예약 취소(사용자)와 제한시간 초과(스케줄러) 두 경로로 들어온다.
        // 여기서 세면 둘 다 잡힌다 — AOP로는 스케줄러 경로가 안 잡힌다
        metrics.depositForfeited();
        ledgerService.transfer(SystemAccount.DEPOSIT_POOL.name(), SystemAccount.PLATFORM.name(),
                deposit.getAmount(), LedgerReason.DEPOSIT_FORFEIT, REF_TYPE, deposit.getId(), memo);
    }
}
