package com.skala.planbmarket.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.skala.planbmarket.domain.entity.Ledger;
import com.skala.planbmarket.domain.entity.Member;
import com.skala.planbmarket.domain.enums.EntryType;
import com.skala.planbmarket.domain.enums.LedgerReason;
import com.skala.planbmarket.domain.enums.SystemAccount;
import com.skala.planbmarket.exception.Error;
import com.skala.planbmarket.exception.ResponseException;
import com.skala.planbmarket.repository.LedgerRepository;
import com.skala.planbmarket.repository.MemberRepository;

import lombok.RequiredArgsConstructor;

/**
 * 정산 원장 서비스. 이 프로젝트에서 돈이 움직이는 유일한 통로임.
 *
 * 다른 서비스는 회원 잔액을 직접 건드리지 않고 전부 여기 transfer()를 통해서만 움직임.
 * 그렇게 강제해야 "잔액을 바꿨는데 원장을 안 남기는" 실수가 원천적으로 안 생김.
 * 잔액과 원장이 따로 놀 수 있는 지점을 하나로 좁혀둔 것.
 *
 * 이체 하나는 반드시 원장 2줄임 — 나가는 쪽 DEBIT, 들어오는 쪽 CREDIT, 금액은 같음.
 * 이래야 전체 원장에서 SUM(DEBIT) == SUM(CREDIT)이 항상 성립하고,
 * 그 항등식이 깨졌다는 건 곧 코드에 버그가 있다는 뜻이 됨.
 */
@Service
@RequiredArgsConstructor
public class LedgerService {

    private final LedgerRepository ledgerRepository;
    private final MemberRepository memberRepository;

    /**
     * 계정 간 이체. 원장 2줄을 한 트랜잭션 안에서 같이 남김.
     *
     * REQUIRED로 동작해서 부르는 쪽 트랜잭션에 그대로 참여함. 일부러 그렇게 둔 것 —
     * 별도 트랜잭션으로 떼면 구매가 실패했는데 원장만 남는 상황이 생길 수 있음.
     */
    @Transactional
    public void transfer(String fromAccount, String toAccount, long amount,
                         LedgerReason reason, String refType, Long refId, String memo) {
        if (amount <= 0) {
            // 0원 이체는 기록할 이유가 없고, 음수는 방향을 뒤집는 셈이라 둘 다 막음.
            // 방향은 entryType으로만 표현한다는 규칙을 여기서 지킴
            throw new IllegalArgumentException("이체 금액은 0보다 커야 함: " + amount);
        }
        if (fromAccount.equals(toAccount)) {
            throw new IllegalArgumentException("같은 계정끼리는 이체할 수 없음: " + fromAccount);
        }

        record(fromAccount, EntryType.DEBIT, amount, reason, refType, refId, memo);
        record(toAccount, EntryType.CREDIT, amount, reason, refType, refId, memo);
    }

    /**
     * 원장 한 줄 기록 + 계정 잔액 반영.
     *
     * 회원 계정이면 Member.balance를 실제로 움직이고 그 값을 balanceAfter에 넣음.
     * 시스템 계정은 Member 행이 없으니 원장을 합산해서 구함. 합산 쿼리가 한 번 더 나가지만,
     * 시스템 계정 잔액을 따로 들고 있으면 그게 또 원장과 어긋날 수 있는 지점이 됨 —
     * 어긋날 수 있는 곳을 늘리지 않으려고 매번 원장에서 다시 구하는 쪽을 택했음.
     */
    private void record(String accountId, EntryType entryType, long amount,
                        LedgerReason reason, String refType, Long refId, String memo) {
        long balanceAfter;

        if (SystemAccount.isSystemAccount(accountId)) {
            long current = ledgerRepository.balanceOf(accountId, EntryType.CREDIT);
            balanceAfter = (entryType == EntryType.CREDIT) ? current + amount : current - amount;
        } else {
            Member member = memberRepository.findById(accountId)
                    .orElseThrow(() -> new ResponseException(Error.DATA_NOT_FOUND, "회원 ID " + accountId));

            if (entryType == EntryType.DEBIT) {
                // 마지막 방어선임. 부르는 쪽에서 락을 잡고 미리 확인하는 게 원칙이고,
                // 여기까지 왔다는 건 그 확인이 빠졌거나 동시성으로 뚫렸다는 뜻
                if (!member.canAfford(amount)) {
                    throw new ResponseException(Error.INSUFFICIENT_BALANCE,
                            "잔액 " + member.getBalance() + "원, 필요 " + amount + "원");
                }
                member.decreaseBalance(amount);
            } else {
                member.increaseBalance(amount);
            }
            balanceAfter = member.getBalance();
        }

        ledgerRepository.save(Ledger.of(accountId, entryType, amount, balanceAfter,
                reason, refType, refId, memo));
    }
}
