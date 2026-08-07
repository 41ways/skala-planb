package com.skala.planbmarket.domain.entity;

import java.time.LocalDateTime;

import com.skala.planbmarket.domain.enums.EntryType;
import com.skala.planbmarket.domain.enums.LedgerReason;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 정산 원장. 이 프로젝트의 핵심임.
 *
 * 모든 금전 이동을 append-only로 쌓기만 함. UPDATE·DELETE는 절대 안 함.
 * 잔액만 UPDATE하는 방식으로 짜면 숫자가 틀어졌을 때 원인을 찾을 방법이 없음.
 * 기록이 남아 있어야 "언제 어디서 얼마가 어긋났는지"를 되짚을 수 있고,
 * 그게 되니까 정합성 자가검증 API가 성립함.
 *
 * append-only를 주석으로만 약속하면 언젠가 누가 깨뜨림. 그래서 두 겹으로 막았음:
 *   1) setter를 아예 안 만들고 생성자를 private으로 잠금 → 코드로 값을 못 바꿈
 *   2) 모든 컬럼에 updatable = false → 혹시 바꿔도 하이버네이트가 UPDATE를 안 날림
 *
 * 기록은 항상 2줄이 한 쌍임 (DEBIT 1줄 + CREDIT 1줄, 같은 금액).
 * 어느 계정에서 어느 계정으로 가는지는 LedgerReason의 표를 따름.
 */
@Entity
@Table(
        name = "ledger",
        indexes = {
                @Index(name = "idx_ledger_account", columnList = "account_id"),
                @Index(name = "idx_ledger_ref", columnList = "ref_type, ref_id"),
                @Index(name = "idx_ledger_created", columnList = "created_at")
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Ledger {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /** 회원 ID 또는 시스템 계정 이름(PLATFORM, EXTERNAL, ESCROW_POOL, DEPOSIT_POOL) */
    @Column(name = "account_id", nullable = false, updatable = false, length = 50)
    private String accountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", nullable = false, updatable = false, length = 10)
    private EntryType entryType;

    /** 항상 양수. 방향은 entryType으로만 구분함 */
    @Column(name = "amount", nullable = false, updatable = false)
    private Long amount;

    /** 기록 직후 잔액. 회원은 실제 잔액, 시스템 계정은 누적값 */
    @Column(name = "balance_after", nullable = false, updatable = false)
    private Long balanceAfter;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason", nullable = false, updatable = false, length = 30)
    private LedgerReason reason;

    /** "ESCROW" / "DEPOSIT" / "CHARGE" 같은 참조 대상 종류 */
    @Column(name = "ref_type", updatable = false, length = 20)
    private String refType;

    @Column(name = "ref_id", updatable = false)
    private Long refId;

    @Column(name = "memo", updatable = false, length = 200)
    private String memo;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private Ledger(String accountId, EntryType entryType, long amount, long balanceAfter,
                   LedgerReason reason, String refType, Long refId, String memo) {
        this.accountId = accountId;
        this.entryType = entryType;
        this.amount = amount;
        this.balanceAfter = balanceAfter;
        this.reason = reason;
        this.refType = refType;
        this.refId = refId;
        this.memo = memo;
        this.createdAt = LocalDateTime.now();
    }

    /**
     * 원장 한 줄 생성.
     *
     * 이건 DEBIT이든 CREDIT이든 한 줄만 만들어주는 저수준 팩토리라서, 직접 부르지 말고
     * LedgerService의 이체 메서드를 통해 쓸 것. 서비스가 2줄을 한 트랜잭션 안에서
     * 같이 만들어야 차대가 맞음.
     */
    public static Ledger of(String accountId, EntryType entryType, long amount, long balanceAfter,
                            LedgerReason reason, String refType, Long refId, String memo) {
        return new Ledger(accountId, entryType, amount, balanceAfter, reason, refType, refId, memo);
    }

    /** 차대 합산할 때 쓰는 부호 있는 금액. CREDIT은 +, DEBIT은 - */
    public long signedAmount() {
        return entryType == EntryType.CREDIT ? amount : -amount;
    }
}
