package com.skala.planbmarket.domain.entity;

import java.time.LocalDateTime;

import com.skala.planbmarket.domain.enums.DepositStatus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 예약금. 판매 건에 자리를 잡을 때 희망가의 10%를 걸어둠.
 *
 * 왜 필요하냐면, 소멸성 자산이라 구매자가 "살까 말까" 고민하는 동안에도 티켓이 계속
 * 소멸하기 때문임. 예약을 걸면 판매자는 그동안 다른 구매자를 못 받는데, 그 기회비용을
 * 아무 대가 없이 판매자에게만 지우면 불공평함. 그래서 이탈에 비용을 붙였음.
 *
 * HELD 상태인 예약금이 곧 "진행 중인 예약"임. 판매 건 하나에 HELD가 둘 있을 수 없고,
 * 그게 곧 한 번에 한 사람만 예약할 수 있다는 뜻이 됨.
 */
@Entity
@Table(
        name = "deposit",
        indexes = {
                @Index(name = "idx_deposit_member", columnList = "member_id"),
                @Index(name = "idx_deposit_listing", columnList = "listing_id, status"),
                @Index(name = "idx_deposit_deadline", columnList = "status, payment_deadline")
        }
)
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class Deposit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "member_id", nullable = false)
    private Member member;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "listing_id", nullable = false)
    private Listing listing;

    @Column(name = "amount", nullable = false)
    private Long amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private DepositStatus status;

    /**
     * 결제 제한시간. now + min(30분, 남은시간 × 0.5)
     *
     * 만료가 임박할수록 결제 시간도 같이 짧아짐. 하루 남은 티켓에 30분을 통째로 묶어두는 건
     * 괜찮지만 두 시간 남은 티켓에 30분이면 남은 가치의 4분의 1을 묶는 셈이라 과함.
     */
    @Column(name = "payment_deadline", nullable = false)
    private LocalDateTime paymentDeadline;

    @Column(name = "held_at", nullable = false)
    private LocalDateTime heldAt;

    /**
     * 결제 마감 임박 알림을 보낸 시각.
     *
     * 스케줄러가 1분마다 도는데 이게 없으면 남은 시간 절반을 지난 순간부터 마감까지
     * 매 분 알림이 쌓임. 티켓의 expiryWarnedAt과 같은 이유로 둔 필드.
     */
    @Column(name = "warned_at")
    private LocalDateTime warnedAt;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    @PrePersist
    void onCreate() {
        if (heldAt == null) {
            heldAt = LocalDateTime.now();
        }
        if (status == null) {
            status = DepositStatus.HELD;
        }
    }

    /**
     * HELD에서 최종 상태로 한 번만 전이됨.
     * 이미 종결된 예약금을 또 건드리면 원장에 중복 기록이 생겨서 차대가 깨지니까 막아둠.
     */
    public void resolve(DepositStatus finalStatus, LocalDateTime resolvedAt) {
        if (this.status != DepositStatus.HELD) {
            throw new IllegalStateException(
                    "이미 처리된 예약금임. depositId=" + id + ", status=" + this.status);
        }
        this.status = finalStatus;
        this.resolvedAt = resolvedAt;
    }

    public boolean isHeld() {
        return status == DepositStatus.HELD;
    }

    public boolean isOverdue(LocalDateTime now) {
        return paymentDeadline.isBefore(now);
    }

    /**
     * 마감 임박 알림을 보낼 때가 됐는지 — 주어진 시간의 절반이 지났는지.
     *
     * 고정분(예: 5분 전)이 아니라 비율로 잡은 이유: 제한시간이 4분인 건에서는
     * 5분 전이 이미 지난 시점이라 알림이 아예 안 나가거나 예약하자마자 나감.
     * 비율이면 어떤 티켓이든 "절반 썼을 때"라는 일관된 경험이 됨.
     */
    public boolean needsDeadlineWarning(LocalDateTime now) {
        if (warnedAt != null || status != DepositStatus.HELD) {
            return false;
        }
        long total = java.time.Duration.between(heldAt, paymentDeadline).toSeconds();
        return !now.isBefore(heldAt.plusSeconds(total / 2));
    }

    public void markWarned(LocalDateTime warnedAt) {
        this.warnedAt = warnedAt;
    }
}
