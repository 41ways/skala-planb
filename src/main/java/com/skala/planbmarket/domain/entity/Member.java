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

    public void changePassword(String password) {
        this.password = password;
    }
}
