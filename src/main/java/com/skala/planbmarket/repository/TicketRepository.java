package com.skala.planbmarket.repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.skala.planbmarket.domain.entity.Ticket;
import com.skala.planbmarket.domain.enums.Category;
import com.skala.planbmarket.domain.enums.TicketStatus;

/**
 * Ticket JPA Repository.
 *
 * 목록 조회에 @EntityGraph로 owner를 같이 끌고 오는 이유: 응답에 ownerId가 들어가는데
 * 지연 로딩이면 20건 조회에 owner 조회가 20번 더 붙음(N+1). 어차피 항상 쓰는 값이라
 * 처음부터 조인해서 가져오는 게 맞음.
 */
@Repository
public interface TicketRepository extends JpaRepository<Ticket, Long> {

    @Override
    @EntityGraph(attributePaths = "owner")
    Page<Ticket> findAll(Pageable pageable);

    @EntityGraph(attributePaths = "owner")
    Page<Ticket> findByCategoryIn(Collection<Category> categories, Pageable pageable);

    /** 회원 상세의 "보유 티켓". 곧 사라질 것부터 보여주는 게 이 도메인에선 자연스러움 */
    @EntityGraph(attributePaths = "owner")
    List<Ticket> findByOwnerIdOrderByExpiresAtAsc(String ownerId);

    /** 만료 스케줄러용 — 시각이 지났는데 아직 정리 안 된 티켓 */
    @EntityGraph(attributePaths = "owner")
    List<Ticket> findByStatusInAndExpiresAtBefore(
            Collection<TicketStatus> statuses, LocalDateTime now);

    /** 만료 임박 경고용 — 아직 경고를 안 보낸 것만 */
    @EntityGraph(attributePaths = "owner")
    List<Ticket> findByStatusInAndExpiryWarnedAtIsNullAndExpiresAtBetween(
            Collection<TicketStatus> statuses, LocalDateTime from, LocalDateTime to);

    /**
     * 거래 요약용 — 회원의 상태별 티켓 건수.
     *
     * 파생 쿼리메서드 하나로 끝남. 단일 회원 기준 필터링이라 GROUP BY로 행을 접을 일이
     * 없어서 MyBatis까지 갈 이유가 없음. 그 경계 판단은 AnalysisMapper 주석 참조.
     */
    long countByOwnerIdAndStatus(String ownerId, TicketStatus status);
}
