package com.skala.planbmarket.repository;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.skala.planbmarket.domain.entity.Ledger;
import com.skala.planbmarket.domain.enums.EntryType;
import com.skala.planbmarket.domain.enums.LedgerReason;

/**
 * Ledger JPA Repository.
 *
 * 쓰기는 save만 씀. 원장은 append-only라 수정·삭제 메서드를 만들 일이 없고,
 * 엔티티 쪽에서도 setter를 안 만들어 두 겹으로 막아뒀음.
 *
 * 조회 쪽은 죄다 집계인데 여기 JPA로 둔 이유가 있음. 정합성 검증은 "원장이 스스로를
 * 검증한다"는 성격이라 원장 저장소에 붙어 있는 게 자연스럽고, 계정별 합계 하나짜리
 * GROUP BY라 MyBatis까지 갈 만큼 복잡하지 않음. 여러 테이블을 조인해서 구간을 나누는
 * 통계는 7단계에서 MyBatis로 감 — 그 경계 판단이 SPEC 12장 4번 항목임.
 */
@Repository
public interface LedgerRepository extends JpaRepository<Ledger, Long> {

    /** 회원 삭제 가능 여부 판단용. 원장이 한 줄이라도 있으면 지우면 안 됨 */
    boolean existsByAccountId(String accountId);

    /** 내 원장 조회. 최근 것부터 */
    Page<Ledger> findByAccountIdOrderByIdDesc(String accountId, Pageable pageable);

    /** 계정 하나의 현재 잔액. 시스템 계정은 이걸로 balanceAfter를 구함 */
    @Query("""
            SELECT COALESCE(SUM(CASE WHEN l.entryType = :credit THEN l.amount ELSE -l.amount END), 0)
            FROM Ledger l WHERE l.accountId = :accountId
            """)
    long balanceOf(@Param("accountId") String accountId, @Param("credit") EntryType credit);

    /** 정합성 검증 1번 규칙용 — 계정별 잔액을 한 방에 */
    @Query("""
            SELECT l.accountId AS accountId,
                   SUM(CASE WHEN l.entryType = :credit THEN l.amount ELSE -l.amount END) AS balance
            FROM Ledger l GROUP BY l.accountId
            """)
    List<AccountBalance> findAccountBalances(@Param("credit") EntryType credit);

    /** 정합성 검증 2번 규칙용 — 전체 차대 */
    @Query("SELECT COALESCE(SUM(l.amount), 0) FROM Ledger l WHERE l.entryType = :entryType")
    long sumAmountByEntryType(@Param("entryType") EntryType entryType);

    /** 에스크로·예약금에 대응하는 원장 기록이 실제로 남았는지 확인 */
    boolean existsByRefTypeAndRefIdAndReason(String refType, Long refId, LedgerReason reason);

    /** 계정별 잔액 조회 결과 */
    interface AccountBalance {
        String getAccountId();

        Long getBalance();
    }
}
