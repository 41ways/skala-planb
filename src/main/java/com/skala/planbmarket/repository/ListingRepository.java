package com.skala.planbmarket.repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.skala.planbmarket.domain.entity.Listing;
import com.skala.planbmarket.domain.enums.Category;
import com.skala.planbmarket.domain.enums.ListingStatus;

import jakarta.persistence.LockModeType;

/**
 * Listing JPA Repository.
 *
 * 목록 검색에서 카테고리 필터를 IN 절로 받는 게 눈에 띌 텐데 의도한 것임.
 * "필터 안 걸림"을 NULL로 표현하면 쿼리마다 (:param IS NULL OR ...) 가 붙어서 지저분해지고,
 * JPQL에서 enum에 NULL을 바인딩할 때 타입 추론이 걸리는 경우도 있음.
 * 필터가 없으면 서비스가 후보 전체를 담아서 넘기면 됨 — 쿼리는 조건 하나로 끝남.
 */
@Repository
public interface ListingRepository extends JpaRepository<Listing, Long> {

    @EntityGraph(attributePaths = {"ticket", "seller"})
    @Query("""
            SELECT l FROM Listing l
            WHERE l.status IN :statuses
              AND l.ticket.category IN :categories
            """)
    Page<Listing> search(@Param("statuses") Collection<ListingStatus> statuses,
                         @Param("categories") Collection<Category> categories,
                         Pageable pageable);

    /** 단건 조회에서도 티켓·판매자를 같이 가져옴. open-in-view가 꺼져 있어 나중엔 못 읽음 */
    @EntityGraph(attributePaths = {"ticket", "seller"})
    Optional<Listing> findWithDetailById(Long id);

    /** 만료 임박 목록. 남은 시간이 짧은 것부터 */
    @EntityGraph(attributePaths = {"ticket", "seller"})
    List<Listing> findByStatusInAndTicketExpiresAtBetweenOrderByTicketExpiresAtAsc(
            Collection<ListingStatus> statuses, LocalDateTime from, LocalDateTime to);


    /** 티켓에 지금 살아 있는 판매 건. 철회 후 재등록이 가능해서 여러 건 중 활성만 골라야 함 */
    @EntityGraph(attributePaths = {"ticket", "seller"})
    Optional<Listing> findFirstByTicketIdAndStatusInOrderByIdDesc(
            Long ticketId, Collection<ListingStatus> statuses);

    /** 거래 요약용 — 판매자로서의 상태별 건수 */
    long countBySellerIdAndStatusIn(String sellerId, Collection<ListingStatus> statuses);

    /**
     * 예약·결제용 비관적 락 (SELECT ... FOR UPDATE).
     *
     * 이 프로젝트에서 경합이 실제로 일어나는 첫 지점이다. 예약은
     * "상태를 읽고 → OPEN인지 보고 → 예약금을 잡고 → 상태를 바꾼다"인데,
     * 읽기와 쓰기 사이가 벌어져 있어서 그 틈에 다른 요청이 같은 OPEN을 읽는다.
     * 그러면 판매 건 하나에 예약이 여러 건 걸리고, 티켓 하나가 여러 명에게 잠긴다.
     *
     * <p><b>왜 낙관적 락이 아닌가.</b> 낙관적 락은 충돌하면 되감고 재시도하는 방식인데,
     * 여기 경합은 <b>마지막 남은 1건</b>을 두고 벌어진다. 재시도해봐야 그때는 이미
     * RESERVED라 어차피 실패한다. 성공할 수 없는 재시도에 비용만 더 드는 셈.
     * 줄을 세워서 한 명씩 확인시키는 쪽이 맞다.
     *
     * <p>@EntityGraph를 같이 안 건 이유: 조인한 테이블까지 잠글 필요가 없다.
     * 잠글 대상은 판매 건 행 하나뿐이고, 티켓·판매자는 같은 트랜잭션 안에서
     * 지연 로딩으로 읽으면 된다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT l FROM Listing l WHERE l.id = :id")
    Optional<Listing> findByIdForUpdate(@Param("id") Long id);
}
