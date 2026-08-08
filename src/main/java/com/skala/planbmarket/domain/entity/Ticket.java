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

    /**
     * 좌석·구역 표기. 카테고리마다 형식이 다르다.
     *
     * <pre>
     *   항공   27A, 27B          기차     4호차 12석
     *   영화   2관 H열 7·8번      콘서트   FLOOR A구역 12열
     *   전시·호텔·기프티콘  보통 없음 (null)
     * </pre>
     *
     * <p><b>왜 항공 전용 필드가 아니라 범용인가.</b> 좌석이라는 개념은 항공에만 있는 게
     * 아니고 표기 형식만 다르다. 카테고리마다 필드를 따로 두면 8개 카테고리에 8개 필드가
     * 붙고, 조회할 때마다 "이 카테고리는 어느 필드를 봐야 하나"라는 분기가 생긴다.
     * 하나로 두면 화면은 그냥 있으면 보여주고 없으면 건너뛰면 된다.
     *
     * <p>구조화(좌석 테이블 분리)까지 가지 않은 이유: 이 프로젝트는 티켓을 <b>통째로</b>
     * 양도한다. 좌석 단위로 나눠 팔지 않으므로 좌석은 계산에 쓰이지 않고 표시만 된다.
     * 계산에 안 쓰는 값을 구조화하는 건 비용만 늘린다.
     */
    @Column(name = "seat_info", length = 100)
    private String seatInfo;

    /**
     * 양도 시 함께 넘어가는 적립 마일. 항공권에만 의미가 있다.
     *
     * <p><b>돈이 아니다.</b> 원장에 안 들어가고 잔액·정산·정합성 검증 어디에도 관여하지
     * 않는다. 티켓에 딸려 오는 부가 가치를 <b>구매자가 판단할 재료로</b> 보여줄 뿐이다.
     *
     * <p>마일로 결제까지 하게 하려면 원장에 통화 축이 필요하다. 지금 원장은
     * "이체 1건 = DEBIT 1줄 + CREDIT 1줄, 전체 차대 0"이라는 항등식 위에 서 있는데,
     * 통화가 둘이 되면 그 항등식이 통화별로 갈라지고 정합성 검증·동시성 검증을
     * 전부 다시 짜야 한다. 표시용으로 두는 한 그 위험이 없다.
     */
    @Column(name = "mileage")
    private Long mileage;

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
                       LocalDateTime eventAt, LocalDate validFrom, LocalDate validUntil,
                       String seatInfo, Long mileage) {
        this.title = title;
        this.originalPrice = originalPrice;
        this.quantity = quantity;
        this.eventAt = eventAt;
        this.validFrom = validFrom;
        this.validUntil = validUntil;
        this.seatInfo = seatInfo;
        this.mileage = mileage;
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
