package com.skala.planbmarket.domain.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 회원. 예치금(balance)을 들고 있음.
 *
 * 비밀번호는 평문 저장임. JWT를 안 쓰고 세션 기반이라 단순 비교만 하면 되고,
 * 암호화는 이번 실습 범위가 아니라서 일부러 안 했음.
 *
 * balance를 직접 건드리는 메서드가 열려 있긴 한데, 반드시 Ledger 기록과 세트로만
 * 호출해야 함. 잔액만 바꾸고 원장을 안 남기면 정합성 검증에서 바로 걸림.
 */
@Entity
@Table(name = "member")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class Member {

    @Id
    @Column(name = "id", length = 50)
    private String id;

    @Column(name = "password", nullable = false, length = 100)
    private String password;

    @Column(name = "balance", nullable = false)
    private Long balance;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (balance == null) {
            balance = 0L;
        }
    }

    /** 잔액 증가. LedgerService.record()와 반드시 같이 호출할 것 */
    public void increaseBalance(long amount) {
        this.balance += amount;
    }

    /**
     * 잔액 차감. 부족한지 여부는 호출하는 쪽에서 미리 확인해야 함.
     * 여기서 예외를 던지지 않는 건, 잔액 확인 시점에 이미 비관적 락이 잡혀 있어야
     * 의미가 있기 때문임. 락 없이 여기서만 막으면 동시 결제에서 그냥 뚫림.
     */
    public void decreaseBalance(long amount) {
        this.balance -= amount;
    }

    public boolean canAfford(long amount) {
        return this.balance >= amount;
    }

    /**
     * 잔액을 원장 합계에 맞춰 되돌림. <b>동시성 시뮬레이터의 뒷정리 전용.</b>
     *
     * 평소에 이 메서드를 부를 일은 없어야 한다. 잔액을 바꾸는 통로는 LedgerService
     * 하나뿐이라는 게 이 프로젝트의 규칙이고, 여기는 그 규칙을 우회하는 유일한 구멍이다.
     *
     * <p>그래도 둔 이유: 락 없이 돌린 시뮬레이션은 <b>잔액에 lost update를 남긴다.</b>
     * 두 스레드가 같은 잔액을 읽고 각자 뺀 값을 써서 앞의 차감이 덮어써지는 것.
     * 원장에는 두 줄 다 남으므로 이때부터 "잔액 != 원장 합"이 되고, 정합성 검증이
     * 영구히 실패한다. 되돌릴 방법이 없으면 시연을 한 번밖에 못 한다.
     *
     * <p>원장을 고치는 게 아니라 <b>잔액을 원장에 맞추는</b> 방향인 게 중요하다.
     * 원장이 사실이고 잔액은 빠르게 읽으려고 들고 있는 사본이다. 사본이 틀어졌으면
     * 사본을 원본에 맞추는 게 맞지, 그 반대가 아니다. (원장은 append-only라 애초에
     * 고칠 수도 없다 — setter도 없고 전 컬럼이 updatable=false다)
     *
     * @return 어긋나 있던 금액. 0이면 멀쩡했던 것
     */
    public long reconcileBalance(long fromLedger) {
        long difference = this.balance - fromLedger;
        this.balance = fromLedger;
        return difference;
    }

    public void changePassword(String password) {
        this.password = password;
    }
}
