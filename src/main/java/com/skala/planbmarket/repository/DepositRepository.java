package com.skala.planbmarket.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.skala.planbmarket.domain.entity.Deposit;
import com.skala.planbmarket.domain.enums.DepositStatus;

/**
 * Deposit JPA Repository.
 *
 * HELD 상태인 예약금이 곧 "진행 중인 예약"이라, 판매 건으로 HELD를 찾는 조회가 핵심임.
 * 그게 있으면 예약 중이고 없으면 비어 있는 것 — 별도의 예약 테이블을 두지 않은 이유.
 */
@Repository
public interface DepositRepository extends JpaRepository<Deposit, Long> {

    /** 판매 건에 걸려 있는 진행 중인 예약 */
    @EntityGraph(attributePaths = {"member", "listing", "listing.ticket", "listing.seller"})
    Optional<Deposit> findByListingIdAndStatus(Long listingId, DepositStatus status);

    /** 제한시간을 넘긴 예약들. 스케줄러가 몰수 처리함 */
    @EntityGraph(attributePaths = {"member", "listing", "listing.ticket"})
    List<Deposit> findByStatusAndPaymentDeadlineBefore(DepositStatus status, LocalDateTime deadline);

    @EntityGraph(attributePaths = {"member", "listing", "listing.ticket"})
    Page<Deposit> findByMemberIdOrderByIdDesc(String memberId, Pageable pageable);

    /** 마감 임박 경고 대상 후보. 절반 지점을 지났는지는 엔티티가 판정함 */
    @EntityGraph(attributePaths = {"member", "listing", "listing.ticket"})
    List<Deposit> findByStatusAndWarnedAtIsNull(DepositStatus status);

    /** 정합성 검증용 — 홀드 중인 예약금 합. DEPOSIT_POOL 잔액과 맞아야 함 */
    @Query("SELECT COALESCE(SUM(d.amount), 0) FROM Deposit d WHERE d.status = :status")
    long sumHeldAmount(@Param("status") DepositStatus status);

    /** 거래 요약용 — 회원의 상태별 예약금 건수 */
    long countByMemberIdAndStatus(String memberId, DepositStatus status);

    /**
     * 판매 건에 걸린 예약을 <b>전부</b> — 위의 단건 조회와 짝이 되는 메서드.
     *
     * 정상 상태라면 HELD는 많아야 하나뿐이라 단건 조회로 충분하다. 그런데 락이 없으면
     * 여러 건이 생길 수 있고, 그때 단건 조회는 예외를 던진다. 동시성 시뮬레이터는
     * 바로 그 깨진 상태를 <b>세고 치워야</b> 해서 목록으로 받는 쪽이 필요하다.
     */
    List<Deposit> findAllByListingIdAndStatus(Long listingId, DepositStatus status);

    /** 시뮬레이터 결과 판정용 — 판매 건에 걸린 진행 중 예약 건수 */
    long countByListingIdAndStatus(Long listingId, DepositStatus status);

    /** 대시보드 — 상태별 전체 예약금 건수 */
    long countByStatus(DepositStatus status);
}
