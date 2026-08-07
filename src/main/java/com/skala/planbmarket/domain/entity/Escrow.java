package com.skala.planbmarket.domain.entity;

import java.time.LocalDateTime;

import com.skala.planbmarket.domain.enums.EscrowStatus;

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
 * 거래. 판매 건 하나에 하나씩 붙음.
 *
 * 교재의 OrderItem에 대응하는데, 결제하자마자 판매자한테 돈이 가는 게 아니라
 * ESCROW_POOL에 잡아두고 확정될 때 넘긴다는 점이 다름.
 *
 * 3자 보관이 필요한 이유는, 티켓이 넘어갔는지 확인할 시간이 필요한데 그 사이에도
 * 티켓은 계속 만료를 향해 가기 때문임. 만료되면 거래 자체가 무의미해지니까
 * 그때는 판매자가 아니라 구매자에게 돈이 돌아가야 함.
 */
@Entity
@Table(
        name = "escrow",
        indexes = {
                @Index(name = "idx_escrow_listing", columnList = "listing_id"),
                @Index(name = "idx_escrow_buyer", columnList = "buyer_id"),
                @Index(name = "idx_escrow_status", columnList = "status"),
                @Index(name = "idx_escrow_auto_confirm", columnList = "auto_confirm_at")
        }
)
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class Escrow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "listing_id", nullable = false)
    private Listing listing;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "buyer_id", nullable = false)
    private Member buyer;

    /** 이 거래로 가져가는 매수. 티켓 quantity 그대로 */
    @Column(name = "quantity", nullable = false)
    private Integer quantity;

    /** 결제 총액. 예약금으로 충당된 몫까지 포함한 값 */
    @Column(name = "amount", nullable = false)
    private Long amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private EscrowStatus status;

    @Column(name = "paid_at", nullable = false)
    private LocalDateTime paidAt;

    /**
     * min(결제 후 24시간, ticket.expiresAt)
     * 만료 시각을 넘겨서 자동확정하면 이미 못 쓰게 된 티켓 값을 판매자에게 주는 꼴이라 안 됨.
     */
    @Column(name = "auto_confirm_at", nullable = false)
    private LocalDateTime autoConfirmAt;

    @Column(name = "confirmed_at")
    private LocalDateTime confirmedAt;

    @PrePersist
    void onCreate() {
        if (paidAt == null) {
            paidAt = LocalDateTime.now();
        }
        if (status == null) {
            status = EscrowStatus.HOLDING;
        }
    }

    /** 판매자 정산의 기준 금액. 여기서 수수료를 뗌 */
    public long sellerAmount() {
        return amount;
    }

    public void confirm(LocalDateTime confirmedAt) {
        if (this.status != EscrowStatus.HOLDING) {
            throw new IllegalStateException(
                    "보관 중인 거래가 아님. escrowId=" + id + ", status=" + this.status);
        }
        this.status = EscrowStatus.CONFIRMED;
        this.confirmedAt = confirmedAt;
    }

    /** 환불(구매자 요청·제안 거절) 또는 무산(만료). 둘 다 돈은 구매자에게 돌아감 */
    public void close(EscrowStatus finalStatus) {
        if (this.status != EscrowStatus.HOLDING) {
            throw new IllegalStateException(
                    "보관 중인 거래가 아님. escrowId=" + id + ", status=" + this.status);
        }
        this.status = finalStatus;
    }

    public boolean isHolding() {
        return status == EscrowStatus.HOLDING;
    }
}
