package com.skala.planbmarket.domain.entity;

import java.time.LocalDateTime;

import com.skala.planbmarket.domain.enums.ListingStatus;

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
 * 판매 등록. 티켓 하나당 하나만 붙음.
 *
 * 티켓 수량을 통째로 파는 구조라 가격은 askingPrice 하나면 됨.
 * 구매는 예약(RESERVED) → 결제(IN_ESCROW) 두 단계로 진행됨.
 */
@Entity
@Table(
        name = "listing",
        indexes = {
                @Index(name = "idx_listing_status", columnList = "status"),
                @Index(name = "idx_listing_seller", columnList = "seller_id")
        }
)
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class Listing {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /**
     * 티켓 하나에 판매 기록이 여러 개 붙을 수 있음 — 철회했다가 다시 올리는 경우.
     * 처음엔 1:1에 unique 제약을 걸었는데, 그러면 철회 뒤 재등록이 제약 위반으로 터짐.
     * "동시에 살아 있는 판매는 하나뿐"이라는 규칙은 ListingService에서 확인함.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ticket_id", nullable = false)
    private Ticket ticket;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "seller_id", nullable = false)
    private Member seller;

    /** 희망가. 티켓 수량 전체 기준이고, 예약금도 이 값의 10%로 계산됨 */
    @Column(name = "asking_price", nullable = false)
    private Long askingPrice;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ListingStatus status;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (status == null) {
            status = ListingStatus.OPEN;
        }
    }

    public void changeStatus(ListingStatus status) {
        this.status = status;
    }

    /** 새 예약을 받을 수 있는 상태인지. 예약 중·거래 중·철회·만료는 다 막힘 */
    public boolean isOpenForReservation() {
        return status == ListingStatus.OPEN;
    }
}
