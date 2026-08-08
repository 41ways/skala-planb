package com.skala.planbmarket.domain.entity;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

import com.skala.planbmarket.domain.enums.Category;
import com.skala.planbmarket.domain.enums.ExpiryType;
import com.skala.planbmarket.domain.enums.TicketStatus;

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
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 양도 대상 자산. 교재의 Product에 해당하는데, 결정적으로 다른 게 만료 시각을 들고 있다는 점임.
 *
 * 재고가 수량(int)이 아니라 개별 자산이라서, 같은 영화표 2장이어도 상영 시각이 다르면
 * 다른 티켓임. 시간이 지나면 알아서 가치가 0이 됨.
 */
@Entity
@Table(
        name = "ticket",
        indexes = {
                @Index(name = "idx_ticket_owner", columnList = "owner_id"),
                @Index(name = "idx_ticket_expires", columnList = "expires_at"),
                @Index(name = "idx_ticket_status", columnList = "status")
        }
)
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class Ticket {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false)
    private Member owner;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 20)
    private Category category;

    /**
     * category만 있으면 유도되는 값인데도 컬럼으로 들고 있음.
     * 스케줄러랑 통계 쿼리가 SQL 레벨에서 만료 유형으로 바로 걸러야 해서,
     * 매번 카테고리 8종을 나열하는 것보다 컬럼 하나 두는 게 나음.
     * 대신 아래 onSave()에서 category 기준으로 항상 덮어써서 어긋날 일은 없게 했음.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "expiry_type", nullable = false, length = 20)
    private ExpiryType expiryType;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    /** 정가. 전체 수량 기준임 (2매면 2매 합친 값) */
    @Column(name = "original_price", nullable = false)
    private Long originalPrice;

    /** 1 또는 2 */
    @Column(name = "quantity", nullable = false)
    private Integer quantity;

    /** POINT_IN_TIME용 — 공연·상영·출발 시각 */
    @Column(name = "event_at")
    private LocalDateTime eventAt;

    /** DATE_RANGE용 — 사용 시작일 */
    @Column(name = "valid_from")
    private LocalDate validFrom;

    /** DATE_RANGE / EXTENDABLE용 — 유효기간 종료일 */
    @Column(name = "valid_until")
    private LocalDate validUntil;

    /** EXTENDABLE 연장 시 채워짐 */
    @Column(name = "extended_until")
    private LocalDate extendedUntil;

    /**
     * 계산해서 저장하는 필드.
     *
     * 만료 유형별로 만료 시각을 구하는 방법이 다 다른데, 스케줄러가 1분마다 돌면서
     * 매번 유형을 따져가며 계산하면 인덱스도 못 타고 쿼리도 지저분해짐.
     * 저장 시점에 한 번 계산해서 넣어두면 스케줄러는 expires_at < now 하나만 보면 됨.
     */
    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private TicketStatus status;

    /**
     * 만료 임박 경고를 보낸 시각.
     *
     * 경고는 1회만 보내야 하는데, 스케줄러가 1분마다 도는 구조라 매번 알림 테이블을
     * 뒤져서 중복을 확인하면 낭비가 큼. 여기 값이 있으면 이미 보낸 걸로 보고 건너뜀.
     */
    @Column(name = "expiry_warned_at")
    private LocalDateTime expiryWarnedAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    @PreUpdate
    void onSave() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (status == null) {
            status = TicketStatus.OWNED;
        }
        refreshDerived();
    }

    /**
     * 카테고리에서 유도되는 값들(expiryType, expiresAt)을 다시 맞춤.
     *
     * 저장 시점 콜백에만 맡기면 안 됨. 콜백은 flush 때 도는데, 값을 바꾼 직후 응답 DTO를
     * 만들면 아직 flush 전이라 낡은 expiresAt이 그대로 나감. 실제로 기한을 연장했더니
     * DB에는 새 값이 들어갔는데 응답만 옛날 값인 상태가 나왔음.
     * 그래서 값을 바꾸는 메서드에서 즉시 부르고, 콜백은 안전망으로 남겨둠.
     */
    private void refreshDerived() {
        // 카테고리가 만료 유형을 결정함. 둘이 어긋나지 않게 항상 맞춰줌
        this.expiryType = category.getExpiryType();
        this.expiresAt = calculateExpiresAt();
    }

    /**
     * 만료 시각 산출.
     *
     * 기간 만료는 마지막 날 자정까지 유효한 거라서 23:59:59를 붙임.
     * 날짜만 저장하고 00:00으로 두면 마지막 날 하루를 통째로 날려먹음.
     */
    private LocalDateTime calculateExpiresAt() {
        switch (category.getExpiryType()) {
            case POINT_IN_TIME:
                return eventAt;
            case DATE_RANGE:
                return validUntil.atTime(LocalTime.of(23, 59, 59));
            case EXTENDABLE:
                LocalDate effective = (extendedUntil != null) ? extendedUntil : validUntil;
                return effective.atTime(LocalTime.of(23, 59, 59));
            default:
                throw new IllegalStateException("처리하지 않은 만료 유형: " + category.getExpiryType());
        }
    }

    public boolean isExpired(LocalDateTime now) {
        return expiresAt.isBefore(now);
    }

    public void changeStatus(TicketStatus status) {
        this.status = status;
    }

    public void markExpiryWarned(LocalDateTime warnedAt) {
        this.expiryWarnedAt = warnedAt;
    }

    /** EXTENDABLE 전용 */
    public void extendUntil(LocalDate newUntil) {
        this.extendedUntil = newUntil;
        refreshDerived();
    }

    /**
     * 판매 등록 전(OWNED)에만 부르는 수정.
     *
     * category는 일부러 안 받음. 카테고리가 바뀌면 만료 유형도 같이 바뀌어서 채워야 할
     * 날짜 필드가 통째로 달라짐. 그건 수정이라기보다 다른 티켓이라, 지우고 새로 등록하는 게 맞음.
     */
    public void modify(String title, Long originalPrice, Integer quantity,
                       LocalDateTime eventAt, LocalDate validFrom, LocalDate validUntil) {
        this.title = title;
        this.originalPrice = originalPrice;
        this.quantity = quantity;
        this.eventAt = eventAt;
        this.validFrom = validFrom;
        this.validUntil = validUntil;
        refreshDerived();
    }

    /** 분할 발행할 때 원본에서 수량을 떼어냄 */
    public void reduceQuantity(int amount) {
        this.quantity -= amount;
    }

    public void transferTo(Member newOwner) {
        this.owner = newOwner;
    }
}
