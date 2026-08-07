package com.skala.planbmarket.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.skala.planbmarket.domain.entity.Deposit;
import com.skala.planbmarket.domain.entity.Escrow;
import com.skala.planbmarket.domain.entity.Member;
import com.skala.planbmarket.domain.enums.DepositStatus;
import com.skala.planbmarket.domain.enums.EntryType;
import com.skala.planbmarket.domain.enums.EscrowStatus;
import com.skala.planbmarket.domain.enums.LedgerReason;
import com.skala.planbmarket.domain.enums.SystemAccount;
import com.skala.planbmarket.dto.response.IntegrityCheckResponse;
import com.skala.planbmarket.repository.DepositRepository;
import com.skala.planbmarket.repository.EscrowRepository;
import com.skala.planbmarket.repository.LedgerRepository;
import com.skala.planbmarket.repository.MemberRepository;

import lombok.RequiredArgsConstructor;

/**
 * 관리·검증 서비스.
 *
 * 정합성 검증이 이 프로젝트의 핵심 장치임. 원장을 append-only로 쌓는 이유가
 * 바로 이걸 할 수 있게 하려는 거고, 반대로 이 검증이 없으면 원장은 그냥 로그일 뿐임.
 *
 * 검증이 실패했다는 건 어딘가에 버그가 있다는 뜻이고, 그 버그를 찾아가는 과정이
 * 곧 이 설계가 값을 하는 순간임.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminService {

    private final LedgerRepository ledgerRepository;
    private final MemberRepository memberRepository;
    private final EscrowRepository escrowRepository;
    private final DepositRepository depositRepository;

    public IntegrityCheckResponse integrityCheck() {
        // ─── 규칙 1: 회원별 잔액 == 원장 합 ───
        Map<String, Long> ledgerBalances = new HashMap<>();
        for (LedgerRepository.AccountBalance row : ledgerRepository.findAccountBalances(EntryType.CREDIT)) {
            ledgerBalances.put(row.getAccountId(), row.getBalance());
        }

        List<IntegrityCheckResponse.MismatchedMember> mismatched = new ArrayList<>();
        for (Member member : memberRepository.findAll()) {
            // 원장이 아예 없는 회원은 잔액이 0이어야 맞음. 가입만 하고 충전 안 한 경우
            long fromLedger = ledgerBalances.getOrDefault(member.getId(), 0L);
            if (member.getBalance() != fromLedger) {
                mismatched.add(new IntegrityCheckResponse.MismatchedMember(
                        member.getId(), member.getBalance(), fromLedger,
                        member.getBalance() - fromLedger));
            }
        }

        // ─── 규칙 2: 전체 차대 일치 ───
        long totalDebit = ledgerRepository.sumAmountByEntryType(EntryType.DEBIT);
        long totalCredit = ledgerRepository.sumAmountByEntryType(EntryType.CREDIT);

        // ─── 규칙 3: 보관 계정 잔액 == 진행 중 거래 금액 합 ───
        long escrowPoolBalance = ledgerRepository.balanceOf(
                SystemAccount.ESCROW_POOL.name(), EntryType.CREDIT);
        long heldEscrowTotal = escrowRepository.sumHeldAmount(EscrowStatus.HOLDING);

        // ─── 고아 검사: 돈이 움직였다고 기록된 건 있는데 원장에 흔적이 없는 경우 ───
        // 잔액 합계는 맞는데 개별 기록이 빠진 상황을 잡음. 위 세 규칙은 총액만 보기 때문에
        // "기록을 아예 안 남기고 잔액도 안 건드린" 유령 데이터는 못 걸러냄
        long orphanEscrows = 0;
        for (Escrow escrow : escrowRepository.findAll()) {
            if (!ledgerRepository.existsByRefTypeAndRefIdAndReason(
                    "ESCROW", escrow.getId(), LedgerReason.PURCHASE)) {
                orphanEscrows++;
            }
        }

        long orphanDeposits = 0;
        for (Deposit deposit : depositRepository.findAll()) {
            if (!ledgerRepository.existsByRefTypeAndRefIdAndReason(
                    "DEPOSIT", deposit.getId(), LedgerReason.DEPOSIT_HOLD)) {
                orphanDeposits++;
            }
        }

        // ─── 예약금 보관 계정도 같은 방식으로 대조 ───
        long depositPoolBalance = ledgerRepository.balanceOf(
                SystemAccount.DEPOSIT_POOL.name(), EntryType.CREDIT);
        long heldDepositTotal = depositRepository.sumHeldAmount(DepositStatus.HELD);

        boolean memberBalanceMatch = mismatched.isEmpty();
        boolean ledgerBalanced = totalDebit == totalCredit;
        boolean escrowPoolMatch = escrowPoolBalance == heldEscrowTotal;
        boolean depositPoolMatch = depositPoolBalance == heldDepositTotal;

        return new IntegrityCheckResponse(
                memberBalanceMatch && ledgerBalanced && escrowPoolMatch && depositPoolMatch
                        && orphanEscrows == 0 && orphanDeposits == 0,
                memberBalanceMatch,
                mismatched,
                ledgerBalanced,
                totalDebit,
                totalCredit,
                escrowPoolMatch,
                escrowPoolBalance,
                heldEscrowTotal,
                depositPoolMatch,
                depositPoolBalance,
                heldDepositTotal,
                orphanEscrows,
                orphanDeposits,
                ledgerRepository.balanceOf(SystemAccount.PLATFORM.name(), EntryType.CREDIT),
                LocalDateTime.now());
    }
}
