package com.skala.planbmarket.repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.skala.planbmarket.domain.entity.Escrow;
import com.skala.planbmarket.domain.enums.EscrowStatus;

/**
 * Escrow JPA Repository.
 *
 * 조회할 때 listing과 그 안의 ticket·seller까지 같이 끌고 오는 게 많은데,
 * 거래 응답에 티켓 제목과 판매자 ID가 늘 들어가고 open-in-view도 꺼져 있어서
 * 트랜잭션 밖에서는 못 읽기 때문임.
 */
@Repository
public interface EscrowRepository extends JpaRepository<Escrow, Long> {

    @EntityGraph(attributePaths = {"buyer", "listing", "listing.ticket", "listing.seller"})
    Optional<Escrow> findWithDetailById(Long id);

    @EntityGraph(attributePaths = {"buyer", "listing", "listing.ticket", "listing.seller"})
    Page<Escrow> findByBuyerIdOrderByIdDesc(String buyerId, Pageable pageable);

    boolean existsByListingIdAndStatusIn(Long listingId, Collection<EscrowStatus> statuses);

    boolean existsByBuyerId(String buyerId);

    /** 자동확정 스케줄러용 */
    @EntityGraph(attributePaths = {"buyer", "listing", "listing.ticket", "listing.seller"})
    List<Escrow> findByStatusAndAutoConfirmAtBefore(EscrowStatus status, LocalDateTime now);

    /** 만료 스케줄러용 — 티켓이 만료됐는데 아직 보관 중인 거래 */
    @EntityGraph(attributePaths = {"buyer", "listing", "listing.ticket", "listing.seller"})
    Optional<Escrow> findFirstByListingIdAndStatus(Long listingId, EscrowStatus status);

    /** 정합성 검증용 — 보관 중인 돈의 합. ESCROW_POOL 잔액과 맞아야 함 */
    @Query("SELECT COALESCE(SUM(e.amount), 0) FROM Escrow e WHERE e.status = :status")
    long sumHeldAmount(@Param("status") EscrowStatus status);

    /** 대시보드 — 상태별 거래 건수 */
    long countByStatus(EscrowStatus status);

    /** 거래 요약용 — 구매자로서의 상태별 건수 */
    long countByBuyerIdAndStatus(String buyerId, EscrowStatus status);

    /**
     * 거래 요약용 — 구매 확정 총액.
     *
     * 합계라 파생 쿼리메서드로는 안 되지만 여전히 JPA임. 단일 회원 필터 + SUM 하나라
     * 여러 행을 구간으로 나눠 접는 작업이 아니고, MyBatis로 옮길 이유가 없음.
     */
    @Query("""
            SELECT COALESCE(SUM(e.amount), 0) FROM Escrow e
            WHERE e.buyer.id = :buyerId AND e.status = :status
            """)
    long sumAmountByBuyer(@Param("buyerId") String buyerId, @Param("status") EscrowStatus status);
}
