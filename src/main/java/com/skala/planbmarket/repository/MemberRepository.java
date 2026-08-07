package com.skala.planbmarket.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.skala.planbmarket.domain.entity.Member;

import jakarta.persistence.LockModeType;

/**
 * Member JPA Repository.
 *
 * 조회 메서드는 그 기능을 만드는 단계에서 필요한 것만 추가함.
 * 안 쓰는 메서드를 미리 깔아두지 않는 게 원칙.
 */
@Repository
public interface MemberRepository extends JpaRepository<Member, String> {

    /**
     * 잔액 차감용 비관적 락 (SELECT ... FOR UPDATE).
     *
     * 락 없이 동시에 결제하면 두 트랜잭션이 같은 잔액을 읽고 각자 뺀 값을 쓴다.
     * 나중에 커밋한 쪽이 앞의 차감을 덮어써서, 돈은 두 번 나갔는데 잔액은 한 번만 줄어듦.
     * 원장은 두 줄 다 남으므로 정합성 검증에 바로 걸리는 종류의 어긋남이다.
     *
     * <p><b>락 획득 순서는 항상 Listing → 구매자 → 판매자.</b> 순서가 뒤집히면
     * 두 트랜잭션이 서로가 쥔 걸 기다려서 데드락이 난다. 순서를 지키는 것만으로
     * 데드락을 구조적으로 없앨 수 있어서, 잡는 쪽에서 규칙을 지키기로 했다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT m FROM Member m WHERE m.id = :id")
    Optional<Member> findByIdForUpdate(@Param("id") String id);
}
