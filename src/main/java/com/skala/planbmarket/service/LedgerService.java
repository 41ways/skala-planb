package com.skala.planbmarket.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
     * 잔액을 원장 합계에 맞춰 되돌리고, 어긋나 있던 회원을 알려줌.
     * <b>동시성 시뮬레이터의 뒷정리 전용</b>이며 평소 경로에서는 부르지 않는다.
     *
     * <p>락 없이 돌린 시뮬레이션은 잔액에 <b>lost update</b>를 남긴다. 두 트랜잭션이
     * 같은 잔액을 읽고 각자 뺀 값을 써서 앞의 차감이 덮어써지는 것. 원장에는 두 줄 다
     * 남으니 그때부터 "잔액 != 원장 합"이 되고 정합성 검증이 영구히 실패한다.
     * 되돌릴 방법이 없으면 락 없음/락 적용을 나란히 시연할 수가 없다.
     *
     * <p><b>왜 하필 여기에 두는가.</b> 잔액을 바꾸는 통로를 LedgerService 하나로
     * 좁혀둔 게 이 프로젝트의 규칙이다. 되돌리는 것도 잔액을 바꾸는 일이니
     * 다른 서비스에 두면 그 규칙에 구멍이 두 개가 된다. 예외를 만들더라도
     * <b>같은 문 안에</b> 두는 게 맞다.
     *
     * <p>고치는 방향은 <b>잔액을 원장에 맞추는</b> 쪽이다. 원장이 사실이고 잔액은
     * 빠르게 읽으려고 들고 있는 사본이다. 원장은 append-only라 애초에 고칠 수도 없다.
     *
     * @return 어긋나 있던 회원과 금액. 비어 있으면 멀쩡했던 것
     */
    @Transactional
    public List<String> reconcileBalances() {
        Map<String, Long> fromLedger = new HashMap<>();
        for (LedgerRepository.AccountBalance row : ledgerRepository.findAccountBalances(EntryType.CREDIT)) {
            fromLedger.put(row.getAccountId(), row.getBalance());
        }

        List<String> repaired = new ArrayList<>();
        for (Member member : memberRepository.findAll()) {
            long difference = member.reconcileBalance(fromLedger.getOrDefault(member.getId(), 0L));
            if (difference != 0) {
                repaired.add(member.getId() + " (" + (difference > 0 ? "+" : "") + difference + "원)");
            }
        }
        return repaired;
    }

    /**
     * 원장 한 줄 기록 + 계정 잔액 반영.
     *
     * 회원 계정이면 Member.balance를 실제로 움직이고 그 값을 balanceAfter에 넣음.
     * 시스템 계정은 Member 행이 없으니 원장을 합산해서 구함. 합산 쿼리가 한 번 더 나가지만,
     * 시스템 계정 잔액을 따로 들고 있으면 그게 또 원장과 어긋날 수 있는 지점이 됨 —
     * 어긋날 수 있는 곳을 늘리지 않으려고 매번 원장에서 다시 구하는 쪽을 택했음.
     *
     * <p><b>알려진 한계 — 시스템 계정의 balanceAfter는 동시성에 취약함.</b>
     * 잠글 행이 없어서(Member 행이 없음) 두 이체가 동시에 같은 합계를 읽고 각자
     * 자기 금액을 더한 값을 쓸 수 있음. 8단계에서 실제로 확인했음 —
     * 락 없이 20개 스레드를 쏘면 DEPOSIT_POOL 26줄 중 6줄의 balanceAfter가 어긋났다.
     *
     * <p>고치려면 모든 이체를 직렬화해야 하는데, <b>표시용 컬럼 하나 때문에 치르기엔
     * 너무 큰 값</b>이라 안 고침. 대신 아무것도 여기에 기대지 않게 해뒀음:
     * <ul>
     *   <li>정합성 검증은 전부 SUM으로 구함 (balanceOf / sumAmountByEntryType)</li>
     *   <li>balanceAfter를 읽는 곳은 원장 조회 응답 하나뿐 — 화면 표시용</li>
     * </ul>
     * 누적 합계 자체는 언제나 맞다. 어긋나는 건 "그 줄 시점의 스냅샷"뿐이다.
     *
     * <p>회원 계정은 해당 없음. 잔액을 건드리는 경로에는 Member 행 비관적 락이
     * 걸려 있어서 순서가 보장됨.
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
